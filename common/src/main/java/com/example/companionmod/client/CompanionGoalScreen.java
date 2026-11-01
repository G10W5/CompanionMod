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

/** Type any goal in plain words; the AI turns it into a plan. Vanilla layout: title, one field, Done/Cancel in the footer. */
public class CompanionGoalScreen extends Screen {
    private static final String[] IDEAS = {"Collect some wood", "Guard this area", "Find coal and come back", "Look around nearby",
            "Continue what you were doing", "Farm the crops", "Go fishing"};
    private final Screen parent;
    private EditBox field;

    public CompanionGoalScreen(Screen parent) {
        super(Component.literal("Give a Goal"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        field = new EditBox(this.font, cx - 154, 58, 308, 20, Component.literal("Goal"));
        field.setHint(Component.literal("e.g. gather 2 stacks of wood, then come back"));
        field.setMaxLength(160);
        this.addRenderableWidget(field);
        this.setInitialFocus(field);

        for (int i = 0; i < IDEAS.length; i++) {
            final String idea = IDEAS[i];
            this.addRenderableWidget(Button.builder(Component.literal(idea), b -> field.setValue(idea))
                    .pos(cx - 154 + (i % 2) * 158, 88 + (i / 2) * 24).size(150, 20).build());
        }
        this.addRenderableWidget(Button.builder(Component.literal("Plan It"), b -> submit())
                .pos(cx - 154, this.height - 27).size(150, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .pos(cx + 4, this.height - 27).size(150, 20).build());
    }

    private void submit() {
        String text = field.getValue().trim();
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
        g.centeredText(this.font, "Describe what you want. The AI picks the steps.", this.width / 2, 24, 0xFFA0A0A0);
        g.fill(0, 32, this.width, 33, 0xFF000000);
        g.fill(0, 33, this.width, 34, 0xFF373737);
        g.fill(0, this.height - 33, this.width, this.height - 32, 0xFF000000);
        g.fill(0, this.height - 32, this.width, this.height - 31, 0xFF373737);
    }
}
