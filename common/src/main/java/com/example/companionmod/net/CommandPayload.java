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
            ByteBufCodecs.stringUtf8(1024), CommandPayload::target,
            CommandPayload::new);

    public CommandPayload(String action, String say) {
        this(action, say, "");
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
