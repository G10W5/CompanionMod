package com.example.companionmod.entity.ai;

import com.example.companionmod.entity.CompanionEntity;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * "Build &lt;name&gt;": places a schematic from {@code config/companionmod/schematics/<name>.json} one block
 * at a time, consuming matching items from the bag. Steps are relative to the anchor (where the order was
 * given). When a needed block is not in the bag the companion waits at that spot and tells the player what
 * it needs - refilling the bag resumes the build automatically, so a palette bigger than the bag just
 * becomes a "fetch me more cobblestone" loop instead of a failure.
 *
 * <p>File format: {@code [{"x":0,"y":0,"z":0,"block":"minecraft:cobblestone"}, ...]} (a bare array, or an
 * object with a "blocks" array). Already-correct blocks are skipped, so re-running continues where it left
 * off after a crash or relog.</p>
 */
public class CompanionBuildGoal extends Goal {
    private record Step(int dx, int dy, int dz, Block block) {}

    private final CompanionEntity mob;
    private final List<Step> steps = new ArrayList<>();
    private BlockPos base;
    private int index;
    private int scanCooldown, speakCooldown;
    private boolean loaded;

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
        base = mob.anchor().immutable();
        loaded = false;
        speakCooldown = 0;
        scanCooldown = 0;
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
            if (!loadSchematic(level)) return;
            loaded = true;
            if (steps.isEmpty()) { mob.clearBuild(); return; }   // loadSchematic already explained why
        }
        while (index < steps.size()) {
            Step s = steps.get(index);
            BlockPos pos = base.offset(s.dx, s.dy, s.dz);
            BlockState there = level.getBlockState(pos);
            if (there.getBlock() == s.block() || !there.isAir()) { index++; continue; }   // done, or occupied: skip
            if (pos.closerToCenterThan(mob.position(), 0.9)) { index++; continue; }  // never entomb ourselves
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
            if (mob.getRandom().nextInt(12) == 0) mob.speak("Building...");
        }
        mob.speak("Build finished!");
        mob.clearBuild();
    }

    /** Reads the schematic file. Returns false when it does not exist (retried, throttled). */
    private boolean loadSchematic(ServerLevel level) {
        String name = mob.buildSchematic();
        Path file = Path.of("config", "companionmod", "schematics", name + ".json");
        if (!Files.isRegularFile(file)) {
            if (--speakCooldown <= 0) {
                speakCooldown = 120;
                mob.speak("I cannot find a schematic called " + name
                        + ". Put a JSON file in config/companionmod/schematics.");
            }
            return false;
        }
        try {
            JsonElement el = JsonParser.parseString(Files.readString(file));
            JsonArray arr = null;
            if (el.isJsonArray()) arr = el.getAsJsonArray();
            else if (el.isJsonObject() && el.getAsJsonObject().has("blocks")) arr = el.getAsJsonObject().getAsJsonArray("blocks");
            if (arr == null) {
                mob.speak("That schematic file has no blocks in it.");
                return true;
            }
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                int dx = o.has("x") ? o.get("x").getAsInt() : 0;
                int dy = o.has("y") ? o.get("y").getAsInt() : 0;
                int dz = o.has("z") ? o.get("z").getAsInt() : 0;
                String id = o.has("block") ? o.get("block").getAsString() : "";
                Identifier loc = Identifier.tryParse(id.startsWith("minecraft:") ? id : "minecraft:" + id);
                Block b = loc == null ? null : BuiltInRegistries.BLOCK.getOptional(loc).orElse(null);
                if (b == null || b == Blocks.AIR) continue;
                steps.add(new Step(dx, dy, dz, b));
            }
            if (steps.isEmpty()) mob.speak("That schematic has no placeable blocks.");
            return true;
        } catch (IOException | RuntimeException ex) {
            mob.speak("I could not read that schematic file.");
            return true;
        }
    }

    private ItemStack takeFromBag(net.minecraft.world.item.Item item) {
        for (int i = CompanionEntity.MAIN_START; i < CompanionEntity.MAIN_START + CompanionEntity.MAIN_SIZE; i++) {
            ItemStack s = mob.storage().getItem(i);
            if (s.getItem() == item) {
                mob.storage().removeItem(i, 1);
                return s;
            }
        }
        return ItemStack.EMPTY;
    }
}
