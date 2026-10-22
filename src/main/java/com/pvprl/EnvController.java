package com.pvprl;

import com.pvprl.action.ActionExecutor;
import com.pvprl.action.DiscreteAction;
import com.pvprl.config.Config;
import com.pvprl.net.PendingAction;
import com.pvprl.net.Protocol;
import com.pvprl.net.RlServer;
import com.pvprl.obs.Observation;
import com.pvprl.obs.ObservationCollector;
import com.pvprl.obs.ObservationSerializer;
import com.pvprl.reward.EpisodeEvent;
import com.pvprl.reward.RewardSnapshot;
import com.pvprl.reward.RewardTracker;
import com.pvprl.util.ArenaReset;
import com.pvprl.util.DebugOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.java_websocket.WebSocket;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Central coordinator for the RL environment on the client.
 *
 * <p>Owns the network server, observation collector, action executor, reward tracker and
 * debug overlay, and drives them from a single end-of-client-tick callback so that all
 * Minecraft state access happens on the main thread. Network I/O happens on the server's own
 * thread; only decoded commands cross back to the main thread via a queue.</p>
 *
 * <p>Lifecycle: {@link #start()} opens the socket, {@link #stop()} closes it and releases any
 * held inputs. {@link #tick()} is idempotent when stopped or when there is no player.</p>
 */
public final class EnvController {

    private final ObservationCollector collector = new ObservationCollector();
    private final ActionExecutor executor = new ActionExecutor();
    private final RewardTracker reward = new RewardTracker();
    private final DebugOverlay overlay = new DebugOverlay();

    private RlServer server;
    private volatile boolean running;
    /** Whether protocol/watchdog episode resets may modify the arena and spawn a target. */
    private boolean arenaResetEnabled = true;
    private long tickCounter;
    private Observation lastObservation;
    /** Player preference restored after a real-time RL session ends. */
    private Boolean pauseOnLostFocusBeforeStart;

    /** Target tracking: log whenever the target entity changes. */
    private String lastLoggedTarget = "";

    /**
     * Synchronized reset state machine:
     * 0: idle / running normally
     * 1: phase-1 issued (respawn, teleport, clear), waiting for server to settle
     * 2: phase-2 issued (kit equip, summon mob), waiting for entity to sync to client
     */
    private int resetStage = 0;
    private long resetScheduledTick = -1;
    private static final int RESET_PHASE2_DELAY = 6;
    /** Extra settle ticks so summoned armor stands / mobs sync before target auto. */
    private static final int RESET_SETTLE_DELAY = 8;
    private static final int SURVIVAL_RESET_SETTLE_DELAY = 12;
    private static final int AIM_GATED_RESET_SETTLE_DELAY = 8;

    /**
     * Ticks of the last observed combat engagement or damage event.
     * When 30 seconds (600 ticks) elapse with no mob attacking the agent (e.g. mob died from
     * terrain or disengaged), the watchdog triggers an automatic episode reset.
     */
    private long lastCombatActivityTick = 0;
    private static final int NO_ATTACK_RESET_TICKS = 30 * 20; // 30 seconds at 20 tps
    private static final String MOVEMENT_MANNEQUIN_NAME = "PvpRlMovementTarget";
    private static final String MOVEMENT_MANNEQUIN_TAG = "pvprl_movement_target";
    /** How often the movement curriculum replaces the mannequin (20s at 20 tps). */
    private static final long MANNEQUIN_SPAWN_INTERVAL_TICKS = 20 * 20;
    private static final double MANNEQUIN_SPAWN_RADIUS = 100.0;
    /** Survival curriculum full episode refresh (1 minute at 20 tps). */
    private static final long SURVIVAL_REFRESH_INTERVAL_TICKS = 60 * 20;
    /** Aim-gated: mannequin respawn interval (2 minutes at 20 tps). */
    private static final long AIM_GATED_SPAWN_INTERVAL_TICKS = 120 * 20;
    /** Aim-gated: max mannequin spawn radius (10 blocks). */
    private static final double AIM_GATED_SPAWN_RADIUS = 10.0;
    /** Flee-aim episodes last 2400 steps; apply low-HP damage after one minute. */
    private static final long AIM_GATED_DAMAGE_DELAY_TICKS = 60 * 20;
    /** Set only by the movement-only Python environment through the wire protocol. */
    private boolean movementTraining;
    /** Set only by the survival-only Python environment through the wire protocol. */
    private boolean survivalTraining;
    /**
     * Health-gated look-only curriculum: each reset starts at full HP (look-AT), then
     * applies damage halfway through the episode for look-AWAY training.
     */
    private boolean aimGatedTraining;
    private long nextMannequinSpawnTick = Long.MAX_VALUE;
    /** Prevents duplicate summon commands while the server syncs a new stand to the client. */
    private long lastMannequinSpawnTick = Long.MIN_VALUE / 2;
    /** Next tick at which survival training forces a fresh flee-and-eat episode. */
    private long nextSurvivalRefreshTick = Long.MAX_VALUE;
    /** Aim-gated: next tick to respawn the mannequin (30s cycle). */
    private long nextAimGatedSpawnTick = Long.MAX_VALUE;
    /** Aim-gated: next tick to transition from looking at to looking away. */
    private long nextAimGatedDamageTick = Long.MAX_VALUE;

    /** Starts the WebSocket server with normal arena-reset behaviour. */
    public synchronized void start() {
        start(true);
    }

    /**
     * Starts the WebSocket server and chooses how automatic episode resets behave.
     * With {@code false}, Gym/protocol resets only clear RL state; they never teleport,
     * heal, change inventory, kill, or summon entities in the current world.
     *
     * @param manageArena whether automatic episode resets manage the configured arena
     */
    public synchronized void start(boolean manageArena) {
        arenaResetEnabled = manageArena;
        keepGameRunningWhenUnfocused();
        if (running) {
            PvpRlEnv.LOGGER.info("EnvController arena reset mode changed: {}.",
                    manageArena ? "managed" : "no-arena");
            return;
        }
        Config cfg = PvpRlEnv.cfg();
        executor.setHoldForwardWhileSprinting(true);
        server = new RlServer(cfg.host(), cfg.port());
        server.setDaemon(true);
        server.start();
        running = true;
        reward.resetEpisode();
        lastCombatActivityTick = tickCounter;
        PvpRlEnv.LOGGER.info("EnvController started (ws://{}:{}, arena resets={}).",
                cfg.host(), cfg.port(), manageArena ? "managed" : "disabled");
    }

    /** Stops the server and releases all driven inputs. Safe to call when already stopped. */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        resetStage = 0;
        resetScheduledTick = -1;
        arenaResetEnabled = true;
        executor.setHoldForwardWhileSprinting(false);
        if (aimGatedTraining) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null && player.connection != null) {
                player.connection.sendCommand("gamerule naturalRegeneration true");
            }
        }
        movementTraining = false;
        survivalTraining = false;
        aimGatedTraining = false;
        nextMannequinSpawnTick = Long.MAX_VALUE;
        lastMannequinSpawnTick = Long.MIN_VALUE / 2;
        nextSurvivalRefreshTick = Long.MAX_VALUE;
        nextAimGatedSpawnTick = Long.MAX_VALUE;
        nextAimGatedDamageTick = Long.MAX_VALUE;
        executor.setSuppressAttacks(false);
        executor.setMaxPitchDegrees(90.0f);
        restorePauseOnLostFocus();
        executor.releaseAll(Minecraft.getInstance());
        if (server != null) {
            try {
                server.stop(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            server = null;
        }
        PvpRlEnv.LOGGER.info("EnvController stopped.");
    }

    /**
     * The Gym process normally owns keyboard focus.  In an integrated-server world the
     * vanilla default pauses both the game and this client tick loop when focus moves to
     * that process, which otherwise looks like a WebSocket timeout.  This is intentionally
     * a temporary runtime override: the user's option is restored by {@link #stop()}.
     */
    private void keepGameRunningWhenUnfocused() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) {
            return;
        }
        if (pauseOnLostFocusBeforeStart == null) {
            pauseOnLostFocusBeforeStart = mc.options.pauseOnLostFocus;
        }
        mc.options.pauseOnLostFocus = false;
    }

    /** Restores the pause-on-focus preference that was active before this session. */
    private void restorePauseOnLostFocus() {
        if (pauseOnLostFocusBeforeStart == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null) {
            mc.options.pauseOnLostFocus = pauseOnLostFocusBeforeStart;
        }
        pauseOnLostFocusBeforeStart = null;
    }

    /** @return whether the environment is currently running. */
    public boolean isRunning() {
        return running;
    }

    /** @return whether protocol/watchdog resets currently manage the arena. */
    public boolean isArenaResetEnabled() {
        return arenaResetEnabled;
    }

    /** @return the debug overlay (toggle via commands). */
    public DebugOverlay overlay() {
        return overlay;
    }

    /**
     * Sets the explicit target entity name, or clears it for automatic nearest-target selection.
     * Passing {@code null}/{@code "auto"} is exactly {@code /pvprl target auto}.
     */
    public void setTarget(String player) {
        boolean auto = player == null || "auto".equalsIgnoreCase(player);
        collector.setTarget(auto ? null : player);
    }

    /** Same as {@code /pvprl target auto}: clear sticky lock and re-pick nearest (prefers mannequins). */
    private void retargetAuto() {
        collector.reset();
        collector.setTarget(null);
    }

    /** @return the current explicit target name, or null. */
    public String getTarget() {
        return collector.getTarget();
    }

    /**
     * Updates the sparring mob type in the arena configuration and saves it.
     *
     * @param mobType entity id (e.g. "minecraft:vindicator")
     */
    public void setMobType(String mobType) {
        Config cfg = PvpRlEnv.cfg();
        Config.Arena a = cfg.arena();
        Config.Arena newArena = new Config.Arena(
                a.x(), a.y(), a.z(),
                a.healOnReset(), a.clearEffects(), a.kit(),
                a.resistanceOnReset(), a.summonTargetOnReset(),
                mobType, a.targetResistance(), a.targetSpawnDistance()
        );
        Config updated = new Config(
                cfg.enabled(), cfg.host(), cfg.port(), cfg.obsEveryNTicks(),
                cfg.nearbyRadius(), cfg.maxNearbyEntities(), cfg.lookStepDegrees(),
                cfg.fineLookStepDegrees(), cfg.fastLookMultiplier(),
                newArena, cfg.reward()
        );
        PvpRlEnv.config().set(updated);
        PvpRlEnv.LOGGER.info("Sparring mob type updated to: {}", mobType);
    }

    /** @return the configured sparring mob type. */
    public String getMobType() {
        return PvpRlEnv.cfg().arena().targetEntity();
    }

    /** Enables/disables the non-combat mannequin curriculum for the connected trainer. */
    private void setMovementTraining(boolean enabled) {
        movementTraining = enabled;
        executor.setHoldForwardWhileSprinting(enabled);
        if (enabled) {
            retargetAuto(); // /pvprl target auto
            nextMannequinSpawnTick = tickCounter;
            lastMannequinSpawnTick = Long.MIN_VALUE / 2;
            PvpRlEnv.LOGGER.info("Movement curriculum enabled: mannequin target every 20 seconds within 100 blocks; "
                    + "forward stays held while sprint is latched.");
        } else {
            retargetAuto();
            nextMannequinSpawnTick = Long.MAX_VALUE;
            PvpRlEnv.LOGGER.info("Movement curriculum disabled.");
        }
    }

    /** Enables/disables health-gated look-at / look-away aiming (no movement curriculum). */
    private void setAimGatedTraining(boolean enabled) {
        aimGatedTraining = enabled;
        if (enabled) {
            retargetAuto(); // /pvprl target auto — prefers aim-gated mannequin
            nextAimGatedSpawnTick = tickCounter; // spawn immediately
            nextAimGatedDamageTick = Long.MAX_VALUE;
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null && player.connection != null) {
                player.connection.sendCommand("gamerule naturalRegeneration false");
            }
            // Keep the crosshair near eye-level — sky/ground stare is useless in PvP.
            executor.setMaxPitchDegrees(ActionExecutor.PVP_MAX_PITCH_DEGREES);
            PvpRlEnv.LOGGER.info(
                    "Aim-gated curriculum enabled: start at 20hp, then transition to LOOK-AWAY (9hp) "
                    + "halfway through each episode. "
                    + "Mannequin refreshes every 2 minutes within 10 blocks. Pitch clamped to ±{}°. "
                    + "Natural regeneration disabled; target auto on every refresh.",
                    ActionExecutor.PVP_MAX_PITCH_DEGREES);
        } else {
            nextAimGatedSpawnTick = Long.MAX_VALUE;
            nextAimGatedDamageTick = Long.MAX_VALUE;
            executor.setMaxPitchDegrees(90.0f);
            retargetAuto();
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null && player.connection != null) {
                player.connection.sendCommand("gamerule naturalRegeneration true");
            }
            PvpRlEnv.LOGGER.info("Aim-gated curriculum disabled (natural regeneration restored).");
        }
    }

    /** Enables/disables the survival flee-and-eat curriculum for the connected trainer. */
    private void setSurvivalTraining(boolean enabled) {
        survivalTraining = enabled;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (enabled) {
            retargetAuto(); // /pvprl target auto — prefers survival mannequin
            nextSurvivalRefreshTick = tickCounter + SURVIVAL_REFRESH_INTERVAL_TICKS;
            if (player != null && player.connection != null) {
                player.connection.sendCommand("gamerule naturalRegeneration false");
            }
            PvpRlEnv.LOGGER.info(
                    "Survival curriculum enabled: flee/eat (≥10 blocks), refresh every 60s; naturalRegen off.");
        } else {
            retargetAuto();
            nextSurvivalRefreshTick = Long.MAX_VALUE;
            executor.setSuppressAttacks(false);
            if (player != null && player.connection != null) {
                player.connection.sendCommand("gamerule naturalRegeneration true");
            }
            PvpRlEnv.LOGGER.info("Survival curriculum disabled (naturalRegen restored).");
        }
    }

    /** Replaces the current mannequin with one at a random horizontal location within 100 blocks. */
    private boolean spawnMovementMannequin(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || player.connection == null) {
            return false;
        }
        double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0);
        // Avoid trivial spawns while retaining a uniform, bounded traversal objective.
        double distance = ThreadLocalRandom.current().nextDouble(12.0, MANNEQUIN_SPAWN_RADIUS);
        double x = player.getX() + Math.cos(angle) * distance;
        double z = player.getZ() + Math.sin(angle) * distance;
        // Start at the player's feet height; the stand then settles naturally on the
        // local terrain instead of assuming the bundled arena's Y level.
        double y = player.getY();
        String pos = ArenaReset.fmt(x) + " " + ArenaReset.fmt(y) + " " + ArenaReset.fmt(z);

        // The explicit name is both the target selector and a stable human-visible label.
        Config.Arena arena = PvpRlEnv.cfg().arena();
        if (arena.targetEntity() != null && !arena.targetEntity().isBlank()) {
            player.connection.sendCommand("kill @e[type=" + arena.targetEntity() + "]");
        }
        // Remove every previous curriculum target, including any stand from an
        // interrupted/older run that predates the tag, before creating exactly one.
        player.connection.sendCommand("kill @e[tag=" + MOVEMENT_MANNEQUIN_TAG + "]");
        player.connection.sendCommand("kill @e[type=minecraft:armor_stand,name="
                + MOVEMENT_MANNEQUIN_NAME + "]");
        player.connection.sendCommand("summon minecraft:armor_stand " + pos
                + " {CustomName:'{\"text\":\"" + MOVEMENT_MANNEQUIN_NAME
                + "\"}',Tags:[\"" + MOVEMENT_MANNEQUIN_TAG
                + "\"],CustomNameVisible:1b,Invulnerable:1b,NoBasePlate:1b}");
        retargetAuto(); // /pvprl target auto after every mannequin refresh
        lastMannequinSpawnTick = tickCounter;
        PvpRlEnv.LOGGER.info("Spawned movement mannequin at ({}, {}, {}), distance {}",
                ArenaReset.fmt(x), ArenaReset.fmt(y), ArenaReset.fmt(z), distance);
        return true;
    }

    /** @return whether a live mannequin from this curriculum is currently synced to the client. */
    private boolean hasLiveMovementMannequin(LocalPlayer player, ClientLevel level) {
        return !level.getEntitiesOfClass(ArmorStand.class,
                        player.getBoundingBox().inflate(MANNEQUIN_SPAWN_RADIUS + 8.0),
                        stand -> stand.isAlive() && isMovementMannequin(stand))
                .isEmpty();
    }

    /** Matches by tag, with custom-name fallback when tags have not synced yet. */
    private static boolean isMovementMannequin(ArmorStand stand) {
        if (stand.entityTags().contains(MOVEMENT_MANNEQUIN_TAG)) {
            return true;
        }
        return stand.getCustomName() != null
                && MOVEMENT_MANNEQUIN_NAME.equals(stand.getCustomName().getString());
    }

    /** Replaces the current mannequin with one at a random horizontal location within 10 blocks. */
    private boolean spawnAimGatedMannequin(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || player.connection == null) {
            return false;
        }
        double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0);
        double distance = ThreadLocalRandom.current().nextDouble(3.0, AIM_GATED_SPAWN_RADIUS);
        double x = player.getX() + Math.cos(angle) * distance;
        double z = player.getZ() + Math.sin(angle) * distance;
        double y = player.getY();
        String pos = ArenaReset.fmt(x) + " " + ArenaReset.fmt(y) + " " + ArenaReset.fmt(z);

        // Kill the previous aim-gated mannequin before creating a new one.
        ArenaReset.killAimGatedMannequins(player.connection);
        player.connection.sendCommand("summon minecraft:armor_stand " + pos
                + " {CustomName:'{\"text\":\"" + ArenaReset.AIM_GATED_MANNEQUIN_NAME
                + "\"}',Tags:[\"" + ArenaReset.AIM_GATED_MANNEQUIN_TAG
                + "\"],CustomNameVisible:1b,Invulnerable:1b,NoBasePlate:1b}");
        retargetAuto(); // /pvprl target auto after every mannequin refresh
        PvpRlEnv.LOGGER.info("Spawned aim-gated mannequin at ({}, {}, {}), distance {}",
                ArenaReset.fmt(x), ArenaReset.fmt(y), ArenaReset.fmt(z), distance);
        return true;
    }

    /** @return whether a live aim-gated mannequin is currently synced to the client. */
    private boolean hasLiveAimGatedMannequin(LocalPlayer player, ClientLevel level) {
        return !level.getEntitiesOfClass(ArmorStand.class,
                        player.getBoundingBox().inflate(AIM_GATED_SPAWN_RADIUS + 8.0),
                        stand -> stand.isAlive() && isAimGatedMannequin(stand))
                .isEmpty();
    }

    /** Matches by tag, with custom-name fallback. */
    private static boolean isAimGatedMannequin(ArmorStand stand) {
        if (stand.entityTags().contains(ArenaReset.AIM_GATED_MANNEQUIN_TAG)) {
            return true;
        }
        return stand.getCustomName() != null
                && ArenaReset.AIM_GATED_MANNEQUIN_NAME.equals(stand.getCustomName().getString());
    }

    /** Requests an episode reset, respecting the mode selected by {@link #start(boolean)}. */
    public void requestReset() {
        requestReset(arenaResetEnabled);
    }

    /** Requests an explicit full arena reset, irrespective of no-arena training mode. */
    public void requestArenaReset() {
        requestReset(true);
    }

    private void requestReset(boolean manageArena) {
        Minecraft mc = Minecraft.getInstance();
        executor.releaseAll(mc);
        retargetAuto(); // /pvprl target auto on every episode refresh
        reward.resetEpisode();

        if (!manageArena) {
            resetStage = 0;
            resetScheduledTick = -1;
            lastCombatActivityTick = tickCounter;
            PvpRlEnv.LOGGER.info("[pvp-rl] Episode reset without arena changes.");
            if (running && server != null) {
                server.broadcastEvent("RESET");
            }
            return;
        }

        // Survival curriculum uses its own damage-inflicting reset phases.
        if (survivalTraining) {
            nextSurvivalRefreshTick = tickCounter + SURVIVAL_REFRESH_INTERVAL_TICKS;
            ArenaReset.performSurvivalPhase1(mc, PvpRlEnv.cfg());
            resetStage = 1;
            resetScheduledTick = tickCounter + RESET_PHASE2_DELAY;
            PvpRlEnv.LOGGER.info("[pvp-rl] Survival reset phase-1 issued; phase-2 scheduled at tick {}",
                    resetScheduledTick);
            if (running && server != null) {
                server.broadcastEvent("RESET");
            }
            return;
        }

        // Aim-gated curriculum: clear + mannequin spawn; HP mode chosen in phase-2.
        if (aimGatedTraining) {
            nextAimGatedDamageTick = Long.MAX_VALUE;
            ArenaReset.performAimGatedPhase1(mc, PvpRlEnv.cfg());
            resetStage = 1;
            resetScheduledTick = tickCounter + RESET_PHASE2_DELAY;
            PvpRlEnv.LOGGER.info("[pvp-rl] Aim-gated reset phase-1 issued; phase-2 scheduled at tick {}",
                    resetScheduledTick);
            if (running && server != null) {
                server.broadcastEvent("RESET");
            }
            return;
        }

        ArenaReset.performPhase1(mc, PvpRlEnv.cfg());
        resetStage = 1;
        resetScheduledTick = tickCounter + RESET_PHASE2_DELAY;
        PvpRlEnv.LOGGER.info("[pvp-rl] Reset phase-1 issued; phase-2 scheduled at tick {}", resetScheduledTick);
        if (running && server != null) {
            server.broadcastEvent("RESET");
        }
    }

    /**
     * Summons the configured entity a few blocks in front of the player to act as a sparring target.
     *
     * <p>Issued as a server command via the client's command channel, so it requires command
     * permission (singleplayer with cheats, or an op'd server).</p>
     *
     * @return {@code true} if the summon command was dispatched
     */
    public boolean summonTarget() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.connection == null) {
            return false;
        }
        Config.Arena arena = PvpRlEnv.cfg().arena();
        String type = arena.targetEntity() != null && !arena.targetEntity().isBlank()
                ? arena.targetEntity() : "minecraft:vindicator";
        double dist = arena.targetSpawnDistance() > 0 ? arena.targetSpawnDistance() : 1.5;
        player.connection.sendCommand("kill @e[type=" + type + "]");
        player.connection.sendCommand("kill @e[type=minecraft:vindicator]");
        player.connection.sendCommand("kill @e[type=minecraft:husk]");
        player.connection.sendCommand("summon " + type + " "
                + ArenaReset.surfaceTargetPosition(player, dist));
        PvpRlEnv.LOGGER.info("Summoned {} sparring target.", type);
        return true;
    }

    /**
     * Restores local-player control for backwards compatibility with {@code /pvprl opponent}.
     *
     * <p>This command intentionally does not summon, remove, teleport, equip, damage, heal, or
     * modify any entity. The policy drives the actual local survival player through the normal
     * input executor, which is the same body used by the PPO training scripts.</p>
     */
    public boolean summonOpponent() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.connection == null) {
            return false;
        }
        // Do not issue a server command or change any controller/world state here. Local-player
        // control is already the only mode, so this is intentionally a true compatibility no-op.
        PvpRlEnv.LOGGER.info("Opponent command ignored: the policy already controls the local survival player.");
        return true;
    }

    /**
     * The per-tick driver. Drains inbound actions, applies the active action, updates reward
     * and periodically collects + broadcasts an observation.
     */
    public void tick() {
        if (!running) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || server == null) {
            return;
        }
        tickCounter++;
        Config cfg = PvpRlEnv.cfg();
        LivingEntity controlled = player;

        // ── Reset State Machine ──────────────────────────────────────────
        if (resetStage == 1) {
            if (tickCounter >= resetScheduledTick) {
                int settle = survivalTraining ? SURVIVAL_RESET_SETTLE_DELAY
                        : aimGatedTraining ? AIM_GATED_RESET_SETTLE_DELAY : RESET_SETTLE_DELAY;
                if (survivalTraining) {
                    ArenaReset.performSurvivalPhase2(mc, cfg);
                    PvpRlEnv.LOGGER.info("[pvp-rl] Survival reset phase-2 (kit+apples+mannequin) issued at tick {}; settle until {}",
                            tickCounter, tickCounter + settle);
                } else if (aimGatedTraining) {
                    ArenaReset.performAimGatedPhase2(mc, cfg);
                    PvpRlEnv.LOGGER.info("[pvp-rl] Aim-gated reset phase-2 (mannequin) issued at tick {}; settle until {}",
                            tickCounter, tickCounter + settle);
                } else if (movementTraining) {
                    ArenaReset.performPhase2(mc, cfg, false);
                    PvpRlEnv.LOGGER.info("[pvp-rl] Movement reset phase-2 (kit only; no configured target) issued at tick {}; settle until {}",
                            tickCounter, tickCounter + settle);
                } else {
                    ArenaReset.performPhase2(mc, cfg);
                    PvpRlEnv.LOGGER.info("[pvp-rl] Reset phase-2 (kit+summon) issued at tick {}; settle until {}",
                            tickCounter, tickCounter + settle);
                }
                resetStage = 2;
                resetScheduledTick = tickCounter + settle;
            }
            server.drainInbound(); // discard stale actions during reset
            return;
        } else if (resetStage == 2) {
            if (tickCounter >= resetScheduledTick) {
                resetStage = 0;
                reward.resetEpisode();
                executor.releaseAll(mc);
                // /pvprl target auto — lock onto the freshly summoned mannequin / mob.
                retargetAuto();

                // Aim-gated: schedule the next mannequin respawn (HP already set in phase-2).
                if (aimGatedTraining) {
                    nextAimGatedSpawnTick = tickCounter + AIM_GATED_SPAWN_INTERVAL_TICKS;
                    nextAimGatedDamageTick = tickCounter + AIM_GATED_DAMAGE_DELAY_TICKS;
                }

                // Collect and broadcast clean initial observation for the new episode
                LivingEntity targetEntity = collector.pickTarget(controlled, level, cfg);
                String targetDesc = targetEntity == null
                        ? "none"
                        : targetEntity.getScoreboardName()
                          + " [" + targetEntity.getType().toShortString() + "]"
                          + " hp=" + String.format("%.1f", targetEntity.getHealth());
                lastLoggedTarget = targetDesc;

                Observation obs = collector.collect(controlled, level, cfg, tickCounter, RewardSnapshot.zero());
                lastObservation = obs;
                lastCombatActivityTick = tickCounter;
                server.broadcastObservation(ObservationSerializer.toJson(obs));
                PvpRlEnv.LOGGER.info("[pvp-rl] Reset complete (target auto). Fresh episode started with target: {}",
                        targetDesc);
                if (survivalTraining) {
                    player.sendSystemMessage(Component.literal(
                            "§e[pvp-rl] §fSurvival reset — target auto → §b" + targetDesc));
                } else if (aimGatedTraining) {
                    player.sendSystemMessage(Component.literal(
                            "§e[pvp-rl] §fAim-gated reset — target auto → §b" + targetDesc));
                }
            }
            server.drainInbound(); // discard stale actions during reset
            return;
        }

        // ── Apply queued commands on the main thread ──────────────────────
        for (PendingAction pending : server.drainInbound()) {
            handle(pending);
        }

        // Refresh on the 20s schedule, or earlier only if the stand is gone *and* a full
        // interval has passed since the last summon. The old 20-tick missing cooldown
        // re-summoned ~every second while the client was still syncing the entity/tags.
        boolean intervalElapsed = tickCounter >= nextMannequinSpawnTick;
        boolean mayResummon = tickCounter - lastMannequinSpawnTick >= MANNEQUIN_SPAWN_INTERVAL_TICKS;
        boolean mannequinMissing = movementTraining && mayResummon
                && !hasLiveMovementMannequin(player, level);
        if (movementTraining && mayResummon && (intervalElapsed || mannequinMissing)) {
            if (spawnMovementMannequin(mc)) {
                nextMannequinSpawnTick = tickCounter + MANNEQUIN_SPAWN_INTERVAL_TICKS;
            }
        }

        // Survival: full episode refresh every 60s (new low-HP setup + mannequin + apples).
        if (survivalTraining && resetStage == 0 && tickCounter >= nextSurvivalRefreshTick) {
            nextSurvivalRefreshTick = tickCounter + SURVIVAL_REFRESH_INTERVAL_TICKS;
            PvpRlEnv.LOGGER.info("[pvp-rl] Survival 60s refresh — resetting episode.");
            player.sendSystemMessage(Component.literal(
                    "§e[pvp-rl] Survival refresh (60s) — new episode."));
            requestReset();
            return;
        }

        // Aim-gated: refresh the mannequin every 2 minutes, aligned with episode length.
        if (aimGatedTraining && resetStage == 0 && tickCounter >= nextAimGatedSpawnTick) {
            nextAimGatedSpawnTick = tickCounter + AIM_GATED_SPAWN_INTERVAL_TICKS;
            if (spawnAimGatedMannequin(mc)) {
                PvpRlEnv.LOGGER.info("[pvp-rl] Aim-gated 2-minute mannequin refresh — target auto.");
                player.sendSystemMessage(Component.literal(
                        "§e[pvp-rl] §fAim-gated mannequin refresh — §b/pvprl target auto"));
            }
        }

        // Flee-aim: train aiming at full health for half the episode, then facing away.
        if (aimGatedTraining && resetStage == 0
                && tickCounter >= nextAimGatedDamageTick) {
            if (ArenaReset.damageAimGatedPlayer(mc)) {
                nextAimGatedDamageTick = Long.MAX_VALUE;
                PvpRlEnv.LOGGER.info("[pvp-rl] Aim-gated midpoint damage applied; player is now at 9 HP.");
                player.sendSystemMessage(Component.literal(
                        "§e[pvp-rl] §fFlee-aim midpoint: health reduced to 4.5 hearts — look away."));
            }
        }

        // ── Advance the currently held action ─────────────────────────────
        executor.tick(mc, cfg);

        // ── Collect observation, update reward, broadcast ─────────────────
        if (tickCounter % cfg.obsEveryNTicks() == 0) {
            // Collect exactly once per observed tick.  In particular, a dead target must
            // remain visible for this observation so RewardTracker can emit KILL.  Calling
            // pickTarget before collect() consumed that one-tick dead-target window, making
            // genuine kills look like the target simply disappeared.
            Observation preview = collector.collect(controlled, level, cfg, tickCounter, RewardSnapshot.zero());
            // Curriculum refreshes can outrun client entity sync — keep running
            // /pvprl target auto until the mannequin/mob is locked again.
            if (!preview.enemy().present()
                    && (aimGatedTraining || survivalTraining || movementTraining)) {
                retargetAuto();
                preview = collector.collect(controlled, level, cfg, tickCounter, RewardSnapshot.zero());
            }
            String targetDesc = preview.enemy().present()
                    ? preview.enemy().name() + " hp=" + String.format("%.1f", preview.enemy().health())
                    : "none";
            if (!targetDesc.equals(lastLoggedTarget)) {
                PvpRlEnv.LOGGER.info("[pvp-rl] Target changed: {}", targetDesc);
                lastLoggedTarget = targetDesc;
                // Notify in-game chat too (significant event, not spammy).
                player.sendSystemMessage(Component.literal(
                        "§e[pvp-rl] §fTarget: §b" + targetDesc));
            }

            float attackStrength = executor.consumeAttackStrength();
            boolean onTarget = ActionExecutor.isCrosshairOnTarget(mc);
            boolean attackWhileBlocking = executor.consumeAttackWhileBlocking();
            boolean shieldOnCooldown = executor.consumeShieldOnCooldownAttempt();
            // Low-HP survival: flee rewards only. Combat aim/on-target must not raise the
            // debug score while the agent should be running from the armor stand.
            boolean survivalFlee = survivalTraining
                    && preview.self().health() < RewardTracker.SURVIVAL_LOW_HEALTH;
            executor.setSuppressAttacks(survivalFlee || aimGatedTraining);
            boolean eatingNow = executor.activeAction() == DiscreteAction.EAT
                    || executor.activeAction() == DiscreteAction.SWAP_TO_FOOD
                    || player.isUsingItem();
            RewardSnapshot rs;
            if (aimGatedTraining) {
                rs = reward.updateAimGated(preview.self(), preview.enemy(), cfg);
            } else if (survivalFlee) {
                rs = reward.updateSurvivalFlee(preview.self(), preview.enemy(), cfg,
                        attackStrength, eatingNow);
            } else {
                rs = reward.update(preview.self(), preview.enemy(), cfg,
                        attackStrength, onTarget, attackWhileBlocking, shieldOnCooldown);
            }
            Observation obs = new Observation(
                    preview.tick(), preview.self(), preview.enemy(), preview.nearby(), rs);
            lastObservation = obs;

            // ── Survival curriculum: detect successful eating ────────────────
            if (survivalTraining && executor.consumeEatCompleted()) {
                RewardSnapshot eatRs = reward.terminal(EpisodeEvent.EAT_SUCCESS, cfg);
                Observation eatObs = new Observation(
                        preview.tick(), preview.self(), preview.enemy(), preview.nearby(), eatRs);
                server.broadcastObservation(ObservationSerializer.toJson(eatObs));
                server.broadcastEvent("EAT_SUCCESS");
                PvpRlEnv.LOGGER.info("[pvp-rl] EAT_SUCCESS: agent ate a golden apple. Resetting episode.");
                player.sendSystemMessage(Component.literal(
                        "§a[pvp-rl] ✔ EAT_SUCCESS — golden apple eaten. Resetting."));
                nextSurvivalRefreshTick = tickCounter + SURVIVAL_REFRESH_INTERVAL_TICKS;
                requestReset();
                return;
            }

            // ── Watchdog: detect if no mob is attacking the agent ─────────────
            // A mannequin is intentionally passive, so it must not trigger a combat reset.
            // Survival mode also bypasses the watchdog — the passive mannequin is only a
            // flee reference, and eat-success/death handle episode termination.
            // Aim-gated mode also bypasses: the passive mannequin is a look-only reference.
            if (!movementTraining && !survivalTraining && !aimGatedTraining) {
                boolean mobAttacking = false;
                if (rs.damageTaken() > 0.0f || rs.damageDealt() > 0.0f || player.hurtTime > 0) {
                    mobAttacking = true;
                } else if (preview.enemy().present() && preview.enemy().health() > 0.0f) {
                    // Target is in active combat range (<= 5.0 blocks) with line of sight
                    if (preview.enemy().distance() <= 5.0 && preview.enemy().lineOfSight()) {
                        mobAttacking = true;
                    }
                }

                if (mobAttacking) {
                    lastCombatActivityTick = tickCounter;
                } else if (rs.event() == null && (tickCounter - lastCombatActivityTick) >= NO_ATTACK_RESET_TICKS) {
                    PvpRlEnv.LOGGER.warn("[pvp-rl] Watchdog: No mob attacking agent for 30s (mob died from terrain). Auto-resetting arena!");
                    player.sendSystemMessage(Component.literal(
                            "§c[pvp-rl] §eNo mob attacking agent for 30s (mob died from terrain). Auto-resetting arena!"));

                    RewardSnapshot timeoutRs = reward.terminal(EpisodeEvent.TIMEOUT, cfg);
                    Observation timeoutObs = new Observation(
                            preview.tick(), preview.self(), preview.enemy(), preview.nearby(), timeoutRs);
                    server.broadcastObservation(ObservationSerializer.toJson(timeoutObs));
                    server.broadcastEvent("TIMEOUT");

                    requestReset();
                    return;
                }
            }

            // ── Reward/Punishment logging ─────────────────────────────────
            logReward(player, rs, targetDesc);

            server.broadcastObservation(ObservationSerializer.toJson(obs));
            if (rs.event() != null) {
                server.broadcastEvent(rs.event());
            }
            String aimGatedMode = null;
            if (aimGatedTraining) {
                float maxHp = preview.self().maxHealth() > 0.0f ? preview.self().maxHealth() : 20.0f;
                aimGatedMode = preview.self().health() >= maxHp * 0.5f ? "LOOK-AT" : "LOOK-AWAY";
            }
            overlay.maybePrint(mc, obs, executor.activeAction().name(), reward.lastComponents(),
                    aimGatedMode != null ? aimGatedMode
                            : (survivalFlee ? "FLEE" : (survivalTraining ? "FIGHT" : null)));
        }
    }

    /**
     * Logs per-step reward components to the console and, for significant events, to chat.
     *
     * <p>Only logs to console if the step reward is non-trivial (|Δr| > step-penalty threshold)
     * or if a named event (kill/death) occurred — avoids log spam during uneventful ticks.</p>
     */
    private void logReward(LocalPlayer player, RewardSnapshot rs, String targetDesc) {
        boolean isEvent = rs.event() != null;
        boolean significant = Math.abs(rs.step()) > 0.02 || isEvent;

        if (significant || !reward.lastReasons().isEmpty()) {
            String reasonStr = String.join(" | ", reward.lastReasons());
            PvpRlEnv.LOGGER.info("[pvp-rl] Δr={}{} cumul={} target=[{}] | {}",
                    rs.step() >= 0 ? "+" : "",
                    String.format("%.4f", rs.step()),
                    String.format("%.2f", rs.cumulative()),
                    targetDesc,
                    reasonStr.isEmpty() ? "—" : reasonStr);
        }

        // Significant in-game chat messages: kill, death, large reward swings.
        if (isEvent) {
            String color = "KILL".equals(rs.event()) ? "§a" : "§c";
            String symbol = "KILL".equals(rs.event()) ? "✔" : "✘";
            player.sendSystemMessage(Component.literal(
                    color + "[pvp-rl] " + symbol + " " + rs.event()
                    + " Δr=" + String.format("%+.1f", rs.step())
                    + " cumul=" + String.format("%.1f", rs.cumulative())
                    + " target=" + targetDesc));
        }
    }

    private void handle(PendingAction pending) {
        Protocol.Inbound msg = pending.message();
        WebSocket conn = pending.conn();
        switch (msg.type()) {
            case ACTION -> {
                DiscreteAction a = DiscreteAction.resolve(msg.action());
                if (a == null) {
                    conn.send(Protocol.error("unknown action: " + msg.action()));
                    return;
                }
                executor.request(a, msg.ticks());
                if (msg.id() >= 0) {
                    conn.send(Protocol.ack(msg.id()));
                }
            }
            case SET_TARGET -> {
                setTarget(msg.player());
                if (msg.id() >= 0) {
                    conn.send(Protocol.ack(msg.id()));
                }
            }
            case MOVEMENT_TRAINING -> {
                setMovementTraining(msg.enabled());
                if (msg.id() >= 0) {
                    conn.send(Protocol.ack(msg.id()));
                }
            }
            case SURVIVAL_TRAINING -> {
                setSurvivalTraining(msg.enabled());
                if (msg.id() >= 0) {
                    conn.send(Protocol.ack(msg.id()));
                }
            }
            case AIM_GATED_TRAINING -> {
                setAimGatedTraining(msg.enabled());
                if (msg.id() >= 0) {
                    conn.send(Protocol.ack(msg.id()));
                }
            }
            case RESET -> {
                requestReset();
                if (msg.id() >= 0) {
                    conn.send(Protocol.ack(msg.id()));
                }
            }
            case UNKNOWN -> conn.send(Protocol.error("unhandled message"));
        }
    }

    /** @return the most recently collected observation, or {@code null}. */
    public Observation lastObservation() {
        return lastObservation;
    }

}
