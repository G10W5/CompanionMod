package com.example.companionmod.client;

import com.example.companionmod.voice.AudioDevices;
import com.example.companionmod.voice.LevelMeter;
import com.example.companionmod.voice.MicCapture;
import com.example.companionmod.voice.OllamaCatalog;
import com.example.companionmod.voice.VoiceConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.example.companionmod.client.CompanionDropdowns.Dropdown;
import com.example.companionmod.client.CompanionDropdowns.Option;

/**
 * Voice-Chat-mod style settings: one grey bevelled panel with four tabs (General / AI / Mic / Voice).
 * Models, voices, microphones and speakers are dropdowns filled from the server / audio system; the Mic tab has a
 * live level bar. Replaces the old CompanionConnectionScreen, CompanionModelScreen, CompanionVoiceScreen and
 * CompanionAudioDeviceScreen. Changes apply live and are saved when the screen closes (Done, Esc, or opening a sub-screen).
 */
public class CompanionSettingsScreen extends Screen {
    public enum Tab {
        GENERAL("General"), AI("AI"), MIC("Mic"), VOICE("Voice");
        final String label;
        Tab(String label) { this.label = label; }
    }

    private static Tab lastTab = Tab.GENERAL;

    private static final int PANEL_W = 300, PAD = 12, ROW = 24;
    private static final int HEAD_H = 52, CONTENT_H = 132, FOOT_H = 32;

    private final VoiceConfig config;
    private final Path configFile;
    private final Screen parent;
    private final boolean initialFeminine;
    private boolean feminine;
    private Tab tab;

    // geometry (recomputed in init)
    private int px, py, panelW, panelH, ix, iw, cy;

    private CompanionDropdowns dropdowns;
    private Dropdown chatDd, sttDd, ttsDd, voiceDd, micDd, spkDd;
    private EditBox hostBox;
    private Button femButton;
    private final List<Dropdown> editables = new ArrayList<>();

    // server catalog (survives tab switches and resizes)
    private boolean catalogRequested;
    private List<String> modelNames = List.of();
    private List<String> voiceNames = List.of();
    private boolean modelsLoading, modelsFailed, voicesLoading;
    private int modelGen, voiceGen;
    private List<String> inputDevices, outputDevices;

    // mic test (only runs while the Mic tab is showing)
    private final LevelMeter meter = new LevelMeter();
    private volatile MicCapture mic;
    private volatile String micError = "";

    public CompanionSettingsScreen(VoiceConfig config, Path configFile) { this(config, configFile, null); }

    public CompanionSettingsScreen(VoiceConfig config, Path configFile, Screen parent) { this(config, configFile, parent, lastTab); }

    public CompanionSettingsScreen(VoiceConfig config, Path configFile, Screen parent, Tab tab) {
        super(Component.literal("Companion Settings"));
        this.config = config;
        this.configFile = configFile;
        this.parent = parent;
        this.initialFeminine = config.feminineModel;
        this.feminine = initialFeminine;
        this.tab = tab == null ? Tab.GENERAL : tab;
    }

    // ---- layout -------------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        dropdowns = new CompanionDropdowns(this.font, () -> this.height);
        chatDd = sttDd = ttsDd = voiceDd = micDd = spkDd = null;
        hostBox = null;
        editables.clear();

        panelW = Math.min(PANEL_W, this.width - 8);
        panelH = HEAD_H + CONTENT_H + FOOT_H;
        px = (this.width - panelW) / 2;
        py = Math.max(4, (this.height - panelH) / 2);
        ix = px + PAD;
        iw = panelW - 2 * PAD;
        cy = py + HEAD_H;

        int gap = 2, tw = (iw - gap * (Tab.values().length - 1)) / Tab.values().length;
        for (int i = 0; i < Tab.values().length; i++) {
            final Tab t = Tab.values()[i];
            Button b = Button.builder(Component.literal(t.label), x -> switchTab(t))
                    .pos(ix + i * (tw + gap), py + 22).size(tw, 20).build();
            b.active = t != tab;
            this.addRenderableWidget(b);
        }

