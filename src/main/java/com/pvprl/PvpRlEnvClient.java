package com.pvprl;

import com.pvprl.util.PvpRlCommands;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Client entrypoint. Owns the {@link EnvController} and connects it to the game:
 * an end-of-tick callback drives the environment on the main thread, and the
 * {@code /pvprl} command tree exposes manual control.
 */
@Environment(EnvType.CLIENT)
public class PvpRlEnvClient implements ClientModInitializer {

    private final EnvController controller = new EnvController();

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> controller.tick());

        PvpRlCommands commands = new PvpRlCommands(controller);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                commands.register(dispatcher));

        PvpRlEnv.LOGGER.info("PvP RL client driver + commands registered.");
    }
}
