package com.example.companionmod.registry;

import com.example.companionmod.CompanionMod;
import com.example.companionmod.block.CompanionShrineBlock;
import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.inventory.CompanionInventoryMenu;
import com.example.companionmod.world.CompanionRegistry;
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
 * All game objects, created once. The loaders only register them (Fabric: Registry.register in order block -> item -> entity -> menu;
 * NeoForge: inside RegisterEvent per registry). Nothing in here touches a loader API.
 */
public final class ModContent {
    private static final ResourceKey<Block> BLOCK_KEY = ResourceKey.create(Registries.BLOCK, CompanionMod.id("shrine_core"));
    private static final ResourceKey<Item> ITEM_KEY = ResourceKey.create(Registries.ITEM, CompanionMod.id("shrine_core"));
    private static final ResourceKey<EntityType<?>> ENTITY_KEY = ResourceKey.create(Registries.ENTITY_TYPE, CompanionMod.id("companion"));

    /** One block, 3D model (assets/.../models/block/shrine_core.json). Unbreakable in survival: the shrine is the mod's anchor. */
    public static final Block SHRINE_CORE = new CompanionShrineBlock(
            BlockBehaviour.Properties.of().setId(BLOCK_KEY).mapColor(MapColor.COLOR_CYAN)
                    .strength(-1.0f, 3600000.0f).noOcclusion().sound(SoundType.AMETHYST).lightLevel(s -> 12)); // 26.1-CHECK: setId is mandatory since 1.21.2

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

    /**
     * Finds the player's loaded companion. The world registry gives its UUID, so this is an O(1) lookup per level
     * instead of scanning every entity. Worlds without a registry entry yet fall back to one scan and adopt the result.
     * Returns null when there is none, or when the registered one sits in an unloaded chunk.
     */
    public static CompanionEntity findCompanion(MinecraftServer server, UUID owner) {
        if (server == null || owner == null) return null;
        UUID known = CompanionRegistry.get(server, owner);
        if (known != null) {
            for (ServerLevel level : server.getAllLevels()) {
                if (level.getEntity(known) instanceof CompanionEntity c && c.isAlive()) return c;
            }
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) { // 26.1-CHECK: ServerLevel.getAllEntities()
                if (e instanceof CompanionEntity c && c.isAlive() && owner.equals(c.getOwnerUuid())) {
                    CompanionRegistry.set(server, owner, c.getUUID());
                    return c;
                }
            }
        }
        return null;
    }
}
