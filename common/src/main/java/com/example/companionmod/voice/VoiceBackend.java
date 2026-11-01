package com.example.companionmod.voice;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * Abstraction for a local voice AI backend (Ollama + LFM3-Audio).
 * Implementations should perform network I/O off the render thread.
 */
public interface VoiceBackend {
    /**
     * Send recorded WAV bytes to the backend and receive a text response.
     */
    CompletableFuture<String> ask(byte[] wavBytes);

    /**
     * Request TTS audio for the given text and return a WAV file path or bytes.
     */
    CompletableFuture<byte[]> speak(String text, String voice);
}
