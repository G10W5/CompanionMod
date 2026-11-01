package com.example.companionmod.client;

import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.voice.VoiceConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight long-term memory + world observer for the companion.
 * Remembers facts the player asks it to keep and a rolling log of things the companion "saw" the
 * player do (attacks, pickups, damage, dimension changes). Stored in config/companionmod-memory.json.
 */
public final class CompanionMemory {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>();
    private static final List<String> FACTS = new ArrayList<>();
    private static final Map<String, Integer> LAST_ITEMS = new HashMap<>();

    private static Path file;
    private static VoiceConfig cfg;
    private static int tickCounter;
    private static String lastDimension = "";
    private static float lastHealth = 20f;
    private static int lastFood = 20;
    private static String lastHurtMob = "";
    private static boolean lastDead;
    private static boolean lastNight;

    private CompanionMemory() {}

    public static void configure(VoiceConfig config, Path memoryFile) {
        cfg = config;
        file = memoryFile;
        load();
    }

    public static void tick(Minecraft mc, CompanionEntity companion) {
        if (cfg == null || !cfg.memoryEnabled || mc.player == null || mc.level == null) return;
        if (++tickCounter % 20 != 0) return;

        String dimension = mc.level.dimension().identifier().toString();
        if (!dimension.equals(lastDimension)) {
            if (!lastDimension.isEmpty()) addEvent("entered " + dimension);
            lastDimension = dimension;
            LAST_ITEMS.clear();
            lastHealth = mc.player.getHealth();
            lastFood = mc.player.getFoodData().getFoodLevel();
        }

        if (mc.player.getHealth() + 0.5f < lastHealth)
            addEvent("player took damage (health " + Math.round(mc.player.getHealth()) + "/20)");
        lastHealth = mc.player.getHealth();

        int food = mc.player.getFoodData().getFoodLevel();
        if (food + 1 < lastFood) addEvent("player is getting hungry");
        lastFood = food;

        LivingEntity hurt = mc.player.getLastHurtMob();
        if (hurt != null && hurt.isAlive() && mc.player.tickCount - mc.player.getLastHurtMobTimestamp() < 30) {
            String name = hurt.getName().getString();
            if (!name.equals(lastHurtMob)) {
                addEvent("player attacked " + name);
                lastHurtMob = name;
            }
        }

        boolean dead = mc.player.isDeadOrDying();
        if (dead && !lastDead) addEvent("player died");
        lastDead = dead;

        long time = mc.level.getOverworldClockTime() % 24000;
        boolean night = time >= 13000 && time < 23000;
        if (night != lastNight) {
            if (night) addEvent("night fell");
            lastNight = night;
        }

        diffInventory(mc);

        if (companion != null) {
            // keep the memory file fresh without writing every tick
            if (tickCounter % 600 == 0) save();
        }
    }

    public static void remember(String fact) {
        if (fact == null) return;
        String clean = fact.trim();
        if (clean.isEmpty() || FACTS.stream().anyMatch(f -> f.equalsIgnoreCase(clean))) return;
        FACTS.add(clean);
        while (FACTS.size() > 40) FACTS.remove(0);
        save();
    }

    public static void forget(String fact) {
        if (fact == null || fact.isBlank()) return;
        FACTS.removeIf(f -> f.toLowerCase().contains(fact.toLowerCase().trim()));
        save();
    }

    public static List<String> facts() {
        return List.copyOf(FACTS);
    }

    public static String summary() {
        if (cfg == null || !cfg.memoryEnabled) return "";
        StringBuilder sb = new StringBuilder();
        if (!FACTS.isEmpty()) {
            sb.append("Remembered: ");
            int start = Math.max(0, FACTS.size() - 8);
            for (int i = start; i < FACTS.size(); i++) {
                if (i > start) sb.append("; ");
                sb.append(FACTS.get(i));
            }
            sb.append(". ");
        }
        if (!EVENTS.isEmpty()) {
            sb.append("Recent events: ");
            int count = 0;
            for (String event : EVENTS) {
                if (count++ > 0) sb.append("; ");
                sb.append(event);
            }
            sb.append('.');
        }
        return sb.toString();
    }

    private static void addEvent(String event) {
        EVENTS.addLast(event);
        int max = cfg == null ? 30 : Math.max(5, cfg.memoryMaxEvents);
        while (EVENTS.size() > max) EVENTS.removeFirst();
    }

    private static void diffInventory(Minecraft mc) {
        Map<String, Integer> now = new HashMap<>();
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            now.merge(id, stack.getCount(), Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : now.entrySet()) {
            int before = LAST_ITEMS.getOrDefault(entry.getKey(), 0);
            if (entry.getValue() > before) {
                String name = entry.getKey().substring(entry.getKey().indexOf(':') + 1).replace('_', ' ');
                addEvent("player picked up " + name);
            }
        }
        LAST_ITEMS.clear();
        LAST_ITEMS.putAll(now);
    }

    // ---------------------------------------------------------------- persistence

    private static final class Data {
        List<String> facts = new ArrayList<>();
        List<String> events = new ArrayList<>();
    }

    private static void load() {
        if (file == null || !Files.exists(file)) return;
        try {
            Data data = GSON.fromJson(Files.readString(file), Data.class);
            if (data == null) return;
            FACTS.clear();
            EVENTS.clear();
            if (data.facts != null) FACTS.addAll(data.facts);
            if (data.events != null) EVENTS.addAll(data.events);
        } catch (Exception ignored) { }
    }

    private static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Data data = new Data();
            data.facts = new ArrayList<>(FACTS);
            data.events = new ArrayList<>(EVENTS);
            Files.writeString(file, GSON.toJson(data));
        } catch (IOException ignored) { }
    }
}
