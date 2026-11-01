package com.example.companionmod.client;

import com.example.companionmod.CompanionMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Plays decoded TTS PCM through Minecraft's own sound engine (OpenAL) instead of a raw Java
 * {@code SourceDataLine}. Because the audio runs on real engine channels, it obeys the game's audio
 * sliders, automatically pauses with the game (Esc), and mods like Sound Physics Remastered can add
 * their room echo/reverb to the companion's voice like any other in-world sound.
 *
 * <p>The instance claims an invented sound event id and resolves it by handing back a single synthetic
 * {@link Sound} marked {@code stream=true}. NeoForge's patched {@link SoundInstance} interface declares
 * {@code getStream(SoundBufferLibrary, Sound, boolean)}; when that hook is present (NeoForge runtime) the
 * engine calls it and we feed the pre-decoded 16-bit little-endian PCM straight from memory. On a vanilla
 * runtime the hook is absent (detected by reflection), so the caller falls back to the {@code SourceDataLine}
 * path instead. Playback is relative and un-attenuated so the voice is always audible at the player, while
 * still flowing through an engine channel that mods can process.</p>
 */
public class PcmStreamSound extends AbstractSoundInstance {
    private static final Logger LOGGER = LoggerFactory.getLogger("CompanionMod/Tts");
    private static final Identifier EVENT_ID = CompanionMod.id("spoken_voice");

    /** True on runtimes whose sound engine streams per-instance PCM (NeoForge's SoundInstance patch). */
    private static final boolean ENGINE_STREAM_HOOK = probeStreamHook();

    /** Bridges engine-side playback events back to SpeechPlayer (called from engine threads). */
    public interface Listener {
        void onProgress(float progress);                       // 0..1 through the PCM data
        void onLevel(byte[] data, int offset, int length);     // raw chunk for the loudness meter
        void onStop();                                         // exactly once, whatever the exit path
    }

    private final byte[] pcm;                                  // 16-bit LE interleaved frames
    private final int sampleRate, channels;
    private final BooleanSupplier stopFlag;
    private final Listener listen;
    private final AtomicBoolean reported = new AtomicBoolean();
    private final AtomicBoolean stopSent = new AtomicBoolean();
    private final CompletableFuture<Void> done = new CompletableFuture<>();
    private volatile boolean reading;                          // engine has consumed at least one chunk
    private int consumed;

    private PcmStreamSound(byte[] pcm, int sampleRate, int channels, float volume,
                           BooleanSupplier stopFlag, Listener listen) {
        super(EVENT_ID, SoundSource.VOICE, SoundInstance.createUnseededRandom());
        this.pcm = pcm;
        this.sampleRate = Math.max(4000, sampleRate);
        this.channels = Math.max(1, Math.min(2, channels));
        this.stopFlag = stopFlag;
        this.listen = listen;
        this.volume = Math.max(0.01f, Math.min(1f, volume));   // engine multiplies instance volume; keep Sound at 1.0
        this.pitch = 1.0f;
        this.relative = true;                                  // voice follows the listener; always audible
        this.attenuation = SoundInstance.Attenuation.NONE;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {                               // anchor at the player so SPR hears an in-room source
            this.x = mc.player.getX();
            this.y = mc.player.getY();
            this.z = mc.player.getZ();
        }
        this.sound = new Sound(EVENT_ID, ConstantFloat.of(1.0f), ConstantFloat.of(1.0f), 1,
                Sound.Type.FILE, true /* stream */, false /* preload */, 16);
    }

    private static boolean probeStreamHook() {
        try {
            Method m = SoundInstance.class.getMethod("getStream", SoundBufferLibrary.class, Sound.class, boolean.class);
            return m != null;
        } catch (Throwable t) {
            return false;                                      // vanilla runtime: engine streams by file location only
        }
    }

    /** Whether this runtime can stream PCM through the engine at all. */
    public static boolean engineStreamingSupported() { return ENGINE_STREAM_HOOK; }

