package com.example.companionmod.voice;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.util.function.IntSupplier;

/** Records 16 kHz mono 16-bit PCM from the default microphone on a daemon thread and feeds a {@link LevelMeter}. */
public final class MicCapture {
    public static final int SAMPLE_RATE = 16000;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);

    private final LevelMeter meter;
    private final IntSupplier maxSeconds;
    private volatile int maxBytes;
    private volatile boolean running;
    private volatile String lastError = "";
    private TargetDataLine line;
    private Thread thread;
    private ByteArrayOutputStream buffer;
    private volatile long startedAt;

    public MicCapture(LevelMeter meter, int maxSeconds) { this(meter, () -> maxSeconds); }

    /** The cap is read on every start, so changing it in the settings screen takes effect immediately. */
    public MicCapture(LevelMeter meter, IntSupplier maxSeconds) {
        this.meter = meter;
        this.maxSeconds = maxSeconds;
    }

    public String lastError() { return lastError; }
    public boolean isRunning() { return running; }

    /** Opens the line: can block for a moment, so call it from a worker thread, never the render thread. */
    public synchronized boolean start() {
        if (running) return true;
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            if (!AudioSystem.isLineSupported(info)) { lastError = "No microphone supporting 16 kHz mono found"; return false; }
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(FORMAT, SAMPLE_RATE / 5 * 2);
            line.start();
        } catch (Exception e) {
            lastError = "Microphone unavailable: " + e.getMessage();
            return false;
        }
        maxBytes = Math.max(1, maxSeconds.getAsInt()) * SAMPLE_RATE * 2;
        buffer = new ByteArrayOutputStream();
        meter.reset();
        running = true;
        startedAt = System.currentTimeMillis();
        final TargetDataLine l = line;
        final ByteArrayOutputStream buf = buffer;
        thread = new Thread(() -> {
            byte[] chunk = new byte[SAMPLE_RATE / 50 * 2]; // 20 ms
            while (running) {
                int n = l.read(chunk, 0, chunk.length);
                if (n <= 0) continue;
                meter.feed(chunk, n);
                if (buf.size() < maxBytes) buf.write(chunk, 0, n);
            }
        }, "companionmod-mic");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    /** Stops recording and returns the PCM captured (empty array if it was never started). */
    public synchronized byte[] stop() {
        if (!running) return new byte[0];
        running = false;
        try { thread.join(200); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        line.stop();
        line.close();
        byte[] out = buffer.toByteArray();
        buffer = null;
        return out;
    }

    public long elapsedMillis() { return running ? System.currentTimeMillis() - startedAt : 0; }
}
