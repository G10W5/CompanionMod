package com.example.companionmod.entity.ai;

import com.example.companionmod.build.Blk;
import com.example.companionmod.build.BlkState;
import com.example.companionmod.build.BuildRunner;
import com.example.companionmod.entity.BlueprintSupply;
import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.entity.CompanionProtection;
import com.example.companionmod.entity.task.BuildJob;
import com.example.companionmod.world.BuildSources;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the companion's {@link BuildJob}: sweeps the ordered block list (see BuildRunner), takes blocks from the
 * bag / nearby chests, walks (or, when the next spot is out of reach, hops) to a safe standing place near each
 * target, and places a few blocks per tick. All decisions live in BuildRunner (unit tested); this class is only the
 * adapter to the real world.
 */
public class CompanionBlueprintGoal extends Goal {
    private static final int PLACE_PER_TICK = 3, SCAN_PER_TICK = 400;
    private static final double REACH = 5.5;
    /** Stall attempts (walk away + come back) before the build is paused with a spoken report. */
    private static final int MAX_STALLS = 6, REPOSITION_TICKS = 80;

    private final CompanionEntity mob;
    private final BlueprintSupply supply;
    private final McEnv env = new McEnv();
    private ServerLevel level;

    private BuildRunner.Status waiting;
    private int waitTicks, speakCooldown;
    private long lastTarget = Long.MIN_VALUE;
    private int moveTicks;
    private BlockPos standTarget;
    private int repositionTicks;                 // >0: walking away and back after a stall, sweep is paused
    private int stallAttempts, seenPlaced = -1;  // attempt budget; any placement progress resets it

