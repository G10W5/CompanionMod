package com.example.companionmod.client;

import com.example.companionmod.voice.VoiceConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * Skin library: type any Minecraft player name and the companion wears that skin.
 * The renderer downloads/caches it through the vanilla SkinManager, so it also works with
 * skins from NameMC-style player names.
 *
 * <p>Body type and the feminine model live only in {@link CompanionSettingsScreen}; this screen used to
 * duplicate them, which produced two places to edit the same value.</p>
 */
public final class CompanionSkinScreen extends Screen {
    private final VoiceConfig config;
    private final Path configFile;
    private EditBox nameField;

    public CompanionSkinScreen(VoiceConfig config, Path configFile) {
        super(Component.literal("Companion Skin Library"));
        this.config = config;
        this.configFile = configFile;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 2 - 60;

        this.nameField = new EditBox(this.font, cx - 110, y, 220, 20, Component.literal("Player name"));
        this.nameField.setValue(config.companionSkin == null ? "" : config.companionSkin);
        this.nameField.setHint(Component.literal("Minecraft player name (e.g. Notch)"));
        this.addRenderableWidget(this.nameField);
        y += 30;

        this.addRenderableWidget(Button.builder(Component.literal("Use my skin"), b -> {
            // "owner" resolves synchronously from the local player (and picks up Quick Skin local skins
            // plus the real slim/classic model); a raw name resolves asynchronously via Mojang and
            // falls back to Steve meanwhile, which is exactly what made slim detection unreliable.
            nameField.setValue("owner");
        }).pos(cx - 110, y).size(105, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Apply"), b -> apply()).pos(cx + 5, y).size(105, 20).build());
        y += 26;

        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> {
            if (this.minecraft != null) this.minecraft.setScreen(new CompanionRadialScreen());
        }).pos(cx - 110, y).size(220, 20).build());
    }

    private void apply() {
        String name = nameField.getValue() == null ? "" : nameField.getValue().trim();
        config.companionSkin = name;
        config.save(configFile);
        if (!name.isBlank()) ClientVoice.send("set_skin", "", name);
        if (this.minecraft != null) this.minecraft.setScreen(null);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        this.extractMenuBackground(g);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        g.centeredText(this.font, "Companion Skin Library", cx, 30, 0xFFFFFFFF);
        g.centeredText(this.font, "The companion downloads the skin once and caches it.", cx, 44, 0xFF9AA6B2);
        g.centeredText(this.font, "Tip: type \"owner\" to copy your own skin automatically.", cx, 56, 0xFF9AA6B2);
    }
}
