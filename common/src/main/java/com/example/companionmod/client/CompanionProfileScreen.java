package com.example.companionmod.client;

import com.example.companionmod.voice.VoiceConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * "Who is my companion?" editor (name, backstory, personality, traits) in the same grey panel style as the
 * settings screen. Save writes the config and sends the name to the server; Cancel discards. Both return to
 * {@code parent} (or close the screen when it was opened without one, e.g. from the radial wheel).
 */
public class CompanionProfileScreen extends Screen {
    private static final int PANEL_W = 300, PAD = 12, HEAD_H = 44, PITCH = 32;

    private final VoiceConfig config;
    private final Path configFile;
    private final Screen parent;

    private EditBox nameField, descriptionField, personalityField, traitsField;
    private int px, py, panelW, panelH, ix, iw;

    public CompanionProfileScreen(VoiceConfig config, Path configFile, Screen parent) {
        super(Component.literal("Personalize Companion"));
        this.config = config;
        this.configFile = configFile;
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = Math.min(PANEL_W, this.width - 8);
        panelH = HEAD_H + 4 * PITCH + 4 + 24 + 20 + 12;
        px = (this.width - panelW) / 2;
        py = Math.max(4, (this.height - panelH) / 2);
        ix = px + PAD;
        iw = panelW - 2 * PAD;

        int y = py + HEAD_H;
        nameField = field(y, config.companionName, "Name (e.g. Aria)");
        y += PITCH;
        descriptionField = field(y, config.companionDescription, "Who are they? Backstory, role, quirks...");
        y += PITCH;
        personalityField = field(y, config.companionPersonality, "Friendly, sarcastic, brave...");
        y += PITCH;
        traitsField = field(y, config.companionTraits, "Comma separated: loyal, curious");
        y += PITCH + 4;

        int colW = (iw - 4) / 2;
        this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .pos(ix, y).size(colW, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Reset to Defaults"), b -> {
            nameField.setValue("Companion");
            descriptionField.setValue("A loyal companion fighting beside you.");
            personalityField.setValue("Friendly and helpful");
            traitsField.setValue("loyal, curious, protective");
        }).pos(ix + colW + 4, y).size(colW, 20).build());
        y += 24;
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> back())
                .pos(ix, y).size(iw, 20).build());
    }

    private EditBox field(int y, String value, String hint) {
        EditBox box = new EditBox(this.font, ix, y, iw, 20, Component.literal(hint));
        box.setMaxLength(240);
        box.setValue(value == null ? "" : value);
        box.setHint(Component.literal(hint));
        this.addRenderableWidget(box);
        return box;
    }

    private void save() {
        config.companionName = blankTo(nameField.getValue(), "Companion");
        config.companionDescription = blankTo(descriptionField.getValue(), "A loyal companion fighting beside you.");
        config.companionPersonality = blankTo(personalityField.getValue(), "Friendly and helpful");
        config.companionTraits = blankTo(traitsField.getValue(), "loyal, curious, protective");
        config.save(configFile);
        ClientVoice.send("set_name", "", config.companionName);
        back();
    }

    private void back() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);   // null parent = close
    }

    @Override
    public void onClose() { back(); }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

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
        centered(g, "Personalize Companion", cx, py + 8, CompanionUi.TEXT_DARK);
        centered(g, "Who your companion is. It shapes every reply.", cx, py + 20, CompanionUi.TEXT_MUTED);
        int y = py + HEAD_H - 11;
        label(g, "Name", y);
        label(g, "Who they are", y + PITCH);
        label(g, "Personality", y + 2 * PITCH);
        label(g, "Traits", y + 3 * PITCH);
    }

    private void label(GuiGraphicsExtractor g, String s, int y) {
        g.text(this.font, s, ix, y, CompanionUi.TEXT_DARK, false);
    }

    private void centered(GuiGraphicsExtractor g, String s, int cx, int y, int color) {
        g.text(this.font, s, cx - this.font.width(s) / 2, y, color, false);
    }
}
