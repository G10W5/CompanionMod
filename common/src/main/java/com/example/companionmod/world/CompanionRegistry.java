package com.example.companionmod.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Which companion entity belongs to which player, stored in the world folder (companionmod_companions.json).
 * This is what lets the shrine find a companion that sits in an unloaded chunk, and lets a stale copy remove itself.
 * Plain JSON on purpose: no SavedData API, so it is identical on Fabric and NeoForge.
 */
public final class CompanionRegistry {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, UUID> OWNERS = new HashMap<>();
    private static Path loadedFrom;

    private CompanionRegistry() {}

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("companionmod_companions.json");
    }

    private static void ensure(MinecraftServer server) {
        Path f = file(server);
        if (f.equals(loadedFrom)) return;
        loadedFrom = f;
        OWNERS.clear();
        try {
            if (!Files.isRegularFile(f)) return;
            JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                try { OWNERS.put(UUID.fromString(e.getKey()), UUID.fromString(e.getValue().getAsString())); }
                catch (RuntimeException ignored) { }
            }
        } catch (IOException | RuntimeException ignored) { /* corrupt file: start empty */ }
    }

    public static synchronized UUID get(MinecraftServer server, UUID owner) {
        ensure(server);
        return OWNERS.get(owner);
    }

    public static synchronized void set(MinecraftServer server, UUID owner, UUID companion) {
        ensure(server);
        if (!companion.equals(OWNERS.put(owner, companion))) save(server);
    }

    /** Removes the entry only if it still points at {@code companion} (a newer companion keeps its entry). */
    public static synchronized void clear(MinecraftServer server, UUID owner, UUID companion) {
        ensure(server);
        if (OWNERS.remove(owner, companion)) save(server);
    }

    /** Forget the in-memory copy (server stopped); the file stays. */
    public static synchronized void reset() {
        loadedFrom = null;
        OWNERS.clear();
    }

    private static void save(MinecraftServer server) {
        JsonObject o = new JsonObject();
        OWNERS.forEach((k, v) -> o.addProperty(k.toString(), v.toString()));
        Path f = file(server);
        try {
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(o));
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { }
    }
}
