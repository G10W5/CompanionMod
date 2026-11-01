package com.example.companionmod.entity.ai;

import com.example.companionmod.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;

/** Walks a ring of waypoints around the anchor while in PATROL mode. */
public class CompanionPatrolGoal extends Goal {
    private final CompanionEntity mob;
    private int cooldown;

    public CompanionPatrolGoal(CompanionEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return mob.moveMode() == CompanionEntity.MoveMode.PATROL && !mob.isSitting() && !mob.isInjured();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        mob.setPatrolIndex(0);
        cooldown = 0;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (--cooldown > 0) return;
        cooldown = 10;
        BlockPos anchor = mob.anchor();
        int index = mob.patrolIndex();
        double angle = index * Math.PI / 4.0;
        int x = anchor.getX() + (int) Math.round(Math.cos(angle) * 9.0);
        int z = anchor.getZ() + (int) Math.round(Math.sin(angle) * 9.0);
        int y = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        boolean moved = mob.getNavigation().moveTo(x + 0.5, y, z + 0.5, 1.0);
        if (!moved || mob.getNavigation().isDone()) {
            mob.setPatrolIndex((index + 1) % 8);
        } else if (mob.distanceToSqr(x + 0.5, y, z + 0.5) < 6.0) {
            mob.setPatrolIndex((index + 1) % 8);
        }
    }
}
