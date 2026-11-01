package com.example.companionmod.fabric;

import com.example.companionmod.CompanionMod;
import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.net.CommandHandler;
import com.example.companionmod.net.CommandPayload;
import com.example.companionmod.registry.ModContent;
import com.example.companionmod.world.ModCommands;
import com.example.companionmod.world.ShrinePlacer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
// import net.fabricmc.fabric.api.event.lifecycle.v1.ServerPlayConnectionEvents; // not available in this Fabric API version

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTabs;

/** Fabric entrypoint. NOTE: every Fabric API package/method name below is a 26.1-CHECK: compare with the Fabric API 26.1 porting guide. */
public class CompanionModFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.BLOCK, ModContent.blockId(), ModContent.SHRINE_CORE);
        Registry.register(BuiltInRegistries.ITEM, ModContent.itemId(), ModContent.SHRINE_CORE_ITEM);
        Registry.register(BuiltInRegistries.ENTITY_TYPE, ModContent.entityId(), ModContent.COMPANION);
        Registry.register(BuiltInRegistries.MENU, ModContent.menuId(), ModContent.COMPANION_INV_MENU);
        FabricDefaultAttributeRegistry.register(ModContent.COMPANION, CompanionEntity.createAttributes());

        PayloadTypeRegistry.serverboundPlay().register(CommandPayload.TYPE, CommandPayload.CODEC);   // 26.1-CHECK: may be serverboundPlay()
        ServerPlayNetworking.registerGlobalReceiver(CommandPayload.TYPE, (payload, ctx) -> CommandHandler.handle(ctx.player(), payload));

        Registry.register(BuiltInRegistries.MENU, ModContent.menuId(), ModContent.COMPANION_INV_MENU);

        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) -> ModCommands.register(dispatcher));
    }
}
