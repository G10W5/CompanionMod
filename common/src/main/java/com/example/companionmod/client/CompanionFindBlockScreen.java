package com.example.companionmod.client;

import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.voice.VoiceConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/** Small chooser for "go and find/mine this block" orders. */
public class CompanionFindBlockScreen extends Screen {
    private static final String[][] QUICK = {
            {"Diamond", "diamond_ore"}, {"Coal", "coal_ore"}, {"Iron", "iron_ore"},
            {"Gold", "gold_ore"}, {"Redstone", "redstone_ore"}, {"Lapis", "lapis_ore"},
            {"Emerald", "emerald_ore"}, {"Copper", "copper_ore"}, {"Ancient Debris", "ancient_debris"},
            {"Wood", "log"}, {"Stone", "stone"}, {"Dirt", "dirt"}, {"Planks", "oak_planks"},
    };

    private final VoiceConfig config;
    private final Path configFile;
    private EditBox blockField;
    private String status = "";

    public CompanionFindBlockScreen(VoiceConfig config, Path configFile) {
        super(Component.literal("Find & Mine"));
        this.config = config;
        this.configFile = configFile;
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int y = 72;

        this.blockField = new EditBox(this.font, cx - 150, y, 300, 20, Component.literal("Block"));
        this.blockField.setHint(Component.literal("Block id or name (diamond_ore, coal, iron...)"));
        this.blockField.setMaxLength(60);
        this.addRenderableWidget(this.blockField);
        y += 26;

        int col = 0;
        for (String[] entry : QUICK) {
            int bw = 96;
            int bx = cx - 150 + col * 102;
            this.addRenderableWidget(Button.builder(Component.literal(entry[0]), b -> {
                blockField.setValue(entry[1]);
                status = "";
            }).pos(bx, y).size(bw, 20).build());
            col++;
            if (col == 3) { col = 0; y += 24; }
        }
        y += 8;

        this.addRenderableWidget(Button.builder(Component.literal("Find & Mine"), b -> find())
                .pos(cx - 150, y).size(300, 20).build());
        y += 24;
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> {
            if (this.minecraft != null) this.minecraft.setScreen(new CompanionRadialScreen());
        }).pos(cx - 150, y).size(300, 20).build());
    }

    private void find() {
        String id = CompanionEntity.normalizeBlockId(blockField.getValue());
        if (id.isBlank()) {
            status = "Unknown block. Try diamond, coal, iron, gold, emerald...";
            return;
        }
        ClientVoice.send("seek_block", "", id);
        if (this.minecraft != null) this.minecraft.setScreen(null);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        this.extractMenuBackground(g);
        int cx = this.width / 2;
        g.fill(cx - 162, 34, cx + 162, this.height - 28, 0xCC101216);
        g.fill(cx - 162, 34, cx + 162, 35, 0xFF3D7BB8);
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        g.centeredText(this.font, "Find & Mine", cx, 42, 0xFFFFFFFF);
        g.centeredText(this.font, "The companion walks to the nearest matching block and mines it.", cx, 54, 0xFF9AA6B2);
        if (!status.isBlank()) g.centeredText(this.font, status, cx, this.height - 46, 0xFFFF7777);
    }
}
