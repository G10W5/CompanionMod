package com.example.companionmod.fabric;

import com.example.companionmod.CompanionMod;
import com.example.companionmod.client.ClientVoice;
import com.example.companionmod.client.CompanionInventoryScreen;
import com.example.companionmod.client.CompanionRenderer;
import com.example.companionmod.registry.ModContent;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.gui.screens.MenuScreens;

/** Fabric client entrypoint: renderer, HUD, and the shared voice/radial/skin logic in common/ClientVoice. */
public class CompanionModFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientVoice.setSender(ClientPlayNetworking::send);
        KeyMappingHelper.registerKeyMapping(ClientVoice.talkKey());   // all three show up in Controls, rebindable
        KeyMappingHelper.registerKeyMapping(ClientVoice.radialKey());
        KeyMappingHelper.registerKeyMapping(ClientVoice.settingsKey());
        EntityRendererRegistry.register(ModContent.COMPANION, CompanionRenderer::new);
        MenuScreens.register(ModContent.COMPANION_INV_MENU, CompanionInventoryScreen::new);
        HudElementRegistry.addLast(CompanionMod.id("voice_hud"),
                (graphics, deltaTracker) -> ClientVoice.renderHud(graphics, deltaTracker));
        ClientTickEvents.END_CLIENT_TICK.register(ClientVoice::tick);
    }
}
