package com.example.companionmod.entity.ai;

import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.entity.CompanionProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * "Plant the saplings": walks around the anchor and plants saplings from the bag on soil, 3 blocks apart and with
 * room to grow. Ends by itself when the bag has no (matching) sapling left or no good spot exists. Never breaks anything.
 */
public class CompanionPlantGoal extends Goal {
    private static final int RADIUS = 12;

    private final CompanionEntity mob;
    private final Set<BlockPos> badSpots = new HashSet<>();
    private BlockPos spot;
    private int scanCooldown, walkTicks, planted;

    public CompanionPlantGoal(CompanionEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean jobRunning() { return mob.plantUntil() > mob.level().getGameTime(); }

    @Override public boolean canUse() { return jobRunning() && !mob.isSitting() && !mob.isInjured() && mob.getTarget() == null; }
    @Override public boolean canContinueToUse() { return canUse(); }

    @Override
    public void start() {
        spot = null;
        scanCooldown = 0;
        walkTicks = 0;
        planted = 0;
        badSpots.clear();
    }

    @Override public void stop() { mob.getNavigation().stop(); }

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel level)) return;
        int slot = saplingSlot();
        if (slot < 0) {
            finish(planted > 0 ? "Planted " + planted + (planted == 1 ? " sapling." : " saplings.")
                    : (mob.plantFilter().isBlank() ? "I don't have any saplings to plant." : "I don't have any " + mob.plantFilter().replace('_', ' ') + " to plant."));
            return;
        }
        BlockState sapling = ((BlockItem) mob.storage().getItem(slot).getItem()).getBlock().defaultBlockState();
        if (spot == null || !valid(level, spot, sapling)) {
            if (--scanCooldown > 0) return;
            scanCooldown = 10;
            spot = findSpot(level, sapling);
            walkTicks = 0;
            if (spot == null) {
                finish(planted > 0 ? "Planted " + planted + (planted == 1 ? " sapling." : " saplings.") : "I can't find a good spot to plant here.");
                return;
            }
        }
        Vec3 c = Vec3.atBottomCenterOf(spot);
        if (mob.distanceToSqr(c) > 6.25) {
            if (++walkTicks > 160) { badSpots.add(spot); spot = null; return; }
            if (mob.getNavigation().isDone() && mob.onGround()) mob.getNavigation().moveTo(c.x, c.y, c.z, 1.0);
            return;
        }
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(c.x, c.y, c.z, 30f, mob.getMaxHeadXRot());
        ItemStack taken = mob.storage().removeItem(slot, 1);
        if (taken.isEmpty()) return;
        level.setBlock(spot, ((BlockItem) taken.getItem()).getBlock().defaultBlockState(), 3);
        mob.swing(InteractionHand.MAIN_HAND);
        planted++;
        spot = null;
    }

    private void finish(String msg) {
        mob.speak(msg);
        mob.endPlant();
    }

    private int saplingSlot() {
        String f = mob.plantFilter();
        for (int i = CompanionEntity.MAIN_START; i < CompanionEntity.MAIN_START + CompanionEntity.MAIN_SIZE; i++) {
            ItemStack s = mob.storage().getItem(i);
            if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) continue;
            if (!bi.getBlock().defaultBlockState().is(BlockTags.SAPLINGS)) continue;
            if (f.isBlank() || BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().contains(f)) return i;
        }
        return -1;
    }

    private BlockPos findSpot(ServerLevel level, BlockState sapling) {
        BlockPos centre = mob.anchor();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(centre.offset(-RADIUS, -3, -RADIUS), centre.offset(RADIUS, 3, RADIUS))) {
            if (!level.getBlockState(p.below()).is(BlockTags.DIRT)) continue;        // cheap test first
            double d = mob.distanceToSqr(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
            if (d >= bestD || !valid(level, p, sapling)) continue;
            bestD = d;
            best = p.immutable();
        }
        return best;
    }

    private boolean valid(ServerLevel level, BlockPos p, BlockState sapling) {
        if (badSpots.contains(p)) return false;
        BlockState here = level.getBlockState(p);
        if (!here.isAir() && !here.canBeReplaced()) return false;                   // air, grass, ferns, snow layers
        if (!level.getBlockState(p.below()).is(BlockTags.DIRT) || !sapling.canSurvive(level, p)) return false;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 0; dy <= 1; dy++) {
            BlockState n = level.getBlockState(p.offset(dx, dy, dz));
            if (n.is(BlockTags.SAPLINGS) || n.is(BlockTags.LOGS)) return false;     // keep trees 3 apart
        }
        for (int dy = 1; dy <= 5; dy++) {                                            // room to grow
            BlockState a = level.getBlockState(p.above(dy));
            if (!a.isAir() && !a.is(BlockTags.LEAVES) && !a.canBeReplaced()) return false;
        }
        return CompanionProtection.canModify(level, mob, p);
    }
}
