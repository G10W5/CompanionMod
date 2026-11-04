package com.example.companionmod.net;

import com.example.companionmod.CompanionMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> server: "the player told their companion to do {@code action}, it answered {@code say},
 * and the order optionally names a {@code target} entity (e.g. "creeper")."
 */
public record CommandPayload(String action, String say, String target) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CommandPayload> TYPE = new CustomPacketPayload.Type<>(CompanionMod.id("command"));
    public static final StreamCodec<ByteBuf, CommandPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(32), CommandPayload::action,
            ByteBufCodecs.stringUtf8(512), CommandPayload::say,
            // 4096: a plan JSON (8 steps + up to 120-char say each) overran the old 1024 cap and the client
            // clamp silently cut the JSON mid-structure - the server then rejected a corrupted plan.
            ByteBufCodecs.stringUtf8(4096), CommandPayload::target,
            CommandPayload::new);

    public CommandPayload(String action, String say) {
        this(action, say, "");
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
