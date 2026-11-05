package com.example.companionmod.build;

import java.util.*;

/**
 * Material substitution: when the supply is short of a block, swap that WHOLE block type for a similar one that is
 * available in sufficient quantity (stairs for stairs, planks for planks, stone-like for stone-like...), so the build
 * stays visually consistent instead of mixing two materials halfway. Works on item/block ids. No Minecraft classes.
 */
public final class Substitution {
    private Substitution() {}

    public record Result(Map<String, String> remap, Map<String, Integer> missing) {}

    private static final Set<String> SOLID = Set.of("stone", "cobblestone", "mossy_cobblestone", "stone_bricks",
            "mossy_stone_bricks", "cracked_stone_bricks", "bricks", "sandstone", "smooth_sandstone", "cut_sandstone",
            "red_sandstone", "andesite", "polished_andesite", "granite", "polished_granite", "diorite",
            "polished_diorite", "smooth_stone", "deepslate", "cobbled_deepslate", "polished_deepslate",
            "deepslate_bricks", "deepslate_tiles", "nether_bricks", "red_nether_bricks", "blackstone",
            "polished_blackstone", "polished_blackstone_bricks", "tuff", "tuff_bricks", "mud_bricks", "terracotta",
            "white_terracotta");

    /** Substitution group of an id; ids that share a group may stand in for each other. Unique group = never. */
    public static String group(String id) {
        String p = id.substring(id.indexOf(':') + 1);
        if (p.equals("iron_door")) return "iron_door";
        if (p.endsWith("_stairs")) return "stairs";
        if (p.endsWith("_slab")) return "slab";
        if (p.endsWith("_door")) return "door";
        if (p.endsWith("_trapdoor")) return "trapdoor";
        if (p.endsWith("_fence_gate")) return "gate";
        if (p.endsWith("_fence")) return "fence";
        if (p.endsWith("_wall")) return "wall";
        if (p.endsWith("_pane") || p.equals("iron_bars")) return "pane";
        if (p.equals("glass") || p.endsWith("_stained_glass") || p.equals("tinted_glass")) return "glass";
        if (p.endsWith("_planks")) return "planks";
        if (p.endsWith("_log") || p.endsWith("_wood") || p.endsWith("_stem") || p.endsWith("_hyphae")) return "log";
        if (p.endsWith("lantern")) return "lantern";
        if (p.equals("torch") || p.equals("soul_torch")) return "torch";
        if (SOLID.contains(p)) return "solid";
        return "unique:" + id;
    }

    /**
     * @param need item id -> how many the build needs
     * @param have item id -> how many are available (bag + chests)
     */
    public static Result plan(Map<String, Integer> need, Map<String, Integer> have) {
        Map<String, Integer> spare = new HashMap<>();
        for (String id : new TreeSet<>(union(need.keySet(), have.keySet())))
            spare.put(id, have.getOrDefault(id, 0) - need.getOrDefault(id, 0));

        List<String> short_ = new ArrayList<>();
        for (var e : need.entrySet()) if (have.getOrDefault(e.getKey(), 0) < e.getValue()) short_.add(e.getKey());
        short_.sort(Comparator.comparingInt((String id) -> -need.get(id)).thenComparing(Comparator.naturalOrder()));

        Map<String, String> remap = new TreeMap<>();
        Map<String, Integer> missing = new TreeMap<>();
        for (String n : short_) {
            int want = need.get(n);
            String g = group(n);
            String best = null;
            if (!g.startsWith("unique:")) {
                List<String> cands = new ArrayList<>(spare.keySet());
                cands.sort(Comparator.comparingInt((String id) -> -spare.get(id)).thenComparing(Comparator.naturalOrder()));
                for (String a : cands) {
                    if (a.equals(n) || !group(a).equals(g)) continue;
                    if (remap.containsKey(a) || remap.containsValue(n)) continue;       // no chains
                    if (spare.get(a) >= want) { best = a; break; }
                }
            }
            if (best != null) {
                remap.put(n, best);
                spare.merge(best, -want, Integer::sum);
                spare.merge(n, have.getOrDefault(n, 0), Integer::sum);                   // what we do have of n is now free
            } else {
                missing.put(n, want - have.getOrDefault(n, 0));
            }
        }
        return new Result(remap, missing);
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> u = new HashSet<>(a);
        u.addAll(b);
        return u;
    }

    // ---- string form for saving on the entity ---------------------------------------

    public static String serialize(Map<String, String> remap) {
        StringBuilder sb = new StringBuilder();
        for (var e : remap.entrySet()) sb.append(sb.length() > 0 ? ";" : "").append(e.getKey()).append('>').append(e.getValue());
        return sb.toString();
    }

    public static Map<String, String> parse(String s) {
        Map<String, String> m = new TreeMap<>();
        if (s == null || s.isBlank()) return m;
        for (String part : s.split(";")) {
            int i = part.indexOf('>');
            if (i > 0 && i < part.length() - 1) m.put(part.substring(0, i), part.substring(i + 1));
        }
        return m;
    }
}
