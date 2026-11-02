package com.example.companionmod;

import net.minecraft.resources.Identifier;

import java.nio.file.Path;

public final class CompanionMod {
    public static final String MOD_ID = "companionmod";
    private static volatile Path configDir = Path.of("config");
    private CompanionMod() {}

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path); // 26.1-CHECK: was ResourceLocation before 26.1
    }

    /** The loader's real config directory (set from each loader's entrypoint; never rely on the working directory). */
    public static Path configDir() { return configDir; }

    public static void setConfigDir(Path dir) { if (dir != null) configDir = dir; }
}
