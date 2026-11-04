package com.example.companionmod.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Type any goal in plain words; the AI turns it into a plan. Same grey bevelled panel style as
 * Companion Settings / Find &amp; Mine (was a full-screen vanilla menu with the chips floating over the
 * world). Examples live in ONE horizontally wheel-scrollable chip row over a black well with a visible
 * scrollbar; the row rebuilds its buttons on scroll and the typed text survives that rebuild.
 */
public class CompanionGoalScreen extends Screen {
    private static final String[] IDEAS = {
            "Collect some wood", "Get 20 iron ore", "Find diamonds", "Mine some coal",
            "Explore a cave", "Bring me 12 lapis", "Dig down deeper", "Get cobblestone",
            "Chop some trees", "Farm the crops", "Go fishing", "Guard this area",
            "Look around nearby", "Continue what you were doing", "Follow me", "Come here",
    };

    private static final int PANEL_W = 340, PAD = 12;
    private static final int HEAD_H = 44, CONTENT_H = 52, FOOT_H = 56;

    private final Screen parent;
    private EditBox field;
    private String fieldValue = "";               // survives the chip-row rebuildWidgets()
    private int chipScroll;                       // horizontal wheel offset in pixels

    private int px, py, panelW, panelH, ix, iw;   // geometry (recomputed in init)

    public CompanionGoalScreen(Screen parent) {
        super(Component.literal("Give a Goal"));
        this.parent = parent;
    }

    private int chipTop() { return py + HEAD_H + 24; }

    @Override
    protected void init() {
        panelW = Math.min(PANEL_W, this.width - 8);
        panelH = HEAD_H + CONTENT_H + FOOT_H;
        px = (this.width - panelW) / 2;
        py = Math.max(4, (this.height - panelH) / 2);
        ix = px + PAD;
        iw = panelW - 2 * PAD;

        field = new EditBox(this.font, ix, py + HEAD_H, iw, 20, Component.literal("Goal"));
        field.setHint(Component.literal("e.g. gather 2 stacks of wood, then come back"));
        field.setMaxLength(160);
        field.setValue(fieldValue);
        field.setResponder(v -> fieldValue = v);
        this.addRenderableWidget(field);
        this.setInitialFocus(field);

        int x = ix - chipScroll;
        for (String idea : IDEAS) {
            int bw = this.font.width(idea) + 14;
            // Only chips that fit FULLY inside the well: partial chips rendered over the panel edge.
            if (x >= ix && x + bw <= ix + iw) {
                this.addRenderableWidget(Button.builder(Component.literal(idea), b -> field.setValue(idea))
                        .pos(x, chipTop()).size(bw, 20).build());
            }
            x += bw + 4;
        }

        int bw = (iw - 4) / 2;
        int fy = py + panelH - 52;
        this.addRenderableWidget(Button.builder(Component.literal("Plan It"), b -> submit())
                .pos(ix, fy).size(bw, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .pos(ix + bw + 4, fy).size(bw, 20).build());
    }

    private int chipTotalW() {
        int w = -4;
        for (String idea : IDEAS) w += this.font.width(idea) + 14 + 4;
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

    private void submit() {
        String text = fieldValue == null ? "" : fieldValue.trim();
        if (!text.isEmpty()) CompanionAutonomy.requestGoal(text);
        if (this.minecraft != null) this.minecraft.setScreen(null);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) { submit(); return true; }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        CompanionUi.panel(g, px, py, px + panelW, py + panelH);
        // Sunken lighter-grey well behind the chips (slot-well style, not harsh black).
        g.fill(ix - 2, chipTop() - 2, ix + iw + 2, chipTop() + 22, 0xFF373737);
        g.fill(ix - 1, chipTop() - 1, ix + iw + 1, chipTop() + 21, 0xFFFFFFFF);
        g.fill(ix, chipTop(), ix + iw, chipTop() + 20, 0xFF8B8B8B);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);                   // background + widgets
        int cx = this.width / 2;
        g.centeredText(this.font, "Give a Goal", cx, py + 8, CompanionUi.TEXT_DARK);
        String sub = "Amounts in the text work: 'bring me 12 iron'";
        g.centeredText(this.font, this.font.plainSubstrByWidth(sub, iw - 8), cx, py + 18, CompanionUi.TEXT_DARK);
        // Visible horizontal scrollbar for the chip row, sunken lighter-grey like the well.
        int max = Math.max(0, chipTotalW() - iw);
        if (max > 0) {
            int ty = chipTop() + 23;
            g.fill(ix, ty, ix + iw, ty + 4, 0xFF373737);
            g.fill(ix + 1, ty + 1, ix + iw - 1, ty + 3, 0xFF8B8B8B);
            int tw = Math.max(24, iw * iw / Math.max(1, chipTotalW()));
            int tx = ix + (iw - tw) * chipScroll / max;
            g.fill(tx - 1, ty - 1, tx + tw + 1, ty + 5, 0xFF000000);
            g.fill(tx, ty, tx + tw, ty + 4, 0xFFC6C6C6);
        }
    }
}
