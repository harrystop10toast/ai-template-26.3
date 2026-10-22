package com.pvprl.util;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.pvprl.EnvController;
import com.pvprl.PvpRlEnv;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * Registers the {@code /pvprl} client command tree:
 * <ul>
 *   <li>{@code /pvprl start [noarena]} — open the server, optionally preserving the current world on episode reset</li>
 *   <li>{@code /pvprl stop} — close the server and release inputs</li>
 *   <li>{@code /pvprl reset} — safe arena reset of the player</li>
 *   <li>{@code /pvprl opponent} — compatibility alias for local survival-player control</li>
 *   <li>{@code /pvprl target <entity>} — set the explicit target name (or {@code auto})</li>
 *   <li>{@code /pvprl reload} — hot-reload the config file</li>
 *   <li>{@code /pvprl debug} — toggle the debug overlay</li>
 *   <li>{@code /pvprl status} — print current state</li>
 * </ul>
 *
 * <p>Built with raw Brigadier builders against {@link FabricClientCommandSource}; the Fabric
 * client command callback supplies the dispatcher.</p>
 */
public final class PvpRlCommands {

    private final EnvController controller;

    public PvpRlCommands(EnvController controller) {
        this.controller = controller;
    }

    /**
     * Registers the command tree on the given dispatcher.
     *
     * @param dispatcher the client command dispatcher from the Fabric callback
     */
    public void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        LiteralArgumentBuilder<FabricClientCommandSource> root =
                LiteralArgumentBuilder.literal("pvprl");

        root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("start")
                .executes(ctx -> {
                    controller.start();
                    feedback(ctx.getSource(), "RL environment started on ws://"
                            + PvpRlEnv.cfg().host() + ":" + PvpRlEnv.cfg().port());
                    return 1;
                })
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("noarena")
                        .executes(ctx -> {
                            controller.start(false);
                            feedback(ctx.getSource(), "RL environment started in no-arena mode "
                                    + "(automatic episode resets preserve the world and mobs)");
                            return 1;
                        })));

        root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("stop")
                .executes(ctx -> {
                    controller.stop();
                    feedback(ctx.getSource(), "RL environment stopped");
                    return 1;
                }));

        root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("reset")
                .executes(ctx -> {
                    controller.requestArenaReset();
                    feedback(ctx.getSource(), "Arena reset issued");
                    return 1;
                }));

        root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("summon")
                .executes(ctx -> {
                    boolean ok = controller.summonTarget();
                    feedback(ctx.getSource(), ok
                            ? "Summoned a sparring target (" + controller.getMobType() + ") in front of you"
                            : "Could not summon (no player/connection)");
                    return 1;
                }));

        root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("opponent")
                .executes(ctx -> {
                    boolean ok = controller.summonOpponent();
                    feedback(ctx.getSource(), ok
                            ? "Local survival-player control selected; no entity was spawned or changed. "
                                    + "Use /pvprl start, then run your PPO trainer."
                            : "Could not select local-player control (no player/connection)");
                    return ok ? 1 : 0;
                }));

        root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("mob")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument(
                                "mobType", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String mob = StringArgumentType.getString(ctx, "mobType").trim();
                            if (!mob.contains(":")) {
                                mob = "minecraft:" + mob;
                            }
                            controller.setMobType(mob);
                            feedback(ctx.getSource(), "Sparring mob set to " + mob);
                            return 1;
                        }))
                .executes(ctx -> {
                    feedback(ctx.getSource(), "Current sparring mob: " + controller.getMobType());
                    return 1;
                }));

        root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("target")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument(
                                "player", StringArgumentType.word())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "player");
                            if (name.equalsIgnoreCase("auto")) {
                                controller.setTarget(null);
                                feedback(ctx.getSource(), "Target set to auto (nearest)");
                            } else {
                                controller.setTarget(name);
                                feedback(ctx.getSource(), "Target set to " + name);
                            }
                            return 1;
                        })));

        root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("reload")
                .executes(ctx -> {
                    PvpRlEnv.config().reload();
                    feedback(ctx.getSource(), "Config reloaded");
                    return 1;
                }));

        root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("debug")
                .executes(ctx -> {
                    boolean on = !controller.overlay().isEnabled();
                    controller.overlay().setEnabled(on);
                    feedback(ctx.getSource(), "Debug overlay " + (on ? "enabled" : "disabled"));
                    return 1;
                }));

        root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status")
                .executes(ctx -> {
                    String target = controller.getTarget() == null ? "auto" : controller.getTarget();
                    feedback(ctx.getSource(), String.format(
                            "running=%b control=%s target=%s overlay=%b arenaResets=%b port=%d",
                            controller.isRunning(), target,
                            "self",
                            controller.overlay().isEnabled(), controller.isArenaResetEnabled(),
                            PvpRlEnv.cfg().port()));
                    return 1;
                }));

        dispatcher.register(root);
    }

    private static void feedback(FabricClientCommandSource src, String msg) {
        src.sendFeedback(Component.literal("[pvp-rl] " + msg));
    }
}