        switch (tab) {
            case GENERAL -> buildGeneral();
            case AI -> buildAi();
            case MIC -> buildMic();
            case VOICE -> buildVoice();
        }

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .pos(ix, py + panelH - 26).size(iw, 20).build());

        if (tab == Tab.MIC) startMic(); else stopMic();
        if (!catalogRequested) { catalogRequested = true; loadModels(); loadVoices(); }
        applyOptions();
    }

    private void switchTab(Tab t) {
        lastTab = t;
        this.tab = t;
        this.inputDevices = null;                     // re-enumerate: picks up hot-plugged devices
        this.outputDevices = null;
        this.rebuildWidgets();
    }

    private void buildGeneral() {
        int colW = (iw - 4) / 2, lx = ix, rx = ix + colW + 4, y = cy;

        nav(lx, y, colW, "Personalize...", () -> new CompanionProfileScreen(config, configFile, this));
        nav(rx, y, colW, "Appearance...", () -> new CompanionSkinScreen(config, configFile, this));
        y += ROW;

        toggle(lx, y, colW, "Dynamic Goals", config.dynamicGoals, v -> config.dynamicGoals = v);
        toggle(rx, y, colW, "Autonomy", config.autonomyEnabled, v -> config.autonomyEnabled = v);
        y += ROW;

        toggle(lx, y, iw, "Memory", config.memoryEnabled, v -> config.memoryEnabled = v);
        y += ROW;

        toggle(lx, y, iw, "Load Chunks", config.forceLoadChunks, v -> {
            config.forceLoadChunks = v;
            ClientVoice.send("chunks", "", v ? "on" : "off");       // server keeps his 3x3 chunk area force-loaded
        });
        y += ROW;

        // 26.1-CHECK: CycleButton.builder signature (copied unchanged from the old screen)
        this.addRenderableWidget(CycleButton.<String>builder(CompanionSettingsScreen::bodyName, config.companionBody == null ? "auto" : config.companionBody)
                .withValues("auto", "slim", "classic")
                .create(lx, y, colW - 24, 20, Component.literal("Body Type"), (b, v) -> config.companionBody = v));
        if (com.example.companionmod.voice.FemaleGenderCompat.isPresent()) {   // only meaningful with Wildfire's mod
            femButton = Button.builder(Component.empty(), b -> {
                feminine = !feminine;
                updateFemButton();
            }).pos(lx + colW - 20, y).size(20, 20).build();
            updateFemButton();
            this.addRenderableWidget(femButton);
        }
        this.addRenderableWidget(new Slider(rx, y, colW, "Think Every", 15, 180, config.autonomyIntervalSeconds, 0, "s",
                v -> config.autonomyIntervalSeconds = Math.round(v)));
    }

    /** Circle icon: filled pink = on, hollow grey = off. The state is only named in the hover tooltip. */
    private void updateFemButton() {
        if (femButton == null) return;                          // hidden when Wildfire's mod is not installed
        femButton.setMessage(feminine
                ? Component.literal("\u25CF").withStyle(ChatFormatting.LIGHT_PURPLE)
                : Component.literal("\u25CB").withStyle(ChatFormatting.GRAY));
        femButton.setTooltip(Tooltip.create(Component.literal(feminine ? "Feminine: On" : "Feminine: Off")));
    }

    private void buildAi() {
        int y = cy + 11;                               // "Server URL" label sits above the box
        hostBox = new EditBox(this.font, ix, y, iw - 24, 20, Component.literal("Server URL"));
        hostBox.setMaxLength(240);
        hostBox.setHint(Component.literal(OllamaCatalog.DEFAULT_HOST));
        hostBox.setValue(config.host == null ? "" : config.host);
        hostBox.setResponder(v -> config.host = v);
        this.addRenderableWidget(hostBox);
        this.addRenderableWidget(Button.builder(Component.literal("\u21BB"), b -> reloadCatalog())
                .pos(ix + iw - 20, y).size(20, 20).build());
        y += ROW;

        chatDd = dd(ix, y, iw, "Chat Model", () -> config.chatModel, v -> config.chatModel = v, true);
        y += ROW;
        sttDd = dd(ix, y, iw, "STT Model", () -> config.sttModel, v -> config.sttModel = v, true);
        y += ROW;
        this.addRenderableWidget(new Slider(ix, y, iw, "Temperature", 0f, 1.5f, config.temperature, 2, "",
                v -> config.temperature = Math.round(v * 100f) / 100f));
    }

    private void buildMic() {
        if (inputDevices == null) inputDevices = new ArrayList<>(AudioDevices.inputs());
        micDd = dd(ix, cy, iw, "Microphone", () -> config.micDevice, v -> { config.micDevice = v; restartMic(); });
        List<Option> opts = new ArrayList<>();
        opts.add(new Option("", "System default"));
        for (String n : inputDevices) opts.add(new Option(n, n));
        micDd.options = opts;

        int y = cy + 68;
        this.addRenderableWidget(new Slider(ix, y, iw, "Silence Gate", 0, 50, config.silenceGate * 100f, 0, "%",
                v -> config.silenceGate = Math.max(0f, Math.min(0.5f, v / 100f))));
        y += ROW;
        this.addRenderableWidget(new Slider(ix, y, iw, "Max Recording", 5, 60, config.maxRecordSeconds, 0, "s",
                v -> config.maxRecordSeconds = Math.round(v)));
    }

    private void buildVoice() {
        int y = cy;
        toggle(ix, y, iw, "Spoken Replies", config.speakReplies, v -> config.speakReplies = v);
        y += ROW;

        if (outputDevices == null) outputDevices = new ArrayList<>(AudioDevices.outputs());
        spkDd = dd(ix, y, iw, "Speaker", () -> config.outputDevice, v -> config.outputDevice = v);
        List<Option> opts = new ArrayList<>();
        opts.add(new Option("", "System default (Minecraft)"));
        for (String n : outputDevices) opts.add(new Option(n, n));
        spkDd.options = opts;
        y += ROW;

        ttsDd = dd(ix, y, iw, "Speech Model", () -> config.speechModel, v -> { config.speechModel = v; loadVoices(); }, true);
        y += ROW;
        voiceDd = dd(ix, y, iw, "Voice", () -> config.speechVoice, v -> config.speechVoice = v, true);
        y += ROW;

        this.addRenderableWidget(new Slider(ix, y, iw, "Reply Volume", 0, 100, config.speechVolume * 100f, 0, "%",
                v -> config.speechVolume = Math.max(0f, Math.min(1f, v / 100f))));
    }

    // ---- widget helpers -----------------------------------------------------------------------------------------

    private Dropdown dd(int x, int y, int w, String title, Supplier<String> get, Consumer<String> set) {
        return dd(x, y, w, title, get, set, false);
    }

    /** {@code editable}: adds a pencil button that swaps the dropdown for a text box, so any value can be typed. */
    private Dropdown dd(int x, int y, int w, String title, Supplier<String> get, Consumer<String> set, boolean editable) {
        Dropdown d = new Dropdown(title, get, set);
        int hw = editable ? w - 22 : w;
        d.button = this.addRenderableWidget(Button.builder(Component.literal(title), b -> dropdowns.toggle(d))
                .pos(x, y).size(hw, 20).build());
        if (editable) {
            EditBox box = new EditBox(this.font, x, y, hw, 20, Component.literal(title));
            box.setMaxLength(120);
            box.setHint(Component.literal("Type a name, Enter to confirm"));
            box.visible = false;
            d.editor = this.addRenderableWidget(box);
            d.editButton = this.addRenderableWidget(Button.builder(Component.literal("\u270E"), b -> toggleEdit(d))
                    .pos(x + w - 20, y).size(20, 20).build());
            editables.add(d);
        }
        dropdowns.register(d);
        return d;
    }

    private void toggleEdit(Dropdown d) {
        if (d.editing) { commitEdit(d); return; }
        dropdowns.close();
        d.editing = true;
        d.button.visible = false;
        String cur = d.getter.get();
        d.editor.setValue(cur == null ? "" : cur);
        d.editor.visible = true;
        this.setFocused(d.editor);
        d.editButton.setMessage(Component.literal("\u2714"));
    }

    private void commitEdit(Dropdown d) {
        String v = d.editor.getValue() == null ? "" : d.editor.getValue().trim();
        endEdit(d);
        d.setter.accept(v);
        dropdowns.refresh(d);
    }

    private void cancelEdit(Dropdown d) {
        endEdit(d);
        dropdowns.refresh(d);
    }

    private void endEdit(Dropdown d) {
        d.editing = false;
        d.editor.visible = false;
        d.button.visible = true;
        d.editButton.setMessage(Component.literal("\u270E"));
    }

    private void nav(int x, int y, int w, String label, Supplier<Screen> target) {
        this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
            if (this.minecraft != null) this.minecraft.setScreen(target.get());
        }).pos(x, y).size(w, 20).build());
    }

    private void toggle(int x, int y, int w, String name, boolean value, Consumer<Boolean> apply) {
        this.addRenderableWidget(CycleButton.onOffBuilder(value)
                .create(x, y, w, 20, Component.literal(name), (b, v) -> apply.accept(v)));
    }

    private static Component bodyName(String v) {
        return Component.literal(switch (v) { case "slim" -> "Slim"; case "classic" -> "Classic"; default -> "Auto"; });
    }

    /** Vanilla slider with a "Name: value" label. */
    private static final class Slider extends AbstractSliderButton {
        private final String label, unit;
        private final float min, max;
        private final int decimals;
        private final Consumer<Float> apply;

        Slider(int x, int y, int w, String label, float min, float max, float cur, int decimals, String unit, Consumer<Float> apply) {
            super(x, y, w, 20, Component.empty(), max > min ? Math.max(0, Math.min(1, (cur - min) / (max - min))) : 0);
            this.label = label; this.unit = unit; this.min = min; this.max = max; this.decimals = decimals; this.apply = apply;
            updateMessage();
        }

        private float val() { return min + (float) this.value * (max - min); }

        @Override protected void updateMessage() {
            if (label == null) return;                       // super() calls this before our fields exist
            String s = decimals == 0 ? String.valueOf(Math.round(val())) : String.format(java.util.Locale.ROOT, "%." + decimals + "f", val());
            setMessage(Component.literal(label + ": " + s + unit));
        }

        @Override protected void applyValue() { apply.accept(val()); }
    }

    // ---- server catalog -----------------------------------------------------------------------------------------

    private void reloadCatalog() {
        loadModels();
        loadVoices();
    }

    private void loadModels() {
        final int gen = ++modelGen;
        modelsLoading = true;
        modelsFailed = false;
        applyOptions();
        final String host = config.host;
        final Minecraft mc = this.minecraft;
        Thread t = new Thread(() -> {
            List<String> r = OllamaCatalog.models(host);
            mc.execute(() -> {
                if (gen != modelGen) return;
                modelsLoading = false;
                modelsFailed = r == null;
                modelNames = r == null ? List.of() : r;
                applyOptions();
            });
        }, "companionmod-models");
        t.setDaemon(true);
        t.start();
    }

    private void loadVoices() {
        final int gen = ++voiceGen;
        voicesLoading = true;
        applyOptions();
        final String host = config.host, model = config.speechModel;
        final Minecraft mc = this.minecraft;
        Thread t = new Thread(() -> {
            List<String> r = OllamaCatalog.voices(host, model);
            mc.execute(() -> {
                if (gen != voiceGen) return;
                voicesLoading = false;
                voiceNames = r;
                applyOptions();
            });
        }, "companionmod-voices");
        t.setDaemon(true);
        t.start();
    }

    private List<Option> modelOptions(String noneLabel) {
        List<Option> out = new ArrayList<>();
        if (noneLabel != null) out.add(new Option("", noneLabel));
        for (String m : modelNames) out.add(new Option(m, m));
        return out;
    }

    /** Pushes the freshest lists into whichever dropdowns the current tab has. */
    private void applyOptions() {
        if (dropdowns == null) return;
        String modelEmpty = modelsFailed ? "Server unreachable - use the pencil" : "None found - use the pencil";
        if (chatDd != null) { chatDd.options = modelOptions(null); chatDd.loading = modelsLoading; chatDd.emptyText = modelEmpty; }
        if (sttDd != null) { sttDd.options = modelOptions("None (audio model)"); sttDd.loading = modelsLoading; sttDd.emptyText = modelEmpty; }
        if (ttsDd != null) { ttsDd.options = modelOptions("Auto"); ttsDd.loading = modelsLoading; ttsDd.emptyText = modelEmpty; }
        if (voiceDd != null) {
            List<Option> v = new ArrayList<>();
            v.add(new Option("", "Server default"));
            for (String n : voiceNames) v.add(new Option(n, n));
            voiceDd.options = v;
            voiceDd.loading = voicesLoading;
            voiceDd.emptyText = "No voices - ollama pull "
                    + ((config.speechModel == null || config.speechModel.isBlank()) ? "phonon" : config.speechModel)
                    + " (or use the pencil)";
        }
        dropdowns.refreshAll();
    }

    // ---- mic test -----------------------------------------------------------------------------------------------

    private void startMic() {
        if (mic != null) return;
        beginMic(null);
    }

    private void restartMic() {
        beginMic(mic);
    }

    /** Opens a fresh capture on a worker thread, after closing {@code old}, so two lines never fight over one device. */
    private void beginMic(MicCapture old) {
        final MicCapture m = new MicCapture(meter, () -> 1, () -> config.micDevice);
        mic = m;
        micError = "";
        Thread t = new Thread(() -> {
            if (old != null) old.stop();
            meter.reset();
            if (mic != m) return;
            if (!m.start()) { micError = m.lastError(); return; }
            if (mic != m) m.stop();                      // tab was left while the line was still opening
        }, "companionmod-mictest");
        t.setDaemon(true);
        t.start();
    }

    private void stopMic() {
        final MicCapture m = mic;
        mic = null;
        if (m == null) return;
        Thread t = new Thread(() -> { m.stop(); meter.reset(); }, "companionmod-mictest-stop");
        t.setDaemon(true);
        t.start();
    }

    // ---- input --------------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (dropdowns.mouseClicked(event.x(), event.y(), event.button())) return true;
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (dropdowns.mouseScrolled(mouseX, mouseY, scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int k = event.key();
        for (Dropdown d : editables) {
            if (!d.editing) continue;
            if (k == GLFW.GLFW_KEY_ESCAPE) { cancelEdit(d); return true; }
            if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) { commitEdit(d); return true; }
        }
        if (k == GLFW.GLFW_KEY_ESCAPE && dropdowns.escape()) return true;
        if ((k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) && hostBox != null && hostBox.isFocused()) {
            reloadCatalog();
            return true;
        }
        return super.keyPressed(event);
    }

    // ---- lifecycle ----------------------------------------------------------------------------------------------

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    /** Runs on Done, Esc and when a sub-screen opens: persist and push changes to the server. */
    @Override
    public void removed() {
        stopMic();
        config.host = OllamaCatalog.normalize(config.host);
        if (config.chatModel == null || config.chatModel.isBlank()) config.chatModel = "lfm3-audio:latest";
        if (config.sttModel == null) config.sttModel = "";
        if (config.speechModel == null) config.speechModel = "";
        if (config.speechVoice == null) config.speechVoice = "";
        if (config.micDevice == null) config.micDevice = "";
        if (config.outputDevice == null) config.outputDevice = "";
        config.temperature = Math.max(0f, Math.min(1.5f, config.temperature));
        config.feminineModel = feminine;
        config.speechVolume = Math.max(0f, Math.min(1f, config.speechVolume));
        config.save(configFile);
        ClientVoice.send("set_name", "", config.companionName);
        if (config.companionSkin != null && !config.companionSkin.isBlank()) ClientVoice.send("set_skin", "", config.companionSkin);
        if (feminine != initialFeminine) ClientVoice.send(feminine ? "feminine" : "masculine", "", "");
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ---- rendering ----------------------------------------------------------------------------------------------

    /** The panel is drawn as part of the background so the vanilla blur / darkening can never paint over it. */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        CompanionUi.panel(g, px, py, px + panelW, py + panelH);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);       // background + panel + widgets

        int cx = this.width / 2;
        centered(g, this.title.getString(), cx, py + 8, CompanionUi.TEXT_DARK);

        switch (tab) {
            case GENERAL -> centered(g, "V = talk   R = wheel   O = settings", cx, cy + 4 * ROW + 6, CompanionUi.TEXT_MUTED);
            case AI -> {
                text(g, "Server URL (Ollama or OpenAI-compatible)", ix, cy, CompanionUi.TEXT_DARK);
                int sy = cy + 108;
                if (modelsLoading) text(g, "Contacting server...", ix, sy, CompanionUi.TEXT_MUTED);
                else if (modelsFailed) text(g, "Could not reach the server. Press the refresh button.", ix, sy, CompanionUi.TEXT_ERROR);
                else text(g, modelNames.size() + " model(s) on the server", ix, sy, CompanionUi.TEXT_MUTED);
            }
            case MIC -> {
                text(g, "Input level - speak to test", ix, cy + 27, CompanionUi.TEXT_DARK);
                CompanionUi.levelBar(g, ix, cy + 40, iw, 10, meter.level(), meter.peak(), config.silenceGate);
                String err = micError;
                if (err != null && !err.isEmpty()) text(g, this.font.plainSubstrByWidth(err, iw), ix, cy + 55, CompanionUi.TEXT_ERROR);
                else text(g, "Red mark = silence gate (quieter is ignored)", ix, cy + 55, CompanionUi.TEXT_MUTED);
            }
            case VOICE -> { }
        }

        dropdowns.extract(g, mouseX, mouseY);                           // open list goes on top of everything
    }

    private void text(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        g.text(this.font, s, x, y, color, false);
    }

    private void centered(GuiGraphicsExtractor g, String s, int cx, int y, int color) {
        g.text(this.font, s, cx - this.font.width(s) / 2, y, color, false);
    }
}
