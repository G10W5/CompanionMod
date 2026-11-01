package com.example.companionmod.client;

import com.example.companionmod.voice.VoiceConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * "Who is my companion?" editor: name, backstory, personality and traits.
 * Everything is saved to the client config and immediately sent to the server (name).
 */
public class CompanionProfileScreen extends Screen {
    private final VoiceConfig config;
    private final Path configFile;
    private final Screen parent;

    private EditBox nameField;
    private EditBox descriptionField;
    private EditBox personalityField;
    private EditBox traitsField;

    public CompanionProfileScreen(VoiceConfig config, Path configFile, Screen parent) {
        super(Component.literal("Personalize Companion"));
        this.config = config;
        this.configFile = configFile;
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int w = 300;
        int y = 64;

        this.nameField = field(cx, y, w, config.companionName, "Name (e.g. Aria)");
        y += 34;
        this.descriptionField = field(cx, y, w, config.companionDescription,
                "Who are they? Backstory, role, quirks...");
        y += 34;
        this.personalityField = field(cx, y, w, config.companionPersonality,
                "Personality (friendly, sarcastic, brave...)");
        y += 34;
        this.traitsField = field(cx, y, w, config.companionTraits,
                "Traits, comma separated (loyal, curious, protective)");
        y += 40;

        this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .pos(cx - w / 2, y).size(w, 20).build());
        y += 24;
        this.addRenderableWidget(Button.builder(Component.literal("Reset to defaults"), b -> {
            nameField.setValue("Companion");
            descriptionField.setValue("A loyal companion fighting beside you.");
            personalityField.setValue("Friendly and helpful");
            traitsField.setValue("loyal, curious, protective");
        }).pos(cx - w / 2, y).size(w, 20).build());
        y += 24;
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> back())
                .pos(cx - w / 2, y).size(w, 20).build());
    }

    private EditBox field(int cx, int y, int w, String value, String hint) {
        EditBox box = new EditBox(this.font, cx - w / 2, y, w, 20, Component.literal(hint));
        box.setValue(value == null ? "" : value);
        box.setHint(Component.literal(hint));
        box.setMaxLength(240);
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
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        this.extractMenuBackground(g);
        int cx = this.width / 2;
        int w = 300;
        g.fill(cx - w / 2 - 12, 34, cx + w / 2 + 12, this.height - 28, 0xCC101216);
        g.fill(cx - w / 2 - 12, 34, cx + w / 2 + 12, 35, 0xFF3D7BB8);
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        g.centeredText(this.font, "Personalize Companion", cx, 42, 0xFFFFFFFF);
        g.centeredText(this.font, "This is who your companion is. It shapes every reply.", cx, 54, 0xFF9AA6B2);

        int y = 64;
        label(g, "Name", cx - w / 2, y - 11);
        label(g, "Who they are", cx - w / 2, y + 23);
        label(g, "Personality", cx - w / 2, y + 57);
        label(g, "Traits", cx - w / 2, y + 91);
    }

    private void label(GuiGraphicsExtractor g, String text, int x, int y) {
        g.text(this.font, text, x, y, 0xFFB8C2CC, true);
    }
}
