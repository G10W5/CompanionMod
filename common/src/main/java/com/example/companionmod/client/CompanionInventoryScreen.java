package com.example.companionmod.client;

import com.example.companionmod.inventory.CompanionInventoryMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * The companion's bag: top row = 4 armor slots + 4 tool slots, below that 18 storage slots,
 * then the player's own inventory. Slot rendering, tooltips and click handling come from
 * {@link AbstractContainerScreen}; we only paint the panel and the section labels.
 */
public class CompanionInventoryScreen extends AbstractContainerScreen<CompanionInventoryMenu> {
    public CompanionInventoryScreen(CompanionInventoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, 176, 196);
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 104;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int x = leftPos, y = topPos;
        g.fill(x - 4, y - 4, x + imageWidth + 4, y + imageHeight + 4, 0xB4101010);
        g.fill(x, y, x + imageWidth, y + imageHeight, 0xE6181818);
        g.fill(x, y, x + imageWidth, y + 1, 0xFFFFFFFF);
        g.fill(x + 4, y + 23, x + imageWidth - 4, y + 47, 0x33383838);
        g.fill(x + 4, y + 51, x + imageWidth - 4, y + 93, 0x22383838);
        g.fill(x + 4, y + 108, x + imageWidth - 4, y + 192, 0x22383838);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.text(this.font, this.title, this.titleLabelX, this.titleLabelY, 0xFFE8EEF4, true);
        g.text(this.font, Component.literal("Armor"), 8, 16, 0xFF9AA6B2, true);
        String[] tools = {"Pick", "Axe", "Shov", "Swrd"};
        for (int i = 0; i < tools.length; i++) {
            g.pose().pushMatrix();
            g.pose().translate(107 + i * 18, 17);
            g.pose().scale(0.68f, 0.68f);
            g.centeredText(this.font, tools[i], 0, 0, 0xFFC8C8C8);
            g.pose().popMatrix();
        }
        g.text(this.font, Component.literal("Storage"), 8, 47, 0xFF9AA6B2, true);
        g.text(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 0xFF9AA6B2, true);
    }
}

