package com.example.companionmod.entity.plan;

import com.example.companionmod.command.CompanionCommand;
import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.voice.PlanParser;
import com.example.companionmod.voice.PlanStep;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayDeque;
import java.util.Set;

/**
 * Runs an AI-made plan one step at a time by issuing ordinary companion orders, so every behaviour (mining, pickup,
 * combat, follow) keeps its existing safety rules. The server re-validates every plan; the model never executes anything itself.
 *
 * <p>Player orders do not throw the plan away any more: they {@link #pause(long) freeze} it in place and
 * {@code continue} {@link #resume(long) unfreezes} it, deadlines shifted forward by the pause so nothing expires
 * while it was interrupted.</p>
 */
public final class CompanionPlanner {
    private static final Set<String> RETURN_AFTER = Set.of("wander", "patrol", "seek_block", "pickup", "attack", "guard", "plant");

    private final ArrayDeque<PlanStep> queue = new ArrayDeque<>();
    private PlanStep current;
    private long startTick, endTick, lastPlayerOrder = -100000L;
    private String goal = "";
    private String lastOp = "";
    private boolean paused;
    private long pausedAt;

    public boolean active() { return current != null || !queue.isEmpty(); }
    public boolean paused() { return paused; }
    public String goal() { return goal; }

    /** A work goal made real progress: a timed seek_block / plant step gets at least {@code ticks} more, so it is never cut off mid-job. */
    public void extendDeadline(long fromTick, long ticks) {
        if (current != null && ("seek_block".equals(current.op()) || "plant".equals(current.op()))) {
            endTick = Math.max(endTick, fromTick + ticks);
        }
    }

    public void cancel() { queue.clear(); current = null; goal = ""; paused = false; }

    /** Freezes the plan in place; resume() continues it as if nothing had happened. */
    public void pause(long tick) {
        if (active() && !paused) { paused = true; pausedAt = tick; }
    }

    /** Unfreezes a paused plan, shifting its deadlines past the pause. Returns true when there was a plan to resume. */
    public boolean resume(long tick) {
        if (!paused) return false;
        paused = false;
        long dt = Math.max(0L, tick - pausedAt);
        startTick += dt;
        endTick += dt;
        return active();
    }

    /** Call whenever the player gives a manual order: it always wins and blocks autonomy for 60 s (the plan pauses, not dies). */
    public void notePlayerOrder(long tick) { lastPlayerOrder = tick; pause(tick); }

    /** Accepts a plan JSON from the client. Returns true if a plan was started. An empty non-auto plan cancels the current one. */
    public boolean accept(String json, PlanActions host, long tick) {
        boolean auto = PlanParser.isAuto(json);
        if (auto && (active() || host.planPlayerHold() || tick - lastPlayerOrder < 1200)) return false;
        PlanParser.Plan plan = PlanParser.parse(json, auto);
        if (plan.isEmpty()) { if (!auto) cancel(); return false; }
        cancel();
        queue.addAll(plan.steps());
        goal = plan.goal();
        return true;
    }

    public void tick(PlanActions host, long tick) {
        if (paused || !active()) return;
        Player owner = host.planOwner();
        if (owner == null || !owner.isAlive()) { cancel(); return; }
        if (host.planPaused()) { endTick++; return; }
        if (current == null) { current = queue.poll(); begin(host, tick); return; }
        if (!finished(host, tick)) return;
        current = null;
        if (queue.isEmpty()) {
            if (RETURN_AFTER.contains(lastOp) && !host.planPlayerHold()) host.planCommand(CompanionCommand.FOLLOW, "");
            goal = "";
        }
    }

