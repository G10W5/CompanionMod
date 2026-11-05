package com.example.companionmod.build;

import com.example.companionmod.build.BuildPlan.Placement;

import java.util.HashMap;
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
 * cell, ...) is DEFERRED instead of stalling the whole build, and the sweep runs again until a pass makes no
 * progress. Then it reports what is missing and waits.</p>
 */
public final class BuildRunner {

    public enum Inspect { ALREADY, FREE, OCCUPIED, UNLOADED, PROTECTED, ENTITY }
    public enum Reach { IN_REACH, MOVING, GAVE_UP }
    public enum Status { WORKING, WAITING_MATERIALS, WAITING_ENTITY, DONE }

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

    public int index, pass, placed, skipped, blocked, unreachable;
    private int passPlaced, deferred;
    private boolean entityDeferred;
    private Status waiting;
    /**
     * Per-cell retry budgets, keyed by placement index: unreachable gets one extra pass, creatures
     * {@link #MAX_ENTITY_RETRIES} waits. RETIRED cells are permanently written off - a later pass sweeps from
     * index 0 again, so without this set every OCCUPIED/retired cell would be counted (and re-tried) once per
     * pass, inflating the skipped count.
     */
    private final Map<Integer, Integer> gaveUp = new HashMap<>();
    private final Map<Integer, Integer> entityBlocked = new HashMap<>();
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
        missing.clear();
        waiting = null;
    }

    /** Call after the supply changed (or after waiting for a creature to move): sweeps again from the start. */
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
                case OCCUPIED -> { if (retired.add(index)) skipped++; index++; continue; }
                case PROTECTED -> { if (retired.add(index)) blocked++; index++; continue; }
                case ENTITY -> {
                    // A creature standing in the cell is retried for up to MAX_ENTITY_RETRIES waits, then THAT
                    // cell alone is given up - the old code skipped EVERY deferred cell of the pass at once.
                    // (+1: pass 1 discovers the cell while still placing, which costs no wait.)
                    if (entityBlocked.merge(index, 1, Integer::sum) > MAX_ENTITY_RETRIES + 1) {
                        if (retired.add(index)) skipped++;
                        index++;
                        continue;
                    }
                    deferred++;
                    entityDeferred = true;
                    index++;
                    continue;
                }
                case UNLOADED -> {
                    Reach r = env.reach(x, y, z);        // walk there so the chunk loads
                    if (r == Reach.GAVE_UP) {
                        if (gaveUp.merge(index, 1, Integer::sum) < 2) { deferred++; index++; continue; }
                        if (retired.add(index)) { skipped++; unreachable++; }
                        index++;
                        continue;
                    }
                    return Status.WORKING;
                }
                case FREE -> { /* fall through to placement */ }
            }

            String item = env.itemId(p.blk());
            if (item == null) { if (retired.add(index)) skipped++; index++; continue; }
            int need = p.blk().itemsNeeded();
            if (need > 0 && !env.creative() && env.have(item) < need) {
                missing.merge(item, need, Integer::sum);
                deferred++;
                index++;
                continue;
            }
            Reach r = env.reach(x, y, z);
            if (r == Reach.MOVING) return Status.WORKING;
            if (r == Reach.GAVE_UP) {
                // One extra pass of retries before a cell is written off as unreachable ("couldn't reach up"
                // must not become a permanent hole on the first failed attempt).
                if (gaveUp.merge(index, 1, Integer::sum) < 2) { deferred++; index++; continue; }
                if (retired.add(index)) { unreachable++; skipped++; }
                index++;
                continue;
            }

            if (need > 0 && !env.creative()) env.take(item, need);
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
        if (passPlaced > 0) { startPass(); return null; }          // progress: sweep again for the deferred cells
        if (!missing.isEmpty()) return waiting = Status.WAITING_MATERIALS;
        if (entityDeferred) return waiting = Status.WAITING_ENTITY;
        // Nothing else was deferred (only unreachable first-chances) and no progress is possible: write them off.
        skipped += deferred;
        unreachable += deferred;
        return Status.DONE;
    }
}
