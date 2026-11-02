package com.example.companionmod.world;

import com.example.companionmod.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Places the shrine (a single block with a 3D model) and remembers it with a tiny marker file in the world folder,
 * so it is placed exactly once: next to the first player that ever joins a world, which on a fresh world is the spawn point.
 */
public final class ShrinePlacer {
    private ShrinePlacer() {}

    private static Path marker(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("companionmod_shrine.json");
    }

    public static void onPlayerJoin(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        MinecraftServer server = level.getServer();
        Path file = marker(server);
        if (Files.exists(file)) return;
        BlockPos core = place(level, player.blockPosition().offset(3, 0, 2));
        try {
            Files.writeString(file, "{\"x\":" + core.getX() + ",\"y\":" + core.getY() + ",\"z\":" + core.getZ() + "}");
        } catch (IOException ignored) { }
        player.sendSystemMessage(Component.literal("A strange shrine hums near spawn at " + core.getX() + " " + core.getY() + " " + core.getZ() + "."));
    }

    /** Puts the shrine block on the surface at (origin.x, origin.z). Returns its position. */
    public static BlockPos place(ServerLevel level, BlockPos origin) {
        int x = origin.getX(), z = origin.getZ();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);   // first free block above the ground
        BlockPos core = new BlockPos(x, y, z);
        BlockPos below = core.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {   // water, leaves, a hole: give it footing
            level.setBlock(below, Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 3);
        }
        level.setBlock(core, ModContent.SHRINE_CORE.defaultBlockState(), 3);
        return core;
    }
}
