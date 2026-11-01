package com.example.companionmod.voice;

import com.example.companionmod.client.CompanionRenderState;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Soft integration with Wildfire's Female Gender Mod 5.x
 * (<a href="https://github.com/FemaleGenderMod/FemaleGenderMod">GitHub</a>, mod id {@code female_gender_mod}).
 *
 * <p>Version 5 made {@code com.wildfire.client.render.GenderLayer} generic over
 * {@code HumanoidRenderState}/{@code HumanoidModel}, so it <b>can</b> be attached to the companion's renderer
 * (the old player-typed layer could not). The layer reads its data from the render state through
 * {@code ClientHelper.getRenderState(state)}; the mod only fills that for players / mannequins / armor stands,
 * so we fill it ourselves from the <b>owner's</b> Wildfire configuration. The result is the mod's real chest
 * model, UV layouts, physics, jacket overlay and textures on the companion - not our own approximation.</p>
 *
 * <p>Everything is reflective (no compile-time dependency, no crash when the mod is absent or on a loader where
 * the render-data setter is unavailable). When the layer cannot be attached, or the owner's gender has no
 * chest, the renderer falls back to its own overlay.</p>
 */
public final class FemaleGenderCompat {
    private static Boolean present;
    private static boolean layerAttached;

    private static boolean initTried;
    private static Object playerCache;                  // WildfireClientAPI.players()
    private static Method cacheGetOrCreate;             // EntityCache.getOrCreate(UUID)
    private static Method holderGender;                 // EntityConfigHolder.gender()
    private static Method valueGet;                     // ConfigValue.get()
    private static Method genderCanHaveBreasts;         // Gender.canHaveBreasts()
    private static Constructor<?> genderRenderStateCtor;// GenderRenderState(holder, entity, state, partialTick)
    private static Object stateKey;                     // ClientHelper impl's static STATE ContextKey
    private static Method setRenderData;                // HumanoidRenderState.setRenderData(ContextKey, Object)

    private FemaleGenderCompat() {}

    public static boolean isPresent() {
        if (present == null) {
            present = modLoaded("female_gender_mod")          // current id (WildfireAPI.MODID)
                    || modLoaded("wildfire_gender")            // legacy id
                    || modLoaded("femalegender")               // very old id
                    || classExists("com.wildfire.api.client.WildfireClientAPI")
                    || classExists("com.wildfire.client.render.GenderLayer");
        }
        return present;
    }

    /** True once the mod's real chest layer has been added to the companion renderer. */
    public static boolean modRendering() { return layerAttached; }

    /**
     * Builds the mod's {@code GenderLayer} for the companion renderer, or null when unavailable.
     * Returns null when the render-data plumbing is missing (e.g. the Fabric build of the mod), so the
     * renderer keeps using its own chest overlay instead of silently rendering nothing.
     */
    @SuppressWarnings("unchecked")
    public static RenderLayer<CompanionRenderState, HumanoidModel<CompanionRenderState>> createLayer(
            RenderLayerParent<CompanionRenderState, HumanoidModel<CompanionRenderState>> parent) {
        if (!isPresent()) return null;
        init();
        if (playerCache == null || cacheGetOrCreate == null || genderRenderStateCtor == null
                || stateKey == null || setRenderData == null) return null;
        try {
            Class<?> layerClass = Class.forName("com.wildfire.client.render.GenderLayer");
            Object layer = layerClass.getConstructor(RenderLayerParent.class).newInstance(parent);
            layerAttached = true;
            return (RenderLayer<CompanionRenderState, HumanoidModel<CompanionRenderState>>) layer;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Fills the render state with a {@code GenderRenderState} built from the owner's Wildfire settings, which is
     * exactly what the mod's layer expects to find. Called from the companion's {@code extractRenderState}.
     *
     * @return true when the mod's layer will draw the chest (so the fallback overlay must stay hidden)
     */
    public static boolean populateRenderState(HumanoidRenderState state, LivingEntity entity, UUID ownerUuid,
                                              float partialTick) {
        if (!layerAttached || state == null || entity == null || ownerUuid == null) return false;
        init();
        if (playerCache == null || cacheGetOrCreate == null || genderRenderStateCtor == null
                || stateKey == null || setRenderData == null) return false;
        try {
            Object holder = cacheGetOrCreate.invoke(playerCache, ownerUuid);
            if (holder == null || !isFemale(holder)) {
                setRenderData.invoke(state, stateKey, (Object) null);   // clear stale data from a previous frame
                return false;
            }
            Object genderState = genderRenderStateCtor.newInstance(holder, entity, state, partialTick);
            setRenderData.invoke(state, stateKey, genderState);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isFemale(Object holder) {
        try {
            Object value = holderGender.invoke(holder);
            Object gender = valueGet.invoke(value);
            return Boolean.TRUE.equals(genderCanHaveBreasts.invoke(gender));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static synchronized void init() {
        if (initTried) return;
        initTried = true;
        try {
            Class<?> api = Class.forName("com.wildfire.api.client.WildfireClientAPI");
            playerCache = api.getMethod("players").invoke(null);
            cacheGetOrCreate = Class.forName("com.wildfire.api.EntityCache").getMethod("getOrCreate", UUID.class);
            Class<?> holderClass = Class.forName("com.wildfire.common.entities.EntityConfigHolder");
            holderGender = holderClass.getMethod("gender");
            valueGet = Class.forName("com.wildfire.common.config.value.ConfigValue").getMethod("get");
            genderCanHaveBreasts = Class.forName("com.wildfire.api.Gender").getMethod("canHaveBreasts");
            genderRenderStateCtor = Class.forName("com.wildfire.client.render.GenderRenderState")
                    .getConstructor(holderClass, LivingEntity.class, HumanoidRenderState.class, float.class);
        } catch (Throwable ignored) { }
        try {
            Class<?> helper = Class.forName("com.wildfire.client.ClientHelper");
            Object instance = helper.getField("INSTANCE").get(null);
            Field keyField = instance.getClass().getField("STATE");
            stateKey = keyField.get(null);
            Class<?> contextKey = Class.forName("net.minecraft.util.context.ContextKey");
            setRenderData = HumanoidRenderState.class.getMethod("setRenderData", contextKey, Object.class);
        } catch (Throwable ignored) { }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, FemaleGenderCompat.class.getClassLoader());
            return true;
        } catch (Throwable ignored) { }
        return false;
    }

    private static boolean modLoaded(String id) {
        try {
            Class<?> loader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = loader.getMethod("getInstance").invoke(null);
            Object result = loader.getMethod("isModLoaded", String.class).invoke(instance, id);
            if (Boolean.TRUE.equals(result)) return true;
        } catch (Throwable ignored) { }
        try {
            Class<?> modList = Class.forName("net.neoforged.fml.ModList");
            Object instance = modList.getMethod("get").invoke(null);
            Object result = modList.getMethod("isLoaded", String.class).invoke(instance, id);
            if (Boolean.TRUE.equals(result)) return true;
        } catch (Throwable ignored) { }
        return false;
    }
}
