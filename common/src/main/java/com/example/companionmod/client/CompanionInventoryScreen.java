package com.example.companionmod.client;

import com.example.companionmod.inventory.CompanionInventoryMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * The companion's bag in the grey panel style of the settings screens: armor row, tool row, 2x9 storage, then the
 * player's own inventory. Slot rendering, tooltips and click handling come from {@link AbstractContainerScreen};
 * this class paints the panel, a sunken well behind every slot, and the section labels.
 */
public class CompanionInventoryScreen extends AbstractContainerScreen<CompanionInventoryMenu> {
    public CompanionInventoryScreen(CompanionInventoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, 176, 196);
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 102;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);   // 26.1-CHECK: vanilla blur/dim; remove if it draws twice
        CompanionUi.panel(g, leftPos, topPos, leftPos + imageWidth, topPos + imageHeight);
        for (Slot s : this.menu.slots) slotWell(g, leftPos + s.x - 1, topPos + s.y - 1);
    }

    /** Vanilla-style sunken 18x18 slot: dark top/left edge, light bottom/right edge, mid-grey fill. */
    private static void slotWell(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x, y, x + 18, y + 18, 0xFF373737);
        g.fill(x + 1, y + 1, x + 18, y + 18, 0xFFFFFFFF);
        g.fill(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.text(this.font, this.title, this.titleLabelX, this.titleLabelY, CompanionUi.TEXT_DARK, false);
        g.text(this.font, Component.literal("Armor"), 8, 17, CompanionUi.TEXT_MUTED, false);
        g.text(this.font, Component.literal("Tools"), 98, 17, CompanionUi.TEXT_MUTED, false);
        g.text(this.font, Component.literal("Storage"), 8, 44, CompanionUi.TEXT_MUTED, false);
        g.text(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, CompanionUi.TEXT_DARK, false);
    }
}
