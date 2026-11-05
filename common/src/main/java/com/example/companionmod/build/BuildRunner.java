package com.example.companionmod.build;

import com.example.companionmod.build.BuildPlan.Placement;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The placement state machine, with no Minecraft classes: the entity goal supplies an {@link Env} that talks to the
 * real world. Because of that it can be unit-tested here.
 *
 * <p>It sweeps the ordered block list. Anything it cannot place yet (material missing, a creature standing in the
 * cell, the cell out of reach...) is DEFERRED instead of stalling the whole build, and the sweep runs again until a
 * pass makes no progress. Then it reports WHY: WAITING_MATERIALS, WAITING_ENTITY, or STALLED.</p>
 *
 * <p><b>Nothing is ever silently skipped</b> (user requirement: "i dont want him to skip blocks"). A pass that cannot
 * progress ends in STALLED with per-cause counts instead of DONE-with-holes: the goal reports the reason, walks away
 * and back for a fresh approach, and after its attempt budget pauses the build and TELLS the owner - never a quiet
 * hole in the wall. Only cells whose block id does not exist (a design error) are written off, because no amount of
 * retrying can ever place them.</p>
 */
public final class BuildRunner {

    public enum Inspect { ALREADY, FREE, OCCUPIED, UNLOADED, PROTECTED, ENTITY, UNKNOWN_BLOCK }
    public enum Reach { IN_REACH, MOVING, GAVE_UP }
    /** STALLED = no progress, nothing to wait for: unreachable / blocked cells. The goal reports and retries it. */
    public enum Status { WORKING, WAITING_MATERIALS, WAITING_ENTITY, STALLED, DONE }

    public interface Env {
        /** What is at that cell compared with what we want to put there. */
        Inspect inspect(int x, int y, int z, Blk want);
        boolean creative();
        /** Item that pays for this block, or null if it cannot be placed from items. */
        String itemId(Blk b);
        int have(String itemId);
        void take(String itemId, int count);
        /** IN_REACH = place now; MOVING = the builder is walking/teleporting there; GAVE_UP = unreachable. */
        Reach reach(int x, int y, int z);
        void place(int x, int y, int z, Blk want);
    }

    public static final int MAX_ENTITY_RETRIES = 40;

    private final List<Placement> list;
    private final int ox, oy, oz;
    private final Env env;

    public int index, pass, placed, skipped, blocked;
    private int passPlaced, deferred;
    private boolean entityDeferred;
    private Status waiting;
    /** Why the current pass stalled - counts reset with each pass, read them right after a STALLED return. */
    public int reachStall, blockedStall, protStall;
    public boolean entityStalled;
    private int entityWaits;                               // WAITING_ENTITY cycles for the current stall stretch
    /** Permanently written-off cells (unknown block ids only): swept past without re-counting. */
    private final Set<Integer> retired = new HashSet<>();
    private final Map<String, Integer> missing = new TreeMap<>();

    public BuildRunner(List<Placement> list, int ox, int oy, int oz, Env env) {
        this.list = list;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.env = env;
        startPass();
    }

    public int size() { return list.size(); }
    public Map<String, Integer> missing() { return missing; }
    public int percent() { return list.isEmpty() ? 100 : Math.min(100, (int) ((long) placed * 100 / list.size())); }

    private void startPass() {
        index = 0;
        pass++;
        passPlaced = 0;
        deferred = 0;
        entityDeferred = false;
        reachStall = 0;
        blockedStall = 0;
        protStall = 0;
        entityStalled = false;
        missing.clear();
        waiting = null;
    }

    /** Call after the supply changed (or after repositioning): sweeps again from the start. */
    public void resume() {
        if (waiting != null) startPass();
    }

    /**
     * @param placeBudget max blocks to place this call
     * @param scanBudget  max cells to look at this call (keeps a big already-built sweep from lagging the server)
     */
    public Status tick(int placeBudget, int scanBudget) {
        if (waiting != null) return waiting;
        while (true) {
            if (index >= list.size()) {
                Status s = endOfPass();
                if (s == null) continue;                 // a fresh pass started
                return s;
            }
            if (retired.contains(index)) { index++; continue; }       // written off for good: skip, no re-count
            if (placeBudget <= 0 || scanBudget <= 0) return Status.WORKING;
            scanBudget--;

            Placement p = list.get(index);
            int x = ox + p.dx(), y = oy + p.dy(), z = oz + p.dz();
            switch (env.inspect(x, y, z, p.blk())) {
                case ALREADY -> { index++; continue; }
                case OCCUPIED -> {                              // never skipped: reported as a stall instead
                    deferred++;
                    blockedStall++;
                    index++;
                    continue;
                }
                case PROTECTED -> {
                    deferred++;
                    protStall++;
                    blocked++;
                    index++;
                    continue;
                }
                case UNKNOWN_BLOCK -> {                         // design error: no item can ever pay for this
                    if (retired.add(index)) skipped++;
                    index++;
                    continue;
                }
                case ENTITY -> {
                    deferred++;
                    entityDeferred = true;
                    index++;
                    continue;
                }
                case UNLOADED -> {
                    Reach r = env.reach(x, y, z);               // walk there so the chunk loads
                    if (r == Reach.GAVE_UP) {
                        deferred++;
                        reachStall++;
                        index++;
                        continue;
                    }
                    return Status.WORKING;
                }
                case FREE -> { /* fall through to placement */ }
            }

            String item = env.itemId(p.blk());
            int need = p.blk().itemsNeeded();
            if (item == null && !env.creative()) {              // block has no placeable item: hopeless in survival
                if (retired.add(index)) skipped++;
                index++;
                continue;
            }
            if (item != null && need > 0 && !env.creative() && env.have(item) < need) {
                missing.merge(item, need, Integer::sum);
                deferred++;
                index++;
                continue;
            }
            Reach r = env.reach(x, y, z);
            if (r == Reach.MOVING) return Status.WORKING;
            if (r == Reach.GAVE_UP) {                           // never skipped: counts toward the stall report
                deferred++;
                reachStall++;
                index++;
                continue;
            }

            if (item != null && need > 0 && !env.creative()) env.take(item, need);
            env.place(x, y, z, p.blk());
            placed++;
            passPlaced++;
            index++;
            placeBudget--;
        }
    }

    /** @return null when a new pass was started, otherwise the status to report. */
    private Status endOfPass() {
        if (deferred == 0) return Status.DONE;
        if (passPlaced > 0) {                                 // progress: sweep again, entity wait budget renews
            entityWaits = 0;
            startPass();
            return null;
        }
        if (!missing.isEmpty()) return waiting = Status.WAITING_MATERIALS;
        if (entityDeferred) {
            if (entityWaits++ >= MAX_ENTITY_RETRIES) {         // a creature that never moves: report it as stalled
                entityStalled = true;
                return waiting = Status.STALLED;
            }
            return waiting = Status.WAITING_ENTITY;
        }
        return waiting = Status.STALLED;                       // unreachable or blocked cells, no progress possible
    }
}
