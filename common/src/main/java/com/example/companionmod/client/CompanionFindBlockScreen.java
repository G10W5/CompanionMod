package com.example.companionmod.client;

import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.voice.VoiceConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * "Go and find/mine this block" chooser in the mod's grey bevelled settings look. Amount field carries an
 * explicit count (blank = default: 16, diamonds 6) sent as "iron_ore:12". The suggestion chips sit in a
 * BLACK well and are drawn manually INSIDE A SCISSOR: chips that do not fit are CUT OFF at the well edge
 * (not hidden), clicking a chip hit-tests against the same math, and the wheel scrolls them with a visible
 * scrollbar underneath.
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
    private static final int HEAD_H = 26, CONTENT_H = 96, FOOT_H = 56;   // HEAD 26: title sits tight above the field (was a 44px void)

    private final VoiceConfig config;
    private final Path configFile;
    private EditBox blockField, amountField;
    private String status = "";

    private String blockValue = "", amountValue = "";
    private int chipScroll;                       // horizontal wheel offset in pixels
    private boolean barDrag;                      // dragging the scrollbar thumb/track

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
            if (!d.equals(v)) { amountField.setValue(d); return; }
            amountValue = d;
        });
        this.addRenderableWidget(amountField);

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

    private int chipMax() { return Math.max(0, chipTotalW() - iw); }

    private int thumbW() { return Math.max(24, iw * iw / Math.max(1, chipTotalW())); }

    private boolean inBar(double mx, double my) {
        int ty = chipTop() + 23;
        return mx >= ix && mx < ix + iw && my >= ty - 2 && my < ty + 6;
    }

    /** Map an x position on the track to a scroll offset (click-to-jump and thumb dragging share this). */
    private void barTo(int mouseX) {
        int max = chipMax(), travel = iw - thumbW();
        if (max <= 0 || travel <= 0) { chipScroll = 0; return; }
        int rel = Math.max(0, Math.min(travel, mouseX - ix - thumbW() / 2));
        chipScroll = rel * max / travel;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int top = chipTop();
        if (chipMax() > 0 && mouseY >= top - 2 && mouseY <= top + 26) {
            chipScroll = (int) Math.max(0, Math.min(chipMax(), chipScroll - scrollY * 24));
            return true;                            // repaint only - no widget rebuild needed
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Interactable scrollbar: press anywhere on the bar = jump the thumb there and start dragging.
        if (event.button() == 0 && inBar(event.x(), event.y()) && chipMax() > 0) {
            barTo((int) event.x());
            barDrag = true;
            return true;
        }
        if (event.button() == 0 && event.x() >= ix - 2 && event.x() < ix + iw + 2
                && event.y() >= chipTop() - 2 && event.y() < chipTop() + 22) {
            int x = ix - chipScroll;
            for (String[] e : QUICK) {
                int bw = this.font.width(e[0]) + 14;
                if (event.x() >= x && event.x() < x + bw) {
                    blockValue = e[1];
                    blockField.setValue(e[1]);
                    status = "";
                    return true;
                }
                x += bw + 4;
            }
            return true;                            // inside the well: swallow the click
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (barDrag) { barTo((int) event.x()); return true; }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        barDrag = false;
        return super.mouseReleased(event);
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
        CompanionUi.panel(g, px, py, px + panelW, py + panelH);
        g.fill(ix - 2, chipTop() - 2, ix + iw + 2, chipTop() + 22, 0xFF000000);   // BLACK well behind the chips
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);      // background + widgets
        int cx = this.width / 2;
        // Shadowless 'clean' text like Appearance & Skins (g.centeredText draws a shadow = the bold mush),
        // title in the lighter grey, and the hint line removed entirely (clutter - user asked twice).
        String title = "Find & Mine";
        g.text(this.font, title, cx - this.font.width(title) / 2, py + 8, CompanionUi.TEXT_MUTED, false);
        g.text(this.font, "Amount", ix + 62, py + HEAD_H + 30, CompanionUi.TEXT_DARK, false);

        // Chips inside a scissor: ones that do not fit are CUT OFF at the well edge, never hidden.
        g.enableScissor(ix - 2, chipTop() - 2, ix + iw + 2, chipTop() + 22);
        int x = ix - chipScroll;
        for (String[] e : QUICK) {
            int bw = this.font.width(e[0]) + 14;
            if (x + bw > ix - 2 && x < ix + iw + 2) {
                boolean hot = mouseX >= x && mouseX < x + bw && mouseY >= chipTop() && mouseY < chipTop() + 20;
                g.fill(x, chipTop(), x + bw, chipTop() + 20, 0xFF000000);
                g.fill(x + 1, chipTop() + 1, x + bw - 1, chipTop() + 19, hot ? 0xFF8C8C8C : 0xFF6C6C6C);
                g.fill(x + 1, chipTop() + 1, x + bw - 1, chipTop() + 2, hot ? 0xFFA2A2A2 : 0xFF8E8E8E);
                g.fill(x + 1, chipTop() + 18, x + bw - 1, chipTop() + 19, 0xFF3B3B3B);
                g.text(this.font, e[0], x + 7, chipTop() + 6, 0xFFFFFFFF, true);
            }
            x += bw + 4;
        }
        g.disableScissor();

        // Visible horizontal scrollbar: white hover outline, thumb darkens while dragging.
        int max = chipMax();
        if (max > 0) {
            int ty = chipTop() + 23;
            boolean hot = mouseX >= ix && mouseX < ix + iw && mouseY >= ty - 2 && mouseY < ty + 6;
            if (hot || barDrag) g.fill(ix - 1, ty - 1, ix + iw + 1, ty + 5, 0xFFFFFFFF);
            g.fill(ix, ty, ix + iw, ty + 4, 0xFF000000);
            int tw = thumbW();
            int tx = ix + (iw - tw) * chipScroll / max;
            g.fill(tx + 1, ty, tx + tw - 1, ty + 4, barDrag ? 0xFF6C6C6C : 0xFFB0B0B0);
        }
        if (!status.isBlank()) {
            g.text(this.font, status, cx - this.font.width(status) / 2, py + HEAD_H + 80, CompanionUi.TEXT_ERROR, false);
        }
    }
}
