package com.example.companionmod.world;

import com.example.companionmod.net.CommandHandler;
import net.minecraft.server.level.ServerPlayer;

/** Everything both loaders must call from their join / leave / stop events, so Fabric and NeoForge behave identically. */
public final class ServerHooks {
    private ServerHooks() {}

    public static void onPlayerJoin(ServerPlayer player) { ShrinePlacer.onPlayerJoin(player); }

    public static void onPlayerLeave(ServerPlayer player) { CommandHandler.forget(player.getUUID()); }

    public static void onServerStopped() {
        CompanionRegistry.reset();
        CommandHandler.forgetAll();
    }
}
