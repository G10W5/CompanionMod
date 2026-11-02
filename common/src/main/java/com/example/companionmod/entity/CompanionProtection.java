package com.example.companionmod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

/**
 * One place that decides whether the companion may break or place a block. It acts on behalf of its owner, so it
 * respects vanilla spawn protection and the world border. Mods that protect land through player events
 * (claim mods) can only be honoured fully with a fake player; this is the loader-neutral baseline.
 */
public final class CompanionProtection {
    private CompanionProtection() {}

    public static boolean canModify(ServerLevel level, CompanionEntity mob, BlockPos pos) {
        Player owner = mob.getOwner();
        if (owner == null) return false;
        return level.mayInteract(owner, pos);   // 26.1-CHECK: parameter type is Player or Entity; a Player fits both
    }
}
