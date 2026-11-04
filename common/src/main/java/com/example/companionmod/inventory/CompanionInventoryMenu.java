package com.example.companionmod.inventory;

import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.entity.CompanionTools;
import com.example.companionmod.registry.ModContent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;

/**
 * The companion's 35-slot bag: 4 armor slots, 4 tool slots and 27 storage slots, plus the player's
 * inventory. The server menu wraps the companion's live container; the client menu (created from the
 * {@link MenuType} supplier) uses a synced shadow container. Armor/tool row changes are mirrored onto
 * the companion's equipment slots by {@link CompanionEntity#refreshEquipment()}.
 */
public class CompanionInventoryMenu extends AbstractContainerMenu {
    public static final int SIZE = CompanionEntity.SIZE;          // 35
    private static final int PLAYER_SLOTS = 36;

    private final Container storage;
    private final CompanionEntity companion;   // null on the client

    /** Server side: opens the companion's live container. */
    public CompanionInventoryMenu(int id, Inventory playerInventory, CompanionEntity companion) {
        this(id, playerInventory, companion.storage(), companion);
    }

    /** Client side (called from the MenuType supplier): shadow container filled by slot sync. */
    public CompanionInventoryMenu(int id, Inventory playerInventory) {
        this(id, playerInventory, new SimpleContainer(SIZE), null);
    }

    private CompanionInventoryMenu(int id, Inventory playerInventory, Container storage, CompanionEntity companion) {
        super(ModContent.COMPANION_INV_MENU, id);
        this.storage = storage;
        this.companion = companion;

        // Row 1 (y 28): armor [0..3] + gap + tools [4..7]
        for (int i = 0; i < 4; i++) {
            addSlot(new ArmorSlot(storage, i, 8 + i * 18, 28, CompanionEntity.ARMOR_SLOTS[i]));
        }
        for (int i = 0; i < 4; i++) {
            addSlot(new ToolSlot(storage, CompanionEntity.TOOL_START + i, 98 + i * 18, 28));
        }
        // Rows 2-4 (y 55, 73, 91): main storage [8..34] - the bag grew from 18 to 27 slots (third row)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(storage, CompanionEntity.MAIN_START + row * 9 + col, 8 + col * 18, 55 + row * 18));
            }
        }
        // Player inventory + hotbar (shifted down 18px for the new storage row)
        addStandardInventorySlots(playerInventory, 8, 130);
    }

    @Override
    public boolean stillValid(Player player) {
        return companion == null
                || (companion.isAlive() && player.distanceToSqr(companion) <= 64.0);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (companion != null && !companion.level().isClientSide()) companion.refreshEquipment();
    }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        if (container == storage && companion != null && !companion.level().isClientSide()) {
            companion.refreshEquipment();
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack rest = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return rest;
        ItemStack stack = slot.getItem();
        rest = stack.copy();
        if (index < SIZE) {
            // companion storage -> player
            if (!moveItemStackTo(stack, SIZE, SIZE + PLAYER_SLOTS, true)) return ItemStack.EMPTY;
        } else {
            // player -> companion storage (armor row first, then tools, then storage)
            EquipmentSlot equipSlot = armorSlotFor(stack);
            if (equipSlot != null) {
                int target = CompanionEntity.ARMOR_START + armorIndex(equipSlot);
                if (!moveItemStackTo(stack, target, target + 1, false)
                        && !moveItemStackTo(stack, CompanionEntity.MAIN_START, CompanionEntity.MAIN_START + CompanionEntity.MAIN_SIZE, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (CompanionTools.isDedicatedTool(stack)) {
                int target = CompanionTools.dedicatedSlot(stack);
                if (!moveItemStackTo(stack, target, target + 1, false)
                        && !moveItemStackTo(stack, CompanionEntity.MAIN_START, CompanionEntity.MAIN_START + CompanionEntity.MAIN_SIZE, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, CompanionEntity.MAIN_START, CompanionEntity.MAIN_START + CompanionEntity.MAIN_SIZE, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return rest;
    }

    private static EquipmentSlot armorSlotFor(ItemStack stack) {
        Equippable e = stack.get(DataComponents.EQUIPPABLE);
        if (e == null) return null;
        return switch (e.slot()) {
            case HEAD, CHEST, LEGS, FEET -> e.slot();
            default -> null;
        };
    }

    private static int armorIndex(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> 0;
            case CHEST -> 1;
            case LEGS -> 2;
            case FEET -> 3;
            default -> -1;
        };
    }

    /** Armor row slots only accept the piece that belongs in that slot. */
    private static class ArmorSlot extends Slot {
        private final EquipmentSlot slot;

        ArmorSlot(Container container, int index, int x, int y, EquipmentSlot slot) {
            super(container, index, x, y);
            this.slot = slot;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            Equippable e = stack.get(DataComponents.EQUIPPABLE);
            return e != null && e.slot() == slot;
        }
    }

    /** Any tool slot accepts any item: a "weapon" can be an axe, a pickaxe, whatever the player wants there. */
    private static class ToolSlot extends Slot {
        ToolSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !stack.isEmpty();          // dedicated tools still auto-route here on shift-click; anything may sit in any slot
        }
    }
}

