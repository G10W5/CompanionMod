package com.example.companionmod.entity.ai;

import com.example.companionmod.entity.CompanionEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.EnumSet;
import java.util.List;

/**
 * Walks to dropped items and stores them in the companion inventory.
 *
 * <p>An explicit "pick up this bone" order sets a filter and {@code pickupRequested}. The goal then
 * targets that exact item and walks to it — it never opens the inventory screen. With the general
 * pickup toggle on, it hoovers up every nearby drop instead.</p>
 */
public class CompanionPickupGoal extends Goal {
    private final CompanionEntity mob;
    private ItemEntity item;
    private int cooldown;
    private boolean storedAny;

    public CompanionPickupGoal(CompanionEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.isInjured() || mob.isSitting()) return false;
        if (!mob.isPickupEnabled() && !mob.isPickupRequested()) return false;
        if (--cooldown > 0) return false;
        cooldown = 10;
        item = nearestItem();
        return item != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (mob.isInjured() || mob.isSitting()) return false;
        if (!mob.isPickupEnabled() && !mob.isPickupRequested()) return false;
        if (item == null || !item.isAlive() || item.getItem().isEmpty()) {
            item = nearestItem();
        }
        return item != null;
    }

    @Override
    public void start() {
        storedAny = false;
        if (item != null) mob.getNavigation().moveTo(item, 1.15);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        item = null;
        if (mob.isPickupRequested()) mob.finishPickup(storedAny);
    }

    @Override
    public void tick() {
        if (item == null || !item.isAlive() || item.getItem().isEmpty()) {
            item = nearestItem();
            if (item == null) return;
            mob.getNavigation().moveTo(item, 1.15);
            return;
        }

        double distSq = mob.distanceToSqr(item);
        if (distSq <= 4.0) {
            boolean stored = mob.storeItem(item);
            if (stored) {
                storedAny = true;
                mob.collectNearbyItems();          // grab the rest of the same drop pile
            } else {
                mob.speak("My bag is full.");
                mob.finishPickup(true);
                item = null;
                return;
            }
            item = null;
            if (mob.isPickupRequested() && nearestItem() == null) mob.finishPickup(storedAny);
            return;
        }
        if (mob.getNavigation().isDone()) mob.getNavigation().moveTo(item, 1.15);
        mob.getLookControl().setLookAt(item, 10f, mob.getMaxHeadXRot());
    }

    private ItemEntity nearestItem() {
        double radius = mob.isPickupRequested() ? 12.0 : 10.0;
        List<ItemEntity> items = mob.level().getEntitiesOfClass(ItemEntity.class,
                mob.getBoundingBox().inflate(radius),
                e -> e.isAlive() && !e.getItem().isEmpty() && mob.matchesPickup(e));
        ItemEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (ItemEntity e : items) {
            double d = mob.distanceToSqr(e);
            if (d < bestD) { bestD = d; best = e; }
        }
        return best;
    }
}
