package com.example.companionmod.entity.ai;

import com.example.companionmod.entity.CompanionEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/** Walk to the owner when farther than startDist, stop at stopDist, sprint when far. Never teleports: only an explicit "come" order does. */
public class CompanionFollowGoal extends Goal {
    private final CompanionEntity mob;
    private final double speed, startSq, stopSq;
    private Player owner;
    private int recalc, stuck;

    public CompanionFollowGoal(CompanionEntity mob, double speed, double startDist, double stopDist, double teleportDist) {
        this.mob = mob;
        this.speed = speed;
        this.startSq = startDist * startDist;
        this.stopSq = stopDist * stopDist;   // teleportDist kept for callers; teleporting on follow is disabled
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.isInjured() || mob.moveMode() != CompanionEntity.MoveMode.FOLLOW) return false;
        Player o = mob.getOwner();
        if (o == null || o.isSpectator() || o.level() != mob.level()) return false; // cross-dimension follow: phase 2
        owner = o;
        return mob.distanceToSqr(o) >= startSq;
    }

    @Override
    public boolean canContinueToUse() {
        return !mob.isInjured() && mob.moveMode() == CompanionEntity.MoveMode.FOLLOW && owner != null && owner.isAlive()
                && owner.level() == mob.level() && mob.distanceToSqr(owner) > stopSq;
    }

    @Override public void start() { recalc = 0; stuck = 0; }
    @Override public void stop() { mob.getNavigation().stop(); owner = null; }

    @Override
    public void tick() {
        mob.getLookControl().setLookAt(owner, 10.0f, mob.getMaxHeadXRot());
        if (--recalc > 0) return;
        recalc = adjustedTickDelay(5);              // repath more often; it keeps walking, never warps
        double d = mob.distanceToSqr(owner);
        mob.getNavigation().moveTo(owner, d > 144 ? speed * 1.35 : speed);
    }
}
