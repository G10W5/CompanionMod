package com.example.companionmod.client;

import com.example.companionmod.entity.CompanionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;

/** A bounded, real client-world observation snapshot for the language model. */
final class CompanionWorldContext {
    private CompanionWorldContext() {}

    static String build(Minecraft mc, CompanionEntity companion) {
        if (mc.player == null || mc.level == null) return "World unavailable.";
        StringBuilder out = new StringBuilder(5000);
        out.append("LIVE MINECRAFT OBSERVATIONS (not suggestions or memories).\n");
        out.append("Dimension=").append(mc.level.dimension())
                .append(" dayTime=").append(mc.level.getOverworldClockTime() % 24000)
                .append(" night=").append(mc.level.isDarkOutside())
                .append(" raining=").append(mc.level.isRaining()).append('\n');
        out.append("Owner name=").append(mc.player.getName().getString())
                .append(" pos=").append(pos(mc.player.blockPosition()))
                .append(" health=").append(Math.round(mc.player.getHealth()))
                .append('/').append(Math.round(mc.player.getMaxHealth()))
                .append(" hunger=").append(mc.player.getFoodData().getFoodLevel())
                .append(" held=").append(item(mc.player.getMainHandItem())).append('\n');
        if (companion == null) {
            out.append("No owned companion is currently loaded.\n");
            return out.toString();
        }
        out.append("Companion name=").append(companion.getName().getString())
                .append(" pos=").append(pos(companion.blockPosition()))
                .append(" health=").append(Math.round(companion.getHealth()))
                .append('/').append(Math.round(companion.getMaxHealth()))
                .append(" moveMode=").append(companion.moveMode())
                .append(" stance=").append(companion.stance())
                .append(" injured=").append(companion.isInjured())
                .append(" sitting=").append(companion.isSitting())
                .append(" light=").append(mc.level.getMaxLocalRawBrightness(companion.blockPosition()))
                .append(" held=").append(item(companion.getMainHandItem())).append('\n');
        out.append("Current tasks: mine=").append(companion.seekBlock())
                .append(" pickupRequested=").append(companion.isPickupRequested())
                .append(" pickupEnabled=").append(companion.isPickupEnabled())
                .append(" farmUntil=").append(companion.farmUntil())
                .append(" fishUntil=").append(companion.fishUntil())
                .append(" build=").append(companion.buildSchematic())
                .append(" gameTime=").append(mc.level.getGameTime()).append('\n');

        out.append("Companion inventory:\n");
        for (int i = 0; i < companion.storage().getContainerSize(); i++) {
            ItemStack stack = companion.storage().getItem(i);
            if (!stack.isEmpty()) out.append(" slot ").append(i).append(' ').append(item(stack)).append('\n');
        }

        out.append("Nearby entities (absolute positions):\n");
        int entityCount = 0;
        for (Entity entity : mc.level.getEntitiesOfClass(Entity.class,
                companion.getBoundingBox().inflate(24), e -> e != companion && e != mc.player)) {
            if (++entityCount > 28) break;
            String category = entity instanceof Monster ? "hostile"
                    : entity instanceof Animal ? "animal"
                    : entity instanceof ItemEntity ? "drop" : "other";
            out.append(' ').append(category).append(' ')
                    .append(EntityType.getKey(entity.getType()))
                    .append(" pos=").append(pos(entity.blockPosition()));
            if (entity instanceof ItemEntity drop) out.append(" item=").append(item(drop.getItem()));
            out.append('\n');
        }
        if (entityCount == 0) out.append(" none within 24 blocks\n");

        out.append("Useful nearby blocks (absolute positions):\n");
        int blockCount = 0;
        BlockPos centre = companion.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(centre.offset(-16, -7, -16), centre.offset(16, 7, 16))) {
            BlockState state = mc.level.getBlockState(p);
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (!interesting(id)) continue;
            out.append(' ').append(id).append(" pos=").append(pos(p)).append('\n');
            if (++blockCount >= 36) {
                out.append(" block list truncated; explore or move for more observations\n");
                break;
            }
        }
        if (blockCount == 0) out.append(" none detected in the 33x15x33 scan\n");
        out.append("Use only observed IDs and supported plan operations. Never invent items, blocks, entities or capabilities.\n");
        return out.toString();
    }

    private static boolean interesting(String id) {
        String path = id.toLowerCase(Locale.ROOT);
        return path.contains("_ore") || path.endsWith("_log") || path.endsWith("_wood")
                || path.contains("leaves") || path.contains("farmland")
                || path.equals("minecraft:wheat") || path.equals("minecraft:carrots")
                || path.equals("minecraft:potatoes") || path.equals("minecraft:beetroots")
                || path.equals("minecraft:water") || path.equals("minecraft:lava")
                || path.contains("chest") || path.contains("furnace")
                || path.equals("minecraft:crafting_table");
    }

    private static String item(ItemStack stack) {
        return stack.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount();
    }

    private static String pos(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }
}