    private void begin(PlanActions host, long tick) {
        startTick = tick;
        endTick = tick + current.seconds() * 20L;
        lastOp = current.op();
        switch (current.op()) {
            case "say" -> host.planSay(current.arg());
            case "wait" -> { }
            case "seek_block" -> {
                String id = CompanionEntity.normalizeBlockId(current.arg());
                // What the PLAYER NAMED in the goal text always wins over the model's block pick:
                // "get copper ore" is copper (never the model's coal), "get wood" is the log family
                // (never oak_leaves or oak_sapling).
                String fromGoal = goalMaterial(goal);
                if (!fromGoal.isBlank()) {
                    id = fromGoal;
                } else if ((id.endsWith("_leaves") || id.equals("leaves")) && mentionsWood(goal)) {
                    id = "log";
                } else if (id.isBlank() && mentionsWood(goal)) {
                    id = "log";
                } else if (id.isBlank() && asksDigDown(goal)) {
                    // "dig down" names no material: a deep mine job is the only honest reading,
                    // so branch-mine the diamond level (targetY does the descent), not a random model pick.
                    id = "diamond_ore";
                }
                if (id.isBlank()) endTick = tick;
                else {
                    host.planCommand(CompanionCommand.SEEK_BLOCK, id);
                    // Explicit amount in the goal text ("bring me 12 iron ore") beats the default 16/6.
                    int n = goalCount(goal);
                    if (n > 0) host.planSeekCount(n);
                }
            }
            case "pickup" -> host.planCommand(CompanionCommand.PICKUP, current.arg());
            default -> host.planCommand(CompanionCommand.parse(current.op()), current.arg());
        }
    }

    private boolean finished(PlanActions host, long tick) {
        long dt = tick - startTick;
        return switch (current.op()) {
            case "say" -> dt >= 30;                                   // small pause so lines never flood chat
            case "seek_block" -> tick >= endTick || (dt > 40 && !host.planSeekActive());
            case "pickup" -> tick >= endTick || (dt > 20 && !host.planPickupActive());
            case "plant" -> tick >= endTick || (dt > 40 && !host.planPlantActive());
            case "attack" -> tick >= endTick || (dt > 60 && !host.planFighting());
            default -> tick >= endTick;
        };
    }

    /** Whole-word wood words in the plan's goal text ("get 20 wood", "chop some logs", "collect timber"). */
    private static boolean mentionsWood(String goal) {
        String g = goal == null ? "" : goal.toLowerCase(java.util.Locale.ROOT);
        for (String w : new String[]{"wood", "log", "timber", "lumber", "tree", "trunk"}) {
            if (java.util.regex.Pattern.compile("(?<![a-z])" + java.util.regex.Pattern.quote(w) + "(?![a-z])").matcher(g).find()) return true;
        }
        return false;
    }

    /** The first block/family word the GOAL TEXT itself names ("get copper ore" -> copper_ore). "" when none. */
    private static String goalMaterial(String goal) {
        if (goal == null || goal.isBlank()) return "";
        for (String tok : goal.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9 ]+", " ").split(" ")) {
            if (tok.isBlank()) continue;
            String id = CompanionEntity.normalizeBlockId(tok);
            if (!id.isBlank()) return id;
        }
        return "";
    }

    /** Whole-word "dig down / dig deeper / go down" phrasing: the player wants depth, no material named. */
    private static boolean asksDigDown(String goal) {
        String g = goal == null ? "" : goal.toLowerCase(java.util.Locale.ROOT);
        return words(g, "dig down", "dig deeper", "mine down", "go down", "dig a hole");
    }

    /** Explicit amount in the goal text ("bring me 12 iron ore" -> 12). 1..64; 0 when none (defaults apply). */
    private static int goalCount(String goal) {
        if (goal == null || goal.isBlank()) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?<!\\d)(\\d{1,2})(?!\\d)").matcher(goal);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            return n >= 1 && n <= 64 ? n : 0;
        }
        return 0;
    }

    private static boolean words(String g, String... phrases) {
        for (String p : phrases) {
            if (java.util.regex.Pattern.compile("(?<![a-z])" + java.util.regex.Pattern.quote(p) + "(?![a-z])").matcher(g).find()) return true;
        }
        return false;
    }
}
