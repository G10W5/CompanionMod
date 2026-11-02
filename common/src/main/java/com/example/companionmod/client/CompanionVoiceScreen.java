package com.example.companionmod.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Voice picker for the selected TTS model. Detects which voices the Ollama server exposes for the chosen
 * speech model (GET /v1/audio/voices, falling back to the native GET /api/voices) and lists them as
 * clickable rows; clicking one assigns it to the Voice field. Voices are pre-fetched on a worker thread.
 */
public final class CompanionVoiceScreen extends Screen {
    private static final int ROW = 22;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private final String host;
    private final String model;
    private final Screen parent;
    private final Consumer<String> picker;
    private final List<String> voices;
    private final String status;

    private CompanionVoiceScreen(String host, String model, Screen parent, Consumer<String> picker,
                                 List<String> voices, String status) {
        super(Component.literal(voices.isEmpty() ? "Pick a Voice" : "Pick a Voice (" + voices.size() + ")"));
        this.host = host;
        this.model = model;
        this.parent = parent;
        this.picker = picker;
        this.voices = voices;
        this.status = status == null ? "" : status;
    }

    /** Opens the picker. {@code model} is the selected speech model (blank = list every voice the server has). */
    static void open(Minecraft mc, String host, String model, Screen parent, Consumer<String> picker) {
        String url = (host == null || host.isBlank()) ? "http://127.0.0.1:11434" : host;
        String m = model == null ? "" : model.trim();
        new Thread(() -> {
            List<String> fetched = fetch(url, m);
            String st = fetched.isEmpty()
                    ? "No voices answered at " + url + ". Is the speech model installed? (ollama pull "
                        + (m.isBlank() ? "phonon" : m) + ")"
                    : "";
            mc.execute(() -> mc.setScreen(new CompanionVoiceScreen(url, m, parent, picker, fetched, st)));
        }, "companionmod-voices").start();
    }

    private static List<String> fetch(String host, String model) {
        List<String[]> all = new ArrayList<>();           // {voiceId, model}
        collect(all, get(host + "/v1/audio/voices"), "data", "id");        // OpenAI-compatible
        if (all.isEmpty()) collect(all, get(host + "/api/voices"), "voices", "name");   // Ollama native
        if (model.isEmpty()) return ids(all);
        List<String> matching = new ArrayList<>();
        for (String[] v : all) {
            if (matchesModel(v[1], model)) matching.add(v[0]);
        }
        return matching.isEmpty() ? ids(all) : matching;   // filter unsure? show everything rather than nothing
    }

    /** Equal, or one is a tag-less prefix of the other ("phonon" vs "phonon:latest"). */
    private static boolean matchesModel(String entryModel, String model) {
        if (entryModel == null || entryModel.isBlank()) return true;
        return entryModel.equals(model) || entryModel.startsWith(model) || model.startsWith(entryModel);
    }

    private static List<String> ids(List<String[]> all) {
        List<String> out = new ArrayList<>();
        for (String[] v : all) out.add(v[0]);
        return out;
    }

    private static String get(String url) {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 ? r.body() : null;
        } catch (IOException | RuntimeException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Reads a JSON array of voice objects (top-level array, or nested under {@code arrayKey}). */
    private static void collect(List<String[]> out, String body, String arrayKey, String idKey) {
        if (body == null || body.isBlank()) return;
        try {
            JsonElement root = JsonParser.parseString(body);
            JsonElement arr = root.isJsonArray() ? root : (root.isJsonObject() ? root.getAsJsonObject().get(arrayKey) : null);
            if (arr == null || !arr.isJsonArray()) return;
            for (JsonElement e : arr.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject v = e.getAsJsonObject();
                String id = str(v, idKey);
                if (id == null || id.isBlank()) continue;
                out.add(new String[]{id, str(v, "model")});
            }
        } catch (RuntimeException ignored) { }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int y = 74;
        int listW = 190, colGap = 14;
        int cols = voices.size() > 12 ? 2 : 1;
        int rows = (voices.size() + cols - 1) / cols;
        int leftX = cx - (cols * listW + (cols - 1) * colGap) / 2;
        for (int i = 0; i < voices.size(); i++) {
            int c = i % cols, r = i / cols;
            int bx = leftX + c * (listW + colGap);
            int by = y + r * ROW;
            if (by + ROW > this.height - 80) break;            // keep above the footer
            final String voice = voices.get(i);
            this.addRenderableWidget(Button.builder(Component.literal(voice), b -> {
                picker.accept(voice);
                if (this.minecraft != null) this.minecraft.setScreen(parent);
            }).pos(bx, by).size(listW, 20).build());
        }
        y += Math.max(1, rows) * ROW;

        if (voices.isEmpty()) {
            this.addRenderableWidget(Button.builder(Component.literal("Retry"), b -> {
                if (this.minecraft != null) CompanionVoiceScreen.open(this.minecraft, host, model, parent, picker);
            }).pos(cx - 40, y).size(80, 20).build());
            y += ROW;
        }

        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                .pos(cx - 50, Math.max(y + 6, this.height - 30)).size(100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        this.extractMenuBackground(g);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        g.centeredText(this.font, this.title, cx, 30, 0xFFFFFFFF);
        if (!status.isBlank()) g.centeredText(this.font, status, cx, 44, 0xFFFF7777);
        g.centeredText(this.font, model.isBlank() ? "All voices on the server" : "Voices for '" + model + "'",
                cx, 56, 0xFF9AA6B2);
        g.centeredText(this.font, "Click a voice to use it for spoken replies.", cx, this.height - 24, 0xFF666666);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
