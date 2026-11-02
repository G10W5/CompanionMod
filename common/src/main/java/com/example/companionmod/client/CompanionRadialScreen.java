package com.example.companionmod.client;

import com.example.companionmod.voice.VoiceConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Radial wheel, overhaul.
 *  - Smooth ring segments with gaps, a vanilla-grey palette and a white outline + pop-out on the hovered segment.
 *  - Hold R and release on a segment to pick it (ARK style), or tap R and click. Right-click / Backspace = back, 1-8 = quick select.
 *  - Centre hub shows what is hovered, the breadcrumb and the AI's current goal.
 *  - Fixes the old half-segment offset between what was drawn and what was hit-tested.
 *  Geometry is rasterised once per segment count into horizontal runs, so drawing is just a few thousand fills.
 */
public final class CompanionRadialScreen extends Screen {
    private static final int INNER = 40, OUTER = 122, POP = 7, GAP = 3;
    private static final int C = OUTER + POP, SIZE = 2 * C + 1;
    private static final double ICON_R = (INNER + OUTER) / 2.0;

    private static final int FILL = 0xB4101010, EDGE = 0xC8383838, HOT_FILL = 0xDC5C5C5C, HOT_EDGE = 0xFFFFFFFF;

    // ------------------------------------------------------------------ geometry cache

    private record Run(int x, int y, int len, int seg, int flags) {}   // flags: 1 = pop-out zone, 2 = segment edge, 4 = base rim
    private static int cachedN = -1;
    private static List<Run> cachedRuns = List.of();

