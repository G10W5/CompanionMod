package com.example.companionmod.build;

import java.util.Map;

public final class Generators {
    private Generators() {}

    private static final Map<String, Generator> REGISTRY = Map.of(
            "house", new HouseGenerator(),
            "tower", new TowerGenerator());

    /** Single entry point: clamp -> resolve materials -> generate -> normalise to origin (0,0,0). */
    public static Schematic generate(BuildSpec raw, BlockPolicy policy) {
        BuildSpec spec = raw.clamped();
        Materials m = Materials.resolve(spec.materials(), policy);
        return REGISTRY.get(spec.type()).generate(spec, m).normalized();
    }
}
