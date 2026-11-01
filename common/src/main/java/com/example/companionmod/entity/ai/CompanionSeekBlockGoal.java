package com.example.companionmod.entity.ai;

import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.entity.task.MineTask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Player-style "find and mine X".
 *
 * <p>Surface: look for exposed matches, walk there if a path exists, otherwise tunnel to them.
 * Deep ores: walk down a 2-high staircase (never a bare shaft) to the best Y level, then strip-mine in parallel
 * branches, spotting exposed ore (or any ore within 2 blocks of the tunnel) and vein-mining whatever touches it.
 * Every block is broken the way a player does: look at it, swing the arm, crack animation, time based on hardness and tool.
 * Tunnels refuse lava, water, falling blocks overhead and holes underfoot and turn away instead.
 *
 * <p>All progress lives in {@link MineTask} (saved with the entity), so interruptions never reset anything; only
 * the transient per-block state in this goal is rebuilt. No global timeout: if no progress is made for 25 s the
 * companion re-plans, and after four failed re-plans it pauses and asks to be told to continue.</p>
 */
public class CompanionSeekBlockGoal extends Goal {
    private static final double REACH_SQ = 4.5 * 4.5;
    private static final int LEG_LEN = 36, SIDE_LEN = 3, MAX_LEGS = 8;
    private static final int WALK_STAND = 0, WALK_REACH = 1, WALK_FREE = 2;

    private final CompanionEntity mob;
    private final Deque<BlockPos> pending = new ArrayDeque<>();   // cells to clear before the next step
    private final Deque<BlockPos> vein = new ArrayDeque<>();
    private final Set<BlockPos> unreachable = new HashSet<>();
    private BlockPos breaking, walkTo, oreTarget, stepStand;
    private int walkMode, breakProgress, breakNeed, walkTicks, scanCooldown, stuck, wanderTries, torchCooldown;
    private long lastProgress, lastSpeak;

