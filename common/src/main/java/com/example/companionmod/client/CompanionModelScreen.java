package com.example.companionmod.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Selection menu for the models exposed by the Ollama server (GET /api/tags). The host and one
 * {@code Consumer<String>} per role (Chat / Speech / STT) are passed in; click a model row to assign it
 * to the currently-selected role. Models are pre-fetched on a worker thread.
 */
public final class CompanionModelScreen extends Screen {
    private static final int ROW = 22;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private final String host;
    private final Map<String, Consumer<String>> assigners;   // role -> what to do with the chosen model
    private final Screen parent;
    private final List<String> models;
    private final String status;
    private int selected = 0;
    private Component roleLabel = Component.literal("Assigning to: " + "Chat");

    private CompanionModelScreen(String host, Map<String, Consumer<String>> assigners, List<String> models,
                                 String status, int selected, Screen parent) {
        super(Component.literal(models.isEmpty() ? "Pick a Model" : "Pick a Model (" + models.size() + ")"));
        this.host = host;
        this.assigners = assigners;
        this.models = models;
        this.status = status == null ? "" : status;
        this.selected = Math.max(0, Math.min(selected, assigners.size() - 1));
        this.parent = parent;
    }

    /** Opens the picker. {@code host} may be blank (defaults to the local Ollama URL). */
    static void open(Minecraft mc, String host, Screen parent,
                     Consumer<String> pickChat, Consumer<String> pickSpeech, Consumer<String> pickStt) {
        String url = (host == null || host.isBlank()) ? "http://127.0.0.1:11434" : host;
        new Thread(() -> {
            List<String> fetched = fetch(url);
            mc.execute(() -> {
                String st = fetched.isEmpty() ? "No models found at " + url : "";
                Map<String, Consumer<String>> m = new LinkedHashMap<>();
                m.put("Chat", pickChat); m.put("Speech", pickSpeech); m.put("STT", pickStt);
                mc.setScreen(new CompanionModelScreen(url, m, fetched, st, 0, parent));
            });
        }, "companionmod-models").start();
    }

    private static List<String> fetch(String host) {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(host + "/api/tags"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return List.of();
            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            JsonElement arr = o.get("models");
            if (arr == null || !arr.isJsonArray()) return List.of();
            List<String> out = new ArrayList<>();
            for (JsonElement e : arr.getAsJsonArray()) {
                if (e.isJsonObject()) {
                    String n = e.getAsJsonObject().has("name") ? e.getAsJsonObject().get("name").getAsString() : "";
                    if (n != null && !n.isBlank()) out.add(n);
                }
            }
            return out;
        } catch (IOException | RuntimeException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private String[] roleKeys() { return assigners.keySet().toArray(String[]::new); }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int y = 46;
        String[] keys = roleKeys();
        int bw = 72, gap = 6;
        int baseX = cx - (keys.length * bw + (keys.length - 1) * gap) / 2;
        for (int i = 0; i < keys.length; i++) {
            final int idx = i;
            this.addRenderableWidget(Button.builder(Component.literal(keys[i]), b -> {
                selected = idx;
                roleLabel = Component.literal("Assigning to: " + keys[idx]);
            }).pos(baseX + i * (bw + gap), y).size(bw, 20).build());
        }
        y += 28;

        int listW = 190, colGap = 14;
        int cols = models.size() > 12 ? 2 : 1;
        int rows = (models.size() + cols - 1) / cols;
        int leftX = cx - (cols * listW + (cols - 1) * colGap) / 2;
        for (int i = 0; i < models.size(); i++) {
            int c = i % cols, r = i / cols;
            int bx = leftX + c * (listW + colGap);
            int by = y + r * ROW;
            if (by + ROW > this.height - 80) break;            // keep above the footer
            final String model = models.get(i);
            this.addRenderableWidget(Button.builder(Component.literal(model), b -> {
                assigners.get(roleKeys()[selected]).accept(model);
                if (this.minecraft != null) this.minecraft.setScreen(parent);
            }).pos(bx, by).size(listW, 20).build());
        }
        y += Math.max(1, rows) * ROW;

        if (models.isEmpty()) {
            this.addRenderableWidget(Button.builder(Component.literal("Retry"), b -> {
                if (this.minecraft != null) CompanionModelScreen.open(
                        this.minecraft, host, parent,
                        assigners.get("Chat"), assigners.get("Speech"), assigners.get("STT"));
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
        g.centeredText(this.font, roleLabel, cx, 56, 0xFF9AA6B2);
        g.centeredText(this.font, "Click a model to assign it to " + roleKeys()[selected] + ".", cx, this.height - 24, 0xFF666666);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}