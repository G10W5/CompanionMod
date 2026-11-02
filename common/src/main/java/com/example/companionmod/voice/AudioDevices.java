package com.example.companionmod.voice;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.List;

/**
 * Enumerates the Java Sound input/output devices (mixers) by name and resolves a stored name back to a
 * {@code Mixer.Info}. Names are the stable-ish human identifier ("Microphone Array (Realtek...)", ...);
 * an empty name means the system default, which is always available.
 */
public final class AudioDevices {
    private AudioDevices() {}

    /** Names of every mixer that can record (has a TargetDataLine). */
    public static List<String> inputs() {
        List<String> out = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(info).isLineSupported(new Line.Info(TargetDataLine.class))) out.add(info.getName());
        }
        return out;
    }

    /** Names of every mixer that can play (has a SourceDataLine). */
    public static List<String> outputs() {
        List<String> out = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(info).isLineSupported(new Line.Info(SourceDataLine.class))) out.add(info.getName());
        }
        return out;
    }

    /** The mixer with exactly this name, or null when it was unplugged / renamed / never existed. */
    public static Mixer.Info find(String name) {
        if (name == null || name.isBlank()) return null;
        for (Mixer.Info info : AudioSystem.getMixerInfo()) if (name.equals(info.getName())) return info;
        return null;
    }
}
