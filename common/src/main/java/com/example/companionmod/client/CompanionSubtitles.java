package com.example.companionmod.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Audio-clocked, independent word bubbles with a playful feel. Every word is revealed at the same anchor
 * above the speaker and then floats up on its own, but each word gets its own personality: a random size,
 * a slight tilt, sideways drift (left or right), and a pastel color of its own. Words pop in from tiny to
 * full size with a little overshoot bounce. The audio clock arrives in coarse chunks, so it is smoothed
 * exponentially every rendered frame to keep the motion fluid at screen refresh rate.
 */
final class CompanionSubtitles {
    /** {@code y} is the upward lift in local pixels (the renderer translates by {@code -y}). */
    record Word(String text, float x, float y, float scale, float rotDeg, int argb) {}

    private static final long LIFE_MS = 1500;
    private static final long POP_MS = 260;
    private static final float RISE_PX = 20f;     // was 64: bubbles climbed ~1.6 blocks above an already high anchor
    private static final long FADE_MS = 450;
    /** Extra smoothing factor: how much of the audio-clock's coarse jump we accept per rendered frame. */
    private static final float CLOCK_EASE = 0.45f;

    /** Pastel bubbles: one color per word, cycling. Shared with the downed text (same playful style). */
    static final int[] PALETTE = {
            0xFFC9DBFF,   // sky blue
            0xFFFFC2E0,   // pink
            0xFFFFE3B3,   // amber
            0xFFC9F5C8,   // mint
            0xFFE5C8FF,   // lavender
            0xFFFFBFBD,   // rose
            0xFFBFF2F4,   // cyan
            0xFFF6F3B8,   // lemon
    };

    private static final class Anim {
        final int id;
        final String text;
        String[] words;
        float[] reveal;
        float[] sizeMul;     // playful: some words big, some small
        float[] rotDeg;      // per-word tilt
        float[] driftX;      // sideways drift over the lifetime
        float[] liftMul;     // vertical pace variance
        float[] phase;       // wobble phase (rotation over time)
        Anim(int id, String text) { this.id = id; this.text = text; }
    }

    private static final Map<Integer, Anim> ANIMS = new ConcurrentHashMap<>();
    private static String clockText = "";
    private static long durationMs, startedAt, finishedAt;
    private static float progress;
    private static boolean fallback;
    private static long smoothedMs;      // per-frame eased view of the (chunky) audio clock

    private CompanionSubtitles() {}

    static synchronized void audioStarted(String text, long duration) {
        if (text == null || text.isBlank()) return;
        clockText = text;
        durationMs = Math.max(1, duration);
        startedAt = System.currentTimeMillis();
        finishedAt = 0;
        progress = 0;
        fallback = false;
        smoothedMs = 0;
    }

    static synchronized void audioProgress(String text, float value) {
        if (text != null && text.equals(clockText)) progress = Math.max(0, Math.min(1, value));
    }

    static synchronized void audioStopped(String text) {
        if (text == null || !text.equals(clockText)) return;
        progress = 1;
        finishedAt = System.currentTimeMillis();
    }

    static synchronized void fallback(String text) {
        if (text == null || text.isBlank()) return;
        clockText = text;
        durationMs = estimateDuration(text);
        startedAt = System.currentTimeMillis();
        finishedAt = 0;
        progress = 0;
        fallback = true;
        smoothedMs = 0;
    }

    /** Text arrival no longer starts an animation; audioStarted/fallback does. */
    static long startFor(int entityId, int speechId, String text) { return 0L; }

    static List<Word> layout(int entityId, int speechId, String text) {
        if (text == null || text.isBlank()) {
            ANIMS.remove(entityId);
            return List.of();
        }
        long raw = elapsed(text);
        if (raw < 0) {                        // no audio clock for this line on this client (not the owner,
            fallback(text);                   // or TTS off): reveal on a timed estimate so bubbles still show
            raw = elapsed(text);
        }
        long elapsed = smoothClock(raw);      // coarse audio ticks -> fluid motion between frames
        Anim a = ANIMS.computeIfAbsent(entityId, ignored -> new Anim(speechId, text));
        if (a.id != speechId || !a.text.equals(text)) {
            a = new Anim(speechId, text);
            ANIMS.put(entityId, a);
        }
        if (a.words == null) build(entityId, a);

        List<Word> out = new ArrayList<>(a.words.length);
        for (int i = 0; i < a.words.length; i++) {
            long age = elapsed - (long) (a.reveal[i] * durationMs);
            if (age < 0) break;
            if (age >= LIFE_MS) continue;
            float life = age / (float) LIFE_MS;
            float travel = Math.min(1, age / (float) LIFE_MS);              // float up slowly for the word's whole life
            float walk = (1 - (1 - travel) * (1 - travel));                 // ease-out rise/drift fraction
            float lift = RISE_PX * a.liftMul[i] * walk;
            float pop = Math.min(1, age / (float) POP_MS);
            float scale = (0.2f + 0.8f * popIn(pop)) * a.sizeMul[i];        // small -> big with a bounce
            scale = Math.max(0.16f, scale);
            float wobble = (float) Math.sin(age * 0.0045 + a.phase[i]) * 1.6f;
            float fade = age > LIFE_MS - FADE_MS
                    ? 1 - (age - (LIFE_MS - FADE_MS)) / (float) FADE_MS : 1;
            float alpha = Math.min(1, age / 70f) * fade;
            if (alpha < 0.03f) continue;     // vanilla text treats alpha < 4/255 as fully OPAQUE: skip instead of flashing at the end of the fade
            float flash = Math.max(0, 1 - age / 150f);                      // bright right at the pop
            int argb = blend(((int) (alpha * 255) << 24) | (PALETTE[i % PALETTE.length] & 0xFFFFFF), flash);
            float rot = a.rotDeg[i] + wobble;
            out.add(new Word(a.words[i], a.driftX[i] * walk, lift, scale, rot, argb));
        }
        return out;
    }

