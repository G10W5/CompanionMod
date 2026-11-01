package com.example.companionmod.registry;

import com.example.companionmod.CompanionMod;
import com.example.companionmod.block.CompanionShrineBlock;
import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.inventory.CompanionInventoryMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import java.util.UUID;

/**
 * All game objects, created once. The loaders only register them (Fabric: Registry.register in order block -> item -> entity;
 * NeoForge: inside RegisterEvent per registry). Nothing in here touches a loader API.
 */
public final class ModContent {
    private static final ResourceKey<Block> BLOCK_KEY = ResourceKey.create(Registries.BLOCK, CompanionMod.id("shrine_core"));
    private static final ResourceKey<Item> ITEM_KEY = ResourceKey.create(Registries.ITEM, CompanionMod.id("shrine_core"));
    private static final ResourceKey<EntityType<?>> ENTITY_KEY = ResourceKey.create(Registries.ENTITY_TYPE, CompanionMod.id("companion"));

    public static final Block SHRINE_CORE = new CompanionShrineBlock(
            BlockBehaviour.Properties.of().setId(BLOCK_KEY).mapColor(MapColor.COLOR_CYAN)
                    .strength(3.0f, 1200.0f).sound(SoundType.AMETHYST).lightLevel(s -> 12)); // 26.1-CHECK: setId is mandatory since 1.21.2

    public static final Item SHRINE_CORE_ITEM = new BlockItem(SHRINE_CORE,
            new Item.Properties().setId(ITEM_KEY).useBlockDescriptionPrefix());

    public static final EntityType<CompanionEntity> COMPANION = EntityType.Builder
            .of(CompanionEntity::new, MobCategory.MISC)
            .sized(0.6f, 1.8f).eyeHeight(1.62f).clientTrackingRange(10)
            .build(ENTITY_KEY);

    private static final ResourceKey<MenuType<?>> MENU_KEY = ResourceKey.create(Registries.MENU, CompanionMod.id("companion_inventory"));
    /** The supplier is the client-side factory (shadow container); the server creates menus with the entity directly. */
    public static final MenuType<CompanionInventoryMenu> COMPANION_INV_MENU = new MenuType<>(
            CompanionInventoryMenu::new, FeatureFlags.VANILLA_SET);

    private ModContent() {}

    public static net.minecraft.resources.Identifier blockId() { return BLOCK_KEY.identifier(); }   // 26.1-CHECK: ResourceKey.location() -> identifier()
    public static net.minecraft.resources.Identifier itemId() { return ITEM_KEY.identifier(); }
    public static net.minecraft.resources.Identifier entityId() { return ENTITY_KEY.identifier(); }
    public static net.minecraft.resources.Identifier menuId() { return MENU_KEY.identifier(); }

    /** Finds the loaded companion owned by {@code owner} in any dimension, or null. */
    public static CompanionEntity findCompanion(MinecraftServer server, UUID owner) {
        if (server == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) { // 26.1-CHECK: ServerLevel.getAllEntities()
                if (e instanceof CompanionEntity c && c.isAlive() && owner.equals(c.getOwnerUuid())) return c;
            }
        }
        return null;
    }
}
