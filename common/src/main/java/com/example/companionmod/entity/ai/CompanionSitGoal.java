package com.example.companionmod.entity.ai;

import com.example.companionmod.entity.CompanionEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** Holds the companion still while in SIT mode. Higher priority than everything that moves. */
public class CompanionSitGoal extends Goal {
    private final CompanionEntity mob;

    public CompanionSitGoal(CompanionEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP, Flag.LOOK));
    }

    @Override public boolean canUse() { return mob.isSitting(); }
    @Override public boolean canContinueToUse() { return mob.isSitting(); }
    @Override public void start() { mob.getNavigation().stop(); mob.setTarget(null); }
    @Override public boolean isInterruptable() { return false; }
}
