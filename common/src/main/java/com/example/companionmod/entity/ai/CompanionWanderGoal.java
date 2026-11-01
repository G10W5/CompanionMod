package com.example.companionmod.entity.ai;

import com.example.companionmod.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;

/** Relaxed wandering around the anchor (and the owner when following is not active). */
public class CompanionWanderGoal extends Goal {
    private final CompanionEntity mob;
    private int cooldown;
    private BlockPos target;

    public CompanionWanderGoal(CompanionEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return mob.moveMode() == CompanionEntity.MoveMode.WANDER && !mob.isSitting() && !mob.isInjured();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        cooldown = 0;
        target = null;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        target = null;
    }

    @Override
    public void tick() {
        if (target != null && !mob.getNavigation().isDone()) {
            mob.getLookControl().setLookAt(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 10.0f, mob.getMaxHeadXRot());
            return;
        }
        if (--cooldown > 0) return;
        cooldown = 40;
        BlockPos anchor = mob.anchor();
        int dx = mob.getRandom().nextIntBetweenInclusive(-11, 11);
        int dz = mob.getRandom().nextIntBetweenInclusive(-11, 11);
        if (Math.abs(dx) < 3 && Math.abs(dz) < 3) dx += 3;
        int x = anchor.getX() + dx;
        int z = anchor.getZ() + dz;
        int y = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        target = new BlockPos(x, y, z);
        if (!mob.getNavigation().moveTo(x + 0.5, y, z + 0.5, 0.85)) target = null;
    }
}
