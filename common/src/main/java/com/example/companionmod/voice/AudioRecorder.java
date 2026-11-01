package com.example.companionmod.voice;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal microphone recorder using javax.sound.sampled.
 * Note: In Minecraft, audio device access may require client-side permissions.
 */
public class AudioRecorder {
    private TargetDataLine line;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private Thread thread;

    public void start() {
        try {
            AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format);
            line.start();
            running.set(true);
            buffer.reset();
            thread = new Thread(() -> {
                byte[] chunk = new byte[1024];
                while (running.get()) {
                    int read = line.read(chunk, 0, chunk.length);
                    if (read > 0) buffer.write(chunk, 0, read);
                }
            });
            thread.setDaemon(true);
            thread.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public byte[] stopAndGetWav() {
        running.set(false);
        if (thread != null) {
            try { thread.join(500); } catch (InterruptedException ignored) {}
        }
        if (line != null) {
            line.stop();
            line.close();
        }
        // Wrap raw PCM in a WAV container.
        byte[] pcm = buffer.toByteArray();
        try {
            AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
            ByteArrayInputStream bais = new ByteArrayInputStream(pcm);
            AudioInputStream ais = new AudioInputStream(bais, format, pcm.length / format.getFrameSize());
            ByteArrayOutputStream wavOut = new ByteArrayOutputStream();
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wavOut);
            return wavOut.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
            return new byte[0];
        }
    }
}
