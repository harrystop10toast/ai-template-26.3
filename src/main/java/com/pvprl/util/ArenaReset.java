package com.pvprl.util;

import com.pvprl.PvpRlEnv;
import com.pvprl.config.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;

/**
 * Performs a safe episode reset of the controlled player: teleport to the arena, full heal,
 * clear status effects and grant the configured kit.
 *
 * <p>The reset is split into two phases to avoid a server-side race condition:
 * <ul>
 *   <li><b>Phase 1</b> ({@link #performPhase1}): respawn (if dead), teleport, inventory clear,
 *       effect clear, and heal effects. These fire immediately.</li>
 *   <li><b>Phase 2</b> ({@link #performPhase2}): kit equipment and mob summon. These are
 *       deferred by a few ticks so the server has time to process the respawn and {@code clear}
 *       before {@code item replace} commands arrive — preventing the kit from being wiped.</li>
 * </ul>
 * Both phases are issued as vanilla server commands through the client's command channel
 * ({@link ClientPacketListener#sendCommand(String)}). This works in singleplayer (integrated
 * server) and on any server where the player is permitted to run these commands.</p>
 */
public final class ArenaReset {

    /** Passive flee-reference used by the survival curriculum (not a combatant). */
    public static final String SURVIVAL_MANNEQUIN_NAME = "PvpRlSurvivalTarget";
    public static final String SURVIVAL_MANNEQUIN_TAG = "pvprl_survival_target";

    private ArenaReset() {
    }

    /**
     * Convenience method that issues <em>both</em> phases in one call (no delay). Use this
     * only when a deferred kit grant is not needed (e.g. manual `/pvprl reset` invoked by a
     * human while already alive and not mid-death animation).
     *
     * @param mc  the Minecraft client
     * @param cfg active configuration
     * @return {@code true} if commands were dispatched
     */
    public static boolean perform(Minecraft mc, Config cfg) {
        if (!performPhase1(mc, cfg)) return false;
        return performPhase2(mc, cfg);
    }

    /**
     * Phase 1: respawn (if dead), teleport to arena, clear inventory, clear/apply effects.
     * Fire this immediately on reset request.
     *
     * @param mc  the Minecraft client
     * @param cfg active configuration
     * @return {@code true} if commands were dispatched, {@code false} if no player/connection
     */
    public static boolean performPhase1(Minecraft mc, Config cfg) {
        LocalPlayer player = mc.player;
        if (player == null || player.connection == null) {
            PvpRlEnv.LOGGER.warn("[pvp-rl] ArenaReset phase-1 skipped: no player or connection");
            return false;
        }
        ClientPacketListener conn = player.connection;
        Config.Arena arena = cfg.arena();
        String self = player.getScoreboardName();

        if (player.isDeadOrDying()) {
            PvpRlEnv.LOGGER.info("[pvp-rl] Player is dead/dying — calling respawn() before reset");
            player.respawn();
        }

        conn.sendCommand("clear " + self);
        conn.sendCommand(String.format("tp %s %s %s %s",
                self, fmt(arena.x()), fmt(arena.y()), fmt(arena.z())));

        if (arena.clearEffects()) {
            conn.sendCommand("effect clear " + self);
        }
        if (arena.healOnReset()) {
            conn.sendCommand("effect give " + self + " minecraft:instant_health 1 10 true");
            conn.sendCommand("effect give " + self + " minecraft:saturation 1 10 true");
        }
        if (arena.resistanceOnReset()) {
            conn.sendCommand("effect give " + self + " minecraft:resistance infinite 4 true");
        }

        // Kill all items on the ground so no drops (rotten flesh, gear) clutter the arena
        conn.sendCommand("kill @e[type=item]");
        conn.sendCommand("kill @e[type=minecraft:item]");

        // Clean up old sparring targets immediately on reset phase 1 so no dead or old entities linger
        if (arena.summonTargetOnReset() && arena.targetEntity() != null && !arena.targetEntity().isBlank()) {
            String type = arena.targetEntity();
            conn.sendCommand("kill @e[type=" + type + "]");
            conn.sendCommand("kill @e[type=minecraft:vindicator]");
            conn.sendCommand("kill @e[type=minecraft:husk]");
        }

        PvpRlEnv.LOGGER.info("[pvp-rl] Arena reset phase-1 issued for {}", self);
        return true;
    }