    private static List<Run> runsFor(int n) {
        if (n == cachedN) return cachedRuns;
        double step = 360.0 / n;
        int[] seg = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double dx = x - C, dy = y - C, r = Math.hypot(dx, dy);
                int i = y * SIZE + x;
                if (r < INNER || r > OUTER + POP) { seg[i] = -1; continue; }
                double a = (Math.toDegrees(Math.atan2(dy, dx)) + 450.0) % 360.0;   // 0 = up, clockwise
                int idx = Math.min(n - 1, (int) (a / step));
                double off = a - idx * step;
                seg[i] = Math.toRadians(Math.min(off, step - off)) * r < GAP / 2.0 ? -1 : idx;
            }
        }
        List<Run> runs = new ArrayList<>();
        for (int y = 0; y < SIZE; y++) {
            int runStart = -1, runKey = -1;
            for (int x = 0; x <= SIZE; x++) {
                int key = -1;
                if (x < SIZE) {
                    int s = seg[y * SIZE + x];
                    if (s >= 0) {
                        double r = Math.hypot(x - C, y - C);
                        int f = 0;
                        if (r > OUTER) f |= 1;
                        if (x == 0 || y == 0 || x == SIZE - 1 || y == SIZE - 1
                                || seg[y * SIZE + x - 1] != s || seg[y * SIZE + x + 1] != s
                                || seg[(y - 1) * SIZE + x] != s || seg[(y + 1) * SIZE + x] != s) f |= 2;
                        if (r > OUTER - 1.5 && r <= OUTER) f |= 4;
                        key = (s << 3) | f;
                    }
                }
                if (key != runKey) {
                    if (runKey >= 0) runs.add(new Run(runStart, y, x - runStart, runKey >> 3, runKey & 7));
                    runStart = x;
                    runKey = key;
                }
            }
        }
        cachedN = n;
        cachedRuns = runs;
        return runs;
    }

    // ------------------------------------------------------------------ entries

    private static final class Entry {
        final String label, hint, action, target;
        final ItemStack icon;
        final List<Entry> children;
        final Runnable custom;

        Entry(String label, String hint, String action, String target, ItemStack icon, List<Entry> children, Runnable custom) {
            this.label = label; this.hint = hint; this.action = action; this.target = target;
            this.icon = icon; this.children = children; this.custom = custom;
        }
        boolean isSubmenu() { return children != null && !children.isEmpty(); }
    }

    private record Frame(List<Entry> entries, String title) {}

    private final Deque<Frame> stack = new ArrayDeque<>();
    private List<Entry> current;
    private String currentTitle = "Companion";
    private int hovered = -1;
    private int lastHovered = -1;
    private double lastDistance = Double.MAX_VALUE;
    private final long openedAt = System.currentTimeMillis();
    private boolean holdMode;

    public CompanionRadialScreen() {
        super(Component.literal("Companion"));
        this.current = buildRoot();
        this.holdMode = rDown();
    }

    @Override public boolean isPauseScreen() { return false; }

    private static boolean rDown() {
        return GLFW.glfwGetKey(Minecraft.getInstance().getWindow().handle(), GLFW.GLFW_KEY_R) == GLFW.GLFW_PRESS;
    }

    private List<Entry> buildRoot() {
        VoiceConfig cfg = ClientVoice.config();
        List<Entry> e = new ArrayList<>();
        e.add(order("Follow", "Walk with me", "follow", Items.COMPASS));
        e.add(order("Stay", "Hold this spot", "stay", Items.SHIELD));
        e.add(order("Sit", "Rest here", "sit", Items.OAK_STAIRS));
        e.add(sub("Movement", "Come, patrol, wander", Items.ENDER_PEARL, List.of(
                order("Come", "Teleport to me", "come", Items.ENDER_PEARL),
                order("Patrol", "Walk a patrol ring", "patrol", Items.SPYGLASS),
                order("Wander", "Roam nearby", "wander", Items.OAK_SAPLING),
                order("Explore", "Explore the area", "explore", Items.MAP))));
        e.add(sub("Behavior", "How to fight", Items.DIAMOND_SWORD, List.of(
                order("Aggressive", "Attack nearby hostiles", "guard", Items.DIAMOND_SWORD),
                order("Defensive", "Only fight back", "defensive", Items.SHIELD),
                order("Passive", "Never start a fight", "passive", Items.WHITE_WOOL),
                order("Attack", "Fight what I aim at", "attack", Items.IRON_SWORD))));
        e.add(sub("Work", "Items and mining", Items.CHEST, List.of(
                order("Pick up", "Grab a dropped item", "pickup", Items.HOPPER),
                custom("Find & Mine", "Go mine a block", Items.DIAMOND_PICKAXE,
                        () -> open(new CompanionFindBlockScreen(cfg, ClientVoice.configFile()))),
                order("Inventory", "Open its bag", "inventory", Items.CHEST),
                order("Drop item", "Drop one item", "drop", Items.BONE),
                order("Farm", "Harvest crops for 30s", "farm", Items.WHEAT),
                order("Fish", "Fish for 30s", "fish", Items.FISHING_ROD))));
        e.add(sub("AI", "Goals and autonomy", Items.ENDER_EYE, List.of(
                custom("Give Goal", "Describe a goal in words", Items.WRITABLE_BOOK, () -> open(new CompanionGoalScreen(null))),
                custom("Think Now", "Let the AI decide", Items.EXPERIENCE_BOTTLE,
                        () -> CompanionAutonomy.requestGoal("Decide something useful to do right now.")),
                order("Continue", "Resume interrupted work", "continue", Items.CLOCK),
                custom("Auto: " + (cfg.autonomyEnabled ? "ON" : "OFF"), "Act without being asked",
                        cfg.autonomyEnabled ? Items.LIME_DYE : Items.GRAY_DYE, () -> {
                            cfg.autonomyEnabled = !cfg.autonomyEnabled;
                            cfg.save(ClientVoice.configFile());
                        }),
                custom("Cancel Plan", "Stop the current goal", Items.BARRIER, CompanionAutonomy::cancelPlan))));
        e.add(sub("More", "Skin, persona, settings", Items.NAME_TAG, List.of(
                custom("Skin Library", "Wear a player skin", Items.LEATHER_HELMET,
                        () -> open(new CompanionSkinScreen(cfg, ClientVoice.configFile()))),
                custom("Personalize", "Who your companion is", Items.NAME_TAG,
                        () -> open(new CompanionProfileScreen(cfg, ClientVoice.configFile(), null))),
                custom("Settings", "Voice, memory, AI", Items.REDSTONE,
                        () -> open(new CompanionSettingsScreen(cfg, ClientVoice.configFile()))),
                order("Inventory", "Open its bag", "inventory", Items.CHEST))));
        return List.copyOf(e);
    }

    private static Entry order(String label, String hint, String action, Item icon) {
        return new Entry(label, hint, action, "", new ItemStack(icon), null, null);
    }
    private static Entry sub(String label, String hint, Item icon, List<Entry> children) {
        return new Entry(label, hint, "", "", new ItemStack(icon), children, null);
    }
    private static Entry custom(String label, String hint, Item icon, Runnable run) {
        return new Entry(label, hint, "", "", new ItemStack(icon), null, run);
    }
    private void open(Screen s) { if (this.minecraft != null) this.minecraft.setScreen(s); }

    // ------------------------------------------------------------------ rendering

    private static int fade(int argb, float alpha) {
        int a = (int) (((argb >>> 24) & 0xFF) * alpha);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int cx = this.width / 2, cy = this.height / 2;
        int n = current.size();
        double step = 360.0 / n;
        float t = Math.min(1f, (System.currentTimeMillis() - openedAt) / 140f);
        float alpha = 1f - (1f - t) * (1f - t) * (1f - t);

        g.fill(0, 0, this.width, this.height, fade(0x78000000, alpha));

        double dx = mouseX - cx, dy = mouseY - cy;
        lastDistance = Math.hypot(dx, dy);
        hovered = -1;
        if (lastDistance >= INNER && lastDistance <= OUTER + POP) {
            double a = (Math.toDegrees(Math.atan2(dy, dx)) + 450.0) % 360.0;
            int idx = Math.min(n - 1, (int) (a / step));
            if (lastDistance <= OUTER || idx == lastHovered) hovered = idx;
        }
        lastHovered = hovered;

        int ox = cx - C, oy = cy - C;
        List<Run> runs = runsFor(n);
        for (int pass = 0; pass < 2; pass++) {                  // idle segments first, hovered one on top
            for (Run r : runs) {
                boolean hot = r.seg() == hovered;
                if ((pass == 1) != hot) continue;
                int f = r.flags();
                if ((f & 1) != 0 && !hot) continue;             // pop-out zone belongs to the hovered segment only
                boolean edge = (f & 2) != 0 || ((f & 4) != 0 && !hot);
                int col = hot ? (edge ? HOT_EDGE : HOT_FILL) : (edge ? EDGE : FILL);
                g.fill(ox + r.x(), oy + r.y(), ox + r.x() + r.len(), oy + r.y() + 1, fade(col, alpha));
            }
        }

        for (int i = 0; i < n; i++) {
            Entry en = current.get(i);
            boolean hot = i == hovered;
            double ang = Math.toRadians((i + 0.5) * step);
            double rad = ICON_R + (hot ? 4 : 0);
            int ix = cx + (int) Math.round(Math.sin(ang) * rad);
            int iy = cy - (int) Math.round(Math.cos(ang) * rad);
            g.item(en.icon, ix - 8, iy - 12);
            String label = en.label + (en.isSubmenu() ? " >" : "");
            g.centeredText(this.font, label, ix, iy + 7, fade(hot ? 0xFFFFFFFF : 0xFFC8C8C8, alpha));
        }

        for (int y = -34; y <= 34; y++) {                       // centre hub
            int w = (int) Math.sqrt(34.0 * 34.0 - y * y);
            g.fill(cx - w, cy + y, cx + w, cy + y + 1, fade(0xE6101010, alpha));
        }
        String title, hint;
        if (hovered >= 0) { title = current.get(hovered).label; hint = current.get(hovered).hint; }
        else if (!stack.isEmpty() && lastDistance < INNER) { title = "Back"; hint = "Click the centre"; }
        else {
            title = currentTitle;
            com.example.companionmod.entity.CompanionEntity mine = ClientVoice.findCompanion(this.minecraft);
            String task = mine == null ? "" : mine.taskText();
            hint = task.isBlank() ? "Choose an order" : task;       // live progress lives here, not in chat
        }
        g.centeredText(this.font, title, cx, cy - 12, fade(0xFFFFFFFF, alpha));
        g.centeredText(this.font, hint.length() > 16 ? hint.substring(0, 15) + "…" : hint, cx, cy - 1, fade(0xFFA0A0A0, alpha));
        String goal = CompanionAutonomy.currentGoal();
        if (!goal.isBlank()) {
            String s = "Goal: " + (goal.length() > 14 ? goal.substring(0, 13) + "…" : goal);
            g.centeredText(this.font, s, cx, cy + 12, fade(0xFF7FD7FF, alpha));
        }

        StringBuilder crumb = new StringBuilder("Companion");
        List<Frame> frames = new ArrayList<>(stack);
        for (int i = frames.size() - 2; i >= 0; i--) crumb.append("  >  ").append(frames.get(i).title());
        if (!stack.isEmpty()) crumb.append("  >  ").append(currentTitle);
        g.centeredText(this.font, crumb.toString(), cx, cy - OUTER - POP - 14, fade(0xFFE0E0E0, alpha));
        g.centeredText(this.font, "Hold R and release to pick  •  click  •  1-8 quick select  •  right-click back",
                cx, this.height - 20, fade(0xFFA0A0A0, alpha));
    }

    // ------------------------------------------------------------------ input

    @Override
    public void tick() {
        if (holdMode && !rDown()) {                            // released R: pick what is under the cursor
            holdMode = false;
            if (System.currentTimeMillis() - openedAt > 220 && hovered >= 0) activate(current.get(hovered));
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        holdMode = false;
        if (event.button() == 1) { backOrClose(); return true; }
        if (event.button() != 0) { onClose(); return true; }
        if (hovered >= 0) { activate(current.get(hovered)); return true; }
        if (lastDistance < INNER) backOrClose(); else onClose();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int k = event.key();
        if (k == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if (k == GLFW.GLFW_KEY_R) {                             // ignore key-repeat while R is still held from opening
            if (!holdMode && System.currentTimeMillis() - openedAt > 300) onClose();
            return true;
        }
        if (k == GLFW.GLFW_KEY_BACKSPACE) { backOrClose(); return true; }
        if (k >= GLFW.GLFW_KEY_1 && k <= GLFW.GLFW_KEY_9 && k - GLFW.GLFW_KEY_1 < current.size()) {
            holdMode = false;
            activate(current.get(k - GLFW.GLFW_KEY_1));
            return true;
        }
        return super.keyPressed(event);
    }

    private void activate(Entry en) {
        if (en.isSubmenu()) {
            stack.push(new Frame(current, currentTitle));
            current = en.children;
            currentTitle = en.label;
            hovered = lastHovered = -1;
            return;
        }
        if (en.custom != null) {
            en.custom.run();
            if (this.minecraft != null && this.minecraft.screen == this) this.minecraft.setScreen(null);
            return;
        }
        ClientVoice.send(en.action, "", en.target);
        if (this.minecraft != null) this.minecraft.setScreen(null);
    }

    private void backOrClose() {
        if (stack.isEmpty()) { onClose(); return; }
        Frame f = stack.pop();
        current = f.entries();
        currentTitle = f.title();
        hovered = lastHovered = -1;
    }
}
