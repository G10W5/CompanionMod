package com.example.companionmod.world;

import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

public final class ShrineSummoner {
    private ShrineSummoner() {}

    /** One companion per player: reuse the living one (calling it to the shrine) or create a new one. */
    public static void summon(ServerLevel level, BlockPos shrine, ServerPlayer player) {
        Vec3 spot = findSpot(level, shrine);
        CompanionEntity existing = ModContent.findCompanion(level.getServer(), player.getUUID());

        if (existing != null && existing.level() == level) {
            existing.setPos(spot.x, spot.y, spot.z);
            existing.getNavigation().stop();
            player.sendSystemMessage(Component.literal(existing.getName().getString() + " answers the shrine's call."));
            effects(level, spot);
            return;
        }
        if (existing != null) existing.discard();   // it was in another dimension: replace it (inventory/progress persistence is phase 2)

        CompanionEntity c = ModContent.COMPANION.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (c == null) return;
        c.setPos(spot.x, spot.y, spot.z);
        c.setYRot(player.getYRot() + 180f);
        c.setOwner(player.getUUID());
        c.setCustomName(Component.literal("Companion"));
        c.setCustomNameVisible(true);
        c.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        level.addFreshEntity(c);
        player.sendSystemMessage(Component.literal("A companion steps out of the light. Hold V to talk to it."));
        effects(level, spot);
    }

    private static void effects(ServerLevel level, Vec3 at) {
        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 1.0, at.z, 40, 0.35, 0.8, 0.35, 0.03);
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, at.x, at.y + 0.1, at.z, 15, 0.4, 0.05, 0.4, 0.02);
        level.playSound(null, BlockPos.containing(at), SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0f, 1.3f);
    }

    /** A free 2-high spot beside the shrine (preferring the four sides), else on top. */
    private static Vec3 findSpot(ServerLevel level, BlockPos shrine) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = shrine.relative(d);
            if (free(level, p) && free(level, p.above())) return Vec3.atBottomCenterOf(p);
        }
        return Vec3.atBottomCenterOf(shrine.above());
    }

    private static boolean free(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }
}
