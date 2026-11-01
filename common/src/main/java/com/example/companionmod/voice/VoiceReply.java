package com.example.companionmod.voice;

import com.example.companionmod.command.CompanionCommand;

/**
 * What the model understood and what the companion answers.
 * {@code heard} may be empty if the model skipped it; {@code target} carries an entity name for
 * "attack this creeper" style orders; {@code memory} is an optional fact to remember.
 */
public record VoiceReply(String heard, String say, CompanionCommand command, String target, String memory) {
    public VoiceReply(String heard, String say, CompanionCommand command) {
        this(heard, say, command, "", "");
    }

    public static VoiceReply text(String say) {
        return new VoiceReply("", say, CompanionCommand.NONE, "", "");
    }
}