    /**
     * Phase 2: equip kit and summon/re-spawn the sparring target.
     * Call this ~10 ticks after phase 1 to let the server process the respawn and {@code clear}.
     *
     * @param mc  the Minecraft client
     * @param cfg active configuration
     * @return {@code true} if commands were dispatched, {@code false} if no player/connection
     */
    public static boolean performPhase2(Minecraft mc, Config cfg) {
        return performPhase2(mc, cfg, true);
    }

    /**
     * Phase 2 variant that can skip the configured combat target, used by movement
     * training which spawns its own passive mannequin.
     *
     * @param mc Minecraft client
     * @param cfg active configuration
     * @param summonConfiguredTarget whether to spawn the configured sparring entity
     * @return {@code true} if commands were dispatched
     */
    public static boolean performPhase2(Minecraft mc, Config cfg, boolean summonConfiguredTarget) {
        LocalPlayer player = mc.player;
        if (player == null || player.connection == null) {
            PvpRlEnv.LOGGER.warn("[pvp-rl] ArenaReset phase-2 skipped: no player or connection");
            return false;
        }
        ClientPacketListener conn = player.connection;
        Config.Arena arena = cfg.arena();
        String self = player.getScoreboardName();

        // Equip full Netherite armor (no enchantments) and knockback wooden sword + shield.
        conn.sendCommand("item replace entity " + self + " armor.head with minecraft:netherite_helmet");
        conn.sendCommand("item replace entity " + self + " armor.chest with minecraft:netherite_chestplate");
        conn.sendCommand("item replace entity " + self + " armor.legs with minecraft:netherite_leggings");
        conn.sendCommand("item replace entity " + self + " armor.feet with minecraft:netherite_boots");
        conn.sendCommand("item replace entity " + self + " weapon.offhand with minecraft:shield[unbreakable={}]");
        conn.sendCommand("item replace entity " + self + " weapon.mainhand with minecraft:wooden_sword[enchantments={knockback:1},unbreakable={}]");
        PvpRlEnv.LOGGER.info("[pvp-rl] Kit equipped for {}", self);

        if (arena.kit() != null) {
            for (String item : arena.kit()) {
                if (!item.contains("netherite") && !item.contains("sword") && !item.contains("shield")) {
                    conn.sendCommand("give " + self + " " + item);
                }
            }
        }

        // Kill any items dropped from previously purged entities before summoning
        conn.sendCommand("kill @e[type=item]");
        conn.sendCommand("kill @e[type=minecraft:item]");

        // Spawn a fresh sparring target each episode.
        if (summonConfiguredTarget && arena.summonTargetOnReset() && arena.targetEntity() != null
                && !arena.targetEntity().isBlank()) {
            String type = arena.targetEntity();
            conn.sendCommand("kill @e[type=" + type + "]");
            conn.sendCommand("kill @e[type=minecraft:vindicator]");
            conn.sendCommand("kill @e[type=minecraft:husk]");
            conn.sendCommand("summon " + type + " "
                    + surfaceTargetPosition(player, arena.targetSpawnDistance()));
            PvpRlEnv.LOGGER.info("[pvp-rl] Summoned {} at distance {}", type, arena.targetSpawnDistance());
            if (arena.targetResistance()) {
                conn.sendCommand("effect give @e[type=" + type + ",limit=1,sort=nearest] "
                        + "minecraft:resistance infinite 4 true");
            }
        }

        PvpRlEnv.LOGGER.info("[pvp-rl] Arena reset phase-2 issued for {}", self);
        return true;
    }

