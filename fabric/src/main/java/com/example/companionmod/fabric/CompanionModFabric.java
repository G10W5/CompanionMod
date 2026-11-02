package com.example.companionmod.fabric;

import com.example.companionmod.CompanionMod;
import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.net.CommandHandler;
import com.example.companionmod.net.CommandPayload;
import com.example.companionmod.registry.ModContent;
import com.example.companionmod.world.ModCommands;
import com.example.companionmod.world.ServerHooks;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTabs;

/** Fabric entrypoint. Mirrors CompanionModNeoForge one-to-one; the real logic lives in common/. */
public class CompanionModFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        CompanionMod.setConfigDir(FabricLoader.getInstance().getConfigDir());

        Registry.register(BuiltInRegistries.BLOCK, ModContent.blockId(), ModContent.SHRINE_CORE);
        Registry.register(BuiltInRegistries.ITEM, ModContent.itemId(), ModContent.SHRINE_CORE_ITEM);
        Registry.register(BuiltInRegistries.ENTITY_TYPE, ModContent.entityId(), ModContent.COMPANION);
        Registry.register(BuiltInRegistries.MENU, ModContent.menuId(), ModContent.COMPANION_INV_MENU);   // was registered twice -> duplicate-key crash
        FabricDefaultAttributeRegistry.register(ModContent.COMPANION, CompanionEntity.createAttributes());

        PayloadTypeRegistry.serverboundPlay().register(CommandPayload.TYPE, CommandPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(CommandPayload.TYPE, (payload, ctx) -> CommandHandler.handle(ctx.player(), payload));

        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) -> ModCommands.register(dispatcher));

        // 26.1-CHECK: ServerPlayConnectionEvents lives in networking.v1 (NOT event.lifecycle.v1); getPlayer() on the handler.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> ServerHooks.onPlayerJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ServerHooks.onPlayerLeave(handler.getPlayer()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerHooks.onServerStopped());
        // 26.1-CHECK: itemgroup.v1.ItemGroupEvents was replaced by creativetab.v1.CreativeModeTabEvents in this Fabric API
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS)
                .register(output -> output.accept(ModContent.SHRINE_CORE_ITEM));
    }
}
