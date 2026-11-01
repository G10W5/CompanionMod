package com.example.companionmod.voice;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Hold-to-talk state machine and the single companion speech playback queue. */
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
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "companionmod-voice");
        t.setDaemon(true);
        return t;
    });
    private volatile VoiceState state = VoiceState.IDLE;
    private volatile boolean probeStarted;

    /** Latest line waiting to be spoken; rapid chatter is intentionally coalesced. */
    private final AtomicReference<String> pendingSpeech = new AtomicReference<>();
    private volatile boolean speechDraining;

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
        this.player = new SpeechPlayer(speakMeter, this.speechListener);
        this.mic = new MicCapture(micMeter, cfg.maxRecordSeconds);
    }

    public VoiceState state() { return state; }
    public float level() {
        return switch (state) { case LISTENING -> micMeter.level(); case SPEAKING -> speakMeter.level(); default -> 0f; };
    }
    public float peak() {
        return switch (state) { case LISTENING -> micMeter.peak(); case SPEAKING -> speakMeter.peak(); default -> 0f; };
    }
    public long listeningMillis() { return mic.elapsedMillis(); }

    public void onPress() {
        if (state == VoiceState.SPEAKING) player.stop();
        else if (state != VoiceState.IDLE) return;
        if (!probeStarted) {
            probeStarted = true;
            worker.execute(() -> onStatus.accept(backend.probe().message()));
        }
        if (mic.start()) state = VoiceState.LISTENING;
        else onStatus.accept(mic.lastError());
    }

    public void onRelease() {
        if (state != VoiceState.LISTENING) return;
        long ms = mic.elapsedMillis();
        byte[] pcm = mic.stop();
        if (ms < cfg.minRecordMillis || micMeter.maxSeen() < cfg.silenceGate) {
            state = VoiceState.IDLE;
            onStatus.accept("I didn't hear anything.");
            return;
        }
        state = VoiceState.THINKING;
        final String ctx = context.get();
        final byte[] wav = WavUtil.pcm16ToWav(pcm, MicCapture.SAMPLE_RATE, 1);
        worker.execute(() -> {
            VoiceReply reply;
            try {
                if (!backend.isProbed()) backend.probe();
                reply = backend.converse(wav, ctx);
            } catch (Exception e) {
                state = VoiceState.IDLE;
                onStatus.accept(e.getMessage() == null ? e.toString() : e.getMessage());
                return;
            }
            onReply.accept(reply);
            state = VoiceState.IDLE;
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
                    speechListener.onUnavailable(current);
                    continue;
                }
                state = VoiceState.SPEAKING;
                byte[] audio = backend.synthesize(current);
                if (audio == null) speechListener.onUnavailable(current);
                else player.playBlocking(audio, cfg.speechVolume, current);
            }
        } catch (Exception e) {
            if (current != null) speechListener.onUnavailable(current);
            onStatus.accept("Speech failed, continuing in text: " + e.getMessage());
        } finally {
            speechDraining = false;
            if (state == VoiceState.SPEAKING) state = VoiceState.IDLE;
        }
    }

    public void shutdown() {
        player.stop();
        if (mic.isRunning()) mic.stop();
        worker.shutdownNow();
    }
}
