package com.example.companionmod.client;

import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.voice.FemaleGenderCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.List;
import java.util.UUID;

/**
 * Draws the companion at the same height as a player (the {@code 0.9375} body scale the avatar
 * renderer uses), with the classic or slim model resolved from the skin, armor + held tool layered
 * on top, a floating stream of word bubbles, and the downed crawl (Mojang's swim pose, which
 * only the player renderer flattens — we replicate that transform here).
 *
 * <p>Slim/classic is a per-frame toggle inside {@link CompanionHumanoidModel}, never a model swap:
 * {@code AgeableMobRenderer.submit()} resets {@code this.model} to the adult model before every draw,
 * which silently discarded swapped models.</p>
 */
public class CompanionRenderer extends HumanoidMobRenderer<CompanionEntity, CompanionRenderState, HumanoidModel<CompanionRenderState>> {
    private static final float AVATAR_SCALE = 0.9375f;   // matches AvatarRenderer so the companion is player-height
    private static final float SUBTITLE_SCALE = 0.025f;

    private final PlayerSkinRenderCache skinCache;
    private boolean genderLayerTried;           // Wildfire layer is attached lazily on the first draw

    public CompanionRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new CompanionHumanoidModel(), 0.5f);
        this.skinCache = ctx.getPlayerSkinRenderCache();
        ArmorModelSet<HumanoidModel<CompanionRenderState>> wideArmor =
                ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, ctx.getModelSet(), HumanoidModel::new);
        ArmorModelSet<HumanoidModel<CompanionRenderState>> slimArmor =
                ArmorModelSet.bake(ModelLayers.PLAYER_SLIM_ARMOR, ctx.getModelSet(), HumanoidModel::new);
        // Both armor layers are registered, but only the one matching the current body model submits,
        // so slim arms get slim armor sleeves instead of wide sleeves clipping through.
        addLayer(new SelectableArmorLayer(this, wideArmor, ctx, false));
        addLayer(new SelectableArmorLayer(this, slimArmor, ctx, true));
        // No chest overlay of our own: when Wildfire's Female Gender Mod is installed its real GenderLayer
        // draws the chest (fed by FemaleGenderCompat.populateRenderState), otherwise the companion is flat.
    }

    /**
     * Attaches Wildfire's real chest layer on the first draw instead of in the constructor, so the mod's
     * client services (render-state key, player config cache) are guaranteed to be initialised.
     */
    private void attachGenderLayer() {
        if (genderLayerTried) return;
        genderLayerTried = true;
        // Wildfire's 5.x GenderLayer is generic over HumanoidRenderState, so it fits this renderer.
        RenderLayer<CompanionRenderState, HumanoidModel<CompanionRenderState>> gender =
                FemaleGenderCompat.createLayer(this);
        if (gender != null) addLayer(gender);
    }

    /** Armor layer that only submits when the companion's body model matches its geometry (wide vs slim arms). */
    private static final class SelectableArmorLayer extends HumanoidArmorLayer<
            CompanionRenderState, HumanoidModel<CompanionRenderState>, HumanoidModel<CompanionRenderState>> {
        private final boolean slim;

        private SelectableArmorLayer(CompanionRenderer parent,
                                     ArmorModelSet<HumanoidModel<CompanionRenderState>> models,
                                     EntityRendererProvider.Context ctx, boolean slim) {
            super(parent, models, ctx.getEquipmentRenderer());
            this.slim = slim;
        }

        @Override
        public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light,
                           CompanionRenderState state, float limbSwing, float limbSwingAmount) {
            if (state.slim == slim) super.submit(poseStack, collector, light, state, limbSwing, limbSwingAmount);
        }
    }

    @Override
    public CompanionRenderState createRenderState() { return new CompanionRenderState(); }

    @Override
    public Identifier getTextureLocation(CompanionRenderState state) { return state.texture; }

    @Override
    protected RenderType getRenderType(CompanionRenderState state, boolean visible, boolean visibleToSpectator, boolean glowing) {
        if (state.renderType != null) return state.renderType;
        return super.getRenderType(state, visible, visibleToSpectator, glowing);
    }

    @Override
    public void extractRenderState(CompanionEntity entity, CompanionRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.isPassenger = entity.isSitting();
        state.skin = entity.skin();
        state.speech = entity.speech();
        state.speechId = entity.speechId();
        state.speechStart = CompanionSubtitles.startFor(entity.getId(), state.speechId, state.speech);
        state.entityId = entity.getId();
        state.speechSpeaker = entity.getName().getString();
        state.injured = entity.isInjured();
        state.reviveProgress = entity.reviveProgress();
        state.injuredLeft = entity.injuredLeftTicks();
        resolveSkin(state, entity.skin(), entity.syncedOwner());
        // Wildfire's layer reads its data from the render state. The mod only fills it for players,
        // so we build it from the owner's config, gated by the companion's own feminine toggle.
        FemaleGenderCompat.populateRenderState(state, entity, entity.syncedOwner(), partialTick);
    }

    private void resolveSkin(CompanionRenderState state, String name, UUID owner) {
        // 1) Resolve the skin texture. "owner" goes through the local player's RESOLVED profile:
        //    synchronous, and it picks up Quick Skin local skins with the real model type. A raw name
        //    resolves asynchronously and shows Steve (wide) until it completes, which is what made
        //    slim auto-detection unreliable.
        if (name == null || name.isBlank() || "default".equalsIgnoreCase(name)) {
            applyInfo(state, null);
        } else if ("owner".equalsIgnoreCase(name)) {
            applyInfo(state, ownerSkin(owner));       // THIS companion's owner, not whoever is looking at it
        } else {
            applyInfo(state, skinCache.getOrDefault(ResolvableProfile.createUnresolved(name)));
        }
        // 2) Body model: explicit slim/classic config wins; "auto" follows the resolved skin (Alex = slim).
        String body = ClientVoice.config().companionBody;
        if ("slim".equals(body)) state.slim = true;
        else if ("classic".equals(body)) state.slim = false;
        else state.slim = state.slimSkin;
    }

    /** Skin of the companion's owner (the local player for your own companion, the other player in multiplayer). */
    private PlayerSkinRenderCache.RenderInfo ownerSkin(UUID owner) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        Player p = owner == null || owner.equals(mc.player.getUUID()) ? mc.player
                : (mc.level == null ? null : mc.level.getPlayerByUUID(owner));
        return p == null ? null : skinCache.getOrDefault(ResolvableProfile.createResolved(p.getGameProfile()));
    }

    private static void applyInfo(CompanionRenderState state, PlayerSkinRenderCache.RenderInfo info) {
        if (info == null) {
            state.texture = CompanionRenderState.DEFAULT;
            state.renderType = null;
            state.slimSkin = false;
        } else {
            state.renderType = info.renderType();
            state.texture = info.playerSkin().body().texturePath();
            state.slimSkin = info.playerSkin().model() == PlayerModelType.SLIM;
        }
    }

    /** Lays the companion flat while it is crawling (downed), exactly like AvatarRenderer does for swimming players. */
    @Override
    protected void setupRotations(CompanionRenderState state, PoseStack poseStack, float age, float yHeadRot) {
        super.setupRotations(state, poseStack, age, yHeadRot);
        float swim = state.swimAmount;
        if (swim > 0.001f) {
            poseStack.mulPose(Axis.XP.rotationDegrees(Mth.lerp(swim, 0f, -90f)));
            if (state.isVisuallySwimming) poseStack.translate(0.0f, -1.0f, 0.3f);
        }
    }

    @Override
    public void submit(CompanionRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        attachGenderLayer();
        super.submit(state, poseStack, collector, camera);
        if (state.injured) submitInjured(state, poseStack, collector, camera);
        else submitSubtitle(state, poseStack, collector, camera);
    }

    @Override
    protected void scale(CompanionRenderState state, PoseStack poseStack) {
        poseStack.scale(AVATAR_SCALE, AVATAR_SCALE, AVATAR_SCALE);     // match player height
        if (state.isPassenger) poseStack.translate(0.0f, 0.5f, 0.0f); // sit pose offset (already scaled)
    }

    /** Name tag only while the player is aiming at the companion. */
    @Override
    protected boolean shouldShowName(CompanionEntity entity, double distance) {
        return this.entityRenderDispatcher.crosshairPickEntity == entity;
    }

    // ------------------------------------------------------------------ floating subtitle (each word its own bubble)

    private void submitSubtitle(CompanionRenderState state, PoseStack poseStack,
                                SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.speech == null || state.speech.isBlank()) return;
        Font font = this.getFont();
        List<CompanionSubtitles.Word> words = CompanionSubtitles.layout(
                state.entityId, state.speechId, state.speech);
        if (words.isEmpty()) return;

        poseStack.pushPose();
        poseStack.translate(0.0, state.boundingBoxHeight + 0.2f, 0.0);    // starts right above the head (was +0.45)
        poseStack.mulPose(camera.orientation);
        poseStack.scale(SUBTITLE_SCALE, -SUBTITLE_SCALE, SUBTITLE_SCALE);

        for (CompanionSubtitles.Word w : words) {
            FormattedCharSequence seq = font.split(Component.literal(w.text()), 220).get(0);
            float wordW = font.width(seq);
            float s = w.scale();
            int a = (w.argb() >>> 24) & 0xFF;
            int bubble = (Math.min(255, a * 200 / 255) << 24) | 0x14181E;   // each word gets its own bubble
            poseStack.pushPose();
            poseStack.translate(w.x(), -w.y(), 0.0);                       // positive lift = upward
            if (Math.abs(w.rotDeg()) > 0.01f) poseStack.mulPose(Axis.ZP.rotationDegrees(w.rotDeg()));
            poseStack.scale(s, s, 1.0f);
            collector.submitText(poseStack, -wordW / 2.0f, 0.0f, seq, false,
                    Font.DisplayMode.SEE_THROUGH, state.lightCoords, w.argb(), bubble, 0);
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    /** Downed state: red countdown + revive progress (depth-tested so they can't be seen through walls). */
    private void submitInjured(CompanionRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.injuredLeft <= 0) return;
        int seconds = (state.injuredLeft + 19) / 20;
        FormattedCharSequence seq = getFont().split(Component.literal("REVIVE ME  " + seconds + "s"), 220).get(0);
        int color = seconds <= 10 ? 0xFFFF4444 : 0xFFFF8888;
        renderBillboardText(poseStack, collector, camera, state, seq,
                state.boundingBoxHeight + 0.35f, false, color, 0x33000000, seconds <= 10 ? 0xFFFF0000 : 0xFF880000);
        if (state.reviveProgress > 0.01f) {
            FormattedCharSequence rev = getFont().split(
                    Component.literal("Reviving... " + Math.round(state.reviveProgress * 100) + "%"), 220).get(0);
            renderBillboardText(poseStack, collector, camera, state, rev,
                    state.boundingBoxHeight + 0.05f, false, 0xFF7CFC7C, 0x33000000, 0xFF2E8B2E);
        }
    }

    private void renderBillboardText(PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera,
                                     CompanionRenderState state, FormattedCharSequence line,
                                     float worldY, boolean seeThrough, int color, int background, int outline) {
        poseStack.pushPose();
        poseStack.translate(0.0, worldY, 0.0);
        poseStack.mulPose(camera.orientation);
        poseStack.scale(SUBTITLE_SCALE, -SUBTITLE_SCALE, SUBTITLE_SCALE);
        Font.DisplayMode mode = seeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL;
        float width = this.getFont().width(line);
        collector.submitText(poseStack, -width / 2.0f, 0.0f, line, false, mode,
                state.lightCoords, color, background, outline);
        poseStack.popPose();
    }
}
