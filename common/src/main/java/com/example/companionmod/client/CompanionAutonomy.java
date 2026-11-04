package com.example.companionmod.client;

import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.voice.PlanParser;
import com.example.companionmod.voice.PlanningBackend;
import com.example.companionmod.voice.VoiceConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client-side goal planner with generation-based cancellation.
 *
 * <p>Every manual gameplay order advances {@link #GENERATION}. A model response is delivered only if
 * its generation is still current, so a slow response can never restart work after STOP or replace a
 * newer order. HTTP calls may finish in the background, but their results become harmless.</p>
 */
public final class CompanionAutonomy {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "companionmod-planner");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicLong GENERATION = new AtomicLong();

    private static VoiceConfig cfg;
    private static PlanningBackend backend;
    private static volatile long nextThinkAt;
    private static volatile boolean busy;
    private static volatile String goal = "";
    private static volatile long goalUntil;

    private CompanionAutonomy() {}

    public static void init(VoiceConfig config) {
        cfg = config;
        backend = new PlanningBackend(config);
        reset();
    }

    /** Label of the plan currently running (empty when none). For the wheel hub / HUD. */
    public static String currentGoal() {
        return System.currentTimeMillis() < goalUntil ? goal : "";
    }

    /** Call every client tick. {@code voiceIdle} = push-to-talk pipeline is idle. */
    public static void tick(Minecraft mc, boolean voiceIdle) {
        if (cfg == null || backend == null || !cfg.autonomyEnabled || busy || !voiceIdle) return;
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        long now = System.currentTimeMillis();
        if (now < nextThinkAt) return;
        nextThinkAt = now + Math.max(15, cfg.autonomyIntervalSeconds) * 1000L
                + (long) (Math.random() * 5000L);
        final String ctx = context(mc);
        final long ticket = GENERATION.get();
        busy = true;
        WORKER.execute(() -> {
            try {
                deliver(backend.think(ctx), true, ticket);
            } catch (Exception ignored) {
                // Autonomy is best effort and silent.
            } finally {
                finish(ticket);
            }
        });
    }

    /** Spoken request that was not a direct order. Greetings, small talk and bare orders are NOT goals. */
    public static void onVoiceHeard(String heard) {
        if (cfg == null) return;
        if (!cfg.dynamicGoals) {
            LOG.info("[companionmod] goals: spoken goal ignored - Dynamic Goals is OFF in settings");
            return;
        }
        if (heard == null || heard.isBlank()) return;
        if (!plannable(heard)) {
            LOG.info("[companionmod] goals: not a goal: '{}'", heard);
            return;
        }
        LOG.info("[companionmod] goals: planning from voice: '{}'", heard);
        requestGoal(heard, true);
    }

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private static final Set<String> GREETINGS = Set.of(
            "hello", "hi", "hey", "yo", "hiya", "howdy", "thanks", "thank you", "bye", "goodbye",
            "good night", "good morning", "good evening", "welcome", "nice to meet you",
            "how are you", "how are u", "whats up", "what is up", "sup");

    /** Words the command parser already owns: a bare one is an order, not a request to plan around. */
    private static final Set<String> ORDER_WORDS = Set.of(
            "stay", "stop", "come", "follow", "sit", "stand", "wait", "hold", "defend", "guard",
            "attack", "fight", "hunt", "wander", "patrol", "explore", "scout", "loot", "mine", "dig",
            "farm", "fish", "build", "equip", "drop", "give", "store", "heal", "revive", "continue",
            "cancel", "halt", "abort", "leave", "dismiss", "call", "summon", "pick", "get", "bring",
            "take", "collect", "seek", "find", "search");

    /** Particles that glue bare orders together: "come to me", "stay with me", "follow me". */
    private static final Set<String> ORDER_PARTICLES = Set.of(
            "me", "my", "with", "to", "here", "there", "up", "down", "out", "it", "please", "now", "back", "along");

    /** Cheap client-side intent filter so chitchat and misheard orders never reach the planner. */
    private static boolean plannable(String heard) {
        String s = heard.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9 ]+", " ").replaceAll(" +", " ").trim();
        if (s.length() < 5) return false;                                  // "hi", "ok", "no"
        if (GREETINGS.contains(s)) return false;
        String[] words = s.split(" ");
        boolean allOrderish = true;
        for (String w : words) {
            if (!ORDER_WORDS.contains(w) && !ORDER_PARTICLES.contains(w)) { allOrderish = false; break; }
        }
        if (allOrderish) return false;                                     // "come to me" is an order, not a goal
        if (words.length <= 2 && ORDER_WORDS.contains(words[0])) return false;  // bare "stay", "come here"
        return true;
    }

    public static void requestGoal(String text) {
        requestGoal(text, false);
    }

    public static void requestGoal(String text, boolean quiet) {
        Minecraft mc = Minecraft.getInstance();
        if (cfg == null || backend == null || mc.player == null || mc.level == null) return;
        if (busy) {
            if (!quiet) chat(mc, "Still thinking about the last one.");
            return;
        }
        final String ctx = context(mc);
        final long ticket = GENERATION.incrementAndGet();
        busy = true;
        nextThinkAt = System.currentTimeMillis() + 60_000L;
        WORKER.execute(() -> {
            try {
                PlanParser.Plan plan = backend.planGoal(text, ctx);
                if (plan.isEmpty()) {
                    // Spoken goals (quiet) used to fail SILENTLY - "voice goals never trigger" with no clue why.
                    if (isCurrent(ticket)) {
                        mc.execute(() -> {
                            if (isCurrent(ticket)) chat(mc, "I'm not sure how to do that.");
                        });
                    }
                } else {
                    LOG.info("[companionmod] goals: plan accepted ({} steps)", plan.steps().size());
                    deliver(plan, false, ticket);
                }
            } catch (Exception e) {
                String message = e.getMessage() == null ? "unknown error" : e.getMessage();
                LOG.warn("[companionmod] goals: planning FAILED: {}", message);
                if (isCurrent(ticket)) {
                    mc.execute(() -> {
                        if (isCurrent(ticket)) chat(mc, "Planning failed: " + message);
                    });
                }
            } finally {
                finish(ticket);
            }
        });
    }

    /**
     * Invalidate pending requests and suppress autonomous thinking after a direct player order.
     * Called centrally by ClientVoice.send for gameplay commands, including radial-menu orders.
     */
    public static void onManualOrder() {
        GENERATION.incrementAndGet();
        busy = false;
        goal = "";
        goalUntil = 0;
        nextThinkAt = System.currentTimeMillis() + 60_000L;
    }

    /** The wheel's Cancel Plan action is a real stop, not merely an empty planner packet. */
    public static void cancelPlan() {
        ClientVoice.send("stop", "", "");
    }

    /** Drop all client planning state on logout/world change. */
    public static void reset() {
        GENERATION.incrementAndGet();
        busy = false;
        goal = "";
        goalUntil = 0;
        nextThinkAt = System.currentTimeMillis() + 20_000L;
    }

    private static boolean isCurrent(long ticket) {
        return GENERATION.get() == ticket;
    }

    private static void finish(long ticket) {
        // A stale worker must not clear the busy flag belonging to a newer request.
        if (isCurrent(ticket)) busy = false;
    }

    private static void deliver(PlanParser.Plan plan, boolean auto, long ticket) {
        if (plan.isEmpty() || !isCurrent(ticket)) return;
        Minecraft mc = Minecraft.getInstance();
        final String json = PlanParser.toJson(plan, auto);
        mc.execute(() -> {
            // Check again on the game thread: STOP may have arrived while this task was queued.
            if (!isCurrent(ticket) || mc.player == null || mc.level == null) return;
            goal = plan.goal();
            goalUntil = System.currentTimeMillis() + PlanParser.totalSeconds(plan) * 1000L + 4000L;
            ClientVoice.send("plan", "", json);
            if (!auto && !plan.goal().isBlank()) {
                mc.player.sendSystemMessage(Component.literal("[Companion] Goal: " + plan.goal()));
            }
        });
    }

    private static void chat(Minecraft mc, String msg) {
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[Companion] " + msg));
    }

    /** Game-thread snapshot for the model. */
    private static String context(Minecraft mc) {
        CompanionEntity mine = ClientVoice.findCompanion(mc);
        return CompanionWorldContext.build(mc, mine) + "\nMemory: " + CompanionMemory.summary();
    }
}
