package com.example.companionmod.client;

import com.example.companionmod.build.BuildSpec;
import com.example.companionmod.build.SpecParser;
import com.example.companionmod.net.BuildOp;
import com.example.companionmod.voice.BuildSpecBackend;
import com.example.companionmod.voice.VoiceConfig;
import com.example.companionmod.world.BuildSources;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * "Build" menu in the mod's grey bevelled panel look: describe a structure (the local model designs it) or pick
 * an .nbt from config/companionmod/schematics. Either way the design goes to the server, which previews it with
 * a particle outline that follows the player's aim; rotate / mirror / place are clickable chat controls.
 */
public class CompanionBuildScreen extends Screen {
    private static final int PANEL_W = 340, PAD = 12;
    private static final int HEAD_H = 26, CONTENT_H = 96, FOOT_H = 34;

    private final VoiceConfig config;
    private EditBox promptField;
    private Button fileButton;
    private List<String> files = List.of();
    private int fileIndex;
    private String status = "";
    private boolean busy;

    private int px, py, panelW, panelH, ix, iw;   // geometry (recomputed in init)

    public CompanionBuildScreen(VoiceConfig config) {
        super(Component.literal("Build"));
        this.config = config;
    }

    @Override
    protected void init() {
        panelW = Math.min(PANEL_W, this.width - 8);
        panelH = HEAD_H + CONTENT_H + FOOT_H;
        px = (this.width - panelW) / 2;
        py = Math.max(4, (this.height - panelH) / 2);
        ix = px + PAD;
        iw = panelW - 2 * PAD;

        files = BuildSources.listFiles();
        fileIndex = Math.min(fileIndex, Math.max(0, files.size() - 1));
        int y = py + HEAD_H;

        promptField = new EditBox(this.font, ix, y, iw, 20, Component.literal("Describe"));
        promptField.setHint(Component.literal("e.g. a tall round stone tower"));
        promptField.setMaxLength(160);
        this.addRenderableWidget(promptField);
        y += 26;

        this.addRenderableWidget(Button.builder(Component.literal("Design with AI"), b -> design())
                .pos(ix, y).size(iw, 20).build());
        y += 26;

        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> cycle(-1)).pos(ix, y).size(24, 20).build());
        fileButton = Button.builder(Component.literal(fileLabel()), b -> importFile()).pos(ix + 28, y).size(iw - 56, 20).build();
        this.addRenderableWidget(fileButton);
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> cycle(1)).pos(ix + iw - 24, y).size(24, 20).build());
        y += 26;

        int bw = (iw - 4) / 2;
        int fy = py + panelH - 27;                  // one footer row, 7px pad at the bottom like the other panels
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> {
            if (this.minecraft != null) this.minecraft.setScreen(new CompanionRadialScreen());
        }).pos(ix, fy).size(bw, 20).build());
    }

    private String fileLabel() {
        return files.isEmpty() ? "No .nbt files found" : "Import: " + files.get(fileIndex);
    }

    private void cycle(int d) {
        if (files.isEmpty()) return;
        fileIndex = Math.floorMod(fileIndex + d, files.size());
        fileButton.setMessage(Component.literal(fileLabel()));
    }

    private void importFile() {
        if (files.isEmpty()) {
            status = "Put .nbt files in config/companionmod/schematics";
            return;
        }
        ClientVoice.send(BuildOp.ACTION, "", BuildOp.file(files.get(fileIndex)));
        if (this.minecraft != null) this.minecraft.setScreen(null);
    }

    private record Designed(BuildSpec spec, boolean usedModel) {}

    private void design() {
        String text = promptField.getValue().trim();
        if (text.isEmpty()) { status = "Describe what to build first."; return; }
        if (busy) return;
        busy = true;
        status = "Designing...";
        var mc = this.minecraft;
        CompletableFuture.supplyAsync(() -> {
            String raw = null;
            try {
                raw = new BuildSpecBackend(config).design(text);
            } catch (IOException | RuntimeException ignored) { }
            boolean ok = raw != null && SpecParser.parse(raw).isPresent();
            return new Designed(SpecParser.parseOrHeuristic(raw, text), ok);     // never fails: falls back to a keyword guess
        }).thenAccept(d -> mc.execute(() -> {
            busy = false;
            ClientVoice.send(BuildOp.ACTION, "", BuildOp.spec(SpecParser.toJson(d.spec())));
            if (!d.usedModel() && mc.player != null) {
                mc.player.sendSystemMessage(Component.literal("[Build] The AI model was unavailable, so I used a basic guess from your words."));
            }
            if (mc.screen == this) mc.setScreen(null);
        }));
    }

    @Override
    public boolean isPauseScreen() { return false; }      // let the world (and the preview outline) keep running

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        CompanionUi.panel(g, px, py, px + panelW, py + panelH);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);      // background + widgets
        int cx = this.width / 2;
        // Shadowless 'clean' text like the other panels; title in the lighter muted grey.
        String title = "Build";
        g.text(this.font, title, cx - this.font.width(title) / 2, py + 6, CompanionUi.TEXT_MUTED, false);
        if (!status.isBlank()) {
            g.text(this.font, status, cx - this.font.width(status) / 2, py + panelH - 6, CompanionUi.TEXT_ERROR, false);
        }
    }
}
