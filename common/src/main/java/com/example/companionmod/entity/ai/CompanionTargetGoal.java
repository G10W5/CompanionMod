package com.example.companionmod.entity.ai;

import com.example.companionmod.entity.CompanionEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** Thin wrapper: the decision logic lives in {@link CompanionEntity#chooseTarget()} so commands and AI share one source of truth. */
public class CompanionTargetGoal extends Goal {
    private final CompanionEntity mob;
    private LivingEntity pending;
    private int cooldown;

    public CompanionTargetGoal(CompanionEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (mob.isInjured()) return false;
        if (--cooldown > 0) return false;
        cooldown = 8;
        pending = mob.chooseTarget();
        return pending != null;
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity t = mob.getTarget();
        if (t == null || !t.isAlive() || mob.isSitting() || mob.isInjured() || mob.stance() == CompanionEntity.Stance.PASSIVE) return false;
        var owner = mob.getOwner();
        // No wall hacking: drop hidden targets unless they are fighting us/owner, or the player ordered them explicitly.
        if (!mob.isForced(t) && !mob.getSensing().hasLineOfSight(t)) {
            boolean aggro = t instanceof net.minecraft.world.entity.Mob m
                    && (m.getTarget() == mob || (owner != null && m.getTarget() == owner));
            if (!aggro) return false;
        }
        double leash = mob.moveMode() == CompanionEntity.MoveMode.STAY ? 12 : 28;
        var anchor = (owner != null && mob.moveMode() == CompanionEntity.MoveMode.FOLLOW) ? owner : mob;
        return anchor.distanceToSqr(t) < leash * leash;
    }

    @Override public void start() { mob.setTarget(pending); }

    @Override
    public void stop() {
        mob.setTarget(null);
        mob.getNavigation().stop();
    }
}
