package com.example.companionmod.client;

import com.example.companionmod.voice.AudioDevices;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * Picker for a Java Sound input (microphone) or output (speakers) device. The first row is always
 * "System default" (stores an empty string); the rest come straight from the audio subsystem, so
 * plug/unplug is reflected every time the screen opens. Clicking a row hands the name to the picker
 * consumer and returns to the settings screen.
 */
public final class CompanionAudioDeviceScreen extends Screen {
    private static final int ROW = 22;

    private final boolean input;
    private final Screen parent;
    private final Consumer<String> picker;
    private final List<String> devices;

    private CompanionAudioDeviceScreen(boolean input, Screen parent, Consumer<String> picker, List<String> devices) {
        super(Component.literal(input ? "Pick a Microphone" : "Pick an Output Device"));
        this.input = input;
        this.parent = parent;
        this.picker = picker;
        this.devices = devices;
    }

    /** {@code input} true = microphone list, false = speaker list. Runs on the game thread (enumeration is local and fast). */
    static void open(Minecraft mc, boolean input, Screen parent, Consumer<String> picker) {
        List<String> devs = new java.util.ArrayList<>();
        devs.add("");                                    // sentinel: system default
        devs.addAll(input ? AudioDevices.inputs() : AudioDevices.outputs());
        mc.setScreen(new CompanionAudioDeviceScreen(input, parent, picker, devs));
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int listW = Math.min(420, this.width - 60);
        int y = 74;
        for (int i = 0; i < devices.size(); i++) {
            if (y + ROW > this.height - 60) break;       // keep above the footer
            final String dev = devices.get(i);
            final boolean isDefault = dev.isEmpty();
            Button row = Button.builder(Component.literal(isDefault ? "System default" : dev),
                            b -> { picker.accept(dev); if (this.minecraft != null) this.minecraft.setScreen(parent); })
                    .pos(cx - listW / 2, y).size(listW, 20).build();
            this.addRenderableWidget(row);
            y += ROW;
        }
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                .pos(cx - 50, Math.max(y + 6, this.height - 30)).size(100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        this.extractMenuBackground(g);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        g.centeredText(this.font, this.title, cx, 30, 0xFFFFFFFF);
        g.centeredText(this.font, input
                ? "Used for push-to-talk (hold V)."
                : "Used for spoken replies. Empty = Minecraft's own audio output.", cx, 44, 0xFF9AA6B2);
        g.centeredText(this.font, (devices.size() - 1) + " device(s) found.", cx, 56, 0xFF9AA6B2);
        g.centeredText(this.font, "Click a device to select it.", cx, this.height - 24, 0xFF666666);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
