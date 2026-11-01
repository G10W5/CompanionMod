package com.example.companionmod;

import net.minecraft.resources.Identifier;

public final class CompanionMod {
    public static final String MOD_ID = "companionmod";
    private CompanionMod() {}

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path); // 26.1-CHECK: was ResourceLocation before 26.1
    }
}
