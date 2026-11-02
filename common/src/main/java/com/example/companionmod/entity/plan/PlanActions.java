package com.example.companionmod.entity.plan;

import com.example.companionmod.command.CompanionCommand;
import net.minecraft.world.entity.player.Player;

/** What the planner needs from the companion. CompanionEntity implements this by delegating to its existing methods. */
public interface PlanActions {
    Player planOwner();
    /** Same effect as the player giving this order (arg = block id for SEEK_BLOCK, item filter for PICKUP, else ""). */
    void planCommand(CompanionCommand cmd, String arg);
    void planSay(String text);
    boolean planSeekActive();
    boolean planPickupActive();
    default boolean planPlantActive() { return false; }
    boolean planFighting();
    /** Freeze the plan while true (e.g. injured and recovering). */
    default boolean planPaused() { return false; }
    /** True when the player told it to stay/sit: autonomous plans must not override that. */
    default boolean planPlayerHold() { return false; }
}
