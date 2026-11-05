package com.example.companionmod.build;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Bridge between the plain-string Blk and real BlockStates / items. */
public final class BlkState {
    private BlkState() {}

    /** Real registry check: model hallucinations (unknown blocks) fall back to defaults. */
    public static final BlockPolicy POLICY = id -> {
        Identifier loc = Identifier.tryParse(id);
        return loc != null && BuiltInRegistries.BLOCK.getOptional(loc).isPresent();
    };

    /** Null when the block does not exist or is air. Unknown properties are ignored, bad values keep the default. */
    public static BlockState toState(Blk b) {
        Identifier loc = Identifier.tryParse(b.id());
        if (loc == null) return null;
        Block block = BuiltInRegistries.BLOCK.getOptional(loc).orElse(null);
        if (block == null || block == Blocks.AIR) return null;
        BlockState s = block.defaultBlockState();
        for (var e : b.props().entrySet()) {
            Property<?> p = block.getStateDefinition().getProperty(e.getKey());
            if (p != null) s = withValue(s, p, e.getValue());
        }
        return s;
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState s, Property<T> p, String v) {
        return p.getValue(v).map(x -> s.setValue(p, x)).orElse(s);
    }

    /** Registry id of the item that places this block ("minecraft:oak_door"), or null if there is none. */
    public static String itemId(Blk b) {
        BlockState s = toState(b);
        if (s == null) return null;
        Item item = s.getBlock().asItem();
        if (item == Items.AIR) return null;
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    public static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
