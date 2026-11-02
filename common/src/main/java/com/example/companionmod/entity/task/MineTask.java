package com.example.companionmod.entity.task;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The companion's current "go find/mine X" job. It lives on the entity and is saved with it, so progress survives
 * fights, sit/stay orders, injuries, chunk unloads and world reloads. The goal only reads and updates this object;
 * stopping and restarting the goal never loses anything. A new order pauses the task, "continue" resumes it.
 */
public final class MineTask {
    public enum Phase { SEARCH, DESCEND, BRANCH, ASCEND }
    public static final int NO_DESCEND = Integer.MIN_VALUE;

    public String blockId = "";
    public int want, mined, ty = NO_DESCEND, dirData, leg, legLen;
    public Phase phase = Phase.SEARCH;
    public boolean paused;
    /**
     * Bumped by every begin()/beginSurface(): the seek goal compares it each tick and drops its stale transient
     * state (walk, break, pillar) the same tick a new order arrives - without it the goal kept finishing the
     * previous task's walk for up to 8 s after a new order, which looked like a freeze. Transient on purpose.
     */
    public int serial;

    public void begin(String id, boolean nether, int facingDirData) {
        blockId = id;
        want = defaultWant(id);
        mined = 0;
        ty = targetY(id, nether);
        phase = Phase.SEARCH;
        dirData = facingDirData;
        leg = 0;
        legLen = 0;
        paused = false;
        serial++;
    }

    /** "Dig up to the surface": a staircase job with no block id, finished by reaching daylight. */
    public void beginSurface(int facingDirData) {
        blockId = "";
        want = 1;
        mined = 0;
        ty = NO_DESCEND;
        phase = Phase.ASCEND;
        dirData = facingDirData;
        leg = 0;
        legLen = 0;
        paused = false;
        serial++;
    }

    public boolean exists() { return !blockId.isEmpty() || phase == Phase.ASCEND; }
    public boolean running() { return exists() && !paused; }
    public boolean deep() { return ty != NO_DESCEND; }
    public void pause() { paused = true; }
    public boolean resume() { if (!exists()) return false; paused = false; return true; }
    public void finish() { blockId = ""; paused = false; mined = 0; phase = Phase.SEARCH; }
    public String describe() { return "mining " + blockId.replace('_', ' ') + " (" + mined + "/" + want + ")"; }

    /** Best Y level to branch-mine for. NO_DESCEND = surface target (logs, stone, emeralds...). Tweak freely. */
    public static int targetY(String id, boolean nether) {
        if (nether) return id.contains("ancient_debris") ? 15 : NO_DESCEND;
        if (id.contains("diamond") || id.contains("redstone")) return -58;
        if (id.contains("lapis")) return 0;
        if (id.contains("gold")) return -16;
        if (id.contains("iron")) return 16;
        if (id.contains("copper")) return 40;
        if (id.contains("coal")) return 45;
        if (id.contains("deepslate")) return -20;
        return NO_DESCEND;
    }

    public static int defaultWant(String id) {
        if (id.contains("diamond") || id.contains("emerald")) return 6;
        if (id.contains("ancient_debris")) return 4;
        return 16;
    }

    public void save(ValueOutput out) {
        if (!exists()) return;
        out.putString("MineBlock", blockId);
        out.putInt("MineWant", want);
        out.putInt("MineDone", mined);
        out.putInt("MineY", ty);
        out.putInt("MinePhase", phase.ordinal());
        out.putInt("MineDir", dirData);
        out.putInt("MineLeg", leg);
        out.putInt("MineLegLen", legLen);
        out.putBoolean("MinePaused", paused);
    }

    public void load(ValueInput in) {
        blockId = in.getStringOr("MineBlock", "");
        want = in.getIntOr("MineWant", 0);
        mined = in.getIntOr("MineDone", 0);
        ty = in.getIntOr("MineY", NO_DESCEND);
        phase = Phase.values()[Math.floorMod(in.getIntOr("MinePhase", 0), Phase.values().length)];
        dirData = in.getIntOr("MineDir", 0);
        leg = in.getIntOr("MineLeg", 0);
        legLen = in.getIntOr("MineLegLen", 0);
        paused = in.getBooleanOr("MinePaused", false);
    }
}
