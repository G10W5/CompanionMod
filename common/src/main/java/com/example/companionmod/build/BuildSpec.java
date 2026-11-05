package com.example.companionmod.build;

import java.util.Map;

/** What the language model produces: a handful of small, bounded decisions. Never coordinates. */
public record BuildSpec(String type, String name, int width, int depth, int height, int floors,
                        String roof, String shape, Map<String, String> materials) {

    public static final int MAX_BLOCKS_HINT = 40_000;

    public static BuildSpec defaults(String type) {
        return new BuildSpec(type, "build", 9, 7, 16, 1, "gable", "square", Map.of());
    }

    /** Forces every value into a range the generators are guaranteed to handle. */
    public BuildSpec clamped() {
        String t = "tower".equals(type) ? "tower" : "house";
        String sh = "round".equals(shape) && t.equals("tower") ? "round" : "square";
        int fl = clamp(floors, 1, 4);
        int w = clamp(width, 5, 25);
        int d = clamp(depth, 5, 25);
        if (t.equals("house") && fl > 1) { d = Math.max(d, 7); w = Math.max(w, 5); }
        if (t.equals("tower")) {
            w = clamp(width, sh.equals("round") ? 7 : 5, 15);
            if (w % 2 == 0) w++;                 // odd => a true centre column
            d = w;
        }
        int h = clamp(height, 10, 40);
        String r = "flat".equals(roof) ? "flat" : "gable";
        String nm = name == null ? "build" : name.replaceAll("[^A-Za-z0-9 _-]", "").trim();
        if (nm.isEmpty()) nm = "build";
        if (nm.length() > 40) nm = nm.substring(0, 40);
        return new BuildSpec(t, nm, w, d, h, fl, r, sh, materials == null ? Map.of() : materials);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
