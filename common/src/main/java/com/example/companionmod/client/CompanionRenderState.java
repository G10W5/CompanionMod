package com.example.companionmod.client;

import com.example.companionmod.CompanionMod;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * Extra render data carried from the entity to the renderer (skin + slim model +
 * injury state + subtitle). The resolved texture/render-type lives here, not in the renderer,
 * because 26.1 extracts every entity before submitting any of them — a field on the renderer would be
 * shared between companions.
 */
public class CompanionRenderState extends HumanoidRenderState {
    public static final Identifier DEFAULT = CompanionMod.id("textures/entity/companion.png");

    public String skin = "";
    public boolean slim;
    public boolean slimSkin;             // auto-detected from the resolved skin (Alex model)
    public Identifier texture = DEFAULT;
    public RenderType renderType;         // null = use the model texture (bundled skin), non-null = live player skin
    public Object wgmData;               // Wildfire GenderRenderState built at extract; re-applied at submit (something clears render data between the two phases)

    /** Current floating line and the local animation clock (milliseconds). */
    public String speech = "";
    public String speechSpeaker = "";
    public int speechId;
    public long speechStart;

    /** Downed state + synced revive progress / timer for the world HUD. */
    public int entityId;
    public boolean injured;
    public float reviveProgress;
    public int injuredLeft;
}
