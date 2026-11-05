package com.example.companionmod.build;

@FunctionalInterface
public interface Generator {
    /** spec is already clamped. Result may have negative coordinates; callers normalise. */
    Schematic generate(BuildSpec spec, Materials m);
}
