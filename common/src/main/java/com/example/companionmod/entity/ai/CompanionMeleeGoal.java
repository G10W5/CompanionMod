package com.example.companionmod.entity.ai;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.Vec3;

/**
 * Melee with creeper hit-and-run: while the target's fuse is lit the companion backs off out of blast
 * range instead of trading hits (vanilla creepers defuse when their target gets away), then closes back
 * in once the fuse is out - the classic attack - retreat - attack loop. Every other mob fights normally.
 */
public class CompanionMeleeGoal extends MeleeAttackGoal {
    private static final double RETREAT_DIST = 7.0;    // well past the 3.5 block creeper blast radius

    public CompanionMeleeGoal(PathfinderMob mob, double speed, boolean followingTarget) {
        super(mob, speed, followingTarget);
    }

    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();
        if (target instanceof Creeper creeper && creeper.isAlive() && creeper.getSwellDir() > 0) {
            Vec3 away = this.mob.position().subtract(creeper.position());
            away = new Vec3(away.x, 0, away.z);
            if (away.lengthSqr() < 1.0e-4) away = new Vec3(0, 0, 1);   // standing on top of it: pick a direction
            away = away.normalize().scale(RETREAT_DIST);
            this.mob.getNavigation().moveTo(this.mob.getX() + away.x, this.mob.getY(), this.mob.getZ() + away.z, 1.35);
            this.mob.getLookControl().setLookAt(creeper.getX(), creeper.getY() + creeper.getEyeHeight(), creeper.getZ(), 30.0f, 30.0f);
            return;                               // never walk into a lit fuse
        }
        super.tick();
    }
}
