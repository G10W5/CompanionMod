package com.example.companionmod.block;

import com.example.companionmod.world.ShrineSummoner;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Right-click to summon your companion, or call it back if it is already out there. */
public class CompanionShrineBlock extends Block {
    public static final MapCodec<CompanionShrineBlock> CODEC = simpleCodec(CompanionShrineBlock::new);

    public CompanionShrineBlock(Properties properties) { super(properties); }

    @Override
    protected MapCodec<? extends Block> codec() { return CODEC; }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) ShrineSummoner.summon(sl, pos, sp);
        return InteractionResult.SUCCESS;
    }
}