    public CompanionBlueprintGoal(CompanionEntity mob) {
        this.mob = mob;
        this.supply = new BlueprintSupply(mob);
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override public boolean canUse() {
        BuildJob j = mob.blueprint();
        return j.running() && !mob.isSitting() && !mob.isInjured() && mob.getTarget() == null;
    }

    @Override public boolean canContinueToUse() { return canUse(); }

    @Override public void start() {
        waiting = null;
        waitTicks = 0;
        standTarget = null;
        lastTarget = Long.MIN_VALUE;
        repositionTicks = 0;
        stallAttempts = 0;
        seenPlaced = -1;
    }

    @Override public void stop() { mob.getNavigation().stop(); }

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel sl)) return;
        level = sl;
        BuildJob job = mob.blueprint();

        if (job.runner == null) {
            if (!BuildSources.prepare(job)) {
                mob.speak(job.error.isBlank() ? "I cannot read that design." : job.error);
                job.cancel();
                return;
            }
            supply.setCenter(job.center());
            job.runner = new BuildRunner(job.list, job.cx, job.cy, job.cz, env);
            job.runner.placed = job.placedTotal;
        }
        supply.beginTick(sl);
        BuildRunner r = job.runner;

        // Stall recovery: walk away from the problem spot and come back for a fresh path/approach angle.
        // While this runs the sweep is parked (runner is in STALLED until resume()).
        if (repositionTicks > 0) {
            if (--repositionTicks == 40 && lastTarget != Long.MIN_VALUE) {
                BlockPos t = BlockPos.of(lastTarget);
                mob.getNavigation().moveTo(t.getX() + 0.5, t.getY(), t.getZ() + 0.5, 1.25);
            }
            if (repositionTicks > 0) return;
            r.resume();
        }

        if (waiting != null) {
            if (--waitTicks > 0) return;
            if (waiting == BuildRunner.Status.WAITING_MATERIALS) {
                if (!suppliesArrived(r)) {
                    waitTicks = 40;
                    announceMissing(r);
                    return;
                }
            }
            waiting = null;
            r.resume();
        }

        BuildRunner.Status st = r.tick(PLACE_PER_TICK, SCAN_PER_TICK);
        job.placedTotal = r.placed;
        switch (st) {
            case WORKING -> { }
            case WAITING_MATERIALS -> {
                waiting = st;
                waitTicks = 40;
                mob.getNavigation().stop();
                speakCooldown = 0;
                announceMissing(r);
            }
            case WAITING_ENTITY -> {
                waiting = st;
                waitTicks = 20;
                mob.getNavigation().stop();
            }
            case STALLED -> onStall(job, r);
            case DONE -> finish(job, r);
        }
    }

    // ------------------------------------------------------------------ stall handling (never a silent hole)

    /**
     * A full pass made no progress (unreachable cells, blocked cells, a creature that never moves). NEVER skip the
     * cells: say WHY, walk away and come back for a fresh approach, and after {@link #MAX_STALLS} attempts pause the
     * build and tell the owner exactly where things stand - resume with "continue" or /cbuild resume.
     */
    private void onStall(BuildJob job, BuildRunner r) {
        if (r.placed != seenPlaced) {                   // real progress since the last stall: budget resets
            seenPlaced = r.placed;
            stallAttempts = 0;
        }
        stallAttempts++;
        mob.getNavigation().stop();
        if (stallAttempts > MAX_STALLS) {
            job.pause();
            mob.speak("I can't make progress on this build. I'll wait here.");
            if (mob.getOwner() instanceof ServerPlayer sp) {
                sp.sendSystemMessage(Component.literal("[Companion] Build paused at " + r.percent() + "% - "
                        + stallWhy(r) + ". Say 'continue' (or /cbuild resume) to try again."));
            }
            return;
        }
        mob.speak(stallSpeak(r));
        reposition();                                   // walk away, then back; resume() after REPOSITION_TICKS
        repositionTicks = REPOSITION_TICKS;
    }

    private void reposition() {
        BlockPos target = lastTarget == Long.MIN_VALUE ? mob.blockPosition() : BlockPos.of(lastTarget);
        double dx = mob.getX() - (target.getX() + 0.5);
        double dz = mob.getZ() - (target.getZ() + 0.5);
        double len = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
        BlockPos away = mob.blockPosition().offset((int) Math.round(4.0 * dx / len), 0, (int) Math.round(4.0 * dz / len));
        mob.getNavigation().moveTo(away.getX() + 0.5, away.getY(), away.getZ() + 0.5, 1.25);
    }

    private static String stallSpeak(BuildRunner r) {
        if (r.entityStalled) return "Something is standing in the way and won't move. Let me try again.";
        if (r.reachStall > 0) return "I can't quite reach " + n(r.reachStall) + ". Trying another angle.";
        if (r.blockedStall > 0) return "Something I can't move is blocking " + n(r.blockedStall) + ".";
        if (r.protStall > 0) return n(r.protStall) + " are protected - I can't build there.";
        return "I'm stuck on this build. Trying another way.";
    }

    private static String stallWhy(BuildRunner r) {
        StringBuilder sb = new StringBuilder();
        if (r.entityStalled) append(sb, "a creature won't move");
        if (r.reachStall > 0) append(sb, "I can't reach " + n(r.reachStall));
        if (r.blockedStall > 0) append(sb, n(r.blockedStall) + " blocked by un-replaceable blocks");
        if (r.protStall > 0) append(sb, n(r.protStall) + " protected");
        return sb.length() == 0 ? "no progress" : sb.toString();
    }

    private static void append(StringBuilder sb, String part) {
        if (sb.length() > 0) sb.append(", ");
        sb.append(part);
    }

    private static String n(int count) {
        return count + " spot" + (count == 1 ? "" : "s");
    }

    // ------------------------------------------------------------------ messages

    private boolean suppliesArrived(BuildRunner r) {
        for (String id : r.missing().keySet()) if (supply.have(id) > 0) return true;
        return false;
    }

    private void announceMissing(BuildRunner r) {
        if (--speakCooldown > 0) return;
        speakCooldown = 15;                                           // x 40 ticks = every 30 s
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, Integer> e : r.missing().entrySet()) {
            if (n++ == 3) { sb.append(", and more"); break; }
            sb.append(n > 1 ? ", " : "").append(e.getValue()).append(' ').append(pretty(e.getKey()));
        }
        mob.speak("I need " + sb + " to finish. Put them in my bag or a chest nearby.");
    }

    private void finish(BuildJob job, BuildRunner r) {
        String text = "Build finished! Placed " + r.placed + " blocks."
                + (r.skipped > 0 ? " " + r.skipped + " cells could not be filled (unknown blocks in the design)." : "")
                + (r.blocked > 0 ? " " + r.blocked + " spots are protected." : "");
        mob.speak("Build finished!");
        if (mob.getOwner() instanceof ServerPlayer sp) sp.sendSystemMessage(Component.literal("[Companion] " + text));
        job.cancel();
    }

    private static String pretty(String id) {
        return id.substring(id.indexOf(':') + 1).replace('_', ' ');
    }

    // ------------------------------------------------------------------ the world adapter

    private final class McEnv implements BuildRunner.Env {

        private BlockState state(Blk b) {
            BuildJob job = mob.blueprint();
            BlockState s = job.states.get(b);
            if (s == null && !job.states.containsKey(b)) {
                s = BlkState.toState(b);
                job.states.put(b, s);
            }
            return s;
        }

        @Override
        public BuildRunner.Inspect inspect(int x, int y, int z, Blk want) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.hasChunkAt(pos)) return BuildRunner.Inspect.UNLOADED;
            BlockState ws = state(want);
            if (ws == null) return BuildRunner.Inspect.UNKNOWN_BLOCK;           // unknown block: design error, retire
            BlockState there = level.getBlockState(pos);
            if (there.getBlock() == ws.getBlock()) return BuildRunner.Inspect.ALREADY;
            if (!overwritable(level, pos, there)) return BuildRunner.Inspect.OCCUPIED;
            if (!CompanionProtection.canModify(level, mob, pos)) return BuildRunner.Inspect.PROTECTED;
            List<LivingEntity> inside = level.getEntitiesOfClass(LivingEntity.class, new AABB(pos));
            if (!inside.isEmpty()) {
                if (inside.contains(mob)) relocate(pos);                         // never entomb ourselves
                return BuildRunner.Inspect.ENTITY;
            }
            return BuildRunner.Inspect.FREE;
        }

        @Override
        public boolean creative() {
            return mob.getOwner() instanceof Player p && p.isCreative();
        }

        @Override public String itemId(Blk b) { return BlkState.itemId(b); }
        @Override public int have(String itemId) { return supply.have(itemId); }
        @Override public void take(String itemId, int count) { supply.take(itemId, count); }

        @Override
        public BuildRunner.Reach reach(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            long key = pos.asLong();
            if (key != lastTarget) {
                lastTarget = key;
                moveTicks = 0;
                standTarget = null;
            }
            if (mob.distanceToSqr(x + 0.5, y + 0.5, z + 0.5) <= REACH * REACH) return BuildRunner.Reach.IN_REACH;
            // Give the walk/hop a REAL window (15 s) before declaring the cell unreachable: the old 200-tick
            // limit hit while he was still climbing, and the cell was then skipped permanently (tower tops never
            // finished - user report). Unreachable cells are now also retried once by BuildRunner.
            if (++moveTicks > 300) return BuildRunner.Reach.GAVE_UP;
            if (standTarget == null) standTarget = findStand(pos);
            if (standTarget == null) {
                mob.getNavigation().moveTo(x + 0.5, y, z + 0.5, 1.15);          // nothing safe nearby: just walk toward it
                return BuildRunner.Reach.MOVING;
            }
            // Hop soon when walking clearly cannot do it: the support spot is above us, or the path is far.
            boolean far = Math.abs(standTarget.getY() - mob.getY()) > 2.0 || mob.distanceToSqr(Vec3.atCenterOf(standTarget)) > 16 * 16;
            if (far || moveTicks > 80) {
                hopTo(standTarget);                                              // too high / too far / stuck: hop there
            } else {
                mob.getNavigation().moveTo(standTarget.getX() + 0.5, standTarget.getY(), standTarget.getZ() + 0.5, 1.2);
            }
            return BuildRunner.Reach.MOVING;
        }

        @Override
        public void place(int x, int y, int z, Blk want) {
            BlockState ws = state(want);
            if (ws == null) return;
            level.setBlock(new BlockPos(x, y, z), ws, 3);
            mob.swing(InteractionHand.MAIN_HAND);
        }
    }

    /**
     * Natural, soft or replaceable terrain may be overwritten; anything solid the player built may not.
     * The collision-empty catch-all covers everything the tags miss: grass tufts, ferns, flowers, vines, snow
     * layers - blocks with no collision box have no business blocking a build cell ("he's not replacing grass").
     * Fluids and block entities (chests, signs, furnaces) are never touched; logs count as natural (trees in
     * the footprint are common) - the plan can only place ONE block per cell anyway, so a player chest that
     * happens to sit in the footprint survives as a hole rather than being destroyed.
     */
    private static boolean overwritable(ServerLevel level, BlockPos pos, BlockState s) {
        if (s.isAir() || s.canBeReplaced()) return true;
        if (s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD)
                || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)) return true;
        if (!s.getFluidState().isEmpty()) return false;
        if (s.hasBlockEntity()) return false;
        return s.getCollisionShape(level, pos).isEmpty();                 // grass tufts, ferns, flowers, snow...
    }

    private void hopTo(BlockPos p) {
        mob.getNavigation().stop();
        mob.setPos(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);                    // 26.1-CHECK: Entity.setPos(double,double,double)
        mob.setDeltaMovement(Vec3.ZERO);
    }

    private void relocate(BlockPos around) {
        BlockPos s = findStand(around);
        if (s != null) hopTo(s);
    }

    /**
     * A place to stand within ~3 blocks of the target: feet and head free, solid ground below.
     *
     * <p>STRICT first (never on the building's own cells, so he never walls himself in). That returns NOTHING
     * for a tower above standing height: the only supported spots up there are the top of the ring below, which
     * IS a future cell - which is exactly why he "couldn't reach up" and never hopped (the hop only fired when
     * a stand spot existed). RELAXED drops the cell filters (cell positions are still ranked lower): standing on
     * the just-placed ring is the correct builder behaviour, and if a future block lands on him, McEnv.inspect
     * relocates him out before placing.</p>
     */
    private BlockPos findStand(BlockPos target) {
        BlockPos strict = findStand(target, true);
        return strict != null ? strict : findStand(target, false);
    }

    private BlockPos findStand(BlockPos target, boolean strict) {
        Set<Long> cells = mob.blueprint().cells;
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos c = target.offset(dx, dy, dz);
                    if (strict && (cells.contains(c.asLong()) || cells.contains(c.above().asLong()))) continue;
                    if (!passable(c) || !passable(c.above())) continue;
                    BlockPos below = c.below();
                    if (passable(below) || !level.getFluidState(below).isEmpty()) continue;
                    double score = c.distSqr(target) + 0.05 * c.distSqr(mob.blockPosition())
                            + (cells.contains(c.asLong()) ? 4.0 : 0.0);      // prefer off-plan spots when relaxed
                    if (score < bestScore) {
                        bestScore = score;
                        best = c.immutable();
                    }
                }
            }
        }
        return best;
    }

    private boolean passable(BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty() && level.getFluidState(p).isEmpty();
    }
}
