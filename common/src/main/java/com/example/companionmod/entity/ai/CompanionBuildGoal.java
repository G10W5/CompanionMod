package com.example.companionmod.entity.ai;

import com.example.companionmod.CompanionMod;
import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.entity.CompanionProtection;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * "Build &lt;name&gt;": places a schematic from {@code <config>/companionmod/schematics/<n>.json} one block
 * at a time, consuming matching items from the bag, bottom layer first. Steps are relative to the anchor.
 * When a needed block is not in the bag the companion waits and says what it needs; refilling the bag resumes the build.
 *
 * <p>File format: {@code [{"x":0,"y":0,"z":0,"block":"minecraft:cobblestone"}, ...]} (a bare array, or an
 * object with a "blocks" array). The file is parsed off the server thread, limited to 2 MB / 20 000 blocks /
 * +-96 offset, and spots the owner may not edit (spawn protection) are skipped.</p>
 */
public class CompanionBuildGoal extends Goal {
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int MAX_STEPS = 20_000, MAX_OFFSET = 96;

    private record Step(int dx, int dy, int dz, Block block) {}
    private record Loaded(List<Step> steps, String error) {}

    private final CompanionEntity mob;
    private final List<Step> steps = new ArrayList<>();
    private BlockPos base;
    private int index, blocked;
    private int speakCooldown;
    private boolean loaded;
    private CompletableFuture<Loaded> loading;

    public CompanionBuildGoal(CompanionEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override public boolean canUse() {
        return !mob.buildSchematic().isBlank() && !mob.isSitting() && !mob.isInjured() && mob.getTarget() == null;
    }

    @Override public boolean canContinueToUse() { return canUse(); }

    @Override
    public void start() {
        steps.clear();
        index = 0;
        blocked = 0;
        base = mob.anchor().immutable();
        loaded = false;
        loading = null;
        speakCooldown = 0;
        mob.speak("Starting the build.");
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (!loaded) {
            if (!pollSchematic()) return;
            loaded = true;
            if (steps.isEmpty()) { mob.clearBuild(); return; }   // pollSchematic already explained why
        }
        while (index < steps.size()) {
            Step s = steps.get(index);
            BlockPos pos = base.offset(s.dx, s.dy, s.dz);
            BlockState there = level.getBlockState(pos);
            if (there.getBlock() == s.block() || !there.isAir()) { index++; continue; }   // done, or occupied: skip
            if (pos.closerToCenterThan(mob.position(), 0.9)) { index++; continue; }  // never entomb ourselves
            if (!CompanionProtection.canModify(level, mob, pos)) { blocked++; index++; continue; }
            if (mob.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 4.5 * 4.5) {
                mob.getNavigation().moveTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1.1);
                return;
            }
            if (takeFromBag(s.block().asItem()).isEmpty()) {
                // Out of material: hold position, tell the player (throttled), retry once the bag is refilled.
                mob.getNavigation().stop();
                if (--speakCooldown <= 0) {
                    speakCooldown = 120;
                    mob.speak("I need more " + s.block().getName().getString() + " to finish the build.");
                }
                return;
            }
            mob.getNavigation().stop();
            mob.swing(InteractionHand.MAIN_HAND);
            level.setBlock(pos, s.block().defaultBlockState(), 3);
            index++;
        }
        mob.speak(blocked > 0 ? "Build finished, but " + blocked + " spots are protected." : "Build finished!");
        mob.clearBuild();
    }

    /** Starts / polls the background parse. Returns true once the result (possibly empty) is in {@link #steps}. */
    private boolean pollSchematic() {
        if (loading == null) {
            String name = mob.buildSchematic();
            Path file = CompanionMod.configDir().resolve("companionmod").resolve("schematics").resolve(name + ".json");
            if (!Files.isRegularFile(file)) {
                if (--speakCooldown <= 0) {
                    speakCooldown = 120;
                    mob.speak("I cannot find a schematic called " + name
                            + ". Put a JSON file in config/companionmod/schematics.");
                }
                return false;
            }
            loading = CompletableFuture.supplyAsync(() -> parse(file));
            return false;
        }
        if (!loading.isDone()) return false;
        Loaded result;
        try {
            result = loading.join();
        } catch (RuntimeException e) {
            result = new Loaded(List.of(), "I could not read that schematic file.");
        }
        loading = null;
        steps.addAll(result.steps());
        if (!result.error().isBlank()) mob.speak(result.error());
        return true;
    }

    /** Runs on a worker thread: touches no world state. */
    private static Loaded parse(Path file) {
        List<Step> out = new ArrayList<>();
        try {
            if (Files.size(file) > MAX_FILE_BYTES) return new Loaded(out, "That schematic file is too big.");
            JsonElement el = JsonParser.parseString(Files.readString(file));
            JsonArray arr = null;
            if (el.isJsonArray()) arr = el.getAsJsonArray();
            else if (el.isJsonObject() && el.getAsJsonObject().has("blocks")) arr = el.getAsJsonObject().getAsJsonArray("blocks");
            if (arr == null) return new Loaded(out, "That schematic file has no blocks in it.");
            for (JsonElement e : arr) {
                if (out.size() >= MAX_STEPS) break;
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                int dx = o.has("x") ? o.get("x").getAsInt() : 0;
                int dy = o.has("y") ? o.get("y").getAsInt() : 0;
                int dz = o.has("z") ? o.get("z").getAsInt() : 0;
                if (Math.abs(dx) > MAX_OFFSET || Math.abs(dy) > MAX_OFFSET || Math.abs(dz) > MAX_OFFSET) continue;
                String id = o.has("block") ? o.get("block").getAsString() : "";
                Identifier loc = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
                Block b = loc == null ? null : BuiltInRegistries.BLOCK.getOptional(loc).orElse(null);
                if (b == null || b == Blocks.AIR || b.asItem() == Items.AIR) continue;   // no item = nothing to take from the bag
                out.add(new Step(dx, dy, dz, b));
            }
            out.sort(Comparator.comparingInt(Step::dy).thenComparingInt(Step::dx).thenComparingInt(Step::dz));   // bottom layer first
            if (out.isEmpty()) return new Loaded(out, "That schematic has no placeable blocks.");
            return new Loaded(out, "");
        } catch (IOException | RuntimeException ex) {
            return new Loaded(new ArrayList<>(), "I could not read that schematic file.");
        }
    }

    /** Removes exactly one matching item from the bag and returns it (EMPTY when there is none). */
    private ItemStack takeFromBag(Item item) {
        for (int i = CompanionEntity.MAIN_START; i < CompanionEntity.MAIN_START + CompanionEntity.MAIN_SIZE; i++) {
            ItemStack s = mob.storage().getItem(i);
            if (!s.isEmpty() && s.getItem() == item) return mob.storage().removeItem(i, 1);
        }
        return ItemStack.EMPTY;
    }
}