    /** easeOutBack: starts tiny, shoots past full size and settles - the playful pop. */
    private static float popIn(float p) {
        float c = 1.70158f, c3 = c + 1;
        float q = p - 1;
        return 1 + c3 * q * q * q + c * q * q;
    }

    /** Pushes the base color toward white during the first moments of a word. */
    static int blend(int base, float flash) {
        if (flash <= 0) return base;
        int r = (base >> 16) & 0xFF, g = (base >> 8) & 0xFF, b = base & 0xFF;
        r += (int) ((255 - r) * flash);
        g += (int) ((255 - g) * flash);
        b += (int) ((255 - b) * flash);
        return (base & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    static synchronized void clear() {
        ANIMS.clear();
        clockText = "";
        finishedAt = 0;
        smoothedMs = 0;
    }

    private static synchronized long elapsed(String text) {
        if (text == null || !sameLine(text, clockText) || clockText.isBlank()) return -1;
        long now = System.currentTimeMillis();
        if (finishedAt != 0) return durationMs + now - finishedAt;
        long wall = now - startedAt;
        // The timed estimate used to be clamped at durationMs, which froze the last words at a young age forever
        // (no fade, stuck above the head). Time now keeps running so every word ages out and fades.
        if (fallback) return wall;
        // Audio mode: if the "stopped" callback never arrives, fall back to wall time after a grace period.
        return wall > durationMs + 2000 ? wall : (long) (progress * durationMs);
    }

    /** The voice queue clips lines to 200 chars, so the audio clock may carry a prefix of the synced line. */
    private static boolean sameLine(String text, String clock) {
        return text.equals(clock) || (clock.length() >= 200 && text.startsWith(clock));
    }

    /** Exponential approach to the raw audio clock; snaps on any backward jump (new line / rewind). */
    private static synchronized long smoothClock(long target) {
        if (target < smoothedMs) {
            smoothedMs = target;
        } else {
            smoothedMs = Math.round(smoothedMs + (target - smoothedMs) * CLOCK_EASE);
        }
        return smoothedMs;
    }

    private static long estimateDuration(String text) {
        long total = 250;
        for (String word : text.trim().split("\\s+")) total += weight(word) * 28;
        return Math.max(800, total);
    }

    private static float weight(String word) {
        float value = Math.max(2, word.length());
        if (word.matches(".*[.!?]$")) value += 5;
        else if (word.matches(".*[,;:]$")) value += 2.5f;
        return value;
    }

    /** Splits the line into words, spreads reveal times across the duration and rolls per-word personality. */
    private static void build(int entityId, Anim a) {
        Random rng = new Random(a.id * 88172645463325252L + entityId + 1);
        String[] words = a.text.trim().split("\\s+");
        int n = words.length;
        a.words = words;
        a.reveal = new float[n];
        a.sizeMul = new float[n];
        a.rotDeg = new float[n];
        a.driftX = new float[n];
        a.liftMul = new float[n];
        a.phase = new float[n];
        float totalWeight = 0;
        for (String word : words) totalWeight += weight(word);
        float at = 0;
        for (int i = 0; i < n; i++) {
            a.reveal[i] = totalWeight == 0 ? 0 : at / totalWeight;
            at += weight(words[i]);
            float kind = rng.nextFloat();                 // short words get to be bouncy and big more often
            a.sizeMul[i] = words[i].length() <= 3 && kind < 0.55f
                    ? 1.15f + rng.nextFloat() * 0.5f      // 1.15 - 1.65
                    : 0.75f + rng.nextFloat() * 0.5f;     // 0.75 - 1.25
            a.rotDeg[i] = (rng.nextBoolean() ? 1 : -1) * (2.5f + rng.nextFloat() * 9.5f);  // 2.5..x tilt
            a.driftX[i] = (rng.nextBoolean() ? 1 : -1) * (7f + rng.nextFloat() * 16f);     // sideways drift
            a.liftMul[i] = 0.72f + rng.nextFloat() * 0.4f;
            a.phase[i] = rng.nextFloat() * 6.283f;
        }
    }
}
