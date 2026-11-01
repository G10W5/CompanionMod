package com.example.companionmod.entity.ai;

import com.example.companionmod.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.FluidState;

import java.util.EnumSet;

/**
 * "Go fishing": walks to the nearest water edge, casts, waits, retrieves and stores the catch.
 * Time-boxed by {@code fishUntil}. There is no real bobber entity - cast/retrieve are sounds plus timing,
 * which keeps it deterministic and multiplayer-safe.
 */
public class CompanionFishGoal extends Goal {
    private static final int CAST_TICKS = 30, MIN_WAIT = 50, MAX_WAIT = 140;

    private final CompanionEntity mob;
    private BlockPos standAt, waterAt;
    private int phase;          // 0 idle, 1 cast, 2 waiting for a bite, 3 retrieve
    private int phaseTicks, scanCooldown, speakCooldown;

    public CompanionFishGoal(CompanionEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean jobRunning() {
        return mob.fishUntil() > mob.level().getGameTime();
    }

    @Override public boolean canUse() {
        return jobRunning() && !mob.isSitting() && !mob.isInjured() && mob.getTarget() == null;
    }

    @Override public boolean canContinueToUse() { return canUse(); }

    @Override
    public void start() {
        phase = 0;
        phaseTicks = 20;
        standAt = waterAt = null;
        scanCooldown = 0;
        speakCooldown = 0;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        if (!jobRunning()) mob.speak("Done fishing.");
    }

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (waterAt == null || level.getFluidState(waterAt).isEmpty()) {
            if (--scanCooldown > 0) return;
            scanCooldown = 40;
            waterAt = findWater(level);
            if (waterAt == null) {
                if (--speakCooldown <= 0) {
                    speakCooldown = 120;
                    mob.speak("I need to be near water to fish.");
                }
                return;
            }
            standAt = findStand(level, waterAt);
            if (standAt == null) { waterAt = null; return; }
        }
        if (mob.distanceToSqr(standAt.getX() + 0.5, standAt.getY(), standAt.getZ() + 0.5) > 2.5 * 2.5) {
            mob.getNavigation().moveTo(standAt.getX() + 0.5, standAt.getY(), standAt.getZ() + 0.5, 1.1);
            return;
        }
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(waterAt.getX() + 0.5, waterAt.getY() + 0.5, waterAt.getZ() + 0.5, 30f, mob.getMaxHeadXRot());

        if (--phaseTicks > 0) return;
        switch (phase) {
            case 0 -> {                                   // idle between casts
                phase = 1;
                phaseTicks = CAST_TICKS;
                mob.swing(InteractionHand.MAIN_HAND);
                mob.playSound(SoundEvents.FISHING_BOBBER_THROW, 1.0f, 1.0f);
            }
            case 1 -> {                                   // bobber is out there
                phase = 2;
                phaseTicks = MIN_WAIT + mob.getRandom().nextInt(MAX_WAIT - MIN_WAIT);
            }
            case 2 -> {                                   // bite!
                phase = 3;
                phaseTicks = 1;
            }
            default -> {                                  // retrieve + catch
                phase = 0;
                phaseTicks = 25 + mob.getRandom().nextInt(20);
                mob.playSound(SoundEvents.FISHING_BOBBER_RETRIEVE, 1.0f, 1.0f);
                catchSomething(level);
            }
        }
    }

    /** A standing cell next to surface water: air here and above, solid floor, water beside. */
    private BlockPos findWater(ServerLevel level) {
        BlockPos centre = mob.anchor();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos s : BlockPos.betweenClosed(centre.offset(-16, -4, -16), centre.offset(16, 4, 16))) {
            if (!level.getBlockState(s).isAir() || !level.getBlockState(s.above()).isAir()) continue;
            if (!level.getBlockState(s.below()).isFaceSturdy(level, s.below(), Direction.UP)) continue;
            if (!nextToSurfaceWater(level, s)) continue;
            double d = mob.distanceToSqr(s.getX() + 0.5, s.getY(), s.getZ() + 0.5);
            if (d < bestD) { bestD = d; best = s.immutable(); }
        }
        return best;
    }

    private boolean nextToSurfaceWater(ServerLevel level, BlockPos s) {
        for (Direction d : Direction.values()) {
            if (!d.getAxis().isHorizontal()) continue;
            BlockPos w = s.relative(d);
            FluidState f = level.getFluidState(w);
            if (!f.isEmpty() && f.typeHolder().is(FluidTags.WATER) && level.getFluidState(w.above()).isEmpty()) {
                waterAt = w.immutable();
                return true;
            }
        }
        return false;
    }

    private BlockPos findStand(ServerLevel level, BlockPos water) {
        for (Direction d : Direction.values()) {
            if (!d.getAxis().isHorizontal()) continue;
            BlockPos s = water.relative(d);
            if (level.getBlockState(s).isAir() && level.getBlockState(s.above()).isAir()
                    && level.getBlockState(s.below()).isFaceSturdy(level, s.below(), Direction.UP)) {
                return s.immutable();
            }
        }
        return null;
    }

    private void catchSomething(ServerLevel level) {
        ItemStack caught;
        String what;
        float r = mob.getRandom().nextFloat();
        if (r < 0.06) { caught = new ItemStack(Items.NAME_TAG); what = "a name tag"; }
        else if (r < 0.10) { caught = new ItemStack(Items.SADDLE); what = "a saddle"; }
        else if (r < 0.13) { caught = new ItemStack(Items.BOWL); what = "an old bowl"; }
        else if (r < 0.48) { caught = new ItemStack(Items.COD); what = "a cod"; }
        else if (r < 0.78) { caught = new ItemStack(Items.SALMON); what = "a salmon"; }
        else if (r < 0.92) { caught = new ItemStack(Items.TROPICAL_FISH); what = "a tropical fish"; }
        else { caught = new ItemStack(Items.PUFFERFISH); what = "a pufferfish"; }
        mob.storeItem(new ItemEntity(level, mob.getX(), mob.getY() + 0.5, mob.getZ(), caught));
        if (--speakCooldown <= 0) {
            speakCooldown = 40;
            String[] lines = {"Caught " + what + "!", "Nice - " + what + "!", "The fish are biting!", "Got " + what + "."};
            mob.speak(lines[mob.getRandom().nextInt(lines.length)]);
        }
    }
}
