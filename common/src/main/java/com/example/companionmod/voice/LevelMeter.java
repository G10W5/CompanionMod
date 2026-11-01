package com.example.companionmod.voice;

/**
 * Turns PCM chunks into a 0..1 loudness value for the HUD bar. Thread-safe for one writer, many readers.
 * Mapping is on a dBFS scale (-60..-12 dB) so quiet speech still moves the bar and shouting does not pin it instantly.
 */
public final class LevelMeter {
    private static final float FLOOR_DB = -60f, CEIL_DB = -12f;
    private volatile float level, peak;
    private volatile long peakStamp;
    private volatile float maxSeen; // loudest raw (unsmoothed) value since reset, used for the "was anything said" gate

    public void feed(byte[] pcm16le, int len) {
        int n = len / 2;
        if (n == 0) return;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            short s = (short) ((pcm16le[2 * i] & 0xFF) | (pcm16le[2 * i + 1] << 8));
            sum += (double) s * s;
        }
        double rms = Math.sqrt(sum / n) / 32768.0;
        float db = rms <= 1e-6 ? -120f : (float) (20 * Math.log10(rms));
        float raw = Math.max(0f, Math.min(1f, (db - FLOOR_DB) / (CEIL_DB - FLOOR_DB)));
        float cur = level;
        level = raw > cur ? cur + (raw - cur) * 0.6f : cur + (raw - cur) * 0.15f; // fast attack, slow release
        long now = System.nanoTime();
        if (raw >= peak || now - peakStamp > 600_000_000L) { peak = Math.max(raw, peak * 0.97f); peakStamp = now; }
        if (raw > maxSeen) maxSeen = raw;
    }

    public float level() { return level; }
    public float peak() { return peak; }
    public float maxSeen() { return maxSeen; }
    public void reset() { level = 0; peak = 0; maxSeen = 0; }
}
