package com.example.companionmod.client;

import com.example.companionmod.voice.VoiceConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Type any goal in plain words; the AI turns it into a plan. The examples live in ONE horizontally
 * wheel-scrollable chip row (the old 2-column list ran out of space for more than 7 ideas); the row
 * rebuilds its buttons on scroll and the typed text survives that rebuild.
 */
public class CompanionGoalScreen extends Screen {
    private static final String[] IDEAS = {
            "Collect some wood", "Get 20 iron ore", "Find diamonds", "Mine some coal",
            "Explore a cave", "Bring me 12 lapis", "Dig down deeper", "Get cobblestone",
            "Chop some trees", "Farm the crops", "Go fishing", "Guard this area",
            "Look around nearby", "Continue what you were doing", "Follow me", "Come here",
    };

    private final Screen parent;
    private EditBox field;
    private String fieldValue = "";               // survives the chip-row rebuildWidgets()
    private int chipScroll;                       // horizontal wheel offset in pixels

    public CompanionGoalScreen(Screen parent) {
        super(Component.literal("Give a Goal"));
        this.parent = parent;
    }

    private int chipTop() { return 88; }

    @Override
    protected void init() {
        int cx = this.width / 2;
        field = new EditBox(this.font, cx - 154, 58, 308, 20, Component.literal("Goal"));
        field.setHint(Component.literal("e.g. gather 2 stacks of wood, then come back"));
        field.setMaxLength(160);
        field.setValue(fieldValue);
        field.setResponder(v -> fieldValue = v);
        this.addRenderableWidget(field);
        this.setInitialFocus(field);

        int x = cx - 154 - chipScroll;
        for (String idea : IDEAS) {
            int bw = this.font.width(idea) + 14;
            if (x + bw > cx - 154 && x < cx + 154) {
                this.addRenderableWidget(Button.builder(Component.literal(idea), b -> field.setValue(idea))
                        .pos(x, chipTop()).size(bw, 20).build());
            }
            x += bw + 4;
        }

        this.addRenderableWidget(Button.builder(Component.literal("Plan It"), b -> submit())
                .pos(cx - 154, this.height - 27).size(150, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .pos(cx + 4, this.height - 27).size(150, 20).build());
    }

    private int chipTotalW() {
        int w = -4;
        for (String idea : IDEAS) w += this.font.width(idea) + 14 + 4;
        return Math.max(0, w);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int top = chipTop();
        int max = Math.max(0, chipTotalW() - 308);
        if (max > 0 && mouseY >= top - 2 && mouseY <= top + 22) {
            int next = (int) Math.max(0, Math.min(max, chipScroll - scrollY * 24));
            if (next != chipScroll) { chipScroll = next; rebuildWidgets(); }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void submit() {
        String text = fieldValue == null ? "" : fieldValue.trim();
        if (!text.isEmpty()) CompanionAutonomy.requestGoal(text);
        if (this.minecraft != null) this.minecraft.setScreen(null);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) { submit(); return true; }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        this.extractMenuBackground(g);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        g.centeredText(this.font, "Describe what you want (amounts in the text work: 'bring me 12 iron').",
                this.width / 2, 24, 0xFFA0A0A0);
        g.fill(0, 32, this.width, 33, 0xFF000000);
        g.fill(0, 33, this.width, 34, 0xFF373737);
        g.fill(0, this.height - 33, this.width, this.height - 32, 0xFF000000);
        g.fill(0, this.height - 32, this.width, this.height - 31, 0xFF373737);
    }
}
