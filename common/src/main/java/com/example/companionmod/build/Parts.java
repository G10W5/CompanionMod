package com.example.companionmod.build;

/** Small shared pieces so generators never have to know blockstate property names. */
final class Parts {
    private Parts() {}

    static Blk trim(Materials m, String axis) {
        Blk b = Blk.of(m.trim());
        return Materials.isLogLike(m.trim()) ? b.with("axis", axis) : b;
    }

    /** Door in a wall on the z=0 face, outside toward -z (north). Occupies y and y+1. */
    static void door(Schematic s, int x, int y, int z, Materials m) {
        Blk d = Blk.of(m.door()).with("facing", "north", "hinge", "left", "open", "false", "powered", "false");
        s.set(x, y, z, d.with("half", "lower"));
        s.set(x, y + 1, z, d.with("half", "upper"));
    }

    /** Window block for a wall running along X (true) or along Z (false). Null = leave a hole. */
    static Blk window(Materials m, boolean wallAlongX) {
        if (m.window().equals("air")) return null;
        Blk b = Blk.of(m.window());
        if (!Materials.isPaneLike(m.window())) return b;
        return wallAlongX
                ? b.with("east", "true", "west", "true", "north", "false", "south", "false")
                : b.with("north", "true", "south", "true", "east", "false", "west", "false");
    }

    static void setWindow(Schematic s, int x, int y, int z, Materials m, boolean wallAlongX) {
        Blk w = window(m, wallAlongX);
        if (w == null) s.remove(x, y, z); else s.set(x, y, z, w);
    }

    static boolean hangs(Materials m) {
        return m.light().endsWith("lantern");
    }

    static Blk light(Materials m, boolean hanging) {
        Blk b = Blk.of(m.light());
        return hangs(m) ? b.with("hanging", String.valueOf(hanging)) : b;
    }

    static Blk stair(Materials m, String facing) {
        return Blk.of(Materials.stairsOf(m.roof())).with("facing", facing, "half", "bottom", "shape", "straight");
    }
}
