package com.example.companionmod.build;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** A block state as plain strings (id + properties). No Minecraft classes, so it is testable anywhere. */
public record Blk(String id, Map<String, String> props) {

    public static final Blk AIR = new Blk("minecraft:air", Map.of());

    public Blk {
        id = id.contains(":") ? id : "minecraft:" + id;
        props = Collections.unmodifiableMap(new TreeMap<>(props));
    }

    public static Blk of(String id) {
        return new Blk(id, Map.of());
    }

    /** Parses "minecraft:oak_stairs[facing=north,half=bottom]" (brackets optional). */
    public static Blk parse(String s) {
        int i = s.indexOf('[');
        if (i < 0) return of(s.trim());
        Map<String, String> p = new TreeMap<>();
        String body = s.substring(i + 1, s.lastIndexOf(']'));
        for (String kv : body.split(",")) {
            int e = kv.indexOf('=');
            if (e > 0) p.put(kv.substring(0, e).trim(), kv.substring(e + 1).trim());
        }
        return new Blk(s.substring(0, i).trim(), p);
    }

    /** with("facing","north","half","bottom") */
    public Blk with(String... kv) {
        Map<String, String> p = new TreeMap<>(props);
        for (int i = 0; i + 1 < kv.length; i += 2) p.put(kv[i], kv[i + 1]);
        return new Blk(id, p);
    }

    public boolean isAir() {
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air");
    }

    /** Part after the namespace, e.g. "oak_stairs". */
    public String path() {
        return id.substring(id.indexOf(':') + 1);
    }

    /** Items consumed to place this block: upper door halves are free (the lower half pays for both),
     *  double slabs cost two. */
    public int itemsNeeded() {
        if (path().endsWith("_door") && "upper".equals(props.get("half"))) return 0;
        if (path().endsWith("_bed") && "head".equals(props.get("part"))) return 0;
        if (path().endsWith("_slab") && "double".equals(props.get("type"))) return 2;
        return 1;
    }

    @Override
    public String toString() {
        if (props.isEmpty()) return id;
        StringBuilder sb = new StringBuilder(id).append('[');
        boolean first = true;
        for (var e : props.entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }
}
