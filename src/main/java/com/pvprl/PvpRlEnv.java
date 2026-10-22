package com.pvprl;

import com.pvprl.config.Config;
import com.pvprl.config.ConfigManager;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint for the PvP RL environment mod.
 *
 * <p>Runs on both client and dedicated server. It owns the shared, environment-agnostic
 * state: the {@link ConfigManager}. Client-side control (ticking, input, observations,
 * network) is wired up in {@link PvpRlEnvClient} because the first iteration drives the
 * local client player.</p>
 */
public class PvpRlEnv implements ModInitializer {
    /** Canonical mod id, matching {@code fabric.mod.json}. */
    public static final String MOD_ID = "pvp-rl-env";

    /** Shared logger, named after the mod id by convention. */
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final ConfigManager CONFIG = new ConfigManager();

    @Override
    public void onInitialize() {
        CONFIG.loadOrCreate();
        LOGGER.info("PvP RL environment initialized (config loaded).");
    }

    /** @return the shared configuration manager (hot-reloadable). */
    public static ConfigManager config() {
        return CONFIG;
    }

    /** @return the active configuration snapshot. */
    public static Config cfg() {
        return CONFIG.get();
    }

    /**
     * Builds a namespaced {@link Identifier} under this mod's id.
     *
     * @param path resource path
     * @return the namespaced identifier
     */
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
