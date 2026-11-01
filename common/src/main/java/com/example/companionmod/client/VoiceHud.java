package com.example.companionmod.client;

import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.voice.PushToTalkController;
import com.example.companionmod.voice.VoiceState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Microphone panel + a small downed-companion revive panel. Only draws when relevant. */
final class VoiceHud {
    private static final int W = 184, H = 36, SEGS = 24, SEG_W = 5, SEG_GAP = 1;
    private static float fade;                                           // 0..1, eased so the panel slides in and out
    private static VoiceState lastState = VoiceState.IDLE;

    private VoiceHud() {}

    static void render(GuiGraphicsExtractor g, PushToTalkController c, CompanionEntity companion) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        if (c == null) { fade = 0f; lastState = VoiceState.IDLE; }         // left the world while the panel was showing
        VoiceState state = c == null ? VoiceState.IDLE : c.state();
        if (state != VoiceState.IDLE) lastState = state;
        fade += ((state == VoiceState.IDLE ? 0f : 1f) - fade) * 0.25f;
        boolean subtitleOn = System.currentTimeMillis() < ClientVoice.subtitleUntil;
        boolean injured = companion != null && companion.isInjured();
        boolean reviveHint = injured || (companion != null && companion.reviveProgress() > 0);
        if (fade < 0.02f && !subtitleOn && !reviveHint) return;

        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        int x = (sw - W) / 2;

        if (subtitleOn) {
            String s = ClientVoice.subtitle;
            text(g, font, s, (sw - font.width(s)) / 2, sh - 84 - H, argb(230, 255, 255, 255));
        }
        if (injured) renderInjured(g, font, sw, sh, companion);
        if (c == null || fade < 0.02f) return;

        int y = sh - 84;
        int a = (int) (fade * 255);
        int accent = switch (lastState) {
            case LISTENING -> argb(a, 235, 70, 70);
            case THINKING -> argb(a, 240, 200, 80);
            default -> argb(a, 240, 240, 240);
        };
        long t = System.currentTimeMillis();

        g.fill(x, y, x + W, y + H, argb((int) (fade * 196), 16, 16, 16));
        g.fill(x, y, x + W, y + 1, accent);
        g.fill(x, y + H - 1, x + W, y + H, argb((int) (fade * 200), 56, 56, 56));
        g.fill(x, y, x + 1, y + H, argb((int) (fade * 200), 56, 56, 56));
        g.fill(x + W - 1, y, x + W, y + H, argb((int) (fade * 200), 56, 56, 56));

        // microphone icon, pulsing while recording
        int ix = x + 10, iy = y + 9;
        int pulse = lastState == VoiceState.LISTENING ? (int) (35 * (0.5 + 0.5 * Math.sin(t / 180.0))) : 0;
        int ic = argb(a, 225 + Math.min(30, pulse), 225 - pulse, 225 - pulse);
        g.fill(ix + 3, iy, ix + 7, iy + 8, ic);
        g.fill(ix + 1, iy + 5, ix + 2, iy + 10, ic);
        g.fill(ix + 8, iy + 5, ix + 9, iy + 10, ic);
        g.fill(ix + 1, iy + 10, ix + 9, iy + 11, ic);
        g.fill(ix + 4, iy + 11, ix + 6, iy + 14, ic);

        String label = switch (lastState) {
            case LISTENING -> String.format("Listening  %.1fs", c.listeningMillis() / 1000.0);
            case THINKING -> "Thinking" + ".".repeat((int) (t / 300 % 4));
            default -> "Speaking";
        };
        text(g, font, label, x + 28, y + 7, argb(a, 235, 235, 240));

        float level = c == null ? 0f : c.level(), peak = c == null ? 0f : c.peak();
        int bx = x + 28, by = y + 21;
        int peakIdx = Math.min(SEGS - 1, (int) (peak * SEGS));
        int scan = (int) (t / 70 % (SEGS + 6));                                       // "thinking" scanner position
        for (int i = 0; i < SEGS; i++) {
            int sx = bx + i * (SEG_W + SEG_GAP);
            boolean lit;
            if (lastState == VoiceState.THINKING) lit = Math.abs(i - (scan - 3)) <= 1;
            else lit = (i + 0.5f) / SEGS <= level;
            int col;
            if (lastState == VoiceState.SPEAKING) col = argb(a, 235, 235, 235);
            else if (lastState == VoiceState.THINKING) col = argb(a, 240, 200, 80);
            else col = i < 14 ? argb(a, 80, 220, 120) : i < 20 ? argb(a, 240, 210, 80) : argb(a, 240, 80, 70);
            g.fill(sx, by, sx + SEG_W, by + 9, lit ? col : argb((int) (fade * 40), 255, 255, 255));
            if (lastState != VoiceState.THINKING && i == peakIdx && peak > level + 0.03f)
                g.fill(sx, by, sx + SEG_W, by + 2, argb(a, 255, 255, 255));            // peak-hold tick
        }
    }

    private static void renderInjured(GuiGraphicsExtractor g, Font font, int sw, int sh, CompanionEntity companion) {
        int seconds = (companion.injuredLeftTicks() + 19) / 20;
        String hint = companion.reviveProgress() > 0.02f
                ? "Reviving... " + Math.round(companion.reviveProgress() * 100) + "%"
                : "Your companion is down — hold LEFT CLICK on it to revive (" + seconds + "s)";
        int w = font.width(hint);
        int x = (sw - w) / 2, y = sh - 108;
        g.fill(x - 8, y - 4, x + w + 8, y + 12, 0xC4101010);
        g.fill(x - 8, y - 4, x + w + 8, y - 3, 0xFFC85A5A);
        g.fill(x - 8, y + 11, x + w + 8, y + 12, 0xC8383838);
        g.fill(x - 8, y - 4, x - 7, y + 12, 0xC8383838);
        g.fill(x + w + 7, y - 4, x + w + 8, y + 12, 0xC8383838);
        g.text(font, hint, x, y, 0xFFFFD0D0, true);
    }

    private static int argb(int a, int r, int gr, int b) {
        return (Math.max(0, Math.min(255, a)) << 24) | (clamp(r) << 16) | (clamp(gr) << 8) | clamp(b);
    }
    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    /** Single text call site. 26.1-CHECK: GuiGraphics.drawString became GuiGraphicsExtractor.text(...) in the 26.1 rendering rewrite. */
    private static void text(GuiGraphicsExtractor g, Font font, String s, int x, int y, int color) {
        g.text(font, s, x, y, color, true);
    }
}

