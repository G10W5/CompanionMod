package com.example.companionmod.world;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/**
 * /cbuild rotate | mirror | up | down | place | cancel | info | stop | resume | subst <true|false>
 * Open to every player (the clickable chat buttons run these), unlike the op-only /companion tree.
 * Register with BuildCommands.register(dispatcher) from ModCommands.register.
 */
public final class BuildCommands {
    private BuildCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("cbuild")
                .then(Commands.literal("rotate").executes(c -> run(c, BuildManager::rotate)))
                .then(Commands.literal("mirror").executes(c -> run(c, BuildManager::mirror)))
                .then(Commands.literal("up").executes(c -> run(c, p -> BuildManager.shift(p, 1))))
                .then(Commands.literal("down").executes(c -> run(c, p -> BuildManager.shift(p, -1))))
                .then(Commands.literal("place").executes(c -> run(c, BuildManager::place)))
                .then(Commands.literal("cancel").executes(c -> run(c, BuildManager::cancel)))
                .then(Commands.literal("info").executes(c -> run(c, BuildManager::info)))
                .then(Commands.literal("stop").executes(c -> run(c, BuildManager::stop)))
                .then(Commands.literal("resume").executes(c -> run(c, BuildManager::resume)))
                .then(Commands.literal("subst")
                        .then(Commands.argument("on", BoolArgumentType.bool())
                                .executes(c -> {
                                    boolean on = BoolArgumentType.getBool(c, "on");
                                    return run(c, p -> BuildManager.substitute(p, on));
                                }))));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Consumer<ServerPlayer> action) {
        try {
            action.accept(ctx.getSource().getPlayerOrException());
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Run this as a player."));
            return 0;
        }
    }
}
