package com.example.companionmod.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Dropdown menus built from vanilla {@link Button}s plus one overlay list drawn on top of everything.
 *
 * <p>The screen owns the header buttons (so they get vanilla styling, hover, narration and keyboard focus);
 * this class only keeps the option lists, draws the open list and swallows the clicks / scrolling that belong to it.
 * Wire it up in the screen with: {@link #extract} at the END of extractRenderState, {@link #mouseClicked} /
 * {@link #mouseScrolled} / {@link #escape} at the START of the matching overrides.</p>
 */
final class CompanionDropdowns {
    static final int ROW_H = 16;
    static final int MAX_ROWS = 7;

    record Option(String value, String label) {}

    static final class Dropdown {
        final String title;
        final Supplier<String> getter;
        final Consumer<String> setter;
        Button button;
        List<Option> options = List.of();
        String emptyText = "Nothing found";
        boolean loading;
        int scroll;

        Dropdown(String title, Supplier<String> getter, Consumer<String> setter) {
            this.title = title;
            this.getter = getter;
            this.setter = setter;
        }
    }

    private record Layout(int x, int y, int w, int h, int rows, int total) {}

    private final Font font;
    private final IntSupplier screenHeight;
    private final List<Dropdown> all = new ArrayList<>();
    private Dropdown open;

    CompanionDropdowns(Font font, IntSupplier screenHeight) {
        this.font = font;
        this.screenHeight = screenHeight;
    }

    /** Forget every dropdown (call at the start of init(): the header buttons are recreated each time). */
    void clear() {
        all.clear();
        open = null;
    }

    void register(Dropdown d) {
        all.add(d);
        refresh(d);
    }

    boolean isOpen() { return open != null; }

    void toggle(Dropdown d) {
        Dropdown previous = open;
        open = null;
        if (previous != null) refresh(previous);
        if (previous != d) {
            open = d;
            List<Option> opts = shown(d);
            int rows = Math.min(Math.max(1, opts.size()), MAX_ROWS);
            int sel = indexOfCurrent(d, opts);
            d.scroll = Math.max(0, Math.min(Math.max(0, opts.size() - rows), sel - rows / 2));
        }
        refresh(d);
    }

    void close() {
        Dropdown d = open;
        open = null;
        if (d != null) refresh(d);
    }

    /** ESC closes an open list first instead of the whole screen. */
    boolean escape() {
        if (open == null) return false;
        close();
        return true;
    }

    void refreshAll() {
        for (Dropdown d : all) refresh(d);
    }

    // ---- labels -------------------------------------------------------------------------------------------------

    /** Options as displayed: the stored value is always selectable even when the server list does not contain it. */
    private List<Option> shown(Dropdown d) {
        String cur = current(d);
        if (cur.isEmpty()) return d.options;
        for (Option o : d.options) if (o.value().equals(cur)) return d.options;
        List<Option> withCur = new ArrayList<>(d.options.size() + 1);
        withCur.add(new Option(cur, cur));
        withCur.addAll(d.options);
        return withCur;
    }

    private static String current(Dropdown d) {
        String v = d.getter.get();
        return v == null ? "" : v;
    }

    private int indexOfCurrent(Dropdown d, List<Option> opts) {
        String cur = current(d);
        for (int i = 0; i < opts.size(); i++) if (opts.get(i).value().equals(cur)) return i;
        return 0;
    }

    void refresh(Dropdown d) {
        if (d.button == null) return;
        String cur = current(d);
        String value = null;
        for (Option o : shown(d)) if (o.value().equals(cur)) { value = o.label(); break; }
        if (value == null) value = d.loading ? "Loading..." : (cur.isEmpty() ? "-" : cur);
        String prefix = d.title + ": ";
        String arrow = open == d ? " \u25B2" : " \u25BC";
        int room = d.button.getWidth() - 12 - font.width(prefix) - font.width(arrow);
        if (room > 12 && font.width(value) > room) {
            value = font.plainSubstrByWidth(value, room - font.width("...")) + "...";
        }
        d.button.setMessage(Component.literal(prefix + value + arrow));
    }

    // ---- layout -------------------------------------------------------------------------------------------------

    private Layout layout(Dropdown d) {
        Button b = d.button;
        int total = Math.max(1, shown(d).size());
        int below = screenHeight.getAsInt() - (b.getY() + b.getHeight()) - 4;
        int above = b.getY() - 4;
        boolean up = total * ROW_H + 2 > below && above > below;
        int room = Math.max(1, ((up ? above : below) - 2) / ROW_H);
        int rows = Math.max(1, Math.min(Math.min(total, MAX_ROWS), room));
        int h = rows * ROW_H + 2;
        int y = up ? b.getY() - h : b.getY() + b.getHeight();
        return new Layout(b.getX(), y, b.getWidth(), h, rows, total);
    }

    // ---- drawing ------------------------------------------------------------------------------------------------

    void extract(GuiGraphicsExtractor g, int mx, int my) {
        if (open == null || open.button == null) return;
        g.nextStratum();                                   // 26.1-CHECK: draw above widget text; delete this line if it does not exist
        Dropdown d = open;
        Layout L = layout(d);
        List<Option> opts = shown(d);
        d.scroll = Math.max(0, Math.min(d.scroll, Math.max(0, L.total() - L.rows())));
        int sbw = L.total() > L.rows() ? 6 : 0;

        g.fill(L.x() - 1, L.y() - 1, L.x() + L.w() + 1, L.y() + L.h() + 1, 0xFF000000);
        g.fill(L.x(), L.y(), L.x() + L.w(), L.y() + L.h(), 0xFF1B1B1B);

        if (opts.isEmpty()) {
            g.text(font, d.loading ? "Loading..." : d.emptyText, L.x() + 6, L.y() + 5, 0xFF9A9A9A, true);
        }
        String cur = current(d);
        for (int i = 0; i < L.rows(); i++) {
            int idx = d.scroll + i;
            if (idx >= opts.size()) break;
            Option o = opts.get(idx);
            int ry = L.y() + 1 + i * ROW_H;
            boolean hover = mx >= L.x() && mx < L.x() + L.w() - sbw && my >= ry && my < ry + ROW_H;
            boolean selected = o.value().equals(cur);
            if (hover) g.fill(L.x() + 1, ry, L.x() + L.w() - 1 - sbw, ry + ROW_H, 0xFF505050);
            if (selected) g.fill(L.x() + 1, ry + 2, L.x() + 3, ry + ROW_H - 2, 0xFF55FF55);
            String label = font.plainSubstrByWidth(o.label(), L.w() - 14 - sbw);
            g.text(font, label, L.x() + 7, ry + 4, selected ? 0xFFFFFF55 : 0xFFFFFFFF, true);
        }
        if (sbw > 0) {
            int tx = L.x() + L.w() - sbw, ty = L.y() + 1, th = L.h() - 2;
            g.fill(tx, ty, L.x() + L.w() - 1, ty + th, 0xFF000000);
            int thumb = Math.max(8, th * L.rows() / L.total());
            int maxScroll = Math.max(1, L.total() - L.rows());
            int thumbY = ty + (th - thumb) * d.scroll / maxScroll;
            g.fill(tx + 1, thumbY, L.x() + L.w() - 2, thumbY + thumb, 0xFF9A9A9A);
        }
    }

    // ---- input --------------------------------------------------------------------------------------------------

    /** @return true when the click was consumed by an open list (or closed it). */
    boolean mouseClicked(double mx, double my, int button) {
        if (open == null || open.button == null) return false;
        Dropdown d = open;
        Layout L = layout(d);
        boolean inList = mx >= L.x() && mx < L.x() + L.w() && my >= L.y() && my < L.y() + L.h();
        if (inList) {
            int sbw = L.total() > L.rows() ? 6 : 0;
            if (button == 0 && mx < L.x() + L.w() - sbw) {
                int i = (int) ((my - L.y() - 1) / ROW_H);
                int idx = d.scroll + i;
                List<Option> opts = shown(d);
                if (i >= 0 && i < L.rows() && idx >= 0 && idx < opts.size()) {
                    d.setter.accept(opts.get(idx).value());
                    close();
                }
            }
            return true;
        }
        Button b = d.button;
        if (mx >= b.getX() && mx < b.getX() + b.getWidth() && my >= b.getY() && my < b.getY() + b.getHeight()) {
            return false;                                  // let the header button run its own press handler (toggles closed)
        }
        close();
        return true;
    }

    boolean mouseScrolled(double mx, double my, double scrollY) {
        if (open == null || open.button == null) return false;
        Layout L = layout(open);
        int max = Math.max(0, L.total() - L.rows());
        open.scroll = Math.max(0, Math.min(max, open.scroll - (int) Math.signum(scrollY)));
        return true;
    }
}
