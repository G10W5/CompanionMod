package com.example.companionmod.voice;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Hold-to-talk state machine and the single companion speech playback queue.
 *
 * <p>Thread rules: the game thread only flips the state and queues work. Opening / closing the microphone happens on
 * its own IO thread (it can block), HTTP and playback on the worker. The state lives in one {@link AtomicReference}
 * and every transition is a compare-and-set, so the speech thread can never overwrite LISTENING / THINKING
 * (which used to leave the microphone open).</p>
 */
public final class PushToTalkController {
    private final OllamaVoiceBackend backend;
    private final VoiceConfig cfg;
    private final Supplier<String> context;
    private final Consumer<VoiceReply> onReply;
    private final Consumer<String> onStatus;
    private final SpeechPlayer.Listener speechListener;

    private final LevelMeter micMeter = new LevelMeter();
    private final LevelMeter speakMeter = new LevelMeter();
    private final MicCapture mic;
    private final SpeechPlayer player;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> daemon(r, "companionmod-voice"));
    private final ExecutorService micIo = Executors.newSingleThreadExecutor(r -> daemon(r, "companionmod-mic-io"));
    private final AtomicReference<VoiceState> state = new AtomicReference<>(VoiceState.IDLE);
    private volatile boolean probeStarted;
    private volatile boolean micFailed;

    /** Latest line waiting to be spoken; rapid chatter is intentionally coalesced. */
    private final AtomicReference<String> pendingSpeech = new AtomicReference<>();
    private volatile boolean speechDraining;
    private volatile long lastSpeechProbe;      // speech-model recovery: re-probe at most once a minute

    public PushToTalkController(OllamaVoiceBackend backend, VoiceConfig cfg, Supplier<String> context,
                                Consumer<VoiceReply> onReply, Consumer<String> onStatus) {
        this(backend, cfg, context, onReply, onStatus, SpeechPlayer.Listener.NONE);
    }

    public PushToTalkController(OllamaVoiceBackend backend, VoiceConfig cfg, Supplier<String> context,
                                Consumer<VoiceReply> onReply, Consumer<String> onStatus,
                                SpeechPlayer.Listener speechListener) {
        this.backend = backend;
        this.cfg = cfg;
        this.context = context;
        this.onReply = onReply;
        this.onStatus = onStatus;
        this.speechListener = speechListener == null ? SpeechPlayer.Listener.NONE : speechListener;
        this.player = new SpeechPlayer(speakMeter, this.speechListener, () -> cfg.outputDevice);
        this.mic = new MicCapture(micMeter, () -> cfg.maxRecordSeconds, () -> cfg.micDevice);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    public VoiceState state() { return state.get(); }
    public float level() {
        return switch (state.get()) { case LISTENING -> micMeter.level(); case SPEAKING -> speakMeter.level(); default -> 0f; };
    }
    public float peak() {
        return switch (state.get()) { case LISTENING -> micMeter.peak(); case SPEAKING -> speakMeter.peak(); default -> 0f; };
    }
    public long listeningMillis() { return mic.elapsedMillis(); }

    /** Game thread. */
    public void onPress() {
        VoiceState s = state.get();
        if (s == VoiceState.SPEAKING) player.stop();
        else if (s != VoiceState.IDLE) return;
        if (!state.compareAndSet(s, VoiceState.LISTENING)) return;     // lost a race with the speech thread: ignore this press
        micFailed = false;
        if (!probeStarted) {
            probeStarted = true;
            worker.execute(() -> onStatus.accept(backend.probe().message()));
        }
        micIo.execute(() -> {
            if (!mic.start()) {
                micFailed = true;
                state.compareAndSet(VoiceState.LISTENING, VoiceState.IDLE);
                onStatus.accept(mic.lastError());
            }
        });
    }

    /** Game thread. */
    public void onRelease() {
        if (state.get() != VoiceState.LISTENING) return;
        final String ctx = context.get();                               // world snapshot must be taken on the game thread
        micIo.execute(() -> {
            if (micFailed) return;
            long ms = mic.elapsedMillis();
            byte[] pcm = mic.stop();
            if (ms < cfg.minRecordMillis || micMeter.maxSeen() < cfg.silenceGate) {
                state.compareAndSet(VoiceState.LISTENING, VoiceState.IDLE);
                onStatus.accept("I didn't hear anything.");
                return;
            }
            if (!state.compareAndSet(VoiceState.LISTENING, VoiceState.THINKING)) return;
            final byte[] wav = WavUtil.pcm16ToWav(pcm, MicCapture.SAMPLE_RATE, 1);
            worker.execute(() -> {
                VoiceReply reply;
                try {
                    if (!backend.isProbed()) backend.probe();
                    reply = backend.converse(wav, ctx);
                } catch (Exception e) {
                    state.compareAndSet(VoiceState.THINKING, VoiceState.IDLE);
                    onStatus.accept(e.getMessage() == null ? e.toString() : e.getMessage());
                    return;
                }
                onReply.accept(reply);
                state.compareAndSet(VoiceState.THINKING, VoiceState.IDLE);
            });
        });
    }

    /** Called on the game thread; synthesis and playback happen on the worker. */
    public void speakText(String text) {
        if (text == null || text.isBlank()) return;
        pendingSpeech.set(text.length() > 200 ? text.substring(0, 200) : text);
        if (speechDraining) return;
        speechDraining = true;
        worker.execute(this::drainSpeech);
    }

    private void drainSpeech() {
        String current = null;
        try {
            for (;;) {
                current = pendingSpeech.getAndSet(null);
                if (current == null) break;
                if (!backend.isProbed()) backend.probe();
                if (backend.speechModel() == null) {
                    // The startup probe may have run before the speech model was pulled (or it 404'd later).
                    // Re-probe at most once a minute so speech recovers without a relog.
                    long nowMs = System.currentTimeMillis();
                    if (nowMs - lastSpeechProbe > 60_000) {
                        lastSpeechProbe = nowMs;
                        try { backend.probe(); } catch (RuntimeException ignored) { }
                    }
                    if (backend.speechModel() == null) {
                        speechListener.onUnavailable(current);
                        continue;
                    }
                }
                // Only speak while idle: never talk over the player (LISTENING / THINKING keep their state).
                if (!state.compareAndSet(VoiceState.IDLE, VoiceState.SPEAKING) && state.get() != VoiceState.SPEAKING) {
                    speechListener.onUnavailable(current);
                    continue;
                }
                byte[] audio = backend.synthesize(current);
                if (state.get() != VoiceState.SPEAKING) {          // the player pressed the key while we were synthesizing
                    speechListener.onUnavailable(current);
                    continue;
                }
                if (audio == null) speechListener.onUnavailable(current);
                else player.playBlocking(audio, cfg.speechVolume, current);
            }
        } catch (Exception e) {
            if (current != null) speechListener.onUnavailable(current);
            onStatus.accept("Speech failed, continuing in text: " + e.getMessage());
        } finally {
            speechDraining = false;
            state.compareAndSet(VoiceState.SPEAKING, VoiceState.IDLE);
        }
    }

    public void shutdown() {
        player.stop();
        micIo.execute(() -> { if (mic.isRunning()) mic.stop(); });
        micIo.shutdown();
        worker.shutdownNow();
        state.set(VoiceState.IDLE);
    }
}
