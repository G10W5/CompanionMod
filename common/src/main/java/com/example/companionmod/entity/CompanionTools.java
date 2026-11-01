package com.example.companionmod.entity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** The single source of truth for the four dedicated equipment slots. */
public final class CompanionTools {
    public enum Kind {
        PICKAXE(0, "Pickaxe", "_pickaxe"),
        AXE(1, "Axe", "_axe"),
        SHOVEL(2, "Shovel", "_shovel"),
        SWORD(3, "Sword", "_sword");

        public final int slotOffset;
        public final String label;
        private final String suffix;

        Kind(int slotOffset, String label, String suffix) {
            this.slotOffset = slotOffset;
            this.label = label;
            this.suffix = suffix;
        }
    }

    private CompanionTools() {}

    public static String itemId(ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static Kind kindOf(ItemStack stack) {
        String path = itemId(stack);
        for (Kind kind : Kind.values()) {
            if (path.endsWith(kind.suffix)) return kind;
        }
        return null;
    }

    public static boolean isDedicatedTool(ItemStack stack) {
        return kindOf(stack) != null;
    }

    public static int dedicatedSlot(ItemStack stack) {
        Kind kind = kindOf(stack);
        return kind == null ? -1 : CompanionEntity.TOOL_START + kind.slotOffset;
    }

    public static Kind preferredKind(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) return Kind.PICKAXE;
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) return Kind.AXE;
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) return Kind.SHOVEL;
        return null;
    }

    /** Searches the tool row and storage, so overflow tools are still usable. */
    public static int bestToolIndex(Container inventory, BlockState state) {
        Kind preferred = preferredKind(state);
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            Kind kind = kindOf(stack);
            if (kind == null) continue;
            float speed = Math.max(1.0F, stack.getDestroySpeed(state));
            double score = speed * (kind == preferred ? 1000.0 : 1.0);
            if (i >= CompanionEntity.TOOL_START && i < CompanionEntity.MAIN_START) score += 0.01;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** First melee-worthy item: any sword anywhere, or anything the player parked in the tool row (slots are universal). */
    public static int bestSwordIndex(Container inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (kindOf(inventory.getItem(i)) == Kind.SWORD) return i;
        }
        for (int i = CompanionEntity.TOOL_START; i < CompanionEntity.MAIN_START; i++) {
            if (!inventory.getItem(i).isEmpty()) return i;
        }
        return -1;
    }
}
