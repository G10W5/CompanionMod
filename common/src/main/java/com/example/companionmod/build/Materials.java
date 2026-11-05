package com.example.companionmod.build;

import java.util.List;
import java.util.Map;

/**
 * Material ROLES instead of raw blocks. The model only names a role->block mapping; anything invalid or
 * hallucinated silently falls back to a default, so a small model can never produce a broken build.
 */
public record Materials(String wall, String floor, String roof, String trim, String window,
                        String foundation, String door, String light) {

    // Curated lists: used for the JSON schema enums AND as the "known good" set.
    public static final List<String> WALL = List.of("stone_bricks", "cobblestone", "mossy_stone_bricks", "bricks",
            "sandstone", "smooth_sandstone", "deepslate_bricks", "polished_andesite", "stone", "nether_bricks",
            "oak_planks", "spruce_planks", "birch_planks", "dark_oak_planks", "jungle_planks", "acacia_planks");
    public static final List<String> FLOOR = List.of("oak_planks", "spruce_planks", "birch_planks", "dark_oak_planks",
            "jungle_planks", "acacia_planks", "stone_bricks", "smooth_stone", "polished_andesite", "cobblestone");
    /** Must all have a *_stairs / *_slab variant. */
    public static final List<String> ROOF = List.of("oak_planks", "spruce_planks", "birch_planks", "dark_oak_planks",
            "jungle_planks", "acacia_planks", "stone_bricks", "cobblestone", "bricks", "sandstone",
            "deepslate_bricks", "nether_bricks", "mossy_cobblestone", "stone", "polished_andesite");
    public static final List<String> TRIM = List.of("oak_log", "spruce_log", "birch_log", "dark_oak_log", "jungle_log",
            "acacia_log", "stripped_oak_log", "stripped_spruce_log", "stripped_dark_oak_log", "stripped_birch_log",
            "stone_bricks", "polished_andesite", "deepslate_bricks");
    public static final List<String> WINDOW = List.of("glass_pane", "glass", "iron_bars", "white_stained_glass_pane", "air");
    public static final List<String> DOOR = List.of("oak_door", "spruce_door", "birch_door", "dark_oak_door",
            "jungle_door", "acacia_door", "iron_door");
    public static final List<String> LIGHT = List.of("lantern", "soul_lantern", "torch", "air");

    public static Materials defaults() {
        return new Materials("stone_bricks", "oak_planks", "spruce_planks", "spruce_log", "glass_pane",
                "cobblestone", "oak_door", "lantern");
    }

    /** Builds a valid set from a (possibly junk) role->block map. Unknown roles are ignored. */
    public static Materials resolve(Map<String, String> req, BlockPolicy policy) {
        Materials d = defaults();
        String roof = pick(req.get("roof"), d.roof, policy);
        if (!policy.isValid("minecraft:" + stairsOf(roof)) || !policy.isValid("minecraft:" + slabOf(roof))) roof = d.roof;
        return new Materials(
                pick(req.get("wall"), d.wall, policy),
                pick(req.get("floor"), d.floor, policy),
                roof,
                pick(req.get("trim"), d.trim, policy),
                pick(req.get("window"), d.window, policy),
                pick(req.get("foundation"), d.foundation, policy),
                pick(req.get("door"), d.door, policy),
                pick(req.get("light"), d.light, policy));
    }

    private static String pick(String cand, String def, BlockPolicy policy) {
        if (cand == null) return def;
        String c = cand.trim().toLowerCase().replace("minecraft:", "").replace(' ', '_');
        if (c.isEmpty() || !c.matches("[a-z0-9_]+")) return def;
        return policy.isValid("minecraft:" + c) ? c : def;
    }

    // ---- families ----------------------------------------------------------------

    public static String stairsOf(String base) { return family(base) + "_stairs"; }
    public static String slabOf(String base) { return family(base) + "_slab"; }

    private static String family(String base) {
        if (base.endsWith("_planks")) return base.substring(0, base.length() - 7);
        if (base.endsWith("bricks")) return base.substring(0, base.length() - 1);   // stone_bricks -> stone_brick
        if (base.endsWith("tiles")) return base.substring(0, base.length() - 1);
        return base;
    }

    public static boolean isLogLike(String id) {
        return id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_stem") || id.endsWith("_hyphae");
    }

    public static boolean isPaneLike(String id) {
        return id.endsWith("_pane") || id.equals("iron_bars");
    }
}
