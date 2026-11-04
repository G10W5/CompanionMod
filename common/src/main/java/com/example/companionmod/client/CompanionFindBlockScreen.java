package com.example.companionmod.client;

import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.voice.VoiceConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * "Go and find/mine this block" chooser in the mod's grey bevelled settings look (was a dark full-screen
 * panel whose suggestion grid overflowed under the buttons). The amount field carries an explicit count
 * (blank = default: 16, diamonds 6) which rides the target as "iron_ore:12"; the server splits the suffix
 * (digits only after the last ':' - namespaced ids like aether:zanite_ore stay untouched). Suggestions sit
 * in ONE horizontally wheel-scrollable chip row that rebuilds its buttons on scroll.
 */
public class CompanionFindBlockScreen extends Screen {
    private static final String[][] QUICK = {
            {"Diamond", "diamond_ore"}, {"Coal", "coal_ore"}, {"Iron", "iron_ore"},
            {"Gold", "gold_ore"}, {"Redstone", "redstone_ore"}, {"Lapis", "lapis_ore"},
            {"Emerald", "emerald_ore"}, {"Copper", "copper_ore"}, {"Ancient Debris", "ancient_debris"},
            {"Wood", "log"}, {"Planks", "oak_planks"}, {"Stone", "stone"}, {"Cobblestone", "cobblestone"},
            {"Dirt", "dirt"}, {"Gravel", "gravel"}, {"Sand", "sand"}, {"Clay", "clay"},
            {"Tuff", "tuff"}, {"Deepslate", "deepslate"}, {"Basalt", "basalt"}, {"Moss", "moss"},
            {"Ice", "ice"}, {"Amethyst", "amethyst_shard"}, {"Obsidian", "obsidian"},
    };

    private static final int PANEL_W = 300, PAD = 12;
    private static final int HEAD_H = 44, CONTENT_H = 96, FOOT_H = 56;

    private final VoiceConfig config;
    private final Path configFile;
    private EditBox blockField, amountField;
    private String status = "";

    // Values survive the chip-row rebuildWidgets() (scrolling re-runs init()).
    private String blockValue = "", amountValue = "";
    private int chipScroll;                       // horizontal wheel offset in pixels

    private int px, py, panelW, panelH, ix, iw;   // geometry (recomputed in init)

    public CompanionFindBlockScreen(VoiceConfig config, Path configFile) {
        super(Component.literal("Find & Mine"));
        this.config = config;
        this.configFile = configFile;
    }

    private int chipTop() { return py + HEAD_H + 50; }

    @Override
    protected void init() {
        panelW = Math.min(PANEL_W, this.width - 8);
        panelH = HEAD_H + CONTENT_H + FOOT_H;
        px = (this.width - panelW) / 2;
        py = Math.max(4, (this.height - panelH) / 2);
        ix = px + PAD;
        iw = panelW - 2 * PAD;

        blockField = new EditBox(this.font, ix, py + HEAD_H, iw, 20, Component.literal("Block"));
        blockField.setHint(Component.literal("Block id or name (diamond_ore, coal, iron...)"));
        blockField.setMaxLength(60);
        blockField.setValue(blockValue);
        blockField.setResponder(v -> blockValue = v);
        this.addRenderableWidget(blockField);

        amountField = new EditBox(this.font, ix, py + HEAD_H + 24, 56, 20, Component.literal("Amount"));
        amountField.setHint(Component.literal("amount"));
        amountField.setMaxLength(2);
        amountField.setValue(amountValue);
        // No setFilter on the common mapping set: strip anything that is not a digit in the responder.
        amountField.setResponder(v -> {
            String d = v.replaceAll("[^0-9]", "");
            if (!d.equals(v)) { amountField.setValue(d); return; }   // re-entrant once, then stable
            amountValue = d;
        });
        this.addRenderableWidget(amountField);

        // One horizontally wheel-scrollable chip row: only the visible slice becomes buttons.
        int cur = ix - chipScroll;
        for (String[] e : QUICK) {
            int bw = this.font.width(e[0]) + 14;
            if (cur + bw > ix && cur < ix + iw) {
                final String id = e[1];
                this.addRenderableWidget(Button.builder(Component.literal(e[0]), b -> {
                    blockValue = id;
                    blockField.setValue(id);
                    status = "";
                }).pos(cur, chipTop()).size(bw, 20).build());
            }
            cur += bw + 4;
        }

        this.addRenderableWidget(Button.builder(Component.literal("Find & Mine"), b -> find())
                .pos(ix, py + panelH - 52).size(iw, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> {
            if (this.minecraft != null) this.minecraft.setScreen(new CompanionRadialScreen());
        }).pos(ix, py + panelH - 27).size(iw, 20).build());
    }

    private int chipTotalW() {
        int w = -4;
        for (String[] e : QUICK) w += this.font.width(e[0]) + 14 + 4;
        return Math.max(0, w);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int top = chipTop();
        int max = Math.max(0, chipTotalW() - iw);
        if (max > 0 && mouseY >= top - 2 && mouseY <= top + 26) {
            int next = (int) Math.max(0, Math.min(max, chipScroll - scrollY * 24));
            if (next != chipScroll) { chipScroll = next; rebuildWidgets(); }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void find() {
        String id = CompanionEntity.normalizeBlockId(blockValue);
        if (id.isBlank()) {
            status = "Unknown block. Try diamond, coal, iron, gold, emerald...";
            return;
        }
        String amt = amountValue == null ? "" : amountValue.trim();
        int count = 0;
        if (!amt.isEmpty()) {
            try { count = Integer.parseInt(amt); } catch (NumberFormatException ignored) { }
            if (count < 1 || count > 64) { status = "Amount must be 1-64 (blank = default)."; return; }
        }
        ClientVoice.send("seek_block", "", count > 0 ? id + ":" + count : id);
        if (this.minecraft != null) this.minecraft.setScreen(null);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        CompanionUi.panel(g, px, py, px + panelW, py + panelH);       // same bevelled panel as Settings
        // Black empty well behind the suggestion chips (chips render later, in the widget phase).
        g.fill(ix - 2, chipTop() - 2, ix + iw + 2, chipTop() + 22, 0xFF000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);      // background + widgets
        int cx = this.width / 2;
        g.centeredText(this.font, "Find & Mine", cx, py + 8, CompanionUi.TEXT_DARK);
        String sub = "Amount blank = default (16, diamonds 6) - wheel the chips";
        g.centeredText(this.font, this.font.plainSubstrByWidth(sub, iw), cx, py + 18, CompanionUi.TEXT_DARK);
        g.text(this.font, "Amount", ix + 62, py + HEAD_H + 30, CompanionUi.TEXT_DARK, true);
        // Visible horizontal scrollbar for the chip row.
        int max = Math.max(0, chipTotalW() - iw);
        if (max > 0) {
            int ty = chipTop() + 23;
            g.fill(ix, ty, ix + iw, ty + 4, 0xFF000000);
            int tw = Math.max(24, iw * iw / Math.max(1, chipTotalW()));
            int tx = ix + (iw - tw) * chipScroll / max;
            g.fill(tx + 1, ty + 1, tx + tw - 1, ty + 3, 0xFFB0B0B0);
        }
        if (!status.isBlank()) g.centeredText(this.font, status, cx, py + 126, CompanionUi.TEXT_ERROR);
    }
}
