package com.example.companionmod.client;

import com.example.companionmod.voice.VoiceConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * Skin library in the grey panel style: type any Minecraft player name and the companion wears that skin
 * (downloaded and cached through the vanilla SkinManager). "owner" copies your own skin.
 *
 * <p>Body type and the feminine model live only in {@link CompanionSettingsScreen}.</p>
 * <p>Back / Apply return to {@code parent}; with no parent (opened from the radial wheel) they close the screen.</p>
 */
public final class CompanionSkinScreen extends Screen {
    private static final int PANEL_W = 300, PAD = 12, HEAD_H = 44;

    private final VoiceConfig config;
    private final Path configFile;
    private final Screen parent;
    private EditBox nameField;
    private int px, py, panelW, panelH, ix, iw;

    public CompanionSkinScreen(VoiceConfig config, Path configFile) { this(config, configFile, null); }

    public CompanionSkinScreen(VoiceConfig config, Path configFile, Screen parent) {
        super(Component.literal("Appearance & Skins"));
        this.config = config;
        this.configFile = configFile;
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = Math.min(PANEL_W, this.width - 8);
        panelH = HEAD_H + 26 + 24 + 30 + 20 + 12;
        px = (this.width - panelW) / 2;
        py = Math.max(4, (this.height - panelH) / 2);
        ix = px + PAD;
        iw = panelW - 2 * PAD;

        int y = py + HEAD_H;
        nameField = new EditBox(this.font, ix, y, iw, 20, Component.literal("Player name"));
        nameField.setMaxLength(64);
        nameField.setValue(config.companionSkin == null ? "" : config.companionSkin);
        nameField.setHint(Component.literal("Minecraft player name (e.g. Notch)"));
        this.addRenderableWidget(nameField);
        y += 26;

        int colW = (iw - 4) / 2;
        // "owner" resolves synchronously from the local player (and picks up Quick Skin local skins plus the real
        // slim/classic model); a raw name resolves asynchronously via Mojang and shows Steve until it arrives.
        this.addRenderableWidget(Button.builder(Component.literal("Use My Skin"), b -> nameField.setValue("owner"))
                .pos(ix, y).size(colW, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Apply"), b -> apply())
                .pos(ix + colW + 4, y).size(colW, 20).build());
        y += 24 + 30;                                     // room for the two tip lines

        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> back())
                .pos(ix, y).size(iw, 20).build());
    }

    private void apply() {
        String name = nameField.getValue() == null ? "" : nameField.getValue().trim();
        config.companionSkin = name;
        config.save(configFile);
        if (!name.isBlank()) ClientVoice.send("set_skin", "", name);
        back();
    }

    private void back() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);   // null parent = close
    }

    @Override
    public void onClose() { back(); }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        CompanionUi.panel(g, px, py, px + panelW, py + panelH);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        String cur = config.companionSkin == null || config.companionSkin.isBlank() ? "default" : config.companionSkin;
        centered(g, "Appearance & Skins", cx, py + 8, CompanionUi.TEXT_DARK);
        centered(g, "Current skin: " + this.font.plainSubstrByWidth(cur, iw - 90), cx, py + 20, CompanionUi.TEXT_MUTED);
        g.text(this.font, "Skin (Minecraft player name)", ix, py + HEAD_H - 11, CompanionUi.TEXT_DARK, false);
        int ty = py + HEAD_H + 26 + 24 + 4;
        g.text(this.font, "Downloaded once, then cached.", ix, ty, CompanionUi.TEXT_MUTED, false);
        g.text(this.font, "Type \"owner\" to copy your own skin.", ix, ty + 12, CompanionUi.TEXT_MUTED, false);
    }

    private void centered(GuiGraphicsExtractor g, String s, int cx, int y, int color) {
        g.text(this.font, s, cx - this.font.width(s) / 2, y, color, false);
    }
}
