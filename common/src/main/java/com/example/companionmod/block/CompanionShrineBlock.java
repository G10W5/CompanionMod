package com.example.companionmod.block;

import com.example.companionmod.world.ShrineSummoner;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The shrine: ONE block with a 3D model (plinth, step, pillar, cup with four prongs, floating crystal).
 * Right-click to summon your companion, or call it back if it is already out there.
 * The hitbox below follows the model in models/block/shrine_core.json.
 */
public class CompanionShrineBlock extends Block {
    public static final MapCodec<CompanionShrineBlock> CODEC = simpleCodec(CompanionShrineBlock::new);

    private static final VoxelShape SHAPE = Shapes.or(
            Block.box(1, 0, 1, 15, 2, 15),     // plinth
            Block.box(3, 2, 3, 13, 4, 13),     // step
            Block.box(5, 4, 5, 11, 10, 11),    // pillar
            Block.box(4, 10, 4, 12, 11, 12),   // cup
            Block.box(6, 11, 6, 10, 15, 10));  // crystal + prongs (rounded up to one box)

    public CompanionShrineBlock(Properties properties) { super(properties); }

    @Override
    protected MapCodec<? extends Block> codec() { return CODEC; }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) ShrineSummoner.summon(sl, pos, sp);
        return InteractionResult.SUCCESS;
    }

    /** Little motes rising off the crystal (client side only; this method is only ever called by the client). */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(5) != 0) return;
        level.addParticle(ParticleTypes.END_ROD,
                pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.3,
                pos.getY() + 0.95,
                pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.3,
                0.0, 0.02, 0.0);
    }
}
