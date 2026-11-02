package com.example.companionmod.neoforge;

import com.example.companionmod.CompanionMod;
import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.net.CommandHandler;
import com.example.companionmod.net.CommandPayload;
import com.example.companionmod.registry.ModContent;
import com.example.companionmod.world.ModCommands;
import com.example.companionmod.world.ServerHooks;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

/** NeoForge entrypoint. Mirrors CompanionModFabric one-to-one; the real logic lives in common/. */
@Mod(CompanionMod.MOD_ID)
public class CompanionModNeoForge {
    public CompanionModNeoForge(IEventBus modBus) {
        CompanionMod.setConfigDir(FMLPaths.CONFIGDIR.get());

        modBus.addListener(RegisterEvent.class, e -> {
            e.register(Registries.BLOCK, h -> h.register(ModContent.blockId(), ModContent.SHRINE_CORE));
            e.register(Registries.ITEM, h -> h.register(ModContent.itemId(), ModContent.SHRINE_CORE_ITEM));
            e.register(Registries.ENTITY_TYPE, h -> h.register(ModContent.entityId(), ModContent.COMPANION));
            e.register(Registries.MENU, h -> h.register(ModContent.menuId(), ModContent.COMPANION_INV_MENU));
        });
        modBus.addListener(EntityAttributeCreationEvent.class, e -> e.put(ModContent.COMPANION, CompanionEntity.createAttributes().build()));
        modBus.addListener(RegisterPayloadHandlersEvent.class, e ->
                e.registrar("1").playToServer(CommandPayload.TYPE, CommandPayload.CODEC,
                        (payload, ctx) -> CommandHandler.handle((ServerPlayer) ctx.player(), payload)));
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, e -> {
            if (e.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) e.accept(ModContent.SHRINE_CORE_ITEM);
        });

        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, e -> ModCommands.register(e.getDispatcher()));
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer sp) ServerHooks.onPlayerJoin(sp);
        });
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer sp) ServerHooks.onPlayerLeave(sp);
        });
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, e -> ServerHooks.onServerStopped());
    }
}
