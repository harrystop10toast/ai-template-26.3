package com.pvprl.config;

import java.util.List;

/**
 * Immutable configuration for the PvP RL environment.
 *
 * <p>Loaded from {@code config/pvp-rl-env.json} and hot-reloadable at runtime via
 * {@link ConfigManager}. All tunables for the network interface, observation
 * cadence, action resolution, arena reset and reward shaping live here so the
 * rest of the mod can treat configuration as a plain value object.</p>
 *
 * @param enabled           master switch; when false the network server stays down
 * @param host              WebSocket bind host
 * @param port              WebSocket bind port
 * @param obsEveryNTicks    emit an observation every N client ticks (>= 1)
 * @param nearbyRadius      radius (blocks) for the optional nearby-entity scan
 * @param maxNearbyEntities cap on nearby entities included in an observation
 * @param lookStepDegrees   degrees rotated by a normal discrete look action
 * @param fineLookStepDegrees degrees rotated by a fine discrete look action
 * @param fastLookMultiplier multiplier applied to {@code lookStepDegrees} by a fast look action
 * @param arena             arena reset parameters
 * @param reward            reward shaping weights
 */
public record Config(
        boolean enabled,
        String host,
        int port,
        int obsEveryNTicks,
        double nearbyRadius,
        int maxNearbyEntities,
        double lookStepDegrees,
        double fineLookStepDegrees,
        double fastLookMultiplier,
        Arena arena,
        Reward reward
) {
    /**
     * Arena / episode-reset parameters.
     *
     * @param x,y,z        spawn coordinates used by a safe reset
     * @param healOnReset  restore full health/food on reset
     * @param clearEffects remove all status effects on reset
     * @param kit          item ids granted on reset (e.g. {@code minecraft:wooden_sword[enchantments={knockback:1}]})
     * @param resistanceOnReset apply (effectively permanent) Resistance V after reset, so the
     *                          agent survives long enough to learn against a damaging opponent
     * @param summonTargetOnReset summon a fresh sparring target entity on each reset
     * @param targetEntity      entity id to summon as the sparring target (e.g.
     *                          {@code minecraft:husk} or {@code minecraft:vindicator})
     * @param targetResistance  give the summoned target permanent Resistance V too (so it is a
     *                          durable opponent that does not die instantly)
     * @param targetSpawnDistance blocks in front of the player to spawn the target. Small values
     *                          (~1.5) put it in melee range immediately — a curriculum that lets
     *                          the agent discover hits without a long approach, which helps break
     *                          training plateaus.
     */
    public record Arena(
            double x, double y, double z,
            boolean healOnReset,
            boolean clearEffects,
            List<String> kit,
            boolean resistanceOnReset,
            boolean summonTargetOnReset,
            String targetEntity,
            boolean targetResistance,
            double targetSpawnDistance
    ) {}

    /**
     * Reward shaping weights. Terminal events use {@code killReward}/{@code deathPenalty};
     * dense signals use the per-step weights.
     *
     * @param damageWeight          penalty per point of damage taken
     * @param damageDealtBonus      additional bonus per point of damage dealt (dealt = damageWeight + damageDealtBonus)
     * @param killReward            reward for killing the target
     * @param deathPenalty          penalty for dying (severely penalizes mutual-trade suicide)
     * @param stepPenalty           small per-tick penalty that discourages idling/staring and creates urgency
     * @param shapedAim             reward aiming toward the enemy
     * @param shapedDistance        potential-based symmetric distance shaping
     * @param aimWeight             dense full-range facing reward and bounded reward/penalty for turning toward/away from the enemy
     * @param notFacingPenalty      dense penalty for looking away from the enemy (ramps through the full rear hemisphere)
     * @param onTargetReward        per-step reward whenever the crosshair is directly on the target
     * @param strikePositionReward  reward while aimed in attack range
     * @param chargedHitBonus       bonus multiplier for landing a hit at high attack strength
     * @param earlySwingPenalty     penalty for spamming attacks before cooldown recharges
     * @param missPenalty           penalty for swinging at air when not aimed at target
     * @param shieldBlockReward     base reward for successfully deflecting an incoming hit with shield (diminishing)
     * @param shieldHoldingCost     per-tick cost for holding shield raised when not deflecting a hit
     * @param shieldMisusePenalty   additional per-tick penalty for turtling with shield > 4 blocks away
     * @param shieldAttackPenalty   penalty for attempting to attack while holding shield up
     * @param shieldCooldownPenalty penalty for attempting to block while shield is disabled (e.g. by axe)
     * @param fleePenalty           penalty for disengaging and running away
     * @param hoverPenalty          per-tick penalty for staring outside melee without closing (anti-farm)
     * @param chaseWeight           reward scale for sprint-closing on a target that is opening distance
     * @param comboHitBonus         extra reward per consecutive hit depth (2nd hit = 1x, 3rd = 2x, …)
     */
    public record Reward(
            double damageWeight,
            double damageDealtBonus,
            double killReward,
            double deathPenalty,
            double stepPenalty,
            boolean shapedAim,
            boolean shapedDistance,
            double aimWeight,
            double notFacingPenalty,
            double onTargetReward,
            double strikePositionReward,
            double chargedHitBonus,
            double earlySwingPenalty,
            double missPenalty,
            double shieldBlockReward,
            double shieldHoldingCost,
            double shieldMisusePenalty,
            double shieldAttackPenalty,
            double shieldCooldownPenalty,
            double fleePenalty,
            double hoverPenalty,
            double chaseWeight,
            double comboHitBonus
    ) {}

    /** @return a sensible default configuration written on first launch. */
    public static Config defaults() {
        return new Config(
                true,
                "127.0.0.1",
                8765,
                1,
                16.0,
                8,
                2.0,
                0.5,
                2.0,
                new Arena(0.0, -60.0, 0.0, true, true,
                        List.of("minecraft:wooden_sword[enchantments={knockback:1}]", "minecraft:shield"), false,
                        true, "minecraft:vindicator", false, 1.5),
                new Reward(
                        4.0,   // damageWeight (damage taken penalty)
                        3.0,   // damageDealtBonus (damage dealt = 4.0 + 3.0 = 7.0 per point)
                        120.0, // killReward (strong reward for victory)
                        -60.0, // deathPenalty (well-balanced penalty instead of cataclysmic -350)
                        0.008, // stepPenalty (urgency; discourage stare-farming)
                        true,  // shapedAim
                        true,  // shapedDistance (PBRS symmetric)
                        0.025, // aimWeight (facing helps, but not enough to farm alone)
                        0.03,  // notFacingPenalty (gentle full-range penalty after 45 deg)
                        0.04,  // onTargetReward (only paid in melee — see RewardTracker)
                        0.03,  // strikePositionReward (in melee and aimed)
                        2.5,   // chargedHitBonus (bonus multiplier for land hit with full cooldown)
                        0.04,  // earlySwingPenalty (gentle penalty for premature spam swings)
                        0.02,  // missPenalty (gentle penalty for swinging at thin air)
                        5.0,   // shieldBlockReward (reward for deflecting incoming hit)
                        0.005, // shieldHoldingCost (stops permanent turtling without punishing defense)
                        0.01,  // shieldMisusePenalty (> 4 blocks away)
                        0.10,  // shieldAttackPenalty
                        0.05,  // shieldCooldownPenalty (axe disable)
                        0.05,  // fleePenalty (running away with back turned > 6 blocks)
                        0.04,  // hoverPenalty (stare outside melee without closing)
                        1.2,   // chaseWeight (sprint-close on a fleeing target)
                        3.0    // comboHitBonus (per extra consecutive hit)
                )
        );
    }

    /**
     * Returns a copy with values clamped to safe ranges, guarding against a
     * hand-edited config file producing nonsensical runtime behaviour.
     *
     * @return a validated configuration
     */
    public Config validated() {
        return new Config(
                enabled,
                host == null || host.isBlank() ? "127.0.0.1" : host,
                port <= 0 || port > 65535 ? 8765 : port,
                Math.max(1, obsEveryNTicks),
                Math.max(0.0, nearbyRadius),
                Math.max(0, maxNearbyEntities),
                Math.max(0.1, lookStepDegrees),
                Math.max(0.01, fineLookStepDegrees <= 0.0
                        ? defaults().fineLookStepDegrees() : fineLookStepDegrees),
                Math.max(1.0, fastLookMultiplier <= 0.0
                        ? defaults().fastLookMultiplier() : fastLookMultiplier),
                arena == null ? defaults().arena() : backfillArena(arena),
                reward == null ? defaults().reward() : backfillReward(reward)
        );
    }
    /**
     * Fills in arena fields absent from an older config (notably {@code targetSpawnDistance},
     * which Gson leaves at {@code 0.0}). A zero spawn distance would spawn the target on top of
     * the player, so it is replaced with the default.
     *
     * @param ar the loaded arena config
     * @return arena config with missing fields defaulted
     */
    private static Arena backfillArena(Arena ar) {
        Arena d = defaults().arena();
        double dist = ar.targetSpawnDistance() <= 0.0 ? d.targetSpawnDistance() : ar.targetSpawnDistance();
        String type = ar.targetEntity() == null || ar.targetEntity().isBlank()
                ? d.targetEntity() : ar.targetEntity();
        return new Arena(ar.x(), ar.y(), ar.z(), ar.healOnReset(), ar.clearEffects(), ar.kit(),
                ar.resistanceOnReset(), ar.summonTargetOnReset(), type, ar.targetResistance(), dist);
    }

    /**
     * Fills in reward weights that are absent from an older config file (Gson leaves missing
     * numeric fields at {@code 0.0}). If fields are zero — which an old file produces —
     * they are replaced with the defaults so new shaping takes effect without the user
     * having to re-create the file.
     *
     * @param r the loaded reward weights
     * @return reward weights with the new fields defaulted when unset
     */
    private static Reward backfillReward(Reward r) {
        Reward d = defaults().reward();
        double dmgWeight = r.damageWeight() == 0.0 ? d.damageWeight() : r.damageWeight();
        double dealtBonus = r.damageDealtBonus() == 0.0 ? d.damageDealtBonus() : r.damageDealtBonus();
        double kill = r.killReward() == 0.0 ? d.killReward() : r.killReward();
        double death = r.deathPenalty() == 0.0 ? d.deathPenalty() : r.deathPenalty();
        double step = r.stepPenalty() == 0.0 ? d.stepPenalty() : r.stepPenalty();
        double strike = r.strikePositionReward() == 0.0 ? d.strikePositionReward() : r.strikePositionReward();
        double aim = r.aimWeight() == 0.0 ? d.aimWeight() : r.aimWeight();
        double charged = r.chargedHitBonus() == 0.0 ? d.chargedHitBonus() : r.chargedHitBonus();
        double early = r.earlySwingPenalty() == 0.0 ? d.earlySwingPenalty() : r.earlySwingPenalty();
        double onTarget = r.onTargetReward() == 0.0 ? d.onTargetReward() : r.onTargetReward();
        double flee = r.fleePenalty() == 0.0 ? d.fleePenalty() : r.fleePenalty();
        double notFacing = r.notFacingPenalty() == 0.0 ? d.notFacingPenalty() : r.notFacingPenalty();
        double miss = r.missPenalty() == 0.0 ? d.missPenalty() : r.missPenalty();
        double shieldBlock = r.shieldBlockReward() == 0.0 ? d.shieldBlockReward() : r.shieldBlockReward();
        double shieldHolding = r.shieldHoldingCost() == 0.0 ? d.shieldHoldingCost() : r.shieldHoldingCost();
        double shieldMisuse = r.shieldMisusePenalty() == 0.0 ? d.shieldMisusePenalty() : r.shieldMisusePenalty();
        double shieldAttack = r.shieldAttackPenalty() == 0.0 ? d.shieldAttackPenalty() : r.shieldAttackPenalty();
        double shieldCooldown = r.shieldCooldownPenalty() == 0.0 ? d.shieldCooldownPenalty() : r.shieldCooldownPenalty();
        double hover = r.hoverPenalty() == 0.0 ? d.hoverPenalty() : r.hoverPenalty();
        double chase = r.chaseWeight() == 0.0 ? d.chaseWeight() : r.chaseWeight();
        double combo = r.comboHitBonus() == 0.0 ? d.comboHitBonus() : r.comboHitBonus();
        return new Reward(
                dmgWeight, dealtBonus, kill, death, step,
                r.shapedAim(), r.shapedDistance(), aim, notFacing,
                onTarget, strike, charged, early, miss, shieldBlock,
                shieldHolding, shieldMisuse, shieldAttack, shieldCooldown, flee,
                hover, chase, combo);
    }
}
