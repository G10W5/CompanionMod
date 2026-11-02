package com.example.companionmod.net;

import com.example.companionmod.command.CompanionCommand;
import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.registry.ModContent;
import com.example.companionmod.voice.ReplyParser;
import com.example.companionmod.world.CompanionRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-side entry point for voice commands. The client is untrusted: validate, rate-limit, only touch the sender's own companion. */
public final class CommandHandler {
    /**
     * Token bucket instead of a single 400 ms gap: the client legitimately sends bursts such as
     * "set_skin" + "feminine" in one tick, and the old single-gap limiter silently dropped the second
     * packet — which is why the feminine model never appeared.
     */
    private static final Map<UUID, Bucket> BUCKETS = new ConcurrentHashMap<>();
    private static final double CAPACITY = 8.0;
    private static final double REFILL_PER_SECOND = 6.0;

    private static final class Bucket {
        double tokens = CAPACITY;
        long lastRefill = System.currentTimeMillis();
    }

    private CommandHandler() {}

    public static void handle(ServerPlayer player, CommandPayload payload) {
        CompanionCommand cmd = CompanionCommand.parse(payload.action());

        // STOP is an emergency control, not optional background work. Never lose it to the token bucket.
        if (cmd != CompanionCommand.STOP) {
            Bucket bucket = BUCKETS.computeIfAbsent(player.getUUID(), id -> new Bucket());
            long now = System.currentTimeMillis();
            synchronized (bucket) {
                bucket.tokens = Math.min(CAPACITY, bucket.tokens + (now - bucket.lastRefill) / 1000.0 * REFILL_PER_SECOND);
                bucket.lastRefill = now;
                if (bucket.tokens < 1.0) return;
                bucket.tokens -= 1.0;
            }
        }
        String say = ReplyParser.clean(payload.say());
        String target = cmd == CompanionCommand.PLAN ? payload.target() : ReplyParser.clean(payload.target());

        CompanionEntity companion = ModContent.findCompanion(player.level().getServer(), player.getUUID());
        if (companion == null) {
            boolean exists = CompanionRegistry.get(player.level().getServer(), player.getUUID()) != null;
            player.sendSystemMessage(Component.literal(exists
                    ? "Your companion is too far away (its chunk is not loaded). Activate a Companion Shrine to call it."
                    : "You have no companion. Activate a Companion Shrine to summon one."));
            return;
        }
        companion.applyCommand(cmd, player, target);
        if (!say.isBlank()) companion.speak(say);
    }

    public static void forget(UUID player) { BUCKETS.remove(player); }

    public static void forgetAll() { BUCKETS.clear(); }
}
