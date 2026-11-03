package com.example.companionmod.voice;

import com.example.companionmod.client.CompanionRenderState;
import com.example.companionmod.entity.CompanionEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.LivingEntity;

import org.slf4j.Logger;

import java.lang.reflect.Constructor;
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
    private static String lastOutcome;             // diagnostics: log only when the outcome changes

    private static final Logger LOG = LogUtils.getLogger();
    private static boolean initDone;
    private static int initAttempts;
    private static boolean populateFailureLogged;
    private static Object playerCache;                  // WildfireClientAPI.players()
    private static Method cacheGetOrCreate;             // EntityCache.getOrCreate(UUID)
    private static Method holderGender;                 // EntityConfigHolder.gender()
    private static Method valueGet;                     // ConfigValue.get()
    private static Method genderCanHaveBreasts;         // Gender.canHaveBreasts()
    private static Constructor<?> genderRenderStateCtor;// GenderRenderState(holder, entity, state, partialTick)
    private static Object stateKey;                     // ClientHelper impl's static STATE ContextKey
    private static Method setRenderData;                // NeoForge: setRenderData(ContextKey, Object); Fabric: setData(RenderStateDataKey, Object)
    private static Method getRenderData;                // read-back check: proves the layer can find our data

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
                || stateKey == null || setRenderData == null) {
            LOG.warn("Female Gender Mod is installed but its chest layer cannot be attached; using the built-in overlay");
            return null;
        }
        try {
            Class<?> layerClass = Class.forName("com.wildfire.client.render.GenderLayer");
            Object layer = layerClass.getConstructor(RenderLayerParent.class).newInstance(parent);
            layerAttached = true;
            return (RenderLayer<CompanionRenderState, HumanoidModel<CompanionRenderState>>) layer;
        } catch (Throwable t) {
            LOG.warn("Female Gender Mod: GenderLayer could not be constructed", t);
            return null;
        }
    }

    /**
     * Fills the render state with a {@code GenderRenderState} built from the owner's Wildfire settings, which is
     * exactly what the mod's layer expects to find. Called from the companion's {@code extractRenderState}.
     *
     * <p>Runs only while the companion's own feminine toggle is on: with the mod installed that switch decides
     * <b>whether</b> the chest renders at all, the mod's data (owner's gender/UVs/physics) decides <b>how</b>.
     * Without the mod the settings screen hides the switch and this method never gets past the layer check.</p>
     *
     * <p>Every distinct outcome logs one line (only when the outcome CHANGES, never per frame), so latest.log
     * always states exactly why the chest did or did not render.</p>
     *
     * @return true when the mod's layer will draw the chest
     */
    public static boolean populateRenderState(HumanoidRenderState state, LivingEntity entity, UUID ownerUuid,
                                              float partialTick) {
        if (state == null || entity == null) return false;
        if (!(entity instanceof CompanionEntity companion)) return false;
        if (!layerAttached) {
            outcome(isPresent() ? "layer not attached (see warning above)" : "mod not installed");
            return false;
        }
        init();
        if (playerCache == null || cacheGetOrCreate == null || genderRenderStateCtor == null
                || stateKey == null || setRenderData == null) {
            outcome("reflection incomplete (attempt " + initAttempts + "/5)");
            return false;
        }
        if (!companion.isFeminine()) { clearRenderData(state); outcome("feminine toggle OFF"); return false; }
        if (ownerUuid == null)      { clearRenderData(state); outcome("owner uuid not synced on the entity"); return false; }
        try {
            Object holder = cacheGetOrCreate.invoke(playerCache, ownerUuid);
            if (holder == null) { clearRenderData(state); outcome("no Wildfire config for the owner"); return false; }
            Object gender = genderOf(holder);
            if (gender == null || !Boolean.TRUE.equals(genderCanHaveBreasts.invoke(gender))) {
                clearRenderData(state);
                outcome("owner gender " + gender + " has no chest - hidden");
                return false;
            }
            Object genderState = genderRenderStateCtor.newInstance(holder, entity, state, partialTick);
            setRenderData.invoke(state, stateKey, genderState);
            if (state instanceof CompanionRenderState cs) cs.wgmData = genderState;   // stash for the submit phase
            Object readback = getRenderData != null ? getRenderData.invoke(state, stateKey) : null;
            outcome("rendering: gender=" + gender + ", bust=" + fmtBust(genderState)
                    + ", readback=" + (readback != null ? "OK" : "MISSING!")
                    + ", chest=" + state.chestEquipment
                    + ", invisible=" + state.isInvisibleToPlayer);
            return true;
        } catch (Throwable t) {
            if (!populateFailureLogged) {
                populateFailureLogged = true;
                LOG.warn("Female Gender Mod: could not build the chest render state for the companion", t);
            }
            outcome("exception: " + t);
            return false;
        }
    }

    /** genderOf(holder) - the raw {@code Gender} enum value, or null when reflection fails. */
    private static Object genderOf(Object holder) {
        try { return valueGet.invoke(holderGender.invoke(holder)); } catch (Throwable t) { return null; }
    }

    /**
     * The mod's layer refuses to draw below physics size 0.02, so report both the configured bust and the LIVE
     * physics size: if the live size stays at 0 the mod's per-entity physics tick is not reaching our holder.
     */
    private static String fmtBust(Object genderState) {
        try {
            float config = (Float) genderState.getClass().getField("bustSize").get(genderState);
            Object phys = genderState.getClass().getField("leftBreastPhysics").get(genderState);
            float live = (Float) phys.getClass().getMethod("getBreastSize").invoke(phys);
            return String.format(java.util.Locale.ROOT, "%.2f, physics %s",
                    config, live < 0.02f ? "TOO SMALL (" + live + ") - mod will not draw!" : "ok");
        } catch (Throwable t) { return "?"; }
    }

    /** Clears stale render data from a previous frame so a gated-off chest can never keep drawing. */
    private static void clearRenderData(HumanoidRenderState state) {
        if (state instanceof CompanionRenderState cs) cs.wgmData = null;
        if (setRenderData == null || stateKey == null || state == null) return;
        try { setRenderData.invoke(state, stateKey, (Object) null); } catch (Throwable ignored) { }
    }

    /** Logs {@code s} only when it differs from the previous outcome - one line per real state change. */
    private static void outcome(String s) {
        if (!s.equals(lastOutcome)) { lastOutcome = s; LOG.info("[companionmod] WGM chest: {}", s); }
    }

    private static boolean layersLogged;

    /** One-time dump of the renderer's layer list - proves whether the mod's GenderLayer is actually attached. */
    public static void logLayersOnce(Object renderer) {
        if (layersLogged) return;
        layersLogged = true;
        try {
            java.lang.reflect.Field f = Class.forName("net.minecraft.client.renderer.entity.LivingEntityRenderer")
                    .getDeclaredField("layers");
            f.setAccessible(true);
            LOG.info("[companionmod] WGM chest: layers = {}", f.get(renderer));
        } catch (Throwable t) {
            LOG.info("[companionmod] WGM chest: layers unreadable ({})", t.toString());
        }
    }

    /**
     * Submit-phase: re-apply the stashed render data. Something (a NeoForge render-state modifier of another mod)
     * clears render data between extract and submit - GenderLayer would read null every frame without this.
     */
    public static void applyAtSubmit(HumanoidRenderState state) {
        if (state == null || !(state instanceof CompanionRenderState cs) || cs.wgmData == null) return;
        if (setRenderData == null || stateKey == null) return;
        try { setRenderData.invoke(state, stateKey, cs.wgmData); } catch (Throwable ignored) { }
    }

    /**
     * Submit-phase probe: the mod's GenderLayer reads the render data during submit, so if it vanished between
     * extract and submit that is the bug. Only logs on FAILURE (presence is the normal state - no line).
     */
    public static void verifyAtSubmit(HumanoidRenderState state) {
        if (getRenderData == null || stateKey == null) return;
        try {
            if (getRenderData.invoke(state, stateKey) == null) outcome("submit: DATA MISSING - cleared between extract and submit!");
        } catch (Throwable ignored) { }
    }

    /**
     * Resolves every Wildfire class/method we call. Retries a few times (the mod's services may not be up on the very
     * first frame) and logs exactly what is missing, so a failure shows up in latest.log instead of a silent no-chest.
     */
    private static synchronized void init() {
        if (initDone || initAttempts >= 5) return;
        initAttempts++;
        StringBuilder problems = new StringBuilder();
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
        } catch (Throwable t) {
            problems.append("\n  config/API lookup failed: ").append(t);
        }
        try {
            Class<?> helper = Class.forName("com.wildfire.client.ClientHelper");
            Object instance = helper.getField("INSTANCE").get(null);
            // NeoClientHelper.STATE is a ContextKey, FabricClientHelper.STATE is a Fabric RenderStateDataKey
            stateKey = instance.getClass().getField("STATE").get(null);
            setRenderData = findSetter(stateKey);
            getRenderData = findGetter(stateKey);
            if (setRenderData == null) {
                problems.append("\n  no render-state setter accepts a ").append(stateKey.getClass().getName());
            }
        } catch (Throwable t) {
            problems.append("\n  render-state key lookup failed: ").append(t);
        }
        if (playerCache != null && cacheGetOrCreate != null && genderRenderStateCtor != null
                && stateKey != null && setRenderData != null) {
            initDone = true;
            LOG.info("Female Gender Mod hookup ready (render-state setter: {})", setRenderData.getName());
        } else {
            LOG.warn("Female Gender Mod hookup incomplete (attempt {}/5){}", initAttempts, problems);
        }
    }

    /** NeoForge exposes {@code setRenderData(ContextKey, Object)}, Fabric API exposes {@code setData(RenderStateDataKey, Object)}. */
    private static Method findSetter(Object key) {
        if (key == null) return null;
        for (Method m : HumanoidRenderState.class.getMethods()) {
            if (m.getParameterCount() != 2) continue;
            String n = m.getName();
            if (!n.equals("setRenderData") && !n.equals("setData")) continue;
            if (m.getParameterTypes()[0].isInstance(key)) return m;
        }
        return null;
    }

    /** The matching getter - used only to prove in the log that what we wrote is readable by the mod's layer. */
    private static Method findGetter(Object key) {
        if (key == null) return null;
        for (Method m : HumanoidRenderState.class.getMethods()) {
            if (m.getParameterCount() != 1) continue;
            String n = m.getName();
            if (!n.equals("getRenderData") && !n.equals("getData")) continue;
            if (m.getParameterTypes()[0].isInstance(key)) return m;
        }
        return null;
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
