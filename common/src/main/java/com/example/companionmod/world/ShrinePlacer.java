package com.example.companionmod.world;

import com.example.companionmod.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds the shrine procedurally (no datapack structure needed) and remembers it with a tiny marker file in the world folder,
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
        BlockPos core = place(level, player.blockPosition().offset(6, 0, 6));
        try {
            Files.writeString(file, "{\"x\":" + core.getX() + ",\"y\":" + core.getY() + ",\"z\":" + core.getZ() + "}");
        } catch (IOException ignored) { }
        player.sendSystemMessage(Component.literal("A strange shrine hums near spawn at " + core.getX() + " " + core.getY() + " " + core.getZ() + "."));
    }

    /** Places the shrine so its core stands on the surface at (origin.x, origin.z). Returns the core block position. */
    public static BlockPos place(ServerLevel level, BlockPos origin) {
        int cx = origin.getX(), cz = origin.getZ();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);   // first free block above the ground
        RandomSource rnd = level.getRandom();
        BlockState bricks = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState mossy = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
        BlockState cracked = Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
        BlockState rim = Blocks.DEEPSLATE_BRICKS.defaultBlockState();

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                BlockPos floor = new BlockPos(cx + dx, y - 1, cz + dz);
                boolean edge = Math.abs(dx) == 3 || Math.abs(dz) == 3;
                float r = rnd.nextFloat();
                level.setBlock(floor, edge ? rim : (r < 0.2f ? mossy : r < 0.35f ? cracked : bricks), 3);
                for (int h = 0; h < 5; h++) level.setBlock(floor.above(1 + h), Blocks.AIR.defaultBlockState(), 2);
                for (int d = 1; d <= 2; d++) {                                  // fill small holes under the platform
                    BlockPos under = floor.below(d);
                    if (level.getBlockState(under).canBeReplaced()) level.setBlock(under, bricks, 2);
                }
            }
        }
        for (int sx : new int[]{-3, 3}) for (int sz : new int[]{-3, 3}) {        // corner pillars with soul lanterns
            for (int h = 0; h < 3; h++) level.setBlock(new BlockPos(cx + sx, y + h, cz + sz), Blocks.STONE_BRICK_WALL.defaultBlockState(), 3);
            level.setBlock(new BlockPos(cx + sx, y + 3, cz + sz), Blocks.SOUL_LANTERN.defaultBlockState(), 3);
        }
        BlockPos[] lamps = {new BlockPos(cx + 2, y, cz), new BlockPos(cx - 2, y, cz), new BlockPos(cx, y, cz + 2), new BlockPos(cx, y, cz - 2)};
        for (BlockPos p : lamps) level.setBlock(p, Blocks.SOUL_LANTERN.defaultBlockState(), 3);

        BlockPos core = new BlockPos(cx, y, cz);
        level.setBlock(core, ModContent.SHRINE_CORE.defaultBlockState(), 3);
        return core;
    }
}
