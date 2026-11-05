package com.example.companionmod.world;

import com.example.companionmod.command.CompanionCommand;
import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.registry.ModContent;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /companion shrine                 place a shrine at your feet
 * /companion summon                 summon / call your companion at your feet (testing)
 * /companion do <action> [target]   run any voice action without a microphone (follow, stay, sit, attack creeper, ...)
 * /companion skin <playerName>      wear that player's Minecraft skin ("" clears it)
 * /companion feminine <true|false>  toggle the feminine chest model
 */
public final class ModCommands {
    private ModCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        BuildCommands.register(d);          // /cbuild ... (open to every player; the chat buttons run these)
        d.register(Commands.literal("companion")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))   // 26.1-CHECK: permission API was reworked in 1.21.11; older form: src -> src.hasPermission(2)
                .then(Commands.literal("shrine").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    var core = ShrinePlacer.place((net.minecraft.server.level.ServerLevel) p.level(), p.blockPosition().offset(3, 0, 3));
                    ctx.getSource().sendSuccess(() -> Component.literal("Shrine placed at " + core.toShortString()), true);
                    return 1;
                }))
                .then(Commands.literal("summon").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    ShrineSummoner.summon((net.minecraft.server.level.ServerLevel) p.level(), p.blockPosition(), p);
                    return 1;
                }))
                .then(Commands.literal("do")
                        .then(Commands.argument("action", StringArgumentType.word())
                                .executes(ctx -> doCommand(ctx, ""))
                                .then(Commands.argument("target", StringArgumentType.greedyString())
                                        .executes(ctx -> doCommand(ctx, StringArgumentType.getString(ctx, "target"))))))
                .then(Commands.literal("skin")
                        .then(Commands.argument("playerName", StringArgumentType.greedyString()).executes(ctx -> {
                            CompanionEntity c = companion(ctx);
                            if (c == null) return 0;
                            c.setSkin(StringArgumentType.getString(ctx, "playerName"));
                            ctx.getSource().sendSuccess(() -> Component.literal("Companion skin set to " + c.skin()), false);
                            return 1;
                        })))
                .then(Commands.literal("feminine")
                        .then(Commands.argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
                            CompanionEntity c = companion(ctx);
                            if (c == null) return 0;
                            boolean on = BoolArgumentType.getBool(ctx, "enabled");
                            c.setFeminine(on);
                            ctx.getSource().sendSuccess(() -> Component.literal("Feminine model: " + on), false);
                            return 1;
                        })))
                .then(Commands.literal("revive").executes(ctx -> {
                    CompanionEntity c = companion(ctx);
                    if (c == null) return 0;
                    c.revive();
                    ctx.getSource().sendSuccess(() -> Component.literal("Companion revived"), false);
                    return 1;
                }))
                .then(Commands.literal("find")
                        .then(Commands.argument("block", StringArgumentType.greedyString()).executes(ctx -> {
                            CompanionEntity c = companion(ctx);
                            if (c == null) return 0;
                            String id = CompanionEntity.normalizeBlockId(StringArgumentType.getString(ctx, "block"));
                            if (id.isBlank()) {
                                ctx.getSource().sendFailure(Component.literal("Unknown block."));
                                return 0;
                            }
                            c.setSeekBlock(id);
                            c.speak("Looking for " + CompanionEntity.prettyBlockId(id) + ".");
                            ctx.getSource().sendSuccess(() -> Component.literal("Seeking " + id), false);
                            return 1;
                        })))
                .then(Commands.literal("name")
                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                            CompanionEntity c = companion(ctx);
                            if (c == null) return 0;
                            String name = StringArgumentType.getString(ctx, "name");
                            c.setCustomName(Component.literal(name));
                            c.setCustomNameVisible(false);
                            ctx.getSource().sendSuccess(() -> Component.literal("Companion renamed to " + name), false);
                            return 1;
                        }))));
    }

    private static CompanionEntity companion(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            CompanionEntity c = ModContent.findCompanion(p.level().getServer(), p.getUUID());
            if (c == null) ctx.getSource().sendFailure(Component.literal("You have no companion."));
            return c;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Run this as a player."));
            return null;
        }
    }

    private static int doCommand(CommandContext<CommandSourceStack> ctx, String target) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            CompanionEntity c = ModContent.findCompanion(p.level().getServer(), p.getUUID());
            if (c == null) { ctx.getSource().sendFailure(Component.literal("You have no companion.")); return 0; }
            CompanionCommand cmd = CompanionCommand.parse(StringArgumentType.getString(ctx, "action"));
            c.applyCommand(cmd, p, target);
            ctx.getSource().sendSuccess(() -> Component.literal("Companion: " + cmd + (target.isBlank() ? "" : " -> " + target)), false);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Run this as a player."));
            return 0;
        }
    }
}