    public CompanionSeekBlockGoal(CompanionEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ------------------------------------------------------------------ lifecycle

    private boolean ready() {
        return mob.mineTask().running() && !mob.isSitting() && !mob.isInjured() && mob.getTarget() == null;
    }

    @Override public boolean canUse() { return ready(); }
    @Override public boolean canContinueToUse() { return ready(); }

    @Override
    public void start() {
        resetTransient();
        lastProgress = mob.level().getGameTime();
    }

    @Override
    public void stop() {
        if (mob.level() instanceof ServerLevel level) cancelBreak(level);
        resetTransient();
        mob.getNavigation().stop();
    }

    private void resetTransient() {
        pending.clear();
        vein.clear();
        breaking = walkTo = oreTarget = stepStand = null;
        breakProgress = walkTicks = scanCooldown = stuck = wanderTries = 0;
        if (unreachable.size() > 200) unreachable.clear();
    }

    // ------------------------------------------------------------------ main loop

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel level)) return;
        MineTask t = mob.mineTask();
        long now = level.getGameTime();

        if (now - lastProgress > 500) {                           // 25 s without progress: re-plan, then give up politely
            lastProgress = now;
            cancelBreak(level);
            pending.clear();
            stepStand = walkTo = oreTarget = null;
            if (++stuck >= 4) {
                t.pause();
                say(level, "I'm stuck. Say 'continue' and I'll try again.", true);
                return;
            }
            t.dirData = Direction.from2DDataValue(t.dirData).getClockWise().get2DDataValue();
            return;
        }
        // Underground it gets dark: drop a torch from the bag every so often so the tunnels stay lit.
        if (t.phase != MineTask.Phase.SEARCH && --torchCooldown <= 0) {
            torchCooldown = 60;
            if (level.getMaxLocalRawBrightness(mob.blockPosition()) < 8) mob.placeTorch();
        }
        if (breaking != null) { keepBreaking(level, t, now); return; }
        if (walkTo != null) { walk(level, now); return; }
        if (!pending.isEmpty()) { startBreak(level, pending.poll()); return; }
        if (stepStand != null) {
            walkTo = stepStand;
            walkMode = WALK_STAND;
            walkTicks = 0;
            stepStand = null;
            mob.getNavigation().moveTo(walkTo.getX() + 0.5, walkTo.getY(), walkTo.getZ() + 0.5, 1.0);
            return;
        }
        decide(level, t);
    }

    private void decide(ServerLevel level, MineTask t) {
        while (!vein.isEmpty() && !matches(t, level.getBlockState(vein.peek()))) vein.poll();
        if (!vein.isEmpty()) { approach(level, t, vein.peek()); return; }

        if (--scanCooldown <= 0) {
            scanCooldown = 12;
            oreTarget = scan(level, t);
            if (oreTarget != null) wanderTries = 0;
        }
        if (oreTarget != null && !matches(t, level.getBlockState(oreTarget))) oreTarget = null;
        if (oreTarget != null) { approach(level, t, oreTarget); return; }

        int y = mob.blockPosition().getY();
        switch (t.phase) {
            case SEARCH -> {
                if (t.deep() && y > t.ty) {
                    t.phase = MineTask.Phase.DESCEND;
                    say(level, "Nothing on the surface. Heading down to y=" + t.ty + ".", true);
                } else if (t.deep()) {
                    t.phase = MineTask.Phase.BRANCH;
                } else wanderSearch(level, t);
            }
            case DESCEND -> {
                if (y <= t.ty || y <= level.getMinY() + 6) {            // 26.1-CHECK: LevelReader.getMinY()
                    t.phase = MineTask.Phase.BRANCH;
                    t.leg = 0;
                    t.legLen = 0;
                    say(level, "This depth looks right. Branch mining.", true);
                } else if (!planStep(level, t, Direction.from2DDataValue(t.dirData), -1, true)) {
                    t.dirData = Direction.from2DDataValue(t.dirData).getClockWise().get2DDataValue();
                    if (++stuck >= 4) { t.pause(); say(level, "I can't dig down safely here. Move me and say 'continue'.", true); }
                }
            }
            case BRANCH -> branch(level, t);
        }
    }

    private void branch(ServerLevel level, MineTask t) {
        Direction d = Direction.from2DDataValue(t.dirData);
        int len = (t.leg % 2 == 0) ? LEG_LEN : SIDE_LEN;
        if (t.legLen >= len) {                                          // finished a tunnel leg: shift sideways, come back parallel
            t.leg++;
            t.legLen = 0;
            if (t.leg >= MAX_LEGS) {
                t.leg = 0;
                t.pause();
                say(level, "I've covered this level. Say 'continue' to keep looking.", true);
                return;
            }
            t.dirData = d.getClockWise().get2DDataValue();
            return;
        }
        if (planStep(level, t, d, 0, false)) t.legLen++;
        else t.legLen = len;                                            // lava/water/hole ahead: end this leg early
    }

    private void wanderSearch(ServerLevel level, MineTask t) {
        if (++wanderTries > 10) {
            t.pause();
            say(level, "I can't find any " + CompanionEntity.prettyBlockId(t.blockId) + " around here.", true);
            return;
        }
        BlockPos me = mob.blockPosition();
        int x = me.getX() + mob.getRandom().nextIntBetweenInclusive(-18, 18);
        int z = me.getZ() + mob.getRandom().nextIntBetweenInclusive(-18, 18);
        BlockPos dest = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        Path p = mob.getNavigation().createPath(dest, 1);
        if (p != null && p.canReach()) {
            walkTo = dest;
            walkMode = WALK_FREE;
            walkTicks = 0;
            mob.getNavigation().moveTo(p, 1.0);
        }
    }

    // ------------------------------------------------------------------ approaching and tunnelling

    private void approach(ServerLevel level, MineTask t, BlockPos target) {
        if (mob.distanceToSqr(Vec3.atCenterOf(target)) <= REACH_SQ && lineClear(level, target)) {
            startBreak(level, target);
            return;
        }
        if (t.phase == MineTask.Phase.SEARCH) {                          // above ground: walk if a path exists
            Path p = mob.getNavigation().createPath(target, 1);
            if (p != null && p.canReach()) {
                walkTo = target;
                walkMode = WALK_REACH;
                walkTicks = 0;
                mob.getNavigation().moveTo(p, 1.1);
                return;
            }
        }
        tunnelToward(level, t, target);
    }

    private void tunnelToward(ServerLevel level, MineTask t, BlockPos target) {
        BlockPos me = mob.blockPosition();
        int dx = target.getX() - me.getX(), dy = target.getY() - me.getY(), dz = target.getZ() - me.getZ();
        Direction d;
        if (dx == 0 && dz == 0) d = Direction.from2DDataValue(t.dirData);
        else if (Math.abs(dx) >= Math.abs(dz)) d = dx > 0 ? Direction.EAST : Direction.WEST;
        else d = dz > 0 ? Direction.SOUTH : Direction.NORTH;
        int dyStep = dy < -1 ? -1 : dy > 1 ? 1 : 0;
        if (!planStep(level, t, d, dyStep, true)) {
            unreachable.add(target);
            vein.remove(target);
            oreTarget = null;
        }
    }

    /** Plans one tunnel step (clear cells, then walk onto {@code stand}). dyStep: -1 stairs down, 0 level, +1 stairs up. */
    private boolean planStep(ServerLevel level, MineTask t, Direction first, int dyStep, boolean allowTurn) {
        Direction[] order = allowTurn
                ? new Direction[]{first, first.getClockWise(), first.getCounterClockWise(), first.getOpposite()}
                : new Direction[]{first};
        BlockPos me = mob.blockPosition();
        for (Direction d : order) {
            BlockPos stand = me.relative(d).above(dyStep);
            List<BlockPos> cells = new ArrayList<>(3);
            cells.add(stand);
            cells.add(stand.above());
            if (dyStep < 0) cells.add(stand.above(2));                  // headroom to walk in before dropping
            if (!safe(level, stand, cells)) continue;
            for (BlockPos c : cells) if (!passable(level, c)) pending.add(c);
            stepStand = stand;
            t.dirData = d.get2DDataValue();
            return true;
        }
        return false;
    }

    private boolean safe(ServerLevel level, BlockPos stand, List<BlockPos> cells) {
        BlockState floor = level.getBlockState(stand.below());
        if (floor.isAir() || !floor.getFluidState().isEmpty()) return false;       // would walk into a hole / liquid
        int topY = Integer.MIN_VALUE;
        for (BlockPos c : cells) {
            BlockState s = level.getBlockState(c);
            if (!s.getFluidState().isEmpty() || s.getDestroySpeed(level, c) < 0) return false;
            topY = Math.max(topY, c.getY());
            for (Direction dd : Direction.values()) {
                BlockPos n = c.relative(dd);
                if (cells.contains(n)) continue;
                var f = level.getBlockState(n).getFluidState();
                if (f.is(FluidTags.LAVA) || !f.isEmpty()) return false;            // lava or water behind the wall
            }
        }
        BlockPos ceil = new BlockPos(stand.getX(), topY + 1, stand.getZ());
        BlockState cs = level.getBlockState(ceil);
        return !(cs.getBlock() instanceof FallingBlock) && cs.getFluidState().isEmpty();
    }

    private boolean passable(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.getCollisionShape(level, p).isEmpty() && s.getFluidState().isEmpty();
    }

    // ------------------------------------------------------------------ breaking and walking

    private void startBreak(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (st.isAir()) return;
        mob.equipBestToolFor(st);                                 // hold the right tool: visible swings + correct speed
        float h = st.getDestroySpeed(level, pos);
        if (h < 0) { unreachable.add(pos); oreTarget = null; pending.clear(); stepStand = null; return; }
        float speed = Math.max(1f, mob.getMainHandItem().getDestroySpeed(st));
        breakNeed = Math.max(3, Math.min(90, (int) (h * 30f / speed)));
        breakProgress = 0;
        breaking = pos;
        // Start only after the server equipment sync, so clients see the correct tool move.
        mob.swing(InteractionHand.MAIN_HAND, true);
    }

    private void keepBreaking(ServerLevel level, MineTask t, long now) {
        BlockState st = level.getBlockState(breaking);
        if (st.isAir()) { cancelBreak(level); lastProgress = now; return; }
        Vec3 c = Vec3.atCenterOf(breaking);
        if (mob.distanceToSqr(c) > REACH_SQ + 1) {
            mob.getNavigation().moveTo(c.x, breaking.getY(), c.z, 1.0);
            return;
        }
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(c.x, c.y, c.z, 30f, mob.getMaxHeadXRot());
        breakProgress++;
        // Re-broadcast repeatedly; a short three-tick break still gets a visible swing.
        if (breakProgress % 4 == 1) mob.swing(InteractionHand.MAIN_HAND, true);
        level.destroyBlockProgress(mob.getId(), breaking, Math.min(9, breakProgress * 10 / breakNeed));
        if (breakProgress < breakNeed) return;

        boolean hit = matches(t, st);
        BlockPos pos = breaking;
        level.destroyBlockProgress(mob.getId(), pos, -1);
        level.destroyBlock(pos, true, mob, 512);
        breaking = null;
        lastProgress = now;
        stuck = 0;
        mob.collectNearbyItems();
        if (hit) onMined(level, t, pos);
    }

    private void onMined(ServerLevel level, MineTask t, BlockPos pos) {
        t.mined++;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            BlockPos n = pos.offset(dx, dy, dz);
            if (vein.size() < 24 && !vein.contains(n) && matches(t, level.getBlockState(n))) vein.add(n);
        }
        say(level, "Got " + CompanionEntity.prettyBlockId(t.blockId) + "! (" + t.mined + "/" + t.want + ")", true);
        if (t.mined >= t.want) {
            String msg = "That's " + t.mined + " " + CompanionEntity.prettyBlockId(t.blockId) + " for you.";
            t.finish();
            mob.speak(msg);
            resetTransient();      // no warp home: the follow goal walks the companion back
        }
    }

    private void walk(ServerLevel level, long now) {
        walkTicks++;
        BlockPos w = walkTo;
        boolean arrived = switch (walkMode) {
            case WALK_REACH -> mob.distanceToSqr(Vec3.atCenterOf(w)) <= REACH_SQ && lineClear(level, w);
            case WALK_FREE -> mob.distanceToSqr(Vec3.atCenterOf(w)) < 4.0;
            default -> Math.abs(mob.getX() - (w.getX() + 0.5)) < 0.7 && Math.abs(mob.getZ() - (w.getZ() + 0.5)) < 0.7
                    && Math.abs(mob.getY() - w.getY()) < 1.2;
        };
        if (arrived) { walkTo = null; lastProgress = now; mob.getNavigation().stop(); return; }
        PathNavigation nav = mob.getNavigation();
        if (walkTicks > 80 || (walkTicks > 5 && nav.isDone())) {
            if (walkMode == WALK_STAND) {                                // last resort so a tunnel step never deadlocks
                mob.setPos(w.getX() + 0.5, w.getY(), w.getZ() + 0.5);
                mob.resetFallDistance();
                nav.stop();
                lastProgress = now;
            } else if (walkMode == WALK_REACH && oreTarget != null) {
                unreachable.add(oreTarget);
                oreTarget = null;
            }
            walkTo = null;
            return;
        }
        if (walkMode == WALK_STAND && walkTicks % 20 == 0) nav.moveTo(w.getX() + 0.5, w.getY(), w.getZ() + 0.5, 1.0);
    }

    private void cancelBreak(ServerLevel level) {
        if (breaking != null) level.destroyBlockProgress(mob.getId(), breaking, -1);
        breaking = null;
    }

    // ------------------------------------------------------------------ sensing

    /** Nearest match that a player could actually see (touching air) or that is within 2 blocks of the companion. */
    private BlockPos scan(ServerLevel level, MineTask t) {
        int r = t.phase == MineTask.Phase.SEARCH ? 22 : 10;
        BlockPos o = mob.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(o.offset(-r, -Math.min(r, 12), -r), o.offset(r, Math.min(r, 8), r))) {
            if (!matches(t, level.getBlockState(p)) || unreachable.contains(p)) continue;
            double d = mob.distanceToSqr(Vec3.atCenterOf(p));
            if (d >= bestD || (d > 9.0 && !exposed(level, p))) continue;
            bestD = d;
            best = p.immutable();
        }
        return best;
    }

    private boolean exposed(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.values()) {
            BlockPos n = p.relative(d);
            if (level.getBlockState(n).getCollisionShape(level, n).isEmpty()) return true;
        }
        return false;
    }

    private boolean lineClear(ServerLevel level, BlockPos target) {
        Vec3 eye = mob.getEyePosition(), goal = Vec3.atCenterOf(target), delta = goal.subtract(eye);
        for (int k = 1; k <= 8; k++) {
            BlockPos bp = BlockPos.containing(eye.add(delta.scale(k / 9.0)));
            if (bp.equals(target)) break;
            if (!level.getBlockState(bp).getCollisionShape(level, bp).isEmpty()) return false;
        }
        return true;
    }

    private static boolean matches(MineTask t, BlockState state) {
        if (state.isAir()) return false;
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        String bid = t.blockId;
        if (path.equals(bid)) return true;
        if (bid.endsWith("_ore") && path.endsWith(bid)) return true;          // diamond_ore also matches deepslate_diamond_ore
        return switch (bid) {                                                 // family ids from "wood"/"planks"/... orders
            case "log", "wood" -> path.endsWith("_log") || path.endsWith("_wood");
            case "planks", "plank" -> path.endsWith("_planks");
            case "leaves" -> path.endsWith("_leaves");
            case "wool" -> path.endsWith("_wool");
            default -> false;
        };
    }

    private void say(ServerLevel level, String msg, boolean force) {
        long now = level.getGameTime();
        if (force || now - lastSpeak > 100) { mob.speak(msg); lastSpeak = now; }
    }
}

