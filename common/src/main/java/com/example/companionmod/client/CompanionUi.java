package com.example.companionmod.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Drawing helpers for the vanilla-grey "Voice Chat mod" look: bevelled panel and the green-to-orange level bar. */
final class CompanionUi {
    static final int TEXT_DARK = 0xFF404040;   // title / labels on the grey panel
    static final int TEXT_MUTED = 0xFF6B6B6B;
    static final int TEXT_ERROR = 0xFFAA0000;

    private CompanionUi() {}

    /** Light-grey bevelled container, like vanilla inventory screens and the Voice Chat settings. */
    static void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
        g.fill(x0, y0, x1, y1, 0xFF000000);                         // outer border
        g.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, 0xFFC6C6C6);         // body
        g.fill(x0 + 1, y0 + 1, x1 - 2, y0 + 3, 0xFFFFFFFF);         // top highlight
        g.fill(x0 + 1, y0 + 1, x0 + 3, y1 - 2, 0xFFFFFFFF);         // left highlight
        g.fill(x0 + 2, y1 - 3, x1 - 1, y1 - 1, 0xFF555555);         // bottom shadow
        g.fill(x1 - 3, y0 + 2, x1 - 1, y1 - 1, 0xFF555555);         // right shadow
    }

    /** Horizontal level bar. level/peak are 0..1; gate is the silence-threshold marker (0..1, or negative to hide). */
    static void levelBar(GuiGraphicsExtractor g, int x, int y, int w, int h, float level, float peak, float gate) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
        g.fill(x, y, x + w, y + h, 0xFF2B2B2B);
        int lit = Math.round(Math.max(0f, Math.min(1f, level)) * w);
        for (int i = 0; i < lit; i += 2) {                          // 2px columns: cheap horizontal gradient
            g.fill(x + i, y, x + Math.min(i + 2, lit), y + h, gradient((i + 1f) / w));
        }
        int p = Math.round(Math.max(0f, Math.min(1f, peak)) * w);
        if (p > 1) g.fill(x + p - 1, y, x + p, y + h, 0xFFFFFFFF);
        if (gate >= 0f) {
            int gx = x + Math.round(Math.min(1f, gate) * w);
            g.fill(gx, y - 2, gx + 1, y + h + 2, 0xFFFF3030);        // threshold tick
        }
    }

    /** Green -> yellow -> orange. */
    private static int gradient(float f) {
        int r, gr;
        if (f < 0.6f) { float t = f / 0.6f; r = lerp(0x20, 0xE8, t); gr = lerp(0xC8, 0xE0, t); }
        else { float t = (f - 0.6f) / 0.4f; r = lerp(0xE8, 0xFF, t); gr = lerp(0xE0, 0x7A, t); }
        return 0xFF000000 | (r << 16) | (gr << 8);
    }

    private static int lerp(int a, int b, float t) {
        return Math.round(a + (b - a) * Math.max(0f, Math.min(1f, t)));
    }
}
