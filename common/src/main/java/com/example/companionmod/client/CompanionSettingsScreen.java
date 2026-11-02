package com.example.companionmod.client;

import com.example.companionmod.voice.VoiceConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Vanilla-style options screen: centred title, two columns of 150x20 buttons ("Name: Value" cycle buttons and sliders),
 * separator lines, one 200px Done button. Changes apply live and are saved when the screen closes (Done or Esc), like vanilla options.
 * Fields used on VoiceConfig: feminineModel, memoryEnabled, speechVolume, speakReplies, dynamicGoals,
 * autonomyEnabled, autonomyIntervalSeconds, companionBody, companionName, companionSkin.
 */
public class CompanionSettingsScreen extends Screen {
    private static final int COL_W = 150, GAP = 8;

    private final VoiceConfig config;
    private final Path configFile;
    private final Screen parent;
    private final boolean initialFeminine;
    private boolean feminine;

    public CompanionSettingsScreen(VoiceConfig config, Path configFile) { this(config, configFile, null); }

    public CompanionSettingsScreen(VoiceConfig config, Path configFile, Screen parent) {
        super(Component.literal("Companion Settings"));
        this.config = config;
        this.configFile = configFile;
        this.parent = parent;
        this.initialFeminine = config.feminineModel;
        this.feminine = initialFeminine;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int lx = cx - COL_W - GAP / 2, rx = cx + GAP / 2;
        int top = 42, rows = 6;
        int pitch = Math.max(22, Math.min(24, (this.height - 36 - top - 4) / rows));
        int y = top;

        nav(lx, y, "Personalize Companion...", () -> new CompanionProfileScreen(config, configFile, this));
        nav(rx, y, "Appearance & Skins...", () -> new CompanionSkinScreen(config, configFile));
        y += pitch;

        nav(lx, y, "AI Connection...", () -> new CompanionConnectionScreen(config, configFile, this));
        toggle(rx, y, "Dynamic Goals", config.dynamicGoals, v -> config.dynamicGoals = v);
        y += pitch;

        toggle(lx, y, "Feminine Model", feminine, v -> feminine = v);
        // 26.1-CHECK: CycleButton.builder signature
        this.addRenderableWidget(CycleButton.<String>builder(CompanionSettingsScreen::bodyName, config.companionBody == null ? "auto" : config.companionBody)
                .withValues("auto", "slim", "classic")
                .create(rx, y, COL_W, 20, Component.literal("Body Type"), (b, v) -> config.companionBody = v));
        y += pitch;

        toggle(lx, y, "Memory", config.memoryEnabled, v -> config.memoryEnabled = v);
        toggle(rx, y, "Spoken Replies", config.speakReplies, v -> config.speakReplies = v);
        y += pitch;

        toggle(lx, y, "Autonomy", config.autonomyEnabled, v -> config.autonomyEnabled = v);
        this.addRenderableWidget(new Slider(rx, y, COL_W, "Think Every", 15, 180, config.autonomyIntervalSeconds, "s",
                v -> config.autonomyIntervalSeconds = Math.round(v)));
        y += pitch;

        this.addRenderableWidget(new Slider(lx, y, COL_W * 2 + GAP, "Reply Volume", 0, 100, config.speechVolume * 100f, "%",
                v -> config.speechVolume = Math.max(0f, Math.min(1f, v / 100f))));

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .pos(cx - 100, this.height - 27).size(200, 20).build());
    }

    private static Component bodyName(String v) {
        return Component.literal(switch (v) { case "slim" -> "Slim"; case "classic" -> "Classic"; default -> "Auto (skin)"; });
    }

    private void nav(int x, int y, String label, java.util.function.Supplier<Screen> target) {
        this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
            if (this.minecraft != null) this.minecraft.setScreen(target.get());
        }).pos(x, y).size(COL_W, 20).build());
    }

    private void toggle(int x, int y, String name, boolean value, Consumer<Boolean> apply) {
        this.addRenderableWidget(CycleButton.onOffBuilder(value)
                .create(x, y, COL_W, 20, Component.literal(name), (b, v) -> apply.accept(v)));
    }

    /** Vanilla-looking slider with a "Name: value" label. */
    private static final class Slider extends AbstractSliderButton {
        private final String label, unit;
        private final float min, max;
        private final Consumer<Float> apply;

        Slider(int x, int y, int w, String label, float min, float max, float cur, String unit, Consumer<Float> apply) {
            super(x, y, w, 20, Component.empty(), max > min ? Math.max(0, Math.min(1, (cur - min) / (max - min))) : 0);
            this.label = label; this.unit = unit; this.min = min; this.max = max; this.apply = apply;
            updateMessage();
        }

        private float val() { return min + (float) this.value * (max - min); }

        @Override protected void updateMessage() {
            if (label == null) return;                       // super() calls this before our fields exist
            setMessage(Component.literal(label + ": " + Math.round(val()) + unit));
        }

        @Override protected void applyValue() { apply.accept(val()); }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    /** Runs on Done, Esc and when a sub-screen opens: persist and push changes to the server. */
    @Override
    public void removed() {
        config.feminineModel = feminine;
        config.speechVolume = Math.max(0f, Math.min(1f, config.speechVolume));
        config.save(configFile);
        ClientVoice.send("set_name", "", config.companionName);
        if (config.companionSkin != null && !config.companionSkin.isBlank()) ClientVoice.send("set_skin", "", config.companionSkin);
        if (feminine != initialFeminine) ClientVoice.send(feminine ? "feminine" : "masculine", "", "");
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        this.extractMenuBackground(g);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        g.centeredText(this.font, this.title, cx, 10, 0xFFFFFFFF);
        g.centeredText(this.font, "V = talk   R = wheel   O = settings", cx, 22, 0xFFA0A0A0);
        g.fill(0, 32, this.width, 33, 0xFF000000);                       // header separator
        g.fill(0, 33, this.width, 34, 0xFF373737);
        g.fill(0, this.height - 33, this.width, this.height - 32, 0xFF000000);      // footer separator
        g.fill(0, this.height - 32, this.width, this.height - 31, 0xFF373737);
    }
}
