package com.example.companionmod.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Type any goal in plain words; the AI turns it into a plan. Same grey bevelled panel style as Settings /
 * Find &amp; Mine. The example chips sit in a BLACK well and are drawn manually INSIDE A SCISSOR: chips
 * that do not fit are CUT OFF at the well edge, clicks hit-test the same math, and the wheel scrolls them
 * with a visible scrollbar underneath.
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
    private String fieldValue = "";
    private int chipScroll;                       // horizontal wheel offset in pixels
    private boolean barDrag;                      // dragging the scrollbar thumb/track

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
            for (String idea : IDEAS) {
                int bw = this.font.width(idea) + 14;
                if (event.x() >= x && event.x() < x + bw) {
                    field.setValue(idea);
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
        g.fill(ix - 2, chipTop() - 2, ix + iw + 2, chipTop() + 22, 0xFF000000);   // BLACK well behind the chips
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);                   // background + widgets
        int cx = this.width / 2;
        // Shadowless 'clean' text like Appearance & Skins, lighter-grey title, no hint line (clutter).
        String title = "Give a Goal";
        g.text(this.font, title, cx - this.font.width(title) / 2, py + 8, CompanionUi.TEXT_MUTED, false);

        // Chips inside a scissor: ones that do not fit are CUT OFF at the well edge, never hidden.
        g.enableScissor(ix - 2, chipTop() - 2, ix + iw + 2, chipTop() + 22);
        int x = ix - chipScroll;
        for (String idea : IDEAS) {
            int bw = this.font.width(idea) + 14;
            if (x + bw > ix - 2 && x < ix + iw + 2) {
                boolean hot = mouseX >= x && mouseX < x + bw && mouseY >= chipTop() && mouseY < chipTop() + 20;
                g.fill(x, chipTop(), x + bw, chipTop() + 20, 0xFF000000);
                g.fill(x + 1, chipTop() + 1, x + bw - 1, chipTop() + 19, hot ? 0xFF8C8C8C : 0xFF6C6C6C);
                g.fill(x + 1, chipTop() + 1, x + bw - 1, chipTop() + 2, hot ? 0xFFA2A2A2 : 0xFF8E8E8E);
                g.fill(x + 1, chipTop() + 18, x + bw - 1, chipTop() + 19, 0xFF3B3B3B);
                g.text(this.font, idea, x + 7, chipTop() + 6, 0xFFFFFFFF, true);
            }
            x += bw + 4;
        }
        g.disableScissor();

        // Visible horizontal scrollbar for the chip row.
        int max = Math.max(0, chipTotalW() - iw);
        if (max > 0) {
            int ty = chipTop() + 23;
            g.fill(ix, ty, ix + iw, ty + 4, 0xFF000000);
            int tw = Math.max(24, iw * iw / Math.max(1, chipTotalW()));
            int tx = ix + (iw - tw) * chipScroll / max;
            g.fill(tx + 1, ty, tx + tw - 1, ty + 4, 0xFFB0B0B0);
        }
    }
}
