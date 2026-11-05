package com.example.companionmod.build;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Turns a schematic into the order a companion should place it in. */
public final class BuildPlan {
    private BuildPlan() {}

    public record Placement(int dx, int dy, int dz, Blk blk) {}

    /**
     * Structure first, then attachments (doors, ladders, lanterns...). Within each of those: bottom layer first,
     * and inside a layer a serpentine sweep (x ascending, z alternating direction) so the builder walks a short,
     * continuous path instead of jumping around.
     * @param remap block id -> replacement block id (material substitution); may be empty
     */
    public static List<Placement> order(Schematic s, Map<String, String> remap) {
        List<Placement> out = new ArrayList<>(s.count());
        for (var e : s.blocks().entrySet()) {
            Blk b = e.getValue();
            String to = remap.get(b.id());
            if (to != null) b = new Blk(to, b.props());
            var p = e.getKey();
            out.add(new Placement(p.x(), p.y(), p.z(), b));
        }
        out.sort(Comparator
                .comparingInt((Placement p) -> Schematic.isAttachment(p.blk()) ? 1 : 0)
                .thenComparingInt(Placement::dy)
                .thenComparingInt(Placement::dx)
                .thenComparingInt(p -> (p.dx() & 1) == 0 ? p.dz() : -p.dz()));
        return out;
    }
}