    /** Formats a coordinate without a trailing {@code .0} where possible, for clean commands. */
    public static String fmt(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    /**
     * Returns a world-coordinate spawn position in front of {@code player} on the arena
     * surface.  Local caret coordinates include the player's pitch: {@code ^ ^ ^1.5}
     * points downward while the agent is looking down, which spawned targets inside the
     * floor.  This uses yaw only and constrains target feet to the requested arena band.
     *
     * @param player source player whose horizontal facing determines the offset
     * @param distance horizontal distance in blocks; non-positive values use 1.5
     * @return three command-safe world coordinates, separated by spaces
     */
    public static String surfaceTargetPosition(LocalPlayer player, double distance) {
        double dist = distance > 0.0 ? distance : 1.5;
        double yawRadians = Math.toRadians(player.getYRot());
        double x = player.getX() - Math.sin(yawRadians) * dist;
        double z = player.getZ() + Math.cos(yawRadians) * dist;
        // The training arena's walkable surface is in this band.  Using the player's
        // feet level preserves uneven but valid terrain while preventing below-floor spawns.
        double y = Math.max(-60.0, Math.min(-55.0, player.getY()));
        return fmt(x) + " " + fmt(y) + " " + fmt(z);
    }

    // ── Survival curriculum reset ────────────────────────────────────────

    /**
     * Survival phase 1: teleport, clear inventory, heal to a known baseline, then
     * {@code /damage} exactly 11 points so health lands at 9 (&lt; 5 hearts). Disables
     * natural regeneration for the curriculum. A passive mannequin (phase 2) is the
     * flee reference; no hostile mob and no harming potion.
     *
     * @param mc  the Minecraft client
     * @param cfg active configuration
     * @return {@code true} if commands were dispatched
     */
    public static boolean performSurvivalPhase1(Minecraft mc, Config cfg) {
        LocalPlayer player = mc.player;
        if (player == null || player.connection == null) {
            PvpRlEnv.LOGGER.warn("[pvp-rl] Survival reset phase-1 skipped: no player or connection");
            return false;
        }
        ClientPacketListener conn = player.connection;
        Config.Arena arena = cfg.arena();
        String self = player.getScoreboardName();

        if (player.isDeadOrDying()) {
            player.respawn();
        }

        // Prevent food-based healing from undoing the low-HP flee setup mid-episode.
        conn.sendCommand("gamerule naturalRegeneration false");

        conn.sendCommand("clear " + self);
        conn.sendCommand(String.format("tp %s %s %s %s",
                self, fmt(arena.x()), fmt(arena.y()), fmt(arena.z())));
        conn.sendCommand("effect clear " + self);

        // Full heal now; precise /damage runs in phase 2 after the server applies this.
        conn.sendCommand("effect give " + self + " minecraft:instant_health 1 10 true");

        conn.sendCommand("kill @e[type=item]");
        conn.sendCommand("kill @e[type=minecraft:item]");

        // Kill leftover sparring targets so `/pvprl target auto` locks onto the
        // survival armor stand rather than a leftover villager/vindicator/husk.
        if (arena.targetEntity() != null && !arena.targetEntity().isBlank()) {
            conn.sendCommand("kill @e[type=" + arena.targetEntity() + "]");
        }
        conn.sendCommand("kill @e[type=minecraft:vindicator]");
        conn.sendCommand("kill @e[type=minecraft:husk]");
        conn.sendCommand("kill @e[type=minecraft:villager]");
        killSurvivalMannequins(conn);

        PvpRlEnv.LOGGER.info("[pvp-rl] Survival reset phase-1 issued for {} (/damage → 9 HP)", self);
        return true;
    }

    /**
     * Survival phase 2: equip armor + sword, give 2 golden apples, and summon a passive
     * armor-stand mannequin a few blocks ahead as the flee reference point.
     *
     * @param mc  the Minecraft client
     * @param cfg active configuration
     * @return {@code true} if commands were dispatched
     */
    public static boolean performSurvivalPhase2(Minecraft mc, Config cfg) {
        LocalPlayer player = mc.player;
        if (player == null || player.connection == null) {
            PvpRlEnv.LOGGER.warn("[pvp-rl] Survival reset phase-2 skipped: no player or connection");
            return false;
        }
        ClientPacketListener conn = player.connection;
        String self = player.getScoreboardName();

        // Drop any lingering absorption / heal effects, then set HP precisely while
        // unarmored so netherite cannot soak the hit: 20 − 11 = 9 HP (< 5 hearts).
        conn.sendCommand("effect clear " + self);
        // out_of_world bypasses armor; plain /damage is the version fallback.
        conn.sendCommand("damage " + self + " 11 minecraft:out_of_world");
        conn.sendCommand("damage " + self + " 11");
        // Force absolute health in case /damage was soaked or queued behind heal.
        conn.sendCommand("data modify entity " + self + " Health set value 9.0f");

        // Equip full Netherite armor + knockback sword + shield.
        conn.sendCommand("item replace entity " + self + " armor.head with minecraft:netherite_helmet");
        conn.sendCommand("item replace entity " + self + " armor.chest with minecraft:netherite_chestplate");
        conn.sendCommand("item replace entity " + self + " armor.legs with minecraft:netherite_leggings");
        conn.sendCommand("item replace entity " + self + " armor.feet with minecraft:netherite_boots");
        conn.sendCommand("item replace entity " + self + " weapon.offhand with minecraft:shield[unbreakable={}]");
        conn.sendCommand("item replace entity " + self + " weapon.mainhand with minecraft:wooden_sword[enchantments={knockback:1},unbreakable={}]");

        // Give exactly 2 golden apples for the survival curriculum (hotbar via give).
        conn.sendCommand("give " + self + " minecraft:golden_apple 2");

        // Passive armor-stand mannequin. Tag is the stable selector; CustomName uses a
        // text-component JSON (plain strings break on modern Minecraft versions).
        killSurvivalMannequins(conn);
        String pos = surfaceTargetPosition(player, 3.0);
        conn.sendCommand("summon minecraft:armor_stand " + pos
                + " {CustomName:'{\"text\":\"" + SURVIVAL_MANNEQUIN_NAME
                + "\"}',Tags:[\"" + SURVIVAL_MANNEQUIN_TAG
                + "\"],CustomNameVisible:1b,Invulnerable:1b,NoBasePlate:1b,NoGravity:0b}");

        PvpRlEnv.LOGGER.info("[pvp-rl] Survival reset phase-2 issued for {} (mannequin, /damage → 9 HP)", self);
        return true;
    }

    /** Removes every previous survival-curriculum mannequin (tag + name fallback). */
    private static void killSurvivalMannequins(ClientPacketListener conn) {
        conn.sendCommand("kill @e[tag=" + SURVIVAL_MANNEQUIN_TAG + "]");
        conn.sendCommand("kill @e[type=minecraft:armor_stand,name=" + SURVIVAL_MANNEQUIN_NAME + "]");
    }

    // ── Aim-gated flee curriculum reset ──────────────────────────────────

    /** Passive target used by the aim-gated flee curriculum (look-at / look-away). */
    public static final String AIM_GATED_MANNEQUIN_NAME = "PvpRlAimGatedTarget";
    public static final String AIM_GATED_MANNEQUIN_TAG = "pvprl_aim_gated_target";

    /**
     * Aim-gated phase 1: teleport to arena, clear inventory, full heal to 20 HP,
     * clear effects. No kit, no mob — only a passive mannequin is spawned later.
     *
     * @param mc  the Minecraft client
     * @param cfg active configuration
     * @return {@code true} if commands were dispatched
     */
    public static boolean performAimGatedPhase1(Minecraft mc, Config cfg) {
        LocalPlayer player = mc.player;
        if (player == null || player.connection == null) {
            PvpRlEnv.LOGGER.warn("[pvp-rl] Aim-gated reset phase-1 skipped: no player or connection");
            return false;
        }
        ClientPacketListener conn = player.connection;
        Config.Arena arena = cfg.arena();
        String self = player.getScoreboardName();

        if (player.isDeadOrDying()) {
            player.respawn();
        }

        conn.sendCommand("clear " + self);
        conn.sendCommand(String.format("tp %s %s %s %s",
                self, fmt(arena.x()), fmt(arena.y()), fmt(arena.z())));
        conn.sendCommand("effect clear " + self);
        // Full heal to exactly 20 HP.
        conn.sendCommand("effect give " + self + " minecraft:instant_health 1 10 true");
        conn.sendCommand("effect give " + self + " minecraft:saturation 1 10 true");

        conn.sendCommand("kill @e[type=item]");
        conn.sendCommand("kill @e[type=minecraft:item]");
        // Kill old sparring targets and curriculum mannequins.
        if (arena.targetEntity() != null && !arena.targetEntity().isBlank()) {
            conn.sendCommand("kill @e[type=" + arena.targetEntity() + "]");
        }
        conn.sendCommand("kill @e[type=minecraft:vindicator]");
        conn.sendCommand("kill @e[type=minecraft:husk]");
        killAimGatedMannequins(conn);

        PvpRlEnv.LOGGER.info("[pvp-rl] Aim-gated reset phase-1 issued for {} (full heal)", self);
        return true;
    }

    /**
     * Aim-gated phase 2: spawn a passive armor-stand mannequin within 10 blocks, then
     * assign full HP. The controller applies the low-HP transition halfway through the
     * episode so each episode teaches both health-gated behaviors.
     *
     * @param mc  the Minecraft client
     * @param cfg active configuration
     * @return {@code true} if commands were dispatched
     */
    public static boolean performAimGatedPhase2(Minecraft mc, Config cfg) {
        LocalPlayer player = mc.player;
        if (player == null || player.connection == null) {
            PvpRlEnv.LOGGER.warn("[pvp-rl] Aim-gated reset phase-2 skipped: no player or connection");
            return false;
        }
        ClientPacketListener conn = player.connection;
        String self = player.getScoreboardName();

        killAimGatedMannequins(conn);

        // Random angle within 10 blocks, mirroring spawnMovementMannequin but closer.
        double angle = java.util.concurrent.ThreadLocalRandom.current().nextDouble(Math.PI * 2.0);
        double distance = java.util.concurrent.ThreadLocalRandom.current().nextDouble(3.0, 10.0);
        double x = player.getX() + Math.cos(angle) * distance;
        double z = player.getZ() + Math.sin(angle) * distance;
        double y = player.getY();
        String pos = fmt(x) + " " + fmt(y) + " " + fmt(z);

        conn.sendCommand("summon minecraft:armor_stand " + pos
                + " {CustomName:'{\"text\":\"" + AIM_GATED_MANNEQUIN_NAME
                + "\"}',Tags:[\"" + AIM_GATED_MANNEQUIN_TAG
                + "\"],CustomNameVisible:1b,Invulnerable:1b,NoBasePlate:1b}");

        conn.sendCommand("effect give " + self + " minecraft:instant_health 1 10 true");
        conn.sendCommand("data modify entity " + self + " Health set value 20.0f");

        PvpRlEnv.LOGGER.info(
                "[pvp-rl] Aim-gated reset phase-2: mannequin at ({}, {}, {}), distance {}; HP=20",
                fmt(x), fmt(y), fmt(z), distance);
        return true;
    }

    /**
     * Applies the flee-aim curriculum's midpoint damage, leaving the unarmored player
     * at 9 HP (4.5 hearts).
     *
     * @param mc Minecraft client
     * @return {@code true} if the damage command was dispatched
     */
    public static boolean damageAimGatedPlayer(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || player.connection == null) {
            PvpRlEnv.LOGGER.warn("[pvp-rl] Aim-gated midpoint damage skipped: no player or connection");
            return false;
        }
        ClientPacketListener conn = player.connection;
        String self = player.getScoreboardName();
        conn.sendCommand("damage " + self + " 11");
        conn.sendCommand("data modify entity " + self + " Health set value 9.0f");
        return true;
    }

    /** Removes every previous aim-gated curriculum mannequin (tag + name fallback). */
    public static void killAimGatedMannequins(ClientPacketListener conn) {
        conn.sendCommand("kill @e[tag=" + AIM_GATED_MANNEQUIN_TAG + "]");
        conn.sendCommand("kill @e[type=minecraft:armor_stand,name=" + AIM_GATED_MANNEQUIN_NAME + "]");
    }
}
