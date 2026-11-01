package com.example.companionmod.neoforge;

import com.example.companionmod.CompanionMod;
import com.example.companionmod.client.ClientVoice;
import com.example.companionmod.client.CompanionInventoryScreen;
import com.example.companionmod.client.CompanionRenderer;
import com.example.companionmod.registry.ModContent;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Replaces the old static @EventBusSubscriber client class. Differences from the first draft:
 *  - the mic key is a real, rebindable KeyMapping (was raw GLFW_KEY_V);
 *  - it is ignored while any screen is open (the old version fired while typing a "v" in chat);
 *  - all logic lives in common/ClientVoice so Fabric behaves identically.
 * 26.1-CHECK: event/class names (RegisterGuiLayersEvent, ClientPacketDistributor) against the NeoForge 26.1 primer.
 */
@Mod(value = CompanionMod.MOD_ID, dist = Dist.CLIENT)
public class CompanionModNeoForgeClient {
    public CompanionModNeoForgeClient(IEventBus modBus) {
        ClientVoice.setSender(ClientPacketDistributor::sendToServer);
        modBus.addListener(RegisterKeyMappingsEvent.class, e -> e.register(ClientVoice.talkKey()));
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class, e -> e.registerEntityRenderer(ModContent.COMPANION, CompanionRenderer::new));
        modBus.addListener(RegisterMenuScreensEvent.class, e ->
                e.register(ModContent.COMPANION_INV_MENU, CompanionInventoryScreen::new));
        modBus.addListener(RegisterGuiLayersEvent.class, e ->
                e.registerAboveAll(CompanionMod.id("voice_hud"), (graphics, deltaTracker) -> ClientVoice.renderHud(graphics, deltaTracker)));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> ClientVoice.tick(Minecraft.getInstance()));
    }
}
