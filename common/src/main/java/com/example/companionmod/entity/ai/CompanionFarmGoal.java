package com.example.companionmod.entity.ai;

import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.entity.CompanionProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.Map;

/**
 * "Farm for a while": harvests mature crops near the anchor, sweeps the produce into the bag and replants
 * from the bag when seeds are available. Time-boxed by {@code farmUntil} so it never runs forever, and it
 * yields to combat and mining (they are higher-priority goals).
 */
public class CompanionFarmGoal extends Goal {
    private static final int RADIUS = 14;

    /** Crop block -> what replants it (wheat needs seeds, carrots replant with carrots...). */
    private static final Map<Block, Item> SEEDS = Map.of(
            Blocks.WHEAT, Items.WHEAT_SEEDS,
            Blocks.CARROTS, Items.CARROT,
            Blocks.POTATOES, Items.POTATO,
            Blocks.BEETROOTS, Items.BEETROOT_SEEDS,
            Blocks.NETHER_WART, Items.NETHER_WART);

    private final CompanionEntity mob;
    private BlockPos target;
    private int scanCooldown, speakCooldown;
    private boolean reportedDone;

    public CompanionFarmGoal(CompanionEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean jobRunning() {
        return mob.farmUntil() > mob.level().getGameTime();
    }

    @Override public boolean canUse() {
        return jobRunning() && !mob.isSitting() && !mob.isInjured() && mob.getTarget() == null;
    }

    @Override public boolean canContinueToUse() { return canUse(); }

    @Override
    public void start() {
        scanCooldown = 0;
        speakCooldown = 0;
        reportedDone = false;
        target = null;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        if (!reportedDone && !jobRunning()) {
            reportedDone = true;
            mob.speak("Done farming.");
        }
    }

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (target == null || !mature(level, target)) {
            if (--scanCooldown > 0) return;
            scanCooldown = 20;
            target = findCrop(level);
            if (target == null) {
                if (--speakCooldown <= 0) {
                    speakCooldown = 100;
                    mob.speak("No ripe crops around here.");
                }
                return;
            }
        }
        if (mob.distanceToSqr(Vec3.atCenterOf(target)) > 4.5 * 4.5) {
            mob.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.1);
            return;
        }
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, 30f, mob.getMaxHeadXRot());
        BlockState state = level.getBlockState(target);
        BlockPos pos = target;
        target = null;
        if (!(state.getBlock() instanceof CropBlock)) return;
        if (!CompanionProtection.canModify(level, mob, pos)) return;     // spawn protection / protected area
        mob.swing(InteractionHand.MAIN_HAND);
        level.destroyBlock(pos, true, mob, 512);
        mob.collectNearbyItems();
        replant(level, pos, state);
        if (--speakCooldown <= 0) {
            speakCooldown = 60;
            mob.speak(new String[]{"Harvesting crops.", "These fields are looking good.", "Nice and ripe."}[mob.getRandom().nextInt(3)]);
        }
    }

    private boolean mature(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.getBlock() instanceof CropBlock c && c.isMaxAge(s);
    }

    private BlockPos findCrop(ServerLevel level) {
        BlockPos centre = mob.anchor();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(centre.offset(-RADIUS, -3, -RADIUS), centre.offset(RADIUS, 3, RADIUS))) {
            if (!mature(level, p)) continue;
            double d = mob.distanceToSqr(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
            if (d < bestD) { bestD = d; best = p.immutable(); }
        }
        return best;
    }

    /** Replants a just-harvested crop when the matching seed is in the bag. */
    private void replant(ServerLevel level, BlockPos pos, BlockState oldState) {
        Item seed = SEEDS.get(oldState.getBlock());
        if (seed == null) return;
        for (int i = CompanionEntity.MAIN_START; i < CompanionEntity.MAIN_START + CompanionEntity.MAIN_SIZE; i++) {
            if (mob.storage().getItem(i).getItem() == seed) {
                mob.storage().removeItem(i, 1);
                level.setBlock(pos, oldState.getBlock().defaultBlockState(), 3);
                return;
            }
        }
    }
}
