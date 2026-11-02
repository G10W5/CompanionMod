package com.example.companionmod.voice;

import com.example.companionmod.client.PcmStreamSound;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.util.function.Supplier;

/**
 * Plays WAV replies and reports actual PCM playback progress to the subtitle clock. Prefers Minecraft's
 * own sound engine (OpenAL) so the voice pauses with the game, obeys the audio sliders, and gets any
 * in-world effects installed (e.g. Sound Physics Remastered's echo); a plain {@code SourceDataLine}
 * stays as a fallback for runtimes where the engine can't stream memory PCM.
 *
 * <p>When the player picks a specific output device in the settings, the engine path is skipped: OpenAL
 * cannot target a chosen Java Sound device, so playback goes straight to that device's SourceDataLine.</p>
 */
public final class SpeechPlayer {
    public interface Listener {
        Listener NONE = new Listener() {};
        default void onStart(String text, long durationMillis) {}
        default void onProgress(String text, float progress) {}
        default void onStop(String text) {}
        default void onUnavailable(String text) {}
    }

    private final LevelMeter meter;
    private final Listener listener;
    private final Supplier<String> outputDevice;   // read on every playback: settings changes apply live
    private volatile boolean playing;
    private volatile boolean stopRequested;

    public SpeechPlayer(LevelMeter meter) { this(meter, Listener.NONE); }
    public SpeechPlayer(LevelMeter meter, Listener listener) { this(meter, listener, () -> ""); }
    public SpeechPlayer(LevelMeter meter, Listener listener, Supplier<String> outputDevice) {
        this.meter = meter;
        this.listener = listener == null ? Listener.NONE : listener;
        this.outputDevice = outputDevice;
    }

    public boolean isPlaying() { return playing; }
    public void stop() { stopRequested = true; }

    public void playBlocking(byte[] wav) { playBlocking(wav, 1.0f, ""); }
    public void playBlocking(byte[] wav, float volume) { playBlocking(wav, volume, ""); }

    /** Blocking playback; call from a worker thread. */
    public void playBlocking(byte[] wav, float volume, String text) {
        WavUtil.Pcm pcm;
        try {
            pcm = WavUtil.decode(wav);
        } catch (RuntimeException e) {
            listener.onUnavailable(text);
            return;
        }
        AudioFormat fmt = new AudioFormat(pcm.sampleRate(), 16, pcm.channels(), true, false);
        float gain = Math.max(0f, Math.min(1f, volume));
        byte[] data = applyGain(pcm.data(), gain);
        int frameBytes = Math.max(1, 2 * pcm.channels());
        long duration = Math.max(1L, Math.round(
                1000.0 * data.length / frameBytes / pcm.sampleRate()));
        playing = true;
        stopRequested = false;
        meter.reset();
        listener.onStart(text, duration);

        // ---- engine path: OpenAL channel -> pause-aware, slider-obeying, SPR-echoed --------------------
        // Skipped when a specific output device was chosen: OpenAL cannot target a chosen Java Sound device.
        String dev = outputDevice == null ? "" : outputDevice.get();
        boolean explicitDevice = dev != null && !dev.isBlank();
        if (!explicitDevice && PcmStreamSound.engineStreamingSupported()) {
            try {
                boolean handled = PcmStreamSound.play(data, pcm.sampleRate(), pcm.channels(), 1.0f, duration,
                        () -> stopRequested, new PcmStreamSound.Listener() {
                            @Override public void onProgress(float progress) { listener.onProgress(text, progress); }
                            @Override public void onLevel(byte[] bytes, int off, int len) {
                                byte[] slice = new byte[len];
                                System.arraycopy(bytes, off, slice, 0, len);
                                meter.feed(slice, len);
                            }
                            @Override public void onStop() { listener.onStop(text); }
                        });
                if (handled) { playing = false; meter.reset(); return; }
            } catch (Throwable ignored) { }   // any engine surprise falls through to the line below
        }

        // ---- fallback: direct SourceDataLine (engine missing / rejected the stream / explicit device) -
        boolean started = false;
        try (SourceDataLine out = openOutput(fmt, explicitDevice ? dev : "")) {
            out.open(fmt);
            out.start();
            started = true;
            int chunk = Math.max(frameBytes, pcm.sampleRate() / 25 * frameBytes);
            for (int off = 0; off < data.length && !stopRequested; off += chunk) {
                int len = Math.min(chunk, data.length - off);
                byte[] slice = new byte[len];
                System.arraycopy(data, off, slice, 0, len);
                meter.feed(slice, len);
                int written = out.write(slice, 0, len);
                listener.onProgress(text, Math.min(1.0f, (off + written) / (float) data.length));
            }
            if (stopRequested) out.flush(); else out.drain();
        } catch (Exception ignored) {
            if (!started) listener.onUnavailable(text);
        } finally {
            if (started) listener.onStop(text);
            playing = false;
            meter.reset();
        }
    }

    /** The chosen device's line, or the system default when {@code dev} is blank/unknown. */
    private static SourceDataLine openOutput(AudioFormat fmt, String dev) throws LineUnavailableException {
        Mixer.Info wanted = AudioDevices.find(dev);
        if (wanted != null) {
            try {
                return AudioSystem.getSourceDataLine(fmt, wanted);
            } catch (IllegalArgumentException | LineUnavailableException e) {
                // chosen mixer vanished or cannot play at this format: fall through to the default line
            }
        }
        return AudioSystem.getSourceDataLine(fmt);
    }

    private static byte[] applyGain(byte[] raw, float gain) {
        if (gain >= 0.999f || raw.length < 2) return raw;
        byte[] out = raw.clone();
        for (int i = 0; i + 1 < out.length; i += 2) {
            short sample = (short) ((out[i] & 0xFF) | (out[i + 1] << 8));
            sample = (short) (sample * gain);
            out[i] = (byte) (sample & 0xFF);
            out[i + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return out;
    }
}