    /**
     * Tries the engine; returns false (nothing played, caller should fall back) if the runtime lacks the
     * hook, the engine wouldn't start, or the engine never actually reads the stream. Blocks the calling
     * thread until the line finishes, is stopped, or times out.
     */
    public static boolean play(byte[] pcm, int sampleRate, int channels, float volume, long durationMs,
                               BooleanSupplier stopFlag, Listener listen) {
        if (!ENGINE_STREAM_HOOK) return false;
        Minecraft mc = Minecraft.getInstance();
        SoundManager manager = mc == null ? null : mc.getSoundManager();
        if (manager == null) return false;
        PcmStreamSound instance = new PcmStreamSound(pcm, sampleRate, channels, volume, stopFlag, listen);
        SoundEngine.PlayResult result;
        try {
            result = manager.play(instance);
        } catch (Throwable t) {
            LOGGER.debug("Engine playback rejected TTS: {}", t.toString());
            return false;
        }
        if (result != SoundEngine.PlayResult.STARTED) {
            LOGGER.debug("Engine playback NOT started ({})", result);
            return false;
        }
        // Watchdog: if the engine never consumes a single chunk (bad device, unsupported format, vanilla
        // runtime without the hook), give up quickly so the caller can fall back to the SourceDataLine path.
        long noDataDeadline = System.currentTimeMillis() + 2500;
        while (!instance.reading && !instance.done.isDone() && System.currentTimeMillis() < noDataDeadline) {
            if (stopFlag.getAsBoolean()) { instance.requestEngineStop(); return false; }
            try {
                instance.done.get(100, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) { }
        }
        if (!instance.reading) {
            instance.requestEngineStop();
            instance.reportStop();
            return false;
        }
        long deadline = System.currentTimeMillis() + durationMs + 8000;
        while (!instance.done.isDone() && System.currentTimeMillis() < deadline) {
            try {
                instance.done.get(100, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) { }
            if (stopFlag.getAsBoolean()) instance.requestEngineStop();
        }
        instance.reportStop();                                 // idempotent: subtitle clock finishes either way
        return true;                                           // started and handled; no fallback replay
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        // The engine needs a resolved event before it will play; provide our synthetic single sound.
        WeighedSoundEvents event = new WeighedSoundEvents(getIdentifier(), null);
        event.addSound(this.sound);
        return event;
    }

    // No @Override: the method only exists on NeoForge's patched SoundInstance. It compiles against the
    // vanilla interface as a plain method and is dispatched virtually at runtime on NeoForge.
    @SuppressWarnings("unused")
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
        return CompletableFuture.completedFuture(new MemoryAudioStream());
    }

    /** Reads the held PCM in engine-sized chunks, reporting progress and loudness as it goes. */
    private class MemoryAudioStream implements AudioStream {
        private final AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);

        @Override
        public AudioFormat getFormat() { return format; }

        @Override
        public ByteBuffer read(int capacity) throws IOException {
            reading = true;
            if (stopFlag.getAsBoolean()) requestEngineStop();
            if (consumed >= pcm.length) {
                reportStop();                                  // engine stops the channel and closes the stream
                return null;                                   // null = EOF, exactly what pumpBuffers checks for
            }
            int len = (int) Math.min(capacity <= 0 ? 4096 : capacity, (long) pcm.length - consumed);
            int offset = consumed;
            consumed += len;
            try {
                listen.onProgress(consumed / (float) pcm.length);
                listen.onLevel(pcm, offset, len);
            } catch (Throwable ignored) { }
            // Direct buffer, like every vanilla stream: heap buffers crash alBufferData on some OpenAL builds.
            ByteBuffer out = ByteBuffer.allocateDirect(len).order(ByteOrder.LITTLE_ENDIAN);
            out.put(pcm, offset, len).flip();
            return out;
        }

        @Override
        public void close() { reportStop(); }
    }

    /** Asks the game thread to stop the channel; the engine then closes the stream -> reportStop(). */
    private void requestEngineStop() {
        if (stopSent.compareAndSet(false, true)) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.execute(() -> mc.getSoundManager().stop(this));
        }
    }

    /** Exactly-once tail of playback: informs the subtitle clock and releases the blocking wait. */
    private void reportStop() {
        if (!reported.compareAndSet(false, true)) return;
        try {
            listen.onStop();
        } catch (Throwable ignored) { }
        done.complete(null);
    }
}
