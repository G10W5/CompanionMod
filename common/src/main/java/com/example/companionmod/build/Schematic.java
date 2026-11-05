package com.example.companionmod.build;

import java.util.*;

/** Sparse block grid. Air is simply absent. Generators write into it; export/placement read from it. */
public final class Schematic {

    public record Pos(int x, int y, int z) {}

    private final Map<Pos, Blk> blocks = new HashMap<>();

    public void set(int x, int y, int z, Blk b) {
        Pos p = new Pos(x, y, z);
        if (b == null || b.isAir()) blocks.remove(p);
        else blocks.put(p, b);
    }

    public Blk get(int x, int y, int z) {
        return blocks.getOrDefault(new Pos(x, y, z), Blk.AIR);
    }

    public boolean isSolidAt(int x, int y, int z) {
        return blocks.containsKey(new Pos(x, y, z));
    }

    public void remove(int x, int y, int z) {
        blocks.remove(new Pos(x, y, z));
    }

    public void fill(int x1, int y1, int z1, int x2, int y2, int z2, Blk b) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++)
                    set(x, y, z, b);
    }

    public int count() {
        return blocks.size();
    }

    public Map<Pos, Blk> blocks() {
        return Collections.unmodifiableMap(blocks);
    }

    // ---- bounds / normalisation -------------------------------------------------

    private int[] bounds() {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Pos p : blocks.keySet()) {
            b[0] = Math.min(b[0], p.x); b[1] = Math.min(b[1], p.y); b[2] = Math.min(b[2], p.z);
            b[3] = Math.max(b[3], p.x); b[4] = Math.max(b[4], p.y); b[5] = Math.max(b[5], p.z);
        }
        return b;
    }

    public int sizeX() { return blocks.isEmpty() ? 0 : bounds()[3] - bounds()[0] + 1; }
    public int sizeY() { return blocks.isEmpty() ? 0 : bounds()[4] - bounds()[1] + 1; }
    public int sizeZ() { return blocks.isEmpty() ? 0 : bounds()[5] - bounds()[2] + 1; }

    /** Copy shifted so the minimum corner is (0,0,0). Always call before export/placement. */
    public Schematic normalized() {
        Schematic out = new Schematic();
        if (blocks.isEmpty()) return out;
        int[] b = bounds();
        for (var e : blocks.entrySet()) {
            Pos p = e.getKey();
            out.blocks.put(new Pos(p.x - b[0], p.y - b[1], p.z - b[2]), e.getValue());
        }
        return out;
    }

    // ---- materials ---------------------------------------------------------------

    /** Items needed per block id. Upper door halves are skipped (one door item = two blocks);
     *  double slabs count twice. */
    public Map<String, Integer> itemCounts() {
        Map<String, Integer> m = new TreeMap<>();
        for (Blk b : blocks.values()) {
            if ("upper".equals(b.props().get("half")) && b.path().endsWith("_door")) continue;
            if (b.path().endsWith("_bed") && "head".equals(b.props().get("part"))) continue;
            int n = "double".equals(b.props().get("type")) && b.path().endsWith("_slab") ? 2 : 1;
            m.merge(b.id(), n, Integer::sum);
        }
        return m;
    }

    // ---- build order ------------------------------------------------------------

    private static final String[] ATTACH_SUFFIX = {
            "_door", "ladder", "torch", "lantern", "_sign", "_button", "lever", "_carpet",
            "_banner", "_pressure_plate", "_trapdoor", "_bed", "_hanging_sign"};

    /** Blocks that need something to hold them; they are placed after all structural blocks. */
    public static boolean isAttachment(Blk b) {
        String p = b.path();
        for (String s : ATTACH_SUFFIX) if (p.endsWith(s)) return true;
        return false;
    }

    /** Placement order: structure first (bottom-up), attachments last (bottom-up). */
    public List<Map.Entry<Pos, Blk>> buildOrder() {
        List<Map.Entry<Pos, Blk>> list = new ArrayList<>(blocks.entrySet());
        list.sort(Comparator
                .comparingInt((Map.Entry<Pos, Blk> e) -> isAttachment(e.getValue()) ? 1 : 0)
                .thenComparingInt(e -> e.getKey().y)
                .thenComparingInt(e -> e.getKey().x)
                .thenComparingInt(e -> e.getKey().z));
        return list;
    }
}
