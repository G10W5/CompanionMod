package com.example.companionmod.entity.ai;

import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.entity.CompanionProtection;
import com.example.companionmod.entity.task.MineTask;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    private static final double REACH_SQ = 4.5 * 4.5;
    private static final int LEG_LEN = 36, SIDE_LEN = 3, MAX_LEGS = 8;
    private static final int WALK_STAND = 0, WALK_REACH = 1, WALK_FREE = 2, WALK_EXPLORE = 3;
    private static final int MAX_EXPLORE_LEGS = 30, SIGHT_RADIUS = 56, SIGHT_MIN = 16;

    private final CompanionEntity mob;
    private final Deque<BlockPos> pending = new ArrayDeque<>();   // cells to clear before the next step
    private final Deque<BlockPos> vein = new ArrayDeque<>();
    private final Set<BlockPos> unreachable = new HashSet<>();
    private BlockPos breaking, walkTo, oreTarget, stepStand;
    private int walkMode, breakProgress, breakNeed, walkTicks, stuck, wanderTries, torchCooldown;
    private long lastProgress, lastSpeak;

    // --- surface search: time-based rescans, long-range heightmap "sight", exploration memory
    private long nextScanAt, nextSightAt, nextExploreAt, nextProbeAt, lastUnreachableClear;
    private final Deque<BlockPos> explored = new ArrayDeque<>();   // where we already looked (explore memory)

    // cache: does Block X match the current task? (replaces a registry lookup + string compare per scanned block)
    private static final Map<Block, Boolean> MATCH_CACHE = new HashMap<>();
    private static String matchCacheId = "";

    // --- scaffolding: climb with blocks from the bag, take them back afterwards; plus per-target bookkeeping
    private static final int MAX_SCAFFOLD = 24;
    private final Deque<BlockPos> scaffold = new ArrayDeque<>();      // blocks WE placed, bottom first (kept across goal restarts)
    private final Map<BlockPos, Integer> approachTries = new HashMap<>();
    private BlockPos approachTarget, pillarTarget, jumpCell;
    private int pillarAir, cleanupWait, pillarPlaced, bridgeRun;
    private BlockPos bridgeCell;
    private long softRecoverAt;
    private int walkRetries;
    private int branchBoxed;         // consecutive fully-blocked branch steps: rotate the leg before ending it
    private long lastDiagLog;
    private int lastTaskSerial;      // MineTask.serial at the last tick: a new order drops stale state same-tick
    private Vec3 lastHeartbeatPos = new Vec3(0, -10000, 0);   // movement-gated progress heartbeat
    private Vec3 standLast = new Vec3(0, -10000, 0);   // tunnel-step stall detector (see WALK_STAND)
    private int standStall;
    private double bestH = Double.MAX_VALUE;           // best horizontal distance reached toward the current step cell
    private boolean selected;        // goal selector has us running (see selected())
    private int lastDescendY = Integer.MIN_VALUE;   // DESCEND: only trust slope-walks while height is actually lost
    private long lastDescendDrop;
    private int watchMined;                         // no-silent-stall watchdog sample (30 s outcome window)
    private long watchAt;
    private Vec3 watchPos;
    private boolean taskWasPaused;                  // pause->'continue' detection: pay for one staircase-reuse walk
    private boolean descendResume;                  // next DESCEND decide tries walking down the EXISTING staircase first
    private String waitingFor;                      // non-null: an entity physically stands in our 1-block walk corridor
    private long lastWaitLog;
    private String lastDiag = "";    // change-gated diagnostic lines (one per real state change, never per frame)

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
        selected = true;
        resetTransient();
        lastProgress = mob.level().getGameTime();
        lastHeartbeatPos = mob.position();
    }

    @Override
    public void stop() {
        selected = false;
        if (mob.level() instanceof ServerLevel level) cancelBreak(level);
        resetTransient();
        mob.getNavigation().stop();
    }

    /** True while this goal is actually selected and ticking - the entity uses it to spot "task assigned but goal dormant" stalls. */
    public boolean selected() { return selected; }

    private void resetTransient() {
        pending.clear();
        vein.clear();
        breaking = walkTo = oreTarget = stepStand = null;
        breakProgress = walkTicks = stuck = wanderTries = 0;
        nextScanAt = nextSightAt = nextExploreAt = 0;
        explored.clear();
        approachTries.clear();
        approachTarget = pillarTarget = jumpCell = bridgeCell = null;
        pillarAir = 0;
        watchAt = 0;                            // the no-silent-stall watchdog re-arms after any state reset
        branchBoxed = 0;
        lastDescendY = Integer.MIN_VALUE;
        lastDiag = "";
        unreachable.clear();       // a fresh task retries every block: old "unreachable" marks must not leak into it
    }

    /** One diagnostic line per real state change - latest.log shows the movement state without flooding. */
    private void diag(String s) {
        if (!s.equals(lastDiag)) { lastDiag = s; LOG.info("[companionmod] seek: {}", s); }
    }

    // ------------------------------------------------------------------ main loop

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel level)) return;
        MineTask t = mob.mineTask();
        long now = level.getGameTime();
        if (t.serial != lastTaskSerial) {          // a NEW order arrived while we were selected: drop stale walk/break/pillar state this tick
            lastTaskSerial = t.serial;
            resetTransient();
        }
        // Ambient peek-through-walls: the probe mechanic works during ANY phase of the task - search,
        // descend, approach, branch - every 2 s from wherever we stand (was: only every 3rd branch block).
        if (!t.paused && now >= nextProbeAt) {
            nextProbeAt = now + 40;
            probeAround(level, t);
        }
        // RESUME detection ('continue' after a pause): the next DESCEND decide must first try the existing
        // staircase, because the height-drop trust window has long expired and the goal used to dig a
        // brand-new staircase right beside the old one (lastDescendDrop is minutes stale after a pause).
        if (t.paused) taskWasPaused = true;
        else if (taskWasPaused) { taskWasPaused = false; descendResume = true; }

        long idle = now - lastProgress;
        if (idle > 200 && now - lastDiagLog > 200) {      // diagnostics FIRST: the watchdog branches below return early
            lastDiagLog = now;
            LOG.warn("[companionmod] mining stall ({}s idle): phase={} ore={} walk={} mode={} breaking={} pending={} pillar={} ground={} marked={}",
                    idle / 20, t.phase, oreTarget, walkTo, walkMode, breaking, pending.size(), pillarTarget, mob.onGround(), unreachable.size());
        }
        if (idle > 160 && idle <= 500 && softRecoverAt != lastProgress) {   // 8 s without progress: drop what we are fixated on, re-decide
            softRecoverAt = lastProgress;
            LOG.warn("[companionmod] soft recovery after {}s idle, state={}", idle / 20, stateName());
            cancelBreak(level);
            if (approachTarget != null) giveUp(approachTarget);
            pending.clear();
            stepStand = walkTo = null;
            nextScanAt = 0;                // re-decide from scratch: rescan immediately
            endPillar();
            bridgeCell = null;
            mob.getNavigation().stop();      // else it keeps steering the old route while we re-decide
            return;
        }
        if (idle > 500) {                                         // 25 s without progress: re-plan, then give up politely
            lastProgress = now;
            LOG.warn("[companionmod] hard re-plan after {}s idle (stuck={}), state={}", idle / 20, stuck, stateName());
            cancelBreak(level);
            pending.clear();
            stepStand = walkTo = oreTarget = null;
            mob.getNavigation().stop();
            if (++stuck >= 4) {
                t.pause();
                say(level, "I'm stuck (" + stateName() + "). Say 'continue' and I'll try again.", true);
                return;
            }
            t.dirData = Direction.from2DDataValue(t.dirData).getClockWise().get2DDataValue();
            return;
        }
        if (now - lastUnreachableClear >= 600) { lastUnreachableClear = now; unreachable.clear(); }   // "unreachable" marks are retried every 30 s: never a permanent blacklist
        // Independent no-silent-stall watchdog: the idle timers above trust lastProgress, and loops that keep
        // "succeeding" without real outcome (arrived flicker on jitter, heartbeat from being nudged by falling
        // gravel, phantom break targets) can refresh lastProgress forever. Real case: gravel buried the
        // companion 14:30:46-14:38:26 in latest.log - 8 MINUTES with no watchdog line and no speech. This one
        // measures the GOAL's actual outcome: nothing mined AND no significant movement over 30 s while not
        // paused -> speak up and pause, instead of silently rotting in place.
        if (now - watchAt >= 600) {
            if (watchAt != 0 && !t.paused && t.mined == watchMined
                    && mob.position().distanceToSqr(watchPos) < 4.0) {
                watchAt = now;                          // re-arm BEFORE pausing: 'continue' starts a fresh sample
                if (waitingFor != null) {
                    // Waiting for someone to step out of the tunnel is NOT 'stuck': keep the task alive and
                    // say who is blocking - the moment they move, the walk resumes on its own.
                    say(level, "I'm waiting for " + waitingFor + " to get out of the way.", false);
                    watchMined = t.mined;
                    watchPos = mob.position();
                } else {
                    t.pause();
                    say(level, "I'm stuck, something's blocking me. Say 'continue' when I'm free.", true);
                    cancelBreak(level);
                    pending.clear();
                    stepStand = walkTo = oreTarget = null;
                    mob.getNavigation().stop();
                    return;
                }
            }
            watchAt = now;
            watchMined = t.mined;
            watchPos = mob.position();
        }
        // Underground it gets dark: drop a torch from the bag every so often so the tunnels stay lit.
        if (t.phase != MineTask.Phase.SEARCH && --torchCooldown <= 0) {
            torchCooldown = 60;
            if (level.getMaxLocalRawBrightness(mob.blockPosition()) < 8) mob.placeTorch();
        }
        if (breaking != null) { keepBreaking(level, t, now); return; }
        // BURIED self-clear (was the 18:47-18:51 pocket: minutes of "pathfinder refuses the step" with no
        // danger visible): gravel/sand packed into OUR feet or head cell makes every path request fail from
        // an invalid START node and the direct-walk push is useless against a solid head - the mob looks
        // perfectly safe yet cannot move 1 block. Dig ourselves out before anything else.
        BlockPos meNow = mob.blockPosition();
        BlockPos buried = !level.getBlockState(meNow).getCollisionShape(level, meNow).isEmpty() ? meNow
                : !level.getBlockState(meNow.above()).getCollisionShape(level, meNow.above()).isEmpty() ? meNow.above() : null;
        if (buried != null && safeToBreak(level, buried)) {
            diag("buried: clearing " + buried + " around my own cell");
            walkTo = null;
            mob.getNavigation().stop();
            pending.addFirst(buried);           // mined next tick (falling-cover guard still applies above it)
            return;
        }
        if (walkTo != null) { walk(level, now); return; }
        if (!pending.isEmpty()) {
            // Falling-block cover: if gravel/sand sits ANYWHERE above the queued cell (fell while we dug
            // elsewhere, or hangs over a fresh notch), mining the cell first just re-buries the hole we
            // opened - at deepslate levels the whole stub-tunnel direction collapses to "one block per
            // direction". Mine the cover top-down BEFORE opening its cell (the real cell stays queued).
            BlockPos head = pending.peek();
            BlockPos up = head;
            while (level.getBlockState(up.above()).getBlock() instanceof FallingBlock) up = up.above();
            if (up != head) {
                diag("falling cover: mining " + up + " first, or it lands in the " + head + " hole");
                startBreak(level, up);
                return;
            }
            // Stale queued cell left behind (walked away, lane turned): mining out-of-reach cells looks like
            // 'swinging at empty space across the room' - drop it; the lane plan re-queues work where we stand.
            if (mob.getEyePosition().distanceToSqr(Vec3.atCenterOf(head)) > REACH_SQ + 8) {
                diag("pending: dropping out-of-reach cell " + head);
                pending.poll();
                return;
            }
            startBreak(level, pending.poll());
            return;
        }
        if (pillarTarget != null) { pillarStep(level, t, now); return; }
        if (!mob.onGround() && !mob.isInWater()) return;        // wait for the landing: path requests fail in mid-air and used to mark good targets "unreachable"
        if (stepStand != null) {
            if (bridgeCell != null) {                                   // mining: lay a floor over an air gap first (stays there)
                BlockPos b = bridgeCell;
                bridgeCell = null;
                if (passable(level, b)) {
                    ItemStack taken = CompanionProtection.canModify(level, mob, b) ? takeScaffoldBlock(t) : ItemStack.EMPTY;
                    if (taken.isEmpty()) { stepStand = null; return; }
                    level.setBlock(b, ((BlockItem) taken.getItem()).getBlock().defaultBlockState(), 3);
                    mob.swing(InteractionHand.MAIN_HAND, true);
                    lastProgress = now;
                }
            }
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
        if (t.mined >= t.want) {                    // done (also when a fight interrupted it): climb down first, then report
            if (cleanupScaffold(level)) return;
            String msg = "That's " + t.mined + " " + CompanionEntity.prettyBlockId(t.blockId) + " for you.";
            t.finish();
            mob.speak(msg);
            resetTransient();                       // no warp home: the follow goal walks the companion back
            return;
        }
        while (!vein.isEmpty() && !matches(t, level.getBlockState(vein.peek()))) vein.poll();
        if (!vein.isEmpty()) { approach(level, t, vein.peek()); return; }

        long now = level.getGameTime();
        if (oreTarget != null && !matches(t, level.getBlockState(oreTarget))) { oreTarget = null; nextScanAt = 0; }
        // DESCEND skips area rescans entirely: the stairwell goes through solid stone, nothing can show up
        // on a surface scan, and the per-level rescan + its 10-tick gate was most of the "5 s per Y level" drag.
        if (t.phase != MineTask.Phase.DESCEND && oreTarget == null && now >= nextScanAt) {
            nextScanAt = now + 10;
            oreTarget = scan(level, t);
            if (oreTarget != null) wanderTries = 0;
        }
        if (oreTarget != null) { approach(level, t, oreTarget); return; }

        if (cleanupScaffold(level)) return;         // nothing left to mine from up here: climb down, take our blocks back

        int y = mob.blockPosition().getY();
        switch (t.phase) {
            case SEARCH -> {
                if (t.deep() && y > t.ty) {
                    t.phase = MineTask.Phase.DESCEND;
                    say(level, "Nothing on the surface. Heading down to y=" + t.ty + ".", true);
                } else if (t.deep()) {
                    t.phase = MineTask.Phase.BRANCH;
                } else searchSurface(level, t);
            }
            case DESCEND -> {
                if (y <= t.ty || y <= level.getMinY() + 6) {            // 26.1-CHECK: LevelReader.getMinY()
                    t.phase = MineTask.Phase.BRANCH;
                    t.leg = 0;
                    t.legLen = 0;
                    lastDescendY = Integer.MIN_VALUE;
                    say(level, "This depth looks right. Branch mining.", true);
                } else {
                    if (lastDescendY == Integer.MIN_VALUE || y < lastDescendY) {
                        lastDescendY = y;
                        lastDescendDrop = now;                          // height lost recently -> walkable slopes are fine
                    }
                    // Slope-walks are only trusted while they actually lose height. A path that walks
                    // without descending used to refresh the progress heartbeat forever - the old
                    // "stuck on planning and walking" loop. After 5 s without losing height: dig stairs.
                    // Slope-walks only get 2 s of actual height loss to prove themselves - else dig the
                    // stairs. The old 5 s window made every level ~8 s (5 s of futile slope wandering +
                    // digging) = "descends one Y per ~8 s" in latest.log.
                    // RESUME ('continue'): lastDescendDrop is minutes stale, so the trust window alone
                    // never fired and the goal re-dug a brand-new staircase beside the old one. A resume
                    // gets ONE free slope/staircase-reuse walk attempt, then normal descent rules apply.
                    boolean trySlope = (now - lastDescendDrop < 40) || descendResume;
                    descendResume = false;
                    if (trySlope && walkDownSlope(level)) {
                        diag("descending by walking (y=" + y + " -> ty=" + t.ty + ")");
                    } else if (!planStep(level, t, Direction.from2DDataValue(t.dirData), -1, true)) {
                        diag("cannot dig down at y=" + y + " dir=" + t.dirData + " stuck=" + stuck);
                        t.dirData = Direction.from2DDataValue(t.dirData).getClockWise().get2DDataValue();
                        if (++stuck >= 4) { t.pause(); say(level, "I can't dig down safely here. Move me and say 'continue'.", true); }
                    } else {
                        diag("digging stairs down (y=" + y + " -> ty=" + t.ty + ")");
                    }
                }
            }
            case BRANCH -> branch(level, t);
            case ASCEND -> ascend(level, t);
        }
    }

    /** "Dig up to the surface": staircase up until the terrain heightmap is at or below us, then done. */
    private void ascend(ServerLevel level, MineTask t) {
        if (atSurface(level)) {
            t.finish();
            mob.speak("I'm up.");
            resetTransient();                       // the follow goal walks the companion back, no warp
            return;
        }
        if (!planStep(level, t, Direction.from2DDataValue(t.dirData), 1, true)) {
            t.dirData = Direction.from2DDataValue(t.dirData).getClockWise().get2DDataValue();
            if (++stuck >= 4) { t.pause(); say(level, "I can't dig up safely here. Move me and say 'continue'.", true); }
        }
    }

    /** At the terrain surface: the no-leaves heightmap at our feet is at or below us, or the sky is visible. */
    private boolean atSurface(ServerLevel level) {
        BlockPos p = mob.blockPosition();
        int surf = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
        return p.getY() >= surf - 1 || level.canSeeSky(p.above());
    }

    private void branch(ServerLevel level, MineTask t) {
        // Chasing ore dropped us BELOW the lane (-59/-60 next to bedrock): mining must stay on ty. The lane
        // code never brought him back up, so a single diamond-pocket fall stranded the whole strip job at
        // bedrock level. Climb back FIRST - headroom cells are cleared upward by planStep (+1).
        if (mob.blockPosition().getY() < t.ty) {
            if (planStep(level, t, Direction.from2DDataValue(t.dirData), 1, true)) {
                diag("branch: climbing back to lane y=" + t.ty + " (was y=" + mob.blockPosition().getY() + ")");
                return;
            }
        }
        Direction d = Direction.from2DDataValue(t.dirData);
        int len = (t.leg % 2 == 0) ? LEG_LEN : SIDE_LEN;
        if (t.legLen >= len) {                                          // finished a tunnel leg: shift sideways, come back parallel
            t.leg++;
            t.legLen = 0;
            if (t.leg >= MAX_LEGS) {
                // Used to PAUSE for a spoken "I've covered this level" here - but a PAUSED task stops every
                // plan deadline extension, so an AI goal cut dead after 30 s while the companion stood
                // doing nothing. Deep jobs now simply turn around and branch on, until the order is done.
                t.leg = 0;
                t.legLen = 0;
                t.dirData = d.getOpposite().get2DDataValue();
                return;
            }
            t.dirData = d.getClockWise().get2DDataValue();
            return;
        }
        if (planStep(level, t, d, 0, false)) {
            t.legLen++;
            branchBoxed = 0;
            if (t.legLen % 3 == 0) digTrays(level, t);        // strip mining: reach 4 trays left+right every 3rd block
            return;
        }
        // Blocked ahead (lava pocket, bedrock bump): mine at a slight angle or step over it before ending the leg.
        // Without this one blocked cell ended the leg instantly, all 8 legs burned in seconds and the task paused.
        // LANE MEMORY: planStep() writes the direction it actually succeeded in into t.dirData - so a single
        // cw/ccw detour step used to PERMANENTLY rotate the lane 90 degrees. Crossing her own tray room (open
        // floor => straight planStep refuses) turned her into the already-mined area and she zigzagged back
        // over old lanes ("mines where he already was going back" + the screenshot). The STAND may wander one
        // block sideways on a detour, but the LANE direction is restored afterwards.
        if (planStep(level, t, d.getClockWise(), 0, false)
                || planStep(level, t, d.getCounterClockWise(), 0, false)
                || planStep(level, t, d, -1, false) || planStep(level, t, d, 1, false)) {
            t.dirData = d.get2DDataValue();                        // keep the leg's heading despite detours
            t.legLen++;
            branchBoxed = 0;
            return;
        }
        // Open space ahead (a cavern crossing the leg line): the tunnel logic refuses holes in the floor, so
        // continue the leg ON the cave floor like a walker instead of burning the leg and pausing the task.
        BlockPos ahead = mob.blockPosition().relative(d, 8);
        Path p = mob.getNavigation().createPath(ahead, 2);
        if (p != null && p.canReach()) {
            walkTo = ahead;
            walkMode = WALK_FREE;
            walkTicks = 0;
            mob.getNavigation().moveTo(p, 1.0);
            t.legLen += 4;                          // credit progress: the leg keeps going, it does not exhaust
            branchBoxed = 0;
            return;
        }
        // Boxed in (lava pocket, bedrock wall): rotate the leg and mine at an angle to escape the pocket
        // instead of ending the leg - two rotations (90 then 180) before the leg really ends.
        if (branchBoxed++ < 2) {
            diag("branch: all 4 directions refused at " + mob.blockPosition() + " (rotation " + branchBoxed + ")");
            t.dirData = d.getClockWise().get2DDataValue();
            return;
        }
        branchBoxed = 0;
        t.legLen = len;                                                 // truly boxed in: end this leg early
    }

    /**
     * Strip-mine probes: called every 3rd block of a tunnel leg. Peek through the tunnel walls (both sides,
     * 2 deep, body and head level): a matching block hiding behind stone is added to the vein deque, and
     * approach()/firstBlocker then digs the wall stone out to reach it - like a player peeking into the wall.
     */
    private void digTrays(ServerLevel level, MineTask t) {
        if (pending.size() > 12) return;                    // don't bury the walk-ahead steps under tray work
        Direction cat = Direction.from2DDataValue(t.dirData);
        BlockPos me = mob.blockPosition();
        int queued = 0;
        for (Direction side : new Direction[]{ cat.getClockWise(), cat.getCounterClockWise() }) {
            for (int depth = 1; depth <= 4; depth++) {
                boolean open = true;
                for (int h = 0; h <= 1; h++) {
                    BlockPos c = me.relative(side, depth).above(h);
                    if (pending.contains(c) || vein.contains(c)) continue;
                    if (passable(level, c)) continue;               // already excavated: awareness of our own mining
                    if (matches(t, level.getBlockState(c))) { vein.add(c); continue; }   // ore: chased via the vein queue
                    if (!safeToBreak(level, c)) { open = false; break; }                // liquid edge: that tray stops here
                    pending.add(c);
                    queued++;
                }
                if (!open) break;
            }
        }
        if (queued > 0) diag("branch: digging side trays (" + queued + " new, 3rd block, reach 4) at " + me);
    }

    /**
     * Ambient probes: same peek-through-walls as the tunnel probes, but in ALL 4 horizontal directions and
     * running the whole time the seek task is active (every 2 s), not just while branch mining - the user
     * asked for the peek mechanic to always work during any task (branch legs dig full reach trays since
     * then). Hidden ore found near wherever we happen to stand joins the vein deque, approach()/firstBlocker
     * found near wherever we happen to stand joins the vein deque, approach()/firstBlocker digs through.
     */
    private void probeAround(ServerLevel level, MineTask t) {
        probe(level, t, Direction.Plane.HORIZONTAL);
    }

    private void probe(ServerLevel level, MineTask t, Iterable<Direction> dirs) {
        BlockPos me = mob.blockPosition();
        for (Direction side : dirs) {
            for (int depth = 1; depth <= 2; depth++) {
                for (int h = 0; h <= 1; h++) {
                    BlockPos c = me.relative(side, depth).above(h);
                    if (vein.size() >= 24) return;
                    if (!vein.contains(c) && matches(t, level.getBlockState(c))) {
                        vein.add(c);
                        diag("probe: " + t.blockId + " spotted " + depth + " behind the " + side + " wall at " + c);
                    }
                }
            }
        }
    }

    /** Surface search: first look far with the heightmap (trees show up as log columns), else explore somewhere new. */
    private void searchSurface(ServerLevel level, MineTask t) {
        long now = level.getGameTime();
        if (now >= nextSightAt) {
            nextSightAt = now + 40;
            BlockPos seen = farSight(level, t);
            if (seen != null) {
                BlockPos me = mob.blockPosition();
                BlockPos foot = groundBelow(level, t, seen).above();
                double dx = foot.getX() - me.getX(), dz = foot.getZ() - me.getZ();
                double hd = Math.sqrt(dx * dx + dz * dz);
                BlockPos wp = foot;
                if (hd > 24) {   // the pathfinder is limited by follow range: go in hops, the near scan takes over on arrival
                    int wx = me.getX() + (int) (dx / hd * 24), wz = me.getZ() + (int) (dz / hd * 24);
                    if (level.hasChunk(wx >> 4, wz >> 4)) {
                        wp = new BlockPos(wx, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz), wz);
                    }
                }
                Path p = mob.getNavigation().createPath(wp, 2);
                if (p != null && p.canReach()) { startExplore(wp, p); return; }
                unreachable.add(seen.immutable());   // cannot walk there (water, cliff): look at the next candidate next time
            }
        }
        exploreSomewhereNew(level, t);
    }

    /** Nearest matching block at the top of a loaded column (trees are the highest non-leaf block of their column). */
    private BlockPos farSight(ServerLevel level, MineTask t) {
        BlockPos o = mob.blockPosition();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE, minSq = SIGHT_MIN * SIGHT_MIN;
        for (int x = o.getX() - SIGHT_RADIUS; x <= o.getX() + SIGHT_RADIUS; x++) {
            for (int z = o.getZ() - SIGHT_RADIUS; z <= o.getZ() + SIGHT_RADIUS; z++) {
                double d = (double) (x - o.getX()) * (x - o.getX()) + (double) (z - o.getZ()) * (z - o.getZ());
                if (d < minSq || d >= bestD) continue;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;          // never force-load chunks // 26.1-CHECK: LevelReader.hasChunk(int,int)
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                m.set(x, y, z);
                if (!matches(t, level.getBlockState(m)) || unreachable.contains(m)) continue;
                bestD = d;
                best = m.immutable();
            }
        }
        return best;
    }

    private void exploreSomewhereNew(ServerLevel level, MineTask t) {
        long now = level.getGameTime();
        if (now < nextExploreAt) return;
        if (wanderTries == 4) unreachable.clear();               // new vantage point: retry old "unreachable" marks
        if (++wanderTries > MAX_EXPLORE_LEGS) {
            t.pause();
            say(level, "I can't find any " + CompanionEntity.prettyBlockId(t.blockId) + " around here.", true);
            return;
        }
        BlockPos me = mob.blockPosition();
        remember(me);

        record Cand(BlockPos pos, double score) { }
        List<Cand> cands = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            double ang = mob.getRandom().nextDouble() * Math.PI * 2;
            double dist = 20 + mob.getRandom().nextDouble() * 16 + Math.min(wanderTries, 10);
            int x = me.getX() + (int) (Math.cos(ang) * dist), z = me.getZ() + (int) (Math.sin(ang) * dist);
            if (!level.hasChunk(x >> 4, z >> 4)) continue;
            BlockPos dest = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (!level.getFluidState(dest).isEmpty() || !level.getFluidState(dest.below()).isEmpty()) continue;   // no lakes / lava
            double nearest = 48;
            for (BlockPos e : explored) nearest = Math.min(nearest, Math.sqrt(e.distSqr(dest)));
            cands.add(new Cand(dest, nearest + mob.getRandom().nextDouble() * 6));   // prefer spots far from everywhere we looked
        }
        cands.sort((a, b) -> Double.compare(b.score(), a.score()));
        for (int i = 0; i < Math.min(4, cands.size()); i++) {
            Path p = mob.getNavigation().createPath(cands.get(i).pos(), 2);
            if (p != null && p.canReach()) { startExplore(cands.get(i).pos(), p); return; }
        }
        nextExploreAt = now + 20;                                  // nothing reachable this time: pause a second, retry
    }

    private void startExplore(BlockPos dest, Path p) {
        remember(dest);
        walkTo = dest;
        walkMode = WALK_EXPLORE;
        walkTicks = 0;
        mob.getNavigation().moveTo(p, 1.0);
    }

    private void remember(BlockPos p) {
        explored.addLast(p.immutable());
        while (explored.size() > 24) explored.pollFirst();
    }

    // ------------------------------------------------------------------ approaching and tunnelling

    private void approach(ServerLevel level, MineTask t, BlockPos target) {
        // Hard escape for the retarget stand-loop: targets that could not be walked to, scaffolded or
        // tunnelled toward kept the goal cycling ("gave up after 16 tries", minutes of standing) because
        // nothing marked them done. After 12 attempts we give up and let scan() pick the next target.
        Integer triesN = approachTries.get(target.immutable());
        if (triesN != null && triesN >= 12) {
            diag("approach exhausted after " + triesN + " tries on " + target + ", giving up");
            giveUp(target);
            return;
        }
        approachTarget = target;
        if (inReach(Vec3.atCenterOf(target))) {
            if (lineClear(level, target)) { startBreak(level, target); return; }
            // Close enough but something is in the way (stone, leaves...): dig it out the way a player would.
            BlockPos blocker = firstBlocker(level, target);
            if (blocker == null) { startBreak(level, target); return; }
            if (approachTries.merge(target.immutable(), 1, Integer::sum) <= 12 && safeToBreak(level, blocker)) {   // big canopies can have several leaf layers in front of a log
                pending.add(blocker);
            } else {
                giveUp(target);
            }
            return;
        }
        // Far away (or above us). Unless we are climbing toward this block, get off our tower first.
        BlockPos me = mob.blockPosition();
        boolean climbing = target.getY() - me.getY() >= 2
                && Math.max(Math.abs(target.getX() - me.getX()), Math.abs(target.getZ() - me.getZ())) <= 3;
        if (!climbing && cleanupScaffold(level)) return;
        // Walk there whenever a path exists - surface, open caves and finished tunnels are crossed like a player
        // crosses them. Only solid rock (no path through) is tunnelled; this is what froze it in caves before,
        // where every tunnel step was "unsafe" (open space below) and the task paused itself.
        Path p = mob.getNavigation().createPath(target, 1);
        if (p != null && p.canReach()) {
            walkTo = target;
            walkMode = WALK_REACH;
            walkTicks = 0;
            mob.getNavigation().moveTo(p, 1.1);
            return;
        }
        if (tryScaffold(level, t, target)) return;                   // high in a tree / on a cave ceiling: build up to it
        // The pathfinder refuses to plan INTO several target shapes (a cactus column gets damage malus, a
        // tree top has no standable node) - that failure used to fall into tunnel mode and dig the infamous
        // one-block trench. Walk to the nearest STANDABLE cell BESIDE the target instead; mining then
        // starts by reach, exactly like a player would do it.
        // Already standing beside the target's column? Skip the walk-around entirely - it used to
        // alternate forever between the two beside cells (13:19:26-30 in latest.log) without ever climbing.
        double dxT = target.getX() + 0.5 - mob.getX(), dzT = target.getZ() + 0.5 - mob.getZ();
        boolean hugging = dxT * dxT + dzT * dzT <= 2.9;
        if (!hugging) {
            BlockPos base = target.getY() > mob.blockPosition().getY() ? groundBelow(level, t, target).above() : target;
            BlockPos beside = null;
            Path besidePath = null;
            for (int dy = 0; dy >= -1 && besidePath == null; dy--) {
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos c = base.above(dy).relative(dir);
                    if (level.getBlockState(c.below()).isAir()) continue;               // nothing to stand on
                    if (!passable(level, c) || !passable(level, c.above())) continue;   // no headroom
                    if (matches(t, level.getBlockState(c))) continue;                   // never stand IN the material itself
                    Path fp = mob.getNavigation().createPath(c, 1);
                    if (fp != null && fp.canReach()) { beside = c.immutable(); besidePath = fp; break; }
                }
            }
            if (besidePath != null) {
                walkTo = beside;
                walkMode = WALK_FREE;
                walkTicks = 0;
                mob.getNavigation().moveTo(besidePath, 1.1);
                diag("walking beside target " + target + " -> " + beside);
                return;
            }
        }
        tunnelToward(level, t, target);
    }

    private void giveUp(BlockPos target) {
        diag("approach: giving up on " + target);
        unreachable.add(target.immutable());
        vein.remove(target);
        if (target.equals(oreTarget)) oreTarget = null;
        if (target.equals(pillarTarget)) { pillarTarget = null; jumpCell = null; }
        pending.clear();
        stepStand = null;
    }

    /** First solid cell between our eyes and the target (the thing we must dig out), or null. */
    private BlockPos firstBlocker(ServerLevel level, BlockPos target) {
        Vec3 eye = mob.getEyePosition(), goal = Vec3.atCenterOf(target), delta = goal.subtract(eye);
        for (int k = 1; k <= 8; k++) {
            BlockPos bp = BlockPos.containing(eye.add(delta.scale(k / 9.0)));
            if (bp.equals(target)) break;
            if (!level.getBlockState(bp).getCollisionShape(level, bp).isEmpty()) return bp.immutable();
        }
        return null;
    }

    /** Never open a wall into liquid, and never break unbreakable blocks. */
    private boolean safeToBreak(ServerLevel level, BlockPos c) {
        BlockState s = level.getBlockState(c);
        if (s.getDestroySpeed(level, c) < 0 || !s.getFluidState().isEmpty()) return false;
        for (Direction dd : Direction.values()) {
            if (!level.getBlockState(c.relative(dd)).getFluidState().isEmpty()) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ scaffolding

    /** Cheap, solid, non-falling blocks only (dirt, stone family, cobble, netherrack, planks), never the block being mined. */
    private static boolean usableScaffold(ItemStack s, MineTask t) {
        if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) return false;
        Block b = bi.getBlock();
        if (b instanceof FallingBlock || b instanceof EntityBlock) return false;
        BlockState st = b.defaultBlockState();
        if (matches(t, st)) return !t.deep();            // surface job: the logs we just chopped are fine (temporary) scaffolding
        return st.is(BlockTags.DIRT) || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(BlockTags.BASE_STONE_NETHER)
                || st.is(BlockTags.PLANKS) || b == Blocks.COBBLESTONE || b == Blocks.COBBLED_DEEPSLATE || b == Blocks.NETHERRACK
                || (!t.deep() && st.is(BlockTags.LOGS));
    }

    private int scaffoldSlot(MineTask t) {
        int best = -1, bestScore = Integer.MAX_VALUE;
        for (int i = CompanionEntity.MAIN_START; i < CompanionEntity.MAIN_START + CompanionEntity.MAIN_SIZE; i++) {
            ItemStack s = mob.storage().getItem(i);
            if (!usableScaffold(s, t)) continue;
            Block b = ((BlockItem) s.getItem()).getBlock();
            int score = (b == Blocks.DIRT || b == Blocks.COBBLESTONE || b == Blocks.COBBLED_DEEPSLATE || b == Blocks.NETHERRACK) ? 0
                    : (matches(t, b.defaultBlockState()) ? 2 : 1);      // our own logs only as a last resort
            if (score < bestScore) { bestScore = score; best = i; }
        }
        return best;
    }

    private boolean hasScaffoldBlock(MineTask t) { return scaffoldSlot(t) >= 0; }

    private ItemStack takeScaffoldBlock(MineTask t) {
        int i = scaffoldSlot(t);
        return i < 0 ? ItemStack.EMPTY : mob.storage().removeItem(i, 1);
    }

    /** The target is above us: walk to the foot of its column if needed, then start pillaring up. */
    private boolean tryScaffold(ServerLevel level, MineTask t, BlockPos target) {
        BlockPos me = mob.blockPosition();
        if (target.getY() - me.getY() < 2) return false;
        if (!hasScaffoldBlock(t)) {
            say(level, "I can't reach that high. Give me some dirt or cobblestone to climb with.", false);
            diag("scaffold: no climbable blocks in bag for " + target);
            return false;
        }
        int tries = approachTries.merge(target.immutable(), 1, Integer::sum);
        if (tries > 8) { diag("scaffold: gave up after " + tries + " tries on " + target); return false; }
        int h = Math.max(Math.abs(target.getX() - me.getX()), Math.abs(target.getZ() - me.getZ()));
        if (h <= 2) {
            if (pillarTarget == null) pillarPlaced = 0;
            pillarTarget = target.immutable();
            jumpCell = null;
            pillarAir = 0;
            return true;
        }
        BlockPos foot = groundBelow(level, t, target).above();
        Path p = mob.getNavigation().createPath(foot, 2);
        if (p == null || !p.canReach()) return false;
        walkTo = foot;
        walkMode = WALK_FREE;
        walkTicks = 0;
        mob.getNavigation().moveTo(p, 1.0);
        return true;
    }

    /** Solid ground under the target's column (skips air, leaves and the mined material itself, e.g. the trunk). */
    private BlockPos groundBelow(ServerLevel level, MineTask t, BlockPos target) {
        BlockPos g = target;
        while (g.getY() > level.getMinY()) {
            BlockState st = level.getBlockState(g);
            if (st.getCollisionShape(level, g).isEmpty() || matches(t, st) || st.is(BlockTags.LEAVES)) g = g.below();
            else break;
        }
        return g;
    }

    /** Jump, and while the feet are above the cell we left, put a block into it; repeat until the target is in reach. */
    private void pillarStep(ServerLevel level, MineTask t, long now) {
        BlockPos target = pillarTarget;
        if (!matches(t, level.getBlockState(target)) || unreachable.contains(target)) { diag("pillar end: target gone"); endPillar(); return; }
        // The reach test MUST use our GROUNDED eye height: mid-jump the eye swings ~1.3 blocks closer, a high
        // target briefly enters reach and pillarStep used to end ("target in reach") every jump BEFORE
        // placing a block - the endless jump-never-places loop (13:18:31-36 in latest.log).
        double feetY = mob.onGround() || jumpCell == null ? mob.getY() : jumpCell.getY();
        Vec3 standEye = new Vec3(mob.getX(), feetY + 1.62, mob.getZ());
        if (standEye.distanceToSqr(Vec3.atCenterOf(target)) <= REACH_SQ) { diag("pillar done: target in standing reach"); endPillar(); return; }
        if (target.getY() <= mob.blockPosition().getY() + 1) { diag("pillar end: target no longer above us"); endPillar(); return; }
        mob.getNavigation().stop();
        if (jumpCell == null) {
            if (!mob.onGround()) return;                                  // wait until we have landed
            if (scaffold.size() >= MAX_SCAFFOLD || pillarPlaced >= MAX_SCAFFOLD) {
                diag("pillar end: scaffold limit " + pillarPlaced);
                say(level, "That's too high to build up to.", true);
                giveUp(target);
                return;
            }
            BlockPos feet = mob.blockPosition();
            for (int up = 2; up <= 3; up++) {                             // headroom for the jump: leaves, branches...
                BlockPos h = feet.above(up);
                if (passable(level, h)) continue;
                if (safeToBreak(level, h)) pending.add(h); else { diag("pillar end: unbreakable headroom at " + h); giveUp(target); }
                return;
            }
            if (!hasScaffoldBlock(t)) {
                say(level, "I need dirt or cobblestone to climb up there.", true);
                diag("pillar end: no scaffold block in bag");
                giveUp(target);
                return;
            }
            jumpCell = feet.immutable();
            pillarAir = 0;
            diag("tower jump #" + pillarPlaced + " from y=" + String.format(java.util.Locale.ROOT, "%.1f", mob.getY())
                    + " to cell y=" + jumpCell.getY());
            mob.getJumpControl().jump();
            return;
        }
        pillarAir++;
        if (mob.getY() >= jumpCell.getY() + 0.9) {                        // feet above the cell we left: place now (apex ~+1.25)
            if (passable(level, jumpCell)) {
                if (!CompanionProtection.canModify(level, mob, jumpCell)) {
                    diag("pillar FAILED: placement refused (protected cell)");
                    giveUp(target);
                    return;
                }
                ItemStack taken = takeScaffoldBlock(t);
                if (taken.isEmpty()) {
                    diag("pillar FAILED: bag ran out of scaffold blocks mid-climb");
                    giveUp(target);
                    return;
                }
                level.setBlock(jumpCell, ((BlockItem) taken.getItem()).getBlock().defaultBlockState(), 3);
                pillarPlaced++;
                if (!t.deep()) scaffold.addLast(jumpCell);        // surface jobs (trees...): temporary, taken back afterwards. Mining: the blocks stay.
                mob.swing(InteractionHand.MAIN_HAND, true);
                lastProgress = now;
                mob.noteMiningProgress();
                diag("pillar: placed block #" + pillarPlaced + " into " + jumpCell);
            } else {
                diag("pillar: cell occupied while airborne (" + jumpCell + ")");
            }
            jumpCell = null;
        } else if (pillarAir > 25) {
            diag("TOWER JUMP DID NOT LIFT: y=" + String.format(java.util.Locale.ROOT, "%.2f", mob.getY())
                    + " cell=" + jumpCell.getY() + " ground=" + mob.onGround() + " water=" + mob.isInWater());
            jumpCell = null;                                              // the jump did not happen (head bump, water): retry
        }
    }

    private void endPillar() {
        pillarTarget = null;
        jumpCell = null;
        pillarAir = 0;
    }

    /** Climbs down by breaking the top block we placed (we stand on it) and takes the blocks back. true = still busy. */
    private boolean cleanupScaffold(ServerLevel level) {
        while (!scaffold.isEmpty() && level.getBlockState(scaffold.peekLast()).isAir()) scaffold.pollLast();
        if (scaffold.isEmpty()) { cleanupWait = 0; return false; }
        BlockPos top = scaffold.peekLast();
        if (!inReach(Vec3.atCenterOf(top))) {
            if (++cleanupWait > 120) { scaffold.clear(); cleanupWait = 0; return false; }   // cannot get back to the tower: leave it
            mob.getNavigation().moveTo(top.getX() + 0.5, top.getY(), top.getZ() + 0.5, 1.0);
            return true;
        }
        cleanupWait = 0;
        startBreak(level, top);
        if (breaking == null) scaffold.pollLast();                        // refused (protected / unbreakable): forget it
        return true;
    }

    /** True when a walkable way down exists (cave slope, natural staircase, pit edge) - navigation walks it, no digging. */
    private boolean walkDownSlope(ServerLevel level) {
        BlockPos me = mob.blockPosition();
        // Several depths: on RESUME the full existing staircase is often deeper than 6 - walking 32 down
        // through the already-dug stairs saves re-descending level by level.
        for (int depth : new int[]{6, 16, 32}) {
            BlockPos down = me.below(depth);
            Path p = mob.getNavigation().createPath(down, 2);
            if (p == null || !p.canReach()) continue;
            walkTo = p.getTarget();
            walkMode = WALK_FREE;
            walkTicks = 0;
            mob.getNavigation().moveTo(p, 1.0);
            lastProgress = level.getGameTime();
            return true;
        }
        return false;
    }

    private void tunnelToward(ServerLevel level, MineTask t, BlockPos target) {
        BlockPos me = mob.blockPosition();
        int dx = target.getX() - me.getX(), dy = target.getY() - me.getY(), dz = target.getZ() - me.getZ();
        if (!t.deep() && dy >= 2) {          // surface job, target high above (top of a tall trunk): pillar or skip, never dig stairs into the ground
            if (!tryScaffold(level, t, target)) giveUp(target);
            return;
        }
        Direction d;
        if (dx == 0 && dz == 0) d = Direction.from2DDataValue(t.dirData);
        else if (Math.abs(dx) >= Math.abs(dz)) d = dx > 0 ? Direction.EAST : Direction.WEST;
        else d = dz > 0 ? Direction.SOUTH : Direction.NORTH;
        int dyStep = dy < -1 ? -1 : dy > 1 ? 1 : 0;
        if (!planStep(level, t, d, dyStep, true)) {
            if (tryScaffold(level, t, target)) return;     // open air / cave: climb with blocks from the bag
            giveUp(target);
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
            List<BlockPos> cells = new ArrayList<>(6);
            cells.add(stand);
            cells.add(stand.above());
            // Strip-mining lanes are 3 TALL: one clear cell above the head, not just 2 (user request - the
            // tunnel currently fits head + body with the ceiling right on top). Lanes only; stairs stay 2.
            if (dyStep == 0 && t.phase == MineTask.Phase.BRANCH) cells.add(stand.above(2));
            if (dyStep < 0) cells.add(stand.above(2));                  // headroom to walk in before dropping
            if (dyStep > 0) {
                // Stairing UP needs headroom above our OWN head first: the head rises through me.above(2)
                // and me.above(3) while jumping into the higher cell. Without these the jump thudded into
                // the rock right overhead ("tries to jump but there's a block above him").
                cells.add(me.above(2));
                cells.add(me.above(3));
            }
            boolean bridge = dyStep == 0 && t.deep() && bridgeRun < 12 && hasScaffoldBlock(t)
                    && level.getBlockState(stand.below()).isAir();      // gap in the floor while mining: bridge it
            if (!safe(level, stand, cells, bridge)) continue;
            for (BlockPos c : cells) if (!passable(level, c)) pending.add(c);
            bridgeCell = bridge ? stand.below().immutable() : null;
            bridgeRun = bridge ? bridgeRun + 1 : 0;
            stepStand = stand;
            t.dirData = d.get2DDataValue();
            return true;
        }
        return false;
    }

    private boolean safe(ServerLevel level, BlockPos stand, List<BlockPos> cells) {
        return safe(level, stand, cells, false);
    }

    private boolean safe(ServerLevel level, BlockPos stand, List<BlockPos> cells, boolean bridge) {
        BlockState floor = level.getBlockState(stand.below());
        if (!floor.getFluidState().isEmpty()) return false;                         // never into liquid
        if (floor.isAir() && !bridge) return false;                                 // would walk into a hole (unless we bridge it)
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

    /** Short block id for diagnostics ("minecraft:stone" -> "stone"). */
    private static String lvlName(Block b) {
        String s = b.toString();
        int i = s.indexOf(':');
        return i < 0 ? s : s.substring(i + 1);
    }

    /**
     * First living entity standing in the straight corridor between us and the walk target (the owner
     * watching from inside the tunnel, another mob). Pathfinding IGNORES entities - it reports
     * reach=true - but a direct move-control push physically cannot pass them: this is the classic
     * "hDist 0.98 -> 0.86 oscillation while nothing blocks me" freeze, with the blocker sitting right
     * at the far lip of the target cell. {@code null} = corridor is clear.
     */
    private String corridorBlocker(ServerLevel level, BlockPos w) {
        double dx = w.getX() + 0.5 - mob.getX(), dz = w.getZ() + 0.5 - mob.getZ();
        var box = mob.getBoundingBox().expandTowards(dx, 0, dz).inflate(0.2);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, o -> o != mob && o.getBbWidth() > 0.35)) {
            return e.getName().getString();
        }
        return null;
    }

    // ------------------------------------------------------------------ breaking and walking

    private void startBreak(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (st.isAir()) return;
        if (!CompanionProtection.canModify(level, mob, pos)) {
            unreachable.add(pos); oreTarget = null; pending.clear(); stepStand = null;
            say(level, "I'm not allowed to break blocks here.", false);
            return;
        }
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
        if (mob.getEyePosition().distanceToSqr(c) > REACH_SQ + 2) {
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

        boolean hit = matches(t, st) && !scaffold.contains(breaking);   // taking our own scaffold back is not a harvest
        BlockPos pos = breaking;
        level.destroyBlockProgress(mob.getId(), pos, -1);
        level.destroyBlock(pos, true, mob, 512);
        breaking = null;
        nextScanAt = 0;      // always look for the next block immediately: this is what stops the "wander after the first tree" bug
        lastProgress = now;
        stuck = 0;
        mob.collectNearbyItems();
        if (hit && !t.deep()) replantAfterChop(level, pos, st);
        if (hit) onMined(level, t, pos);
    }

    private void onMined(ServerLevel level, MineTask t, BlockPos pos) {
        t.mined++;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            BlockPos n = pos.offset(dx, dy, dz);
            if (vein.size() < 24 && !vein.contains(n) && matches(t, level.getBlockState(n))) vein.add(n);
        }
        // Peek BEHIND the block just mined along all 6 axes: an ore 2 further along the axis ("see through the
        // wall I broke") joins the vein; approach()/firstBlocker then digs the stone between us and it.
        // Straight diagonals are already covered by the 3x3x3 growth above.
        for (Direction axis : Direction.values()) {
            BlockPos behind = pos.relative(axis, 2);
            if (vein.size() < 24 && !vein.contains(behind) && matches(t, level.getBlockState(behind))) {
                vein.add(behind);
                diag("peek: " + t.blockId + " seen through the mined block, 2 further " + axis);
            }
        }
        mob.noteMiningProgress();                 // keeps an AI plan step alive; progress is shown in the radial menu, not spoken
        // finishing (and climbing back down) is handled in decide()
    }

    private void walk(ServerLevel level, long now) {
        walkTicks++;
        if (walkTicks == 1) { walkRetries = 0; waitingFor = null; }   // new corridor: the blocker decision starts fresh
        // Progress heartbeat, but only for REAL movement: a mob pushing a wall in place (jam) must not keep
        // refreshing the idle timers, or the watchdogs can never fire and a step-loop stalls silently forever.
        if (walkTicks % 40 == 0 && mob.position().distanceToSqr(lastHeartbeatPos) > 0.25) {
            lastProgress = now;
            stuck = 0;
            lastHeartbeatPos = mob.position();
        }
        BlockPos w = walkTo;
        boolean arrived = switch (walkMode) {
            case WALK_REACH -> inReach(Vec3.atCenterOf(w));     // line of sight is handled by approach(): it digs the blocker out
            case WALK_FREE, WALK_EXPLORE -> mob.distanceToSqr(Vec3.atCenterOf(w)) < 4.0;
            // Must be standing IN the cell: the old 1.2 vertical tolerance let a mob pushing the wall BELOW a
            // 1-block step-up count as "arrived" (dy=1.0) without ever mounting it -> infinite plan-walk loop.
            // The old 0.7 horizontal tolerance was just as lethal: from the center of the PREVIOUS cell
            // (dx=0.7 at the border) the walk cleared early, planStep re-planned the SAME target and legLen
            // grew while the mob never actually crossed - frozen 'destroying and not moving' with tray spam
            // at one cell (log 19:41:03-04, mob=(1861.6-1861.7) vs target x=1862 forever).
            default -> Math.abs(mob.getX() - (w.getX() + 0.5)) < 0.45 && Math.abs(mob.getZ() - (w.getZ() + 0.5)) < 0.45
                    && Math.abs(mob.getY() - w.getY()) < 0.5;
        };
        if (arrived) { walkTo = null; nextScanAt = 0; lastProgress = now; mob.getNavigation().stop(); return; }
        PathNavigation nav = mob.getNavigation();
        // Slope/step stalls: the path finished (or keeps ending) while the body stands still against a ledge.
        // One log per distinct stall state so latest.log shows exactly where and why it stopped moving.
        if (walkTicks > 60 && walkTicks % 40 == 0 && mob.position().distanceToSqr(lastHeartbeatPos) <= 0.25) {
            diag("STUCK walking mode=" + walkMode + " retries=" + walkRetries + " navDone=" + nav.isDone()
                    + " ground=" + mob.onGround() + " dy=" + (w.getY() - mob.getY())
                    + " target=" + w + " at=" + mob.blockPosition());
        }
        if (walkMode == WALK_EXPLORE) {
            // Look around while walking: the moment something matching is in sight, stop exploring and go for it.
            if (walkTicks % 10 == 0) {
                BlockPos found = scan(level, mob.mineTask());
                if (found != null) {
                    oreTarget = found;
                    wanderTries = 0;
                    walkTo = null;
                    nav.stop();
                    lastProgress = now;
                    return;
                }
            }
            if ((walkTicks > 10 && nav.isDone()) || walkTicks > 400) {   // end of path (even a partial one) = this leg is done
                walkTo = null;
                nextScanAt = 0;
            }
            return;
        }
        if (walkMode == WALK_STAND) {
            // Tunnel steps: natural movement only. A freshly cleared step is 1 cell ahead, where the
            // pathfinder often refuses to plan (beside a cactus, tight tunnel corners) - the frozen
            // "walking" state. Re-request the path while it works; if the body stalls, walk the step on
            // direct move control and hop onto a step-up. No teleports anywhere.
            double sdx = w.getX() + 0.5 - mob.getX(), sdz = w.getZ() + 0.5 - mob.getZ();
            double sdy = w.getY() - mob.getY();
            boolean adjacent = Math.abs(sdx) <= 1.6 && Math.abs(sdz) <= 1.6;
            // Progress-based stall: the old positional jitter reset accepted 0.01 wiggles as progress, so an
            // oscillating mob (hDist 0.98 <-> 0.86) never accumulated a stall and never gave up or waited.
            // Only a new BEST distance toward the target counts as movement; oscillation fills the stall clock.
            double hNow = Math.hypot(sdx, sdz);
            if (walkTicks <= 1 || hNow < bestH - 0.02) { bestH = hNow; standStall = 0; } else standStall++;
            // CONTINUOUS DRIVE (log 19:59-20:02: THREE diag lines per single cell, hDist shrinking in
            // bursts): the direct push used to engage only at stall>5 and switch OFF the moment progress
            // reset the stall - a 50% duty cycle (6 dead ticks waiting for stall, 6 pushing) that is
            // exactly the measured ~0.4 blocks/s crawl. The move target is set EVERY tick now; stall
            // counts only drive the diag, the entity wait and the give-up.
            double wcx = w.getX() + 0.5, wcz = w.getZ() + 0.5;
            double hDist = hNow;
            // Step-DOWN: when the target cell is essentially beneath us the wanted position equals our own
            // center and the push has nothing to push toward (the stair-notch lip stalls). Extend the wanted
            // position BEYOND the notch on the far side - walking there forces the body across the hole and
            // gravity does the drop, same as a player stepping into a stair notch.
            if (sdy < -0.3 && hDist < 0.9) {
                double ox = mob.getX() - wcx, oz = mob.getZ() - wcz;
                double ol = Math.sqrt(ox * ox + oz * oz);
                if (ol > 0.05) {
                    ox /= ol; oz /= ol;
                    wcx -= ox * 0.6;
                    wcz -= oz * 0.6;
                }
            }
            mob.getMoveControl().setWantedPosition(wcx, w.getY(), wcz, 1.15);
            if (adjacent && standStall == 6) {
                BlockPos meP = mob.blockPosition();
                var p = mob.getNavigation().createPath(wcx, w.getY(), wcz, 0);
                diag("stand: pathfinder refuses the step, direct walk onto " + w
                        + String.format(" | mob=(%.2f,%.2f,%.2f) hDist=%.2f sdy=%.2f onGround=%s path=%s navDone=%s mode=%s ownerD=%.1f",
                                mob.getX(), mob.getY(), mob.getZ(), hDist, sdy, mob.onGround(),
                                p == null ? "null" : "reach=" + p.canReach(), mob.getNavigation().isDone(),
                                mob.moveMode(), mob.getOwner() == null ? -1 : Math.sqrt(mob.distanceToSqr(mob.getOwner())))
                        + " | target floor=" + lvlName(level.getBlockState(w.below()).getBlock())
                        + " cell=" + passable(level, w) + " head=" + passable(level, w.above())
                        + " | me floor=" + lvlName(level.getBlockState(meP.below()).getBlock())
                        + " cell=" + passable(level, meP) + " head=" + passable(level, meP.above())
                        + "/" + passable(level, meP.above(2)) + "/" + passable(level, meP.above(3))
                        + " | blockedBy=" + String.valueOf(corridorBlocker(level, w)));
            }
            if (adjacent && standStall > 5) {
                // No jump when the head is under a block: a blocked overhead makes the hop a head-thud loop.
                // Hop only while stalled on a step-up - mid-walk jumping would bounce the body off course.
                if (sdy > 0.3 && sdy <= 1.6 && mob.onGround()
                        && passable(level, mob.blockPosition().above(2)) && passable(level, mob.blockPosition().above(3))) {
                    mob.getJumpControl().jump();
                }
                if (standStall > 60) {
                    // An entity (the owner watching, a mob) standing in the last 0.5 blocks of the corridor
                    // is the difference between 'hDist 0.98 -> 0.86 oscillation' and walking in - paths
                    // IGNORE entities (reach=true) but direct pushes cannot pass them. Wait politely instead
                    // of failing the walk 4x and telling the user to move the companion.
                    String blocker = corridorBlocker(level, w);
                    if (blocker == null) {
                        diag("stand: giving up the step onto " + w + " after " + standStall + " stall ticks");
                        failWalk();
                    } else {
                        waitingFor = blocker;
                        if (standStall == 100 || standStall % 400 == 0) {
                            diag("stand: waiting for '" + blocker + "' to move out of the corridor to " + w);
                        }
                        // standStall keeps counting (no reset): the throttled waiting log above relies on it,
                        // and the 30 s outcome watchdog reports the wait to the user in words.
                    }
                }
            }
            if (nav.isDone() && walkTicks % 20 == 0) nav.moveTo(wcx, w.getY(), wcz, 1.15);
            if (walkTicks > 240) {
                if (waitingFor != null) {              // blocked by an entity: waiting is not 'can't get through'
                    walkTicks = 200;                   // checked again in 2 s instead of failing to the pause
                    if (now - lastWaitLog >= 400) { lastWaitLog = now; diag("stand: still waiting for '" + waitingFor + "' to move aside"); }
                } else {
                    failWalk();
                    stuck++;
                    if (stuck >= 4) { mob.mineTask().pause(); say(level, "I can't get through here. Move me and say 'continue'.", true); }
                }
            }
            return;
        }
        // Long walks use the pathfinder: re-request the path when it finished early or could not be made (airborne).
        if (walkTicks % 10 == 1 && nav.isDone() && mob.onGround()) {
            if (++walkRetries > 4) { failWalk(); return; }
            // Ledge hop: the pathfinder marks a 1-block step as reachable but the body just stands against
            // it ("stuck on slopes, can't jump"). One hop on the first retry, then normal re-pathing.
            // No jumping when the head is under a block: that hop is a head-thud, another plan must come.
            if (walkRetries == 1 && passable(level, mob.blockPosition().above(2)) && passable(level, mob.blockPosition().above(3))) {
                double dx = w.getX() + 0.5 - mob.getX(), dz = w.getZ() + 0.5 - mob.getZ();
                double dy = w.getY() - mob.getY();
                if (dx * dx + dz * dz < 3.0 && dy > 0.3 && dy <= 1.6) {
                    diag("ledge hop toward " + w + " (dy=" + String.format(java.util.Locale.ROOT, "%.2f", dy) + ")");
                    mob.getJumpControl().jump();
                }
            }
            nav.moveTo(w.getX() + 0.5, w.getY(), w.getZ() + 0.5, walkMode == WALK_FREE ? 1.0 : 1.1);
        }
        if (walkTicks > 160) failWalk();
    }

    private void failWalk() {
        LOG.warn("[companionmod] walk failed after {}t: mode={} target={} at={} ground={}",
                walkTicks, walkMode, walkTo, mob.blockPosition(), mob.onGround());
        if (walkMode == WALK_REACH && approachTarget != null) giveUp(approachTarget);
        walkTo = null;
        nextScanAt = 0;
    }

    /** Block reach is measured from the eyes (like a player), not from the feet: 1.6 blocks more upward reach. */
    private boolean inReach(Vec3 c) { return mob.getEyePosition().distanceToSqr(c) <= REACH_SQ; }

    /** What the goal is doing right now ("digging", "walking", ...) - shown in the radial hub next to the task. */
    public String stateName() {
        return breaking != null ? "digging" : walkTo != null ? "walking" : pillarTarget != null ? "climbing"
                : !pending.isEmpty() ? "clearing" : "planning";
    }

    /** After felling a trunk base on soil: put a matching sapling from the bag back (silently). */
    private void replantAfterChop(ServerLevel level, BlockPos pos, BlockState chopped) {
        if (!chopped.is(BlockTags.LOGS) || !level.getBlockState(pos.below()).is(BlockTags.DIRT)) return;
        if (!level.getBlockState(pos).isAir()) return;
        String path = BuiltInRegistries.BLOCK.getKey(chopped.getBlock()).getPath();
        int cut = path.indexOf("_log");
        if (cut <= 0) cut = path.indexOf("_wood");
        if (cut <= 0) return;
        String wood = path.substring(0, cut);
        String saplingId = wood.equals("mangrove") ? "mangrove_propagule" : wood + "_sapling";
        for (int i = CompanionEntity.MAIN_START; i < CompanionEntity.MAIN_START + CompanionEntity.MAIN_SIZE; i++) {
            ItemStack s = mob.storage().getItem(i);
            if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) continue;
            if (!BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals(saplingId)) continue;
            BlockState sap = bi.getBlock().defaultBlockState();
            if (!sap.canSurvive(level, pos) || !CompanionProtection.canModify(level, mob, pos)) return;
            mob.storage().removeItem(i, 1);
            level.setBlock(pos, sap, 3);
            return;
        }
    }

    private void cancelBreak(ServerLevel level) {
        if (breaking != null) level.destroyBlockProgress(mob.getId(), breaking, -1);
        breaking = null;
    }

    // ------------------------------------------------------------------ sensing

    /**
     * Nearest match a player could actually see. Exposed blocks (air on at least one face) are preferred, but
     * the bonus is capped at ~12 blocks of distance so a seeable ore far away never hijacks the job from buried
     * ore right next to the companion. Buried blocks only count when close, and the crown height penalty keeps
     * high-up blocks from stealing the target. Columns outside loaded chunks are skipped, never force-loaded.
     */
    private BlockPos scan(ServerLevel level, MineTask t) {
        int r = t.phase == MineTask.Phase.SEARCH ? 22 : 10;
        int down = Math.min(r, 12), up = Math.min(r, 8);
        BlockPos o = mob.blockPosition();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = o.getX() - r; x <= o.getX() + r; x++) {
            for (int z = o.getZ() - r; z <= o.getZ() + r; z++) {
                if (!level.hasChunk(x >> 4, z >> 4)) continue;         // never force-load/generate chunks from a scan
                for (int y = o.getY() - down; y <= o.getY() + up; y++) {
                    m.set(x, y, z);
                    if (!matches(t, level.getBlockState(m)) || unreachable.contains(m)) continue;
                    double d = mob.distanceToSqr(x + 0.5, y + 0.5, z + 0.5);
                    boolean exp = exposed(level, m);
                    if (d > 9.0 && !exp) continue;                      // buried blocks only count when close
                    double score = d + Math.max(0, y - (o.getY() + 2)) * 6.0 - (exp ? Math.min(12.0, d) : 0);
                    if (score >= bestD) continue;
                    bestD = score;
                    best = m.immutable();
                }
            }
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

    /** Cached per-block match (the cache is only touched from the server thread, so a plain HashMap is fine). */
    private static boolean matches(MineTask t, BlockState state) {
        if (state.isAir()) return false;
        if (!t.blockId.equals(matchCacheId)) { MATCH_CACHE.clear(); matchCacheId = t.blockId; }
        return MATCH_CACHE.computeIfAbsent(state.getBlock(), b -> matchesSlow(t.blockId, b));
    }

    private static boolean matchesSlow(String bid, Block block) {
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
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
