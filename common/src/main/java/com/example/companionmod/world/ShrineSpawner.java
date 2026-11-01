package com.example.companionmod.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class ShrineSpawner {
    public static final Block SHRINE_BLOCK = Blocks.NETHER_BRICKS;

    public static BlockPos findShrinePosition(Level level, BlockPos spawnPos) {
        // Search for existing shrine in 32-block radius
        for (int x = -32; x <= 32; x++) {
            for (int y = 0; y < 256; y++) {
                for (int z = -32; z <= 32; z++) {
                    BlockPos pos = new BlockPos(spawnPos.getX() + x, y, spawnPos.getZ() + z);
                    if (level.getBlockState(pos).getBlock() == SHRINE_BLOCK) {
                        return pos;
                    }
                }
            }
        }

        // No shrine found, create one near spawn at Y=64 (above ground)
        BlockPos shrinePos = new BlockPos(spawnPos.getX(), 64, spawnPos.getZ());

        // Check if area is clear
        if (level.isEmptyBlock(shrinePos) && level.isEmptyBlock(shrinePos.north()) &&
            level.isEmptyBlock(shrinePos.south()) && level.isEmptyBlock(shrinePos.east()) &&
            level.isEmptyBlock(shrinePos.west())) {
            return shrinePos;
        }

        // Try a few more positions
        for (int offset = 1; offset <= 10; offset++) {
            shrinePos = new BlockPos(spawnPos.getX() + offset, 64, spawnPos.getZ() + offset);
            if (level.isEmptyBlock(shrinePos) && level.isEmptyBlock(shrinePos.north()) &&
                level.isEmptyBlock(shrinePos.south()) && level.isEmptyBlock(shrinePos.east()) &&
                level.isEmptyBlock(shrinePos.west())) {
                return shrinePos;
            }
        }

        return null;
    }

    public static void placeShrine(Level level, BlockPos pos) {
        level.setBlock(pos, SHRINE_BLOCK.defaultBlockState(), 3);
        level.setBlock(pos.above(), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
    }

    public static boolean isShrineBlock(BlockState state) {
        return state.getBlock() == SHRINE_BLOCK;
    }
}