package com.example.companionmod.entity;

import com.example.companionmod.build.BlkState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a build gets its blocks: the companion's bag first, then chests / barrels / shulker boxes within
 * {@link #RADIUS} blocks of the build site. Counts are cached (re-counted every second) so the build loop can ask
 * "do I have 3 spruce stairs?" hundreds of times per tick without walking every slot.
 */
public final class BlueprintSupply {
    private static final int RADIUS = 10, HEIGHT = 6, RESCAN_CONTAINERS_TICKS = 100, RECOUNT_TICKS = 20;

    private final CompanionEntity mob;
    private final List<BlockEntity> containers = new ArrayList<>();
    private final Map<String, Integer> counts = new HashMap<>();
    private BlockPos center = BlockPos.ZERO;
    private boolean scanned, dirty = true;
    private long scannedAt, countedAt;

    public BlueprintSupply(CompanionEntity mob) { this.mob = mob; }

    public void setCenter(BlockPos c) {
        center = c;
        scanned = false;
        dirty = true;
    }

    /** Call once per tick before asking for counts. */
    public void beginTick(ServerLevel level) {
        long now = level.getGameTime();
        if (!scanned || now - scannedAt >= RESCAN_CONTAINERS_TICKS) {
            scanned = true;
            scannedAt = now;
            containers.clear();
            for (BlockPos p : BlockPos.betweenClosed(center.offset(-RADIUS, -HEIGHT, -RADIUS), center.offset(RADIUS, HEIGHT, RADIUS))) {
                if (!level.hasChunkAt(p)) continue;        // 26.1-CHECK: Level.hasChunkAt(BlockPos)
                BlockEntity be = level.getBlockEntity(p);
                if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity) {
                    containers.add(be);
                }
            }
            dirty = true;
        }
        if (dirty || now - countedAt >= RECOUNT_TICKS) {
            countedAt = now;
            dirty = false;
            counts.clear();
            for (int i = CompanionEntity.MAIN_START; i < CompanionEntity.MAIN_START + CompanionEntity.MAIN_SIZE; i++) add(mob.storage().getItem(i));
            for (BlockEntity be : containers) {
                if (be.isRemoved() || !(be instanceof Container c)) continue;
                for (int i = 0; i < c.getContainerSize(); i++) add(c.getItem(i));
            }
        }
    }

    private void add(ItemStack s) {
        if (!s.isEmpty()) counts.merge(BlkState.itemId(s.getItem()), s.getCount(), Integer::sum);
    }

    public int have(String itemId) { return counts.getOrDefault(itemId, 0); }

    /** Fresh copy of everything available (bag + chests), keyed by item id. */
    public Map<String, Integer> snapshot() { return new HashMap<>(counts); }

    /** Removes {@code n} of the item: bag first, then chests. Only decrements what was actually available. */
    public void take(String itemId, int n) {
        int taken = 0;
        while (taken < n && takeOne(itemId)) taken++;
        if (taken > 0) counts.merge(itemId, -taken, Integer::sum);
    }

    private boolean takeOne(String itemId) {
        for (int i = CompanionEntity.MAIN_START; i < CompanionEntity.MAIN_START + CompanionEntity.MAIN_SIZE; i++) {
            ItemStack s = mob.storage().getItem(i);
            if (!s.isEmpty() && BlkState.itemId(s.getItem()).equals(itemId)) {
                mob.storage().removeItem(i, 1);
                return true;
            }
        }
        for (BlockEntity be : containers) {
            if (be.isRemoved() || !(be instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty() && BlkState.itemId(s.getItem()).equals(itemId)) {
                    c.removeItem(i, 1);
                    c.setChanged();
                    return true;
                }
            }
        }
        return false;
    }
}
