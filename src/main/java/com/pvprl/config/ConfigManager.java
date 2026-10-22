package com.pvprl.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads, saves and hot-reloads the {@link Config} from
 * {@code <game>/config/pvp-rl-env.json}.
 *
 * <p>The current config is held in an {@link AtomicReference} so it can be read
 * from the client tick thread and swapped by the command thread without locking.</p>
 */
public final class ConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("pvp-rl-env/config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "pvp-rl-env.json";

    private final Path path;
    private final AtomicReference<Config> current = new AtomicReference<>(Config.defaults());

    public ConfigManager() {
        this.path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** @return the currently active configuration (never null). */
    public Config get() {
        return current.get();
    }

    /**
     * Updates the active configuration and persists it to disk.
     *
     * @param config the new configuration
     */
    public void set(Config config) {
        Config validated = config.validated();
        current.set(validated);
        save(validated);
    }

    /**
     * Loads the config from disk, creating it with defaults if absent, and makes
     * it the active config. Safe to call repeatedly for hot reloads.
     *
     * @return the newly active configuration
     */
    public Config loadOrCreate() {
        try {
            if (Files.notExists(path)) {
                Config defaults = Config.defaults();
                save(defaults);
                current.set(defaults);
                LOGGER.info("Wrote default config to {}", path);
                return defaults;
            }
            String json = Files.readString(path);
            Config loaded = GSON.fromJson(json, Config.class);
            if (loaded == null) {
                LOGGER.warn("Config at {} was empty; using defaults", path);
                loaded = Config.defaults();
            }
            Config validated = loaded.validated();
            current.set(validated);
            LOGGER.info("Loaded config from {}", path);
            return validated;
        } catch (Exception e) {
            LOGGER.error("Failed to load config, keeping current/default", e);
            return current.get();
        }
    }

    /**
     * Re-reads the file and swaps the active config. Intended for the
     * {@code /pvprl reload} command.
     *
     * @return the reloaded configuration
     */
    public Config reload() {
        return loadOrCreate();
    }

    /**
     * Writes the given config to disk as pretty JSON.
     *
     * @param config configuration to persist
     */
    public void save(Config config) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(config));
        } catch (IOException e) {
            LOGGER.error("Failed to save config to {}", path, e);
        }
    }
}
