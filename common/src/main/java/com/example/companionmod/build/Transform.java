package com.example.companionmod.build;

import java.util.HashMap;
import java.util.Map;

/** Rotation (90-degree steps, clockwise seen from above) and mirroring, including blockstate properties. */
public final class Transform {
    private Transform() {}

    private static final String[] DIRS = {"north", "east", "south", "west"};   // clockwise order

    private static int idx(String d) {
        for (int i = 0; i < 4; i++) if (DIRS[i].equals(d)) return i;
        return -1;
    }

    /** The one transform used everywhere (preview, bill of materials, building): mirror first, then rotate. */
    public static Schematic apply(Schematic s, int quarterTurns, boolean mirror) {
        return rotate(mirror ? mirrorX(s) : s.normalized(), quarterTurns);
    }

    /** Rotates the schematic by quarterTurns * 90 degrees clockwise. Input must be normalised. */
    public static Schematic rotate(Schematic s, int quarterTurns) {
        int q = ((quarterTurns % 4) + 4) % 4;
        Schematic cur = s.normalized();
        for (int i = 0; i < q; i++) cur = rotateOnce(cur);
        return cur;
    }

    private static Schematic rotateOnce(Schematic s) {
        int sz = s.sizeZ();
        Schematic out = new Schematic();
        for (var e : s.blocks().entrySet()) {
            var p = e.getKey();
            out.set(sz - 1 - p.z(), p.y(), p.x(), rotateState(e.getValue()));
        }
        return out;
    }

    private static Blk rotateState(Blk b) {
        if (b.props().isEmpty()) return b;
        Map<String, String> n = new HashMap<>(b.props());
        String f = n.get("facing");
        if (f != null && idx(f) >= 0) n.put("facing", DIRS[(idx(f) + 1) % 4]);
        String ax = n.get("axis");
        if ("x".equals(ax)) n.put("axis", "z"); else if ("z".equals(ax)) n.put("axis", "x");
        String rot = n.get("rotation");                       // signs/banners 0..15
        if (rot != null) try { n.put("rotation", String.valueOf((Integer.parseInt(rot) + 4) % 16)); } catch (NumberFormatException ignored) {}
        // connection properties (panes, fences, walls): value of dir moves to dir.cw
        String[] old = new String[4];
        boolean any = false;
        for (int i = 0; i < 4; i++) { old[i] = b.props().get(DIRS[i]); any |= old[i] != null; }
        if (any) for (int i = 0; i < 4; i++) if (old[i] != null) n.put(DIRS[(i + 1) % 4], old[i]);
        return new Blk(b.id(), n);
    }

    /** Mirrors along X (east <-> west). */
    public static Schematic mirrorX(Schematic s) {
        Schematic in = s.normalized();
        int sx = in.sizeX();
        Schematic out = new Schematic();
        for (var e : in.blocks().entrySet()) out.set(sx - 1 - e.getKey().x(), e.getKey().y(), e.getKey().z(), mirrorState(e.getValue()));
        return out;
    }

    private static String swapLR(String v) {
        return v.replace("left", "@").replace("right", "left").replace("@", "right");
    }

    private static Blk mirrorState(Blk b) {
        if (b.props().isEmpty()) return b;
        Map<String, String> n = new HashMap<>(b.props());
        String f = n.get("facing");
        if ("east".equals(f)) n.put("facing", "west"); else if ("west".equals(f)) n.put("facing", "east");
        if (n.containsKey("shape")) n.put("shape", swapLR(n.get("shape")));        // stairs inner/outer left/right
        if (n.containsKey("hinge")) n.put("hinge", swapLR(n.get("hinge")));
        String e = n.get("east"), w = n.get("west");
        if (e != null && w != null) { n.put("east", w); n.put("west", e); }
        return new Blk(b.id(), n);
    }
}
