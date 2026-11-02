package com.example.companionmod.client;

import com.example.companionmod.voice.VoiceConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/** AI connection settings: Ollama host, chat/STT/speech models and temperature. */
public class CompanionConnectionScreen extends Screen {
    private final VoiceConfig config;
    private final Path configFile;
    private final Screen parent;

    private EditBox hostField;
    private EditBox chatField;
    private EditBox sttField;
    private EditBox speechField;
    private EditBox voiceField;
    private float temperature;

    public CompanionConnectionScreen(VoiceConfig config, Path configFile, Screen parent) {
        super(Component.literal("AI Connection"));
        this.config = config;
        this.configFile = configFile;
        this.parent = parent;
        this.temperature = config.temperature;
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int w = 300;
        int y = 64;

        this.hostField = field(cx, y, w, config.host, "http://127.0.0.1:11434");
        y += 34;
        this.chatField = field(cx, y, w, config.chatModel, "Chat model (e.g. qwen3:8b)");
        y += 34;
        this.sttField = field(cx, y, w, config.sttModel, "STT model (optional, e.g. whisper)");
        y += 34;
        this.speechField = field(cx, y, w, config.speechModel, "Speech model (empty = auto)");
        y += 34;
        this.voiceField = field(cx, y, w, config.speechVoice, "Voice (optional)");
        y += 24;
        this.addRenderableWidget(Button.builder(Component.literal("Detect voices from the speech model"), b -> detectVoices())
                .pos(cx - w / 2, y).size(w, 20).build());
        y += 32;

        this.addRenderableWidget(new AbstractSliderButton(cx - w / 2, y, w, 20,
                Component.literal("Temperature: " + String.format("%.2f", temperature)), temperature / 1.5) {
            @Override protected void updateMessage() {
                temperature = (float) (this.value * 1.5);
                setMessage(Component.literal("Temperature: " + String.format("%.2f", temperature)));
            }
            @Override protected void applyValue() { }
        });
        y += 28;

        this.addRenderableWidget(Button.builder(Component.literal("Load models from server"), b -> loadModels())
                .pos(cx - w / 2, y).size(w, 20).build());
        y += 24;

        this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .pos(cx - w / 2, y).size(w, 20).build());
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

    private void loadModels() {
        String h = hostField.getValue() == null ? "" : hostField.getValue().trim();
        CompanionModelScreen.open(this.minecraft, h, this,
                v -> chatField.setValue(v),
                v -> speechField.setValue(v),
                v -> sttField.setValue(v));
    }

    /** Asks the server which voices the selected speech model supports, then picks one from the list. */
    private void detectVoices() {
        String h = hostField.getValue() == null ? "" : hostField.getValue().trim();
        String m = speechField.getValue() == null ? "" : speechField.getValue().trim();
        CompanionVoiceScreen.open(this.minecraft, h, m, this, v -> voiceField.setValue(v));
    }

    private void save() {
        config.host = trim(hostField.getValue(), "http://127.0.0.1:11434");
        config.chatModel = trim(chatField.getValue(), "lfm3-audio:latest");
        config.sttModel = sttField.getValue() == null ? "" : sttField.getValue().trim();
        config.speechModel = speechField.getValue() == null ? "" : speechField.getValue().trim();
        config.speechVoice = voiceField.getValue() == null ? "" : voiceField.getValue().trim();
        config.temperature = Math.max(0f, Math.min(1.5f, temperature));
        config.save(configFile);
        back();
    }

    private void back() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    private static String trim(String value, String fallback) {
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

        g.centeredText(this.font, "AI Connection", cx, 42, 0xFFFFFFFF);
        g.centeredText(this.font, "Ollama or any OpenAI-compatible server", cx, 54, 0xFF9AA6B2);
        int y = 64;
        label(g, "Server URL", cx - w / 2, y - 11);
        label(g, "Chat model (hears or reads)", cx - w / 2, y + 23);
        label(g, "Speech-to-text model", cx - w / 2, y + 57);
        label(g, "Text-to-speech model", cx - w / 2, y + 91);
        label(g, "Voice", cx - w / 2, y + 125);
    }

    private void label(GuiGraphicsExtractor g, String text, int x, int y) {
        g.text(this.font, text, x, y, 0xFFB8C2CC, true);
    }
}
