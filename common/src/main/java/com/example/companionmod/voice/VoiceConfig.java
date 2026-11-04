package com.example.companionmod.voice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Client-side settings, stored as config/companionmod-client.json. Missing keys fall back to these defaults. */
public final class VoiceConfig {
    public String host = "http://127.0.0.1:11434";
    /** Model that hears the player (audio in) and decides what to say / do. */
    public String chatModel = "lfm3-audio:latest";
    /** Speech model for spoken replies. Empty = auto-detect (chatModel if Ollama reports "speech", else fallbackSpeechModel). */
    public String speechModel = "";
    public String fallbackSpeechModel = "phonon";
    public String speechVoice = "";
    /** Java Sound input device name for push-to-talk. Empty = system default microphone. */
    public String micDevice = "";
    /** Java Sound output device for spoken replies (bypasses Minecraft's engine so a specific device can be used). Empty = default. */
    public String outputDevice = "";
    /** Optional speech-to-text model (Whisper etc.) so a normal text LLM can be used instead of an audio model. */
    public String sttModel = "";
    /** OpenAI-compatible base URL for the STT model. Empty = use {@link #host}. */
    public String sttHost = "";
    public boolean speakReplies = true;
    /** 0..1 playback volume for spoken replies. */
    public float speechVolume = 0.9f;
    public String companionName = "Companion";
    public String companionDescription = "A loyal companion fighting beside you.";
    public String companionPersonality = "Friendly and helpful";
    /** Comma-separated traits the model should stay in character with. */
    public String companionTraits = "loyal, curious, protective";
    public String companionCardBackground = "default";
    /** Minecraft player name whose skin the companion wears. Empty = bundled skin. "owner" = your own skin. */
    public String companionSkin = "";
    /** Renders the optional feminine chest model. */
    public boolean feminineModel = false;
    /** Body model: "auto" (follows the skin), "slim" (Alex) or "classic" (wide). */
    public String companionBody = "auto";
    public boolean memoryEnabled = true;
    public int memoryMaxEvents = 30;
    public int maxRecordSeconds = 20;
    public int minRecordMillis = 350;
    /** Recordings whose loudest moment stays below this (0..1 on the HUD scale) are discarded as silence. */
    public float silenceGate = 0.12f;
    /** When true, spoken requests that aren't direct orders are turned into an AI plan by a second LLM call. Off by default: chitchat must not become chores. */
    public boolean dynamicGoals = false;
    /** When true, the companion decides on its own to do small safe things every few minutes. Off by default. */
    public boolean autonomyEnabled = false;
    /** Keep the chunks around the companion force-loaded, so he keeps mining/working when the player walks away. Off by default. */
    public boolean forceLoadChunks = false;
    /** Rough seconds between two autonomous thoughts. */
    public int autonomyIntervalSeconds = 30;
    /** Bumped whenever defaults change meaningfully; migrates older config files. */
    public int settingsVersion = 2;
    public int historyTurns = 12;
    public int timeoutSeconds = 60;
    /** Higher = more varied/character replies; 0.3 felt robotic, 0.7 keeps it chatty but on-key. */
    public float temperature = 0.7f;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static VoiceConfig load(Path file) {
        try {
            if (Files.exists(file)) {
                VoiceConfig c = GSON.fromJson(Files.readString(file), VoiceConfig.class);
                if (c != null) {
                    if (c.settingsVersion < 2) {          // v2: self-planning was far too trigger-happy (greeting -> "dig diamonds")
                        c.dynamicGoals = false;
                        c.autonomyEnabled = false;
                        c.settingsVersion = 2;
                    }
                    c.save(file); return c;             // re-save so new keys appear in old files
                }
            }
        } catch (Exception ignored) { /* corrupt file: use defaults */ }
        VoiceConfig c = new VoiceConfig();
        c.save(file);
        return c;
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this));
        } catch (IOException ignored) { }
    }
}
