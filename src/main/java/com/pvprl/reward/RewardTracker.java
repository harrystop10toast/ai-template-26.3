package com.pvprl.reward;

import com.pvprl.config.Config;
import com.pvprl.obs.EnemyState;
import com.pvprl.obs.SelfState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes per-step and cumulative rewards for the controlled player from successive
 * observation states.
 *
 * <p>Deliberately free of any Minecraft API: it operates on the already-collected
 * {@link SelfState}/{@link EnemyState} snapshots, which makes the reward logic easy to
 * reason about and unit-testable in isolation. Health deltas drive damage dealt/taken,
 * health-to-zero transitions drive death/kill, and optional shaping adds dense signal.</p>
 *
 * <p>Call {@link #update} once per observed step; call {@link #resetEpisode} on episode
 * boundaries to clear accumulators and baselines.</p>
 */
public final class RewardTracker {

    private double cumulative;
    private long aliveTicks;

    /** Per-step reasons list, populated in {@link #update} and cleared each call. */
    private final List<String> lastReasons = new ArrayList<>();

    /**
     * Complete per-step reward ledger.  It deliberately contains every component on every
     * update, including zeroes, so debug output never hides an inactive punishment/reward.
     */
    private final Map<String, Double> lastComponents = new LinkedHashMap<>();

    private static final List<String> COMPONENT_NAMES = List.of(
            "step", "dealt", "taken", "kill", "death", "aim", "aimTurn", "notFacing",
            "distance", "knockback", "attackBlock", "shieldCooldown", "shieldBlock",
            "shieldHold", "shieldMisuse", "onTarget", "strike", "chargedHit", "miss",
            "earlySwing", "flee", "hover", "chase", "combo",
            // Survival flee curriculum (used instead of combat shaping when HP < 5 hearts)
            "fleeDist", "lookAway", "lookPunish", "stayPunish", "sprintFlee", "attackPunish", "eat",
            // Aim-gated flee curriculum (HP-gated look-at / look-away, look-only)
            "aimGatedAim", "aimGatedAimTurn", "aimGatedLocked", "aimGatedAway", "aimGatedAwayTurn",
            "aimGatedLockedAway", "aimGatedWrongDir", "aimGatedPitch"
    );

    /** Survival: punishments for looking/staying near fully fade out at this distance. */
    private static final double SURVIVAL_SAFE_DISTANCE = 10.0;
    /** Survival: below this health the flee curriculum replaces combat rewards. */
    public static final float SURVIVAL_LOW_HEALTH = 10.0f;

    /** Melee band where stare/on-target rewards are allowed to pay. */
    private static final double MELEE_RANGE = 3.2;
    /** Beyond this, aim alignment is fully gated off (anti stare-farm). */
    private static final double AIM_GATE_RANGE = 11.0;
    /** Hits farther apart than this (ticks) break the combo. */
    private static final int COMBO_WINDOW_TICKS = 40;

    private boolean hasBaseline;
    private float prevSelfHealth;
    private float prevSelfAbsorption;
    private float prevEnemyHealth;
    private boolean prevEnemyPresent;
    private double prevDistance;
    /** Remaining damage eligible for dense hit reward against the current target. */
    private float remainingDamageReward;
    /**
     * Previous angular error for the same target.  This supplies a directional learning
     * signal while the target is behind the player, where an absolute "is facing" reward
     * alone otherwise has a large flat region.
     */
    private double prevAimError = Double.NaN;
    private String prevEnemyId = "";
    /** Absolute yaw error to the target on the previous survival-flee step. */
    private double prevAbsYawDiff = Double.NaN;
    /** Absolute head pitch on the previous aim-gated step (sky/ground leveling). */
    private double prevAbsPitch = Double.NaN;
    /** Whether the agent has already reached the ≥10-block safe flee band this episode. */
    private boolean reachedSafeDistance;

    /** Episode-level block counting to enforce diminishing returns and anti-farming caps. */
    private int blocksInEpisode;
    private long lastBlockTick = -100;

    /** Consecutive landed hits on the current target within {@link #COMBO_WINDOW_TICKS}. */
    private int comboCount;
    private long lastHitTick = Long.MIN_VALUE / 2;
    /** True after at least one hit this episode on the current target (chase trigger). */
    private boolean hitCurrentTarget;

    /**
     * Updates reward state from the latest observed states and returns this step's snapshot.
     *
     * @param self  controlled player's state
     * @param enemy current target's state (see {@link EnemyState#present()})
     * @param cfg   reward weights
     * @return the per-step reward snapshot (also reflecting cumulative reward)
     */
    public RewardSnapshot update(SelfState self, EnemyState enemy, Config cfg) {
        return update(self, enemy, cfg, -1.0f, false, false, false);
    }

    /**
     * Updates reward state, additionally accounting for an attack performed this step.
     *
     * @param self           controlled player's state
     * @param enemy          current target's state
     * @param cfg            reward weights
     * @param attackStrength charge [0,1] of a swing performed this step, or {@code < 0} if none
     * @param onTarget       whether the crosshair is currently on the attackable target
     * @return the per-step reward snapshot
     */
    public RewardSnapshot update(SelfState self, EnemyState enemy, Config cfg,
                                 float attackStrength, boolean onTarget) {
        return update(self, enemy, cfg, attackStrength, onTarget, false, false);
    }

    /**
     * Updates reward state, additionally accounting for an attack performed this step and
     * whether the attack was blocked by the player's own raised shield.
     *
     * @param self                controlled player's state
     * @param enemy               current target's state
     * @param cfg                 reward weights
     * @param attackStrength      charge [0,1] of a swing performed this step, or {@code < 0} if none
     * @param onTarget            whether the crosshair is currently on the attackable target
     * @param attackWhileBlocking whether an attack was attempted while holding shield up
     * @return the per-step reward snapshot
     */
    public RewardSnapshot update(SelfState self, EnemyState enemy, Config cfg,
                                 float attackStrength, boolean onTarget,
                                 boolean attackWhileBlocking) {
        return update(self, enemy, cfg, attackStrength, onTarget, attackWhileBlocking, false);
    }

    /**
     * Updates reward state with full posture, attack, and shield disable tracking.
     *
     * @param self                    controlled player's state
     * @param enemy                   current target's state
     * @param cfg                     reward weights
     * @param attackStrength          charge [0,1] of a swing performed this step, or {@code < 0} if none
     * @param onTarget                whether the crosshair is currently on the attackable target
     * @param attackWhileBlocking     whether an attack was attempted while holding shield up
     * @param shieldOnCooldownAttempt whether shield was commanded while disabled on cooldown (e.g. by axe)
     * @return the per-step reward snapshot
     */
    public RewardSnapshot update(SelfState self, EnemyState enemy, Config cfg,
                                 float attackStrength, boolean onTarget,
                                 boolean attackWhileBlocking,
                                 boolean shieldOnCooldownAttempt) {
        Config.Reward w = cfg.reward();
        aliveTicks++;
        lastReasons.clear();
        resetComponents();

        float selfPool = self.health() + self.absorption();
        float enemyHealth = enemy.present() ? enemy.health() : 0.0f;

        if (!hasBaseline) {
            // First observed step: establish baselines, no deltas yet.
            prevSelfHealth = self.health();
            prevSelfAbsorption = self.absorption();
            prevEnemyHealth = enemyHealth;
            prevEnemyPresent = enemy.present();
            prevDistance = enemy.present() ? enemy.distance() : Double.MAX_VALUE;
            remainingDamageReward = enemy.present() ? enemyHealth : 0.0f;
            prevAimError = enemy.present() ? angularError(enemy) : Double.NaN;
            prevEnemyId = enemy.present() ? enemy.id() : "";
            hasBaseline = true;
            lastReasons.add("baseline-init (no reward)");
            return commit(0.0, 0.0f, 0.0f, null, w);
        }

        float prevSelfPool = prevSelfHealth + prevSelfAbsorption;
        float damageTaken = Math.max(0.0f, prevSelfPool - selfPool);

        // Damage dealt only counts while the same target persists.  The collector keeps a
        // target stable, and this additional guard prevents a target switch from being
        // mistaken for an enormous hit.
        boolean sameTarget = prevEnemyPresent && enemy.present()
                && enemy.id().equals(prevEnemyId);
        float damageDealt = 0.0f;
        if (sameTarget) {
            float observedDamage = Math.max(0.0f, prevEnemyHealth - enemyHealth);
            // A regenerating/invulnerable target must not become a reward printer.  An
            // episode can earn dense hit credit for at most the target's starting health;
            // terminal kill reward remains available as the actual PvP objective.
            damageDealt = Math.min(observedDamage, Math.max(0.0f, remainingDamageReward));
            remainingDamageReward = Math.max(0.0f, remainingDamageReward - damageDealt);
        }

        double step = 0.0;

        // ── Step penalty ──────────────────────────────────────────────────
        double stepPen = -w.stepPenalty();
        step += stepPen;
        addComponent("step", stepPen);
        lastReasons.add(String.format("step-penalty(%.4f)", stepPen));

        // ── Damage accounting + combo ─────────────────────────────────────
        if (damageDealt > 0.0f) {
            double dealt = damageDealt * (w.damageWeight() + w.damageDealtBonus());
            step += dealt;
            addComponent("dealt", dealt);
            lastReasons.add(String.format("damage-dealt=%.2f(+%.4f)", damageDealt, dealt));

            if (aliveTicks - lastHitTick <= COMBO_WINDOW_TICKS) {
                comboCount++;
            } else {
                comboCount = 1;
            }
            lastHitTick = aliveTicks;
            hitCurrentTarget = true;
            if (comboCount > 1 && w.comboHitBonus() > 0.0) {
                // 2nd hit → 1x bonus, 3rd → 2x, capped so long trades stay bounded.
                double comboR = w.comboHitBonus() * Math.min(comboCount - 1, 6);
                step += comboR;
                addComponent("combo", comboR);
                lastReasons.add(String.format("combo-x%d(+%.4f)", comboCount, comboR));
            }
        } else if (aliveTicks - lastHitTick > COMBO_WINDOW_TICKS) {
            comboCount = 0;
        }
        if (damageTaken > 0.0f) {
            double taken = -damageTaken * w.damageWeight();
            step += taken;
            addComponent("taken", taken);
            lastReasons.add(String.format("damage-taken=%.2f(%.4f)", damageTaken, taken));
        }

        EpisodeEvent event = null;

        // ── Death ─────────────────────────────────────────────────────────
        if (prevSelfPool > 0.0f && selfPool <= 0.0f) {
            step += w.deathPenalty();
            addComponent("death", w.deathPenalty());
            event = EpisodeEvent.DEATH;
            lastReasons.add(String.format("DEATH(%.1f)", w.deathPenalty()));
        }

        // ── Kill ──────────────────────────────────────────────────────────
        // Only trigger KILL when the target was previously observed alive and is now observed dead (health <= 0).
        // Target merely leaving range or being despawned on reset never counts as a kill.
        boolean enemyDied = sameTarget
                && prevEnemyHealth > 0.0f && enemyHealth <= 0.0f;
        if (enemyDied) {
            step += w.killReward();
            addComponent("kill", w.killReward());
            event = EpisodeEvent.KILL;
            lastReasons.add(String.format("KILL(+%.1f)", w.killReward()));
        }

        // ── Aim shaping (range-gated — staring from afar must not farm) ───
        if (w.shapedAim() && enemy.present()) {
            double angErr = angularError(enemy);
            // Full aim pay only in/near melee; fades to zero by AIM_GATE_RANGE.
            double rangeFactor = enemy.distance() <= MELEE_RANGE
                    ? 1.0
                    : Math.max(0.0, 1.0 - (enemy.distance() - MELEE_RANGE)
                            / (AIM_GATE_RANGE - MELEE_RANGE));

            // Use the complete 0..180 degree range.  The old 90 degree cutoff made
            // the reward zero for every direction in the rear hemisphere, so an agent
            // that started looking away could not tell a corrective turn from a bad one.
            double alignment = Math.max(0.0, 1.0 - (angErr / 180.0));
            double aimR = alignment * w.aimWeight() * rangeFactor;
            step += aimR;
            addComponent("aim", aimR);
            if (aimR > 0.0001)
                lastReasons.add(String.format("aim-align(+%.4f, rf=%.2f)", aimR, rangeFactor));

            // Turning progress stays available at any range so the agent can acquire
            // a fleeing target, but is scaled down outside melee.
            if (sameTarget && Double.isFinite(prevAimError)) {
                double improvement = Math.max(-30.0, Math.min(30.0, prevAimError - angErr));
                double turnScale = 0.35 + 0.65 * rangeFactor;
                double progressR = (improvement / 180.0) * w.aimWeight() * 4.0 * turnScale;
                step += progressR;
                addComponent("aimTurn", progressR);
                if (Math.abs(progressR) > 0.0001) {
                    lastReasons.add(String.format("aim-progress(%+.4f, %.1fdeg)",
                            progressR, improvement));
                }
            }
        }
        // Not-facing penalty: only penalize when significantly looking away from opponent (> 45 deg)
        if (enemy.present()) {
            double angErr = angularError(enemy);
            if (angErr > 45.0) {
                // Keep a gradient throughout the rear hemisphere instead of saturating
                // at 90 degrees.  Turning from 180 to 172 degrees must be measurably
                // better than turning the opposite way.
                double misAlign = Math.min(1.0, (angErr - 45.0) / 135.0);
                double notFacingPen = -misAlign * w.notFacingPenalty();
                step += notFacingPen;
                addComponent("notFacing", notFacingPen);
                if (notFacingPen < -0.0001)
                    lastReasons.add(String.format("not-facing(%.4f)", notFacingPen));
            }
        }

        // ── Distance shaping (PBRS) ───────────────────────────────────────
        // Stronger close pressure outside melee so hovering at ~4–6 blocks is not stable.
        if (w.shapedDistance() && sameTarget && prevDistance < Double.MAX_VALUE) {
            double distDelta = prevDistance - enemy.distance();
            if (distDelta < 0.0 && damageDealt > 0.0f) {
                // Knockback from our attack: suppress retreat penalty
                lastReasons.add("pbrs-knockback-suppressed");
            } else if (enemy.distance() > MELEE_RANGE || distDelta > 0) {
                double scale = enemy.distance() > MELEE_RANGE ? 0.85 : 0.5;
                double pbrs = distDelta * scale;
                step += pbrs;
                addComponent("distance", pbrs);
                if (Math.abs(pbrs) > 0.0001)
                    lastReasons.add(String.format("pbrs-dist(%s%.4f)", pbrs >= 0 ? "+" : "", pbrs));
            }
        }

        // ── Hover / stare-farm penalty ────────────────────────────────────
        // Looking at a passive flee mob from just outside melee without closing
        // was a free reward printer; tax that posture hard.
        if (enemy.present() && w.hoverPenalty() > 0.0
                && enemy.distance() > MELEE_RANGE && enemy.distance() < AIM_GATE_RANGE) {
            double angErr = angularError(enemy);
            boolean staring = angErr < 30.0;
            boolean notClosing = !sameTarget || enemy.distance() >= prevDistance - 0.02;
            if (staring && notClosing && damageDealt <= 0.0f) {
                double hoverPen = -w.hoverPenalty()
                        * Math.min(1.0, (enemy.distance() - MELEE_RANGE) / 4.0);
                step += hoverPen;
                addComponent("hover", hoverPen);
                lastReasons.add(String.format("hover-stare(%.4f, dist=%.2f)", hoverPen, enemy.distance()));
            }
        }

        // ── Sprint-chase when the target opens distance ───────────────────
        if (sameTarget && enemy.present() && w.chaseWeight() > 0.0 && prevDistance < Double.MAX_VALUE) {
            boolean opening = enemy.distance() > prevDistance + 0.05;
            boolean chaseBand = enemy.distance() > MELEE_RANGE;
            boolean shouldChase = chaseBand && (opening || (hitCurrentTarget && enemy.distance() > 4.0));
            if (shouldChase) {
                double closing = prevDistance - enemy.distance();
                double horizSpeed = Math.hypot(self.vel().x(), self.vel().z());
                if (self.sprinting() && closing > 0.0) {
                    double chaseR = closing * w.chaseWeight();
                    if (!self.onGround() && horizSpeed > 0.2) {
                        chaseR += w.chaseWeight() * 0.2; // sprint-jump chase bonus
                    }
                    step += chaseR;
                    addComponent("chase", chaseR);
                    lastReasons.add(String.format("sprint-chase(+%.4f, close=%.3f)", chaseR, closing));
                } else if (!self.sprinting() && enemy.distance() > 4.5) {
                    double noSprintPen = -w.chaseWeight() * 0.04;
                    step += noSprintPen;
                    addComponent("chase", noSprintPen);
                    lastReasons.add(String.format("chase-no-sprint(%.4f)", noSprintPen));
                }
            }
        }
        // ── Shield training ───────────────────────────────────────────────
        if (attackWhileBlocking) {
            step -= w.shieldAttackPenalty();
            addComponent("attackBlock", -w.shieldAttackPenalty());
            lastReasons.add(String.format("attack-while-blocking(%.4f)", -w.shieldAttackPenalty()));
        }
        if (shieldOnCooldownAttempt) {
            step -= w.shieldCooldownPenalty();
            addComponent("shieldCooldown", -w.shieldCooldownPenalty());
            lastReasons.add(String.format("shield-on-cooldown(%.4f)", -w.shieldCooldownPenalty()));
        }

        boolean deflected = false;
        if (self.blocking() && enemy.present()) {
            boolean facingEnemy = Math.abs(enemy.yawDiff()) <= 90.0;
            deflected = facingEnemy && enemy.distance() <= 2.8 && damageTaken == 0.0f
                    && prevDistance < Double.MAX_VALUE && (enemy.distance() - prevDistance) > 0.15;
            if (deflected) {
                long now = aliveTicks;
                if (now - lastBlockTick >= 20 && blocksInEpisode < 5) {
                    double diminishing = Math.pow(0.7, blocksInEpisode);
                    double blockR = w.shieldBlockReward() * diminishing;
                    step += blockR;
                    addComponent("shieldBlock", blockR);
                    blocksInEpisode++;
                    lastBlockTick = now;
                    lastReasons.add(String.format("SHIELD-DEFLECT(+%.4f, ep#%d, dimFactor=%.2f)",
                            blockR, blocksInEpisode, diminishing));
                } else {
                    lastReasons.add(String.format("shield-deflect-suppressed(ep#%d, cooldown=%d)",
                            blocksInEpisode, (int)(aliveTicks - lastBlockTick)));
                }
            } else {
                double holdCost = -w.shieldHoldingCost();
                step += holdCost;
                addComponent("shieldHold", holdCost);
                lastReasons.add(String.format("shield-holding-cost(%.4f)", holdCost));
                if (enemy.distance() > 4.0) {
                    double misuse = -w.shieldMisusePenalty();
                    step += misuse;
                    addComponent("shieldMisuse", misuse);
                    lastReasons.add(String.format("shield-misuse-turtling(%.4f)", misuse));
                }
            }
        }

        // ── On-target reward (melee only — no far-range crosshair farming) ─
        if (onTarget && enemy.present() && enemy.distance() <= MELEE_RANGE) {
            step += w.onTargetReward();
            addComponent("onTarget", w.onTargetReward());
            lastReasons.add(String.format("on-target(+%.4f)", w.onTargetReward()));
        }

        // ── Strike-position reward ────────────────────────────────────────
        if (enemy.present() && enemy.distance() <= MELEE_RANGE) {
            double angErr = Math.abs(enemy.yawDiff()) + Math.abs(enemy.pitchDiff());
            if (angErr < 20.0) {
                step += w.strikePositionReward();
                addComponent("strike", w.strikePositionReward());
                lastReasons.add(String.format("strike-position(+%.4f)", w.strikePositionReward()));
            }
        }

        // ── Attack timing & precision ─────────────────────────────────────
        if (attackStrength >= 0.0f) {
            if (damageDealt > 0.0f) {
                double chargedBonus = damageDealt * w.chargedHitBonus() * attackStrength;
                step += chargedBonus;
                addComponent("chargedHit", chargedBonus);
                lastReasons.add(String.format("charged-hit-bonus(+%.4f, str=%.2f)", chargedBonus, attackStrength));
            } else if (!onTarget) {
                step -= w.missPenalty();
                addComponent("miss", -w.missPenalty());
                lastReasons.add(String.format("miss-penalty(%.4f)", -w.missPenalty()));
            }
            if (attackStrength < 0.6f) {
                double earlyPen = -w.earlySwingPenalty() * (0.6f - attackStrength);
                step += earlyPen;
                addComponent("earlySwing", earlyPen);
                lastReasons.add(String.format("early-swing(%.4f, str=%.2f)", earlyPen, attackStrength));
            }
        }

        // ── Flee penalty ──────────────────────────────────────────────────
        boolean attempted = attackStrength >= 0.0f;
        if (sameTarget && !attempted && enemy.distance() > 6.0) {
            double angErr = Math.max(Math.abs(enemy.yawDiff()), Math.abs(enemy.pitchDiff()));
            boolean notFacingFlee = angErr > 60.0;
            boolean movingAway = enemy.distance() > prevDistance + 0.05;
            if (notFacingFlee && movingAway) {
                double distFactor = Math.min(1.0, (enemy.distance() - 6.0) / 10.0);
                double fleePen = -w.fleePenalty() * distFactor;
                step += fleePen;
                addComponent("flee", fleePen);
                lastReasons.add(String.format("flee-penalty(%.4f, dist=%.2f)", fleePen, enemy.distance()));
            }
        }

        // Advance baselines.
        prevSelfHealth = self.health();
        prevSelfAbsorption = self.absorption();
        prevEnemyHealth = enemyHealth;
        prevEnemyPresent = enemy.present();
        prevDistance = enemy.present() ? enemy.distance() : Double.MAX_VALUE;
        if (!sameTarget) {
            remainingDamageReward = enemy.present() ? enemyHealth : 0.0f;
            comboCount = 0;
            lastHitTick = Long.MIN_VALUE / 2;
            hitCurrentTarget = false;
        }
        prevAimError = enemy.present() ? angularError(enemy) : Double.NaN;
        prevEnemyId = enemy.present() ? enemy.id() : "";

        return commit(step, damageDealt, damageTaken, event, w);
    }

    /**
     * Survival flee curriculum: used while HP &lt; 5 hearts instead of combat shaping.
     *
     * <p>Rewards opening distance and facing away; punishes looking at or lingering near
     * the target (both fade to zero at {@link #SURVIVAL_SAFE_DISTANCE}); punishes attacks.
     * Combat aim / on-target / chase / flee-penalty terms are intentionally absent so
     * debug scores cannot rise from hitting the armor stand.</p>
     *
     * @param self           controlled player
     * @param enemy          flee-reference target (armor stand)
     * @param cfg            config (step penalty weight reused)
     * @param attackStrength &gt;= 0 if a swing was attempted this step
     * @param eating         whether the agent is currently eating / starting an eat
     * @return flee-shaped reward snapshot
     */
    public RewardSnapshot updateSurvivalFlee(SelfState self, EnemyState enemy, Config cfg,
                                             float attackStrength, boolean eating) {
        aliveTicks++;
        lastReasons.clear();
        resetComponents();

        float selfPool = self.health() + self.absorption();
        if (!hasBaseline) {
            prevSelfHealth = self.health();
            prevSelfAbsorption = self.absorption();
            prevEnemyHealth = enemy.present() ? enemy.health() : 0.0f;
            prevEnemyPresent = enemy.present();
            prevDistance = enemy.present() ? enemy.distance() : Double.MAX_VALUE;
            prevAbsYawDiff = enemy.present() ? Math.abs(enemy.yawDiff()) : Double.NaN;
            prevEnemyId = enemy.present() ? enemy.id() : "";
            hasBaseline = true;
            lastReasons.add("survival-flee-baseline");
            return commit(0.0, 0.0f, 0.0f, null, cfg.reward());
        }

        EpisodeEvent event = null;
        if (prevSelfHealth + prevSelfAbsorption > 0.0f && selfPool <= 0.0f) {
            addComponent("death", cfg.reward().deathPenalty());
            event = EpisodeEvent.DEATH;
            lastReasons.add(String.format("DEATH(%.1f)", cfg.reward().deathPenalty()));
            advanceSurvivalBaselines(self, enemy);
            return commit(cfg.reward().deathPenalty(), 0.0f, 0.0f, event, cfg.reward());
        }

        double step = 0.0;
        if (!enemy.present()) {
            step = -0.02;
            addComponent("step", step);
            lastReasons.add("survival-no-target(-0.02)");
            prevAbsYawDiff = Double.NaN;
            prevDistance = Double.MAX_VALUE;
            advanceSurvivalBaselines(self, enemy);
            return commit(step, 0.0f, 0.0f, null, cfg.reward());
        }

        double distance = enemy.distance();
        double absYaw = Math.abs(enemy.yawDiff());
        double distProgress = 0.0;
        if (prevDistance < Double.MAX_VALUE) {
            distProgress = clamp(distance - prevDistance, -1.0, 1.0);
        }
        boolean movingAway = distProgress > 0.05;
        boolean facingAway = absYaw >= 90.0;
        double near = distance >= SURVIVAL_SAFE_DISTANCE
                ? 0.0
                : clamp(1.0 - distance / SURVIVAL_SAFE_DISTANCE, 0.0, 1.0);
        double horizSpeed = Math.hypot(self.vel().x(), self.vel().z());

        // Opening the gap.
        double fleeDist = distProgress * 3.0;
        step += fleeDist;
        addComponent("fleeDist", fleeDist);
        if (Math.abs(fleeDist) > 0.0001) {
            lastReasons.add(String.format("flee-dist(%+.4f)", fleeDist));
        }
        if (distance >= SURVIVAL_SAFE_DISTANCE && !reachedSafeDistance) {
            step += 12.0;
            addComponent("fleeDist", 12.0);
            reachedSafeDistance = true;
            lastReasons.add("safe-distance(+12.0)");
        }

        // Face opposite the threat.
        double lookAway = Math.pow(absYaw / 180.0, 2.0) * 0.20;
        if (absYaw >= 160.0) {
            lookAway += 0.08;
        }
        step += lookAway;
        addComponent("lookAway", lookAway);
        if (lookAway > 0.0001) {
            lastReasons.add(String.format("look-away(+%.4f, yaw=%.0f)", lookAway, absYaw));
        }
        if (Double.isFinite(prevAbsYawDiff)) {
            double lookProg = clamp(absYaw - prevAbsYawDiff, -2.0, 2.0) * 0.35;
            step += lookProg;
            addComponent("lookAway", lookProg);
            if (Math.abs(lookProg) > 0.0001) {
                lastReasons.add(String.format("look-away-progress(%+.4f)", lookProg));
            }
        }

        // Looking at / staying near: both fade to 0 at 10 blocks.
        double lookAtAmount = Math.max(0.0, 1.0 - absYaw / 180.0);
        double lookPunish = -0.35 * near * lookAtAmount;
        double stayPunish = -0.25 * near;
        step += lookPunish + stayPunish;
        addComponent("lookPunish", lookPunish);
        addComponent("stayPunish", stayPunish);
        if (lookPunish < -0.0001) {
            lastReasons.add(String.format("look-punish(%.4f, near=%.2f)", lookPunish, near));
        }
        if (stayPunish < -0.0001) {
            lastReasons.add(String.format("stay-punish(%.4f, d=%.1f)", stayPunish, distance));
        }

        // Sprint away while facing away.
        double sprintFlee = 0.0;
        if (movingAway && facingAway) {
            sprintFlee += horizSpeed * 2.0;
            if (self.sprinting()) {
                sprintFlee += 0.04;
            }
            if (!self.onGround() && self.sprinting() && horizSpeed > 0.15) {
                sprintFlee += 0.08; // sprint-jump flee
            }
        }
        if (self.sprinting() && horizSpeed > 0.15 && distProgress < -0.05) {
            sprintFlee -= 0.12; // sprinting toward the threat
        }
        step += sprintFlee;
        addComponent("sprintFlee", sprintFlee);
        if (Math.abs(sprintFlee) > 0.0001) {
            lastReasons.add(String.format("sprint-flee(%+.4f)", sprintFlee));
        }

        // Attacking the flee reference is always wrong while low HP.
        if (attackStrength >= 0.0f) {
            double attackPen = -1.5;
            step += attackPen;
            addComponent("attackPunish", attackPen);
            lastReasons.add(String.format("attack-punish(%.4f)", attackPen));
        }

        if (eating) {
            double eatR = distance < SURVIVAL_SAFE_DISTANCE ? -1.5 : 2.0;
            step += eatR;
            addComponent("eat", eatR);
            lastReasons.add(String.format("eat(%+.4f, d=%.1f)", eatR, distance));
        }

        double stepPen = -0.003;
        step += stepPen;
        addComponent("step", stepPen);

        advanceSurvivalBaselines(self, enemy);
        prevAbsYawDiff = absYaw;
        return commit(step, 0.0f, 0.0f, event, cfg.reward());
    }

    private void advanceSurvivalBaselines(SelfState self, EnemyState enemy) {
        prevSelfHealth = self.health();
        prevSelfAbsorption = self.absorption();
        prevEnemyHealth = enemy.present() ? enemy.health() : 0.0f;
        prevEnemyPresent = enemy.present();
        prevDistance = enemy.present() ? enemy.distance() : Double.MAX_VALUE;
        prevEnemyId = enemy.present() ? enemy.id() : "";
        prevAimError = enemy.present() ? angularError(enemy) : Double.NaN;
    }

    /**
     * Aim-gated flee curriculum: health-gated look-at / look-away training.
     *
     * <p>When HP ≥ 50% of maxHealth: reward aiming at the mannequin (alignment +
     * turn progress + locked bonus), punish looking away. When HP &lt; 50%: reward
     * facing <em>away</em> from the mannequin (anti-alignment + turn-away progress +
     * locked-away bonus), punish looking at it. No combat, movement, or distance
     * shaping — this is look-only training for a basic flee algorithm.</p>
     *
     * @param self  controlled player's state (uses health / maxHealth for the gate)
     * @param enemy target mannequin
     * @param cfg   config (step penalty weight reused)
     * @return aim-gated reward snapshot
     */
    public RewardSnapshot updateAimGated(SelfState self, EnemyState enemy, Config cfg) {
        aliveTicks++;
        lastReasons.clear();
        resetComponents();

        Config.Reward w = cfg.reward();

        if (!hasBaseline) {
            prevSelfHealth = self.health();
            prevSelfAbsorption = self.absorption();
            prevEnemyHealth = enemy.present() ? enemy.health() : 0.0f;
            prevEnemyPresent = enemy.present();
            prevDistance = enemy.present() ? enemy.distance() : Double.MAX_VALUE;
            prevAimError = enemy.present() ? angularError(enemy) : Double.NaN;
            prevAbsYawDiff = enemy.present() ? Math.abs(enemy.yawDiff()) : Double.NaN;
            prevAbsPitch = Math.abs(self.pitch());
            prevEnemyId = enemy.present() ? enemy.id() : "";
            hasBaseline = true;
            lastReasons.add("aim-gated-baseline");
            return commit(0.0, 0.0f, 0.0f, null, w);
        }

        double step = 0.0;

        // No step penalty — it made NOOP safer than trying a wrong look.
        if (!enemy.present()) {
            lastReasons.add("aim-gated-no-target");
            prevAimError = Double.NaN;
            prevAbsYawDiff = Double.NaN;
            prevAbsPitch = Math.abs(self.pitch());
            prevEnemyId = "";
            prevEnemyPresent = false;
            prevDistance = Double.MAX_VALUE;
            return commit(step, 0.0f, 0.0f, null, w);
        }

        double angErr = angularError(enemy);
        double absYaw = Math.abs(enemy.yawDiff());
        double absPitch = Math.abs(self.pitch());
        float maxHp = self.maxHealth() > 0.0f ? self.maxHealth() : 20.0f;
        boolean healthy = self.health() >= maxHp * 0.5f;

        // Keep the crosshair near eye-level — staring at sky/ground is useless in PvP.
        final double pitchFree = 15.0;
        double pitchExcess = Math.max(0.0, absPitch - pitchFree);
        double pitchPen = -0.40 * (pitchExcess / (90.0 - pitchFree));
        step += pitchPen;
        addComponent("aimGatedPitch", pitchPen);
        if (pitchPen < -0.0001) {
            lastReasons.add(String.format("pitch-horizon(%.4f, |p|=%.0f)", pitchPen, absPitch));
        }
        if (Double.isFinite(prevAbsPitch)) {
            double levelProg = clamp(prevAbsPitch - absPitch, -4.0, 8.0) / 8.0;
            // Stronger while fleeing: leveling pitch is part of the flee-sight skill.
            double levelR = levelProg * (healthy ? 0.10 : 0.25);
            step += levelR;
            addComponent("aimGatedPitch", levelR);
            if (Math.abs(levelR) > 0.0001) {
                lastReasons.add(String.format("pitch-level(%+.4f)", levelR));
            }
        }

        if (healthy) {
            // ── HP ≥ 50%: MUST look AT the mannequin ───────────────────────
            // Dense positive when on-target so cumul rises while facing it; strong
            // penalty when looking away so the policy cannot keep a flee habit.
            double alignment = Math.max(0.0, 1.0 - (angErr / 180.0));
            double aimR = alignment * 0.40;
            step += aimR;
            addComponent("aimGatedAim", aimR);
            if (aimR > 0.0001) {
                lastReasons.add(String.format("aim-at(+%.4f, align=%.2f)", aimR, alignment));
            }

            if (Double.isFinite(prevAimError)) {
                double improvement = Math.max(-8.0, Math.min(8.0, prevAimError - angErr));
                double progressR = (improvement / 8.0) * 0.60;
                step += progressR;
                addComponent("aimGatedAimTurn", progressR);
                if (Math.abs(progressR) > 0.0001) {
                    lastReasons.add(String.format("aim-turn(%+.4f, %.1fdeg)", progressR, improvement));
                }
            }

            if (angErr < 8.0) {
                double locked = 1.5;
                step += locked;
                addComponent("aimGatedLocked", locked);
                lastReasons.add(String.format("aim-locked(+%.4f, err=%.1f)", locked, angErr));
            } else if (angErr > 30.0) {
                // Facing away / off-target at full HP must hurt — this is what the
                // old mid-episode look-away bias taught the agent to do wrongly.
                double wrongDir = -0.60 * (1.0 - alignment);
                step += wrongDir;
                addComponent("aimGatedWrongDir", wrongDir);
                lastReasons.add(String.format("aim-wrong-dir(%.4f, err=%.0f)", wrongDir, angErr));
            }
        } else {
            // ── HP < 50%: MUST face AWAY (yaw-only) ────────────────────────
            double yawAlignment = Math.max(0.0, 1.0 - (absYaw / 180.0));
            double antiAlignment = 1.0 - yawAlignment;
            double awayR = antiAlignment * 0.40;
            step += awayR;
            addComponent("aimGatedAway", awayR);
            if (awayR > 0.0001) {
                lastReasons.add(String.format("aim-away(+%.4f, anti=%.2f, yaw=%.0f)", awayR, antiAlignment, absYaw));
            }

            if (Double.isFinite(prevAbsYawDiff)) {
                double awayProgress = Math.max(-8.0, Math.min(8.0, absYaw - prevAbsYawDiff));
                double progressR = (awayProgress / 8.0) * 0.60;
                step += progressR;
                addComponent("aimGatedAwayTurn", progressR);
                if (Math.abs(progressR) > 0.0001) {
                    lastReasons.add(String.format("away-turn(%+.4f, %.1fdeg)", progressR, awayProgress));
                }
            }

            if (absYaw > 150.0) {
                double lockedAway = 1.5;
                step += lockedAway;
                addComponent("aimGatedLockedAway", lockedAway);
                lastReasons.add(String.format("away-locked(+%.4f, yaw=%.1f)", lockedAway, absYaw));
            } else if (absYaw < 90.0) {
                // Looking at the threat while low HP: cumul must drop.
                double wrongDir = -0.60 * yawAlignment;
                step += wrongDir;
                addComponent("aimGatedWrongDir", wrongDir);
                lastReasons.add(String.format("away-wrong-dir(%.4f, yaw=%.0f)", wrongDir, absYaw));
            }
        }

        // Advance baselines.
        prevSelfHealth = self.health();
        prevSelfAbsorption = self.absorption();
        prevEnemyHealth = enemy.present() ? enemy.health() : 0.0f;
        prevEnemyPresent = enemy.present();
        prevDistance = enemy.present() ? enemy.distance() : Double.MAX_VALUE;
        prevAimError = enemy.present() ? angularError(enemy) : Double.NaN;
        prevAbsYawDiff = enemy.present() ? absYaw : Double.NaN;
        prevAbsPitch = absPitch;
        prevEnemyId = enemy.present() ? enemy.id() : "";

        return commit(step, 0.0f, 0.0f, null, w);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Returns the larger of the horizontal and vertical aiming errors.  Keeping this
     * calculation in one place prevents the aim reward and not-facing penalty from
     * disagreeing about whether a target is aligned.
     */
    private static double angularError(EnemyState enemy) {
        return Math.max(Math.abs(enemy.yawDiff()), Math.abs(enemy.pitchDiff()));
    }

    private void resetComponents() {
        lastComponents.clear();
        for (String name : COMPONENT_NAMES) {
            lastComponents.put(name, 0.0);
        }
    }

    private void addComponent(String name, double value) {
        lastComponents.merge(name, value, Double::sum);
    }

    private RewardSnapshot commit(double step, float dealt, float taken,
                                  EpisodeEvent event, Config.Reward w) {
        cumulative += step;
        return new RewardSnapshot(step, cumulative, dealt, taken,
                event == null ? null : event.name());
    }

    /**
     * Emits a terminal snapshot for an externally-detected event (e.g. timeout) without a
     * health delta, applying the relevant weight.
     *
     * @param event the terminal event
     * @param cfg   reward weights
     * @return the terminal snapshot
     */
    public RewardSnapshot terminal(EpisodeEvent event, Config cfg) {
        resetComponents();
        double step = switch (event) {
            case DEATH -> cfg.reward().deathPenalty();
            case KILL -> cfg.reward().killReward();
            case TIMEOUT, RESET, EAT_SUCCESS -> 0.0;
        };
        switch (event) {
            case DEATH -> addComponent("death", step);
            case KILL -> addComponent("kill", step);
            case TIMEOUT, RESET, EAT_SUCCESS -> { /* terminal event has no scalar reward */ }
        }
        cumulative += step;
        return new RewardSnapshot(step, cumulative, 0.0f, 0.0f, event.name());
    }

    /** Clears accumulators and baselines for a new episode. */
    public void resetEpisode() {
        cumulative = 0.0;
        aliveTicks = 0;
        hasBaseline = false;
        remainingDamageReward = 0.0f;
        prevAimError = Double.NaN;
        prevAbsYawDiff = Double.NaN;
        prevAbsPitch = Double.NaN;
        prevEnemyId = "";
        reachedSafeDistance = false;
        blocksInEpisode = 0;
        lastBlockTick = -100;
        comboCount = 0;
        lastHitTick = Long.MIN_VALUE / 2;
        hitCurrentTarget = false;
        lastReasons.clear();
        resetComponents();
    }

    /**
     * Returns the list of human-readable reason strings that explain each reward/penalty
     * component from the most recent {@link #update} call. May be empty on the baseline-init
     * step. The list is replaced on every call to {@code update}.
     */
    public List<String> lastReasons() {
        return Collections.unmodifiableList(lastReasons);
    }

    /**
     * Returns the complete component ledger for the latest reward update.  All known
     * components are present and are {@code 0.0} when they did not contribute this step.
     */
    public Map<String, Double> lastComponents() {
        return Collections.unmodifiableMap(lastComponents);
    }

    /** @return number of ticks the current episode has been running. */
    public long aliveTicks() {
        return aliveTicks;
    }

    /** @return cumulative reward for the current episode. */
    public double cumulative() {
        return cumulative;
    }
}
