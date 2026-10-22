package com.pvprl.obs;

import com.pvprl.config.Config;
import com.pvprl.reward.RewardSnapshot;
import com.pvprl.util.ArenaReset;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a structured {@link Observation} from the live client state.
 *
 * <p>This is the single place in the mod that reads Minecraft world/entity state for
 * observation purposes. Keeping all Mojang-API reads here means a changed getter in a
 * future Minecraft version is a localized fix. Every method is a cheap field read or a
 * single bounded query, so sampling every tick is affordable.</p>
 *
 * <p>Not thread-safe: must be called on the client main thread (end-of-tick).</p>
 */
public final class ObservationCollector {

    /** Target selection strategy. */
    private String targetName; // null => auto nearest living entity
    private LivingEntity currentTarget;

    /** Resets target tracking state on episode boundary. */
    public void reset() {
        currentTarget = null;
    }

    /**
     * @param targetName explicit target entity scoreboard name, or {@code null} for nearest
     *                   ({@code /pvprl target auto}). Clears the sticky lock so the next
     *                   {@link #pickTarget} re-searches — required after every mannequin refresh.
     */
    public void setTarget(String targetName) {
        this.targetName = targetName;
        this.currentTarget = null;
    }

    /** @return the explicit target name, or {@code null} if auto-targeting. */
    public String getTarget() {
        return targetName;
    }

    /**
     * Collects a full observation for the given player.
     *
     * @param player the controlled local player
     * @param level  the client level the player is in
     * @param cfg    active configuration (radii, caps)
     * @param tick   current tick counter
     * @param reward reward snapshot to embed
     * @return the assembled observation
     */
    public Observation collect(LocalPlayer player, ClientLevel level, Config cfg,
                               long tick, RewardSnapshot reward) {
        return collect((LivingEntity) player, level, cfg, tick, reward);
    }

    /** Collects an observation for a server-owned ground bot such as the opponent husk. */
    public Observation collect(LivingEntity player, ClientLevel level, Config cfg,
                               long tick, RewardSnapshot reward) {
        SelfState self = collectSelf(player);
        LivingEntity target = pickTarget(player, level, cfg);
        EnemyState enemy = target == null ? EnemyState.absent() : collectEnemy(player, target, level);
        List<EntityBrief> nearby = collectNearby(player, level, cfg, target);
        return new Observation(tick, self, enemy, nearby, reward);
    }

    // --- self -------------------------------------------------------------

    private SelfState collectSelf(LivingEntity p) {
        List<EffectDto> effects = new ArrayList<>();
        for (MobEffectInstance e : p.getActiveEffects()) {
            effects.add(new EffectDto(effectId(e), e.getAmplifier(), e.getDuration()));
        }
        double toughness = p.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        ItemStack shield = p.getOffhandItem().is(Items.SHIELD) ? p.getOffhandItem() : p.getMainHandItem();
        // Item cooldowns belong to a connected player. A server-owned husk is a
        // LivingEntity, so it has no cooldown tracker to query.
        float shieldCooldown = p instanceof LocalPlayer local
                ? local.getCooldowns().getCooldownPercent(shield, 0.0f)
                : 0.0f;
        return new SelfState(
                Vec3Dto.of(p.position()),
                Vec3Dto.of(p.getDeltaMovement()),
                wrapDegrees(p.getYRot()),
                p.getXRot(),
                p.getHealth(),
                p.getMaxHealth(),
                p.getAbsorptionAmount(),
                p instanceof LocalPlayer local ? local.getFoodData().getFoodLevel() : 20,
                p instanceof LocalPlayer local ? local.getFoodData().getSaturationLevel() : 5.0f,
                p.getArmorValue(),
                toughness,
                p instanceof LocalPlayer local ? local.getInventory().getSelectedSlot() : 0,
                itemId(p.getMainHandItem()),
                p.onGround(),
                p.isSprinting(),
                p.isShiftKeyDown(),
                p.isBlocking(),
                p instanceof LocalPlayer local ? local.getAttackStrengthScale(0.0f) : 1.0f,
                shieldCooldown,
                effects
        );
    }

    // --- enemy ------------------------------------------------------------

    private EnemyState collectEnemy(LivingEntity self, LivingEntity target, ClientLevel level) {
        Vec3 relPos = Vec3Dto.diffVec(target.position(), self.position());
        Vec3 relVel = Vec3Dto.diffVec(target.getDeltaMovement(), self.getDeltaMovement());
        double distance = self.distanceTo(target);

        // Yaw the self player would need to face the target, normalized to [-180,180].
        double dx = target.getX() - self.getX();
        double dz = target.getZ() - self.getZ();
        float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float yawDiff = wrapDegrees(desiredYaw - self.getYRot());

        double horiz = Math.sqrt(dx * dx + dz * dz);
        double dy = target.getEyePosition().y - self.getEyePosition().y;
        float desiredPitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        float pitchDiff = wrapDegrees(desiredPitch - self.getXRot());

        float enemyHealth = Math.max(0.0f, target.getHealth());

        return new EnemyState(
                true,
                target.getScoreboardName(),
                target.getStringUUID(),
                Vec3Dto.of(relPos),
                Vec3Dto.of(relVel),
                distance,
                yawDiff,
                pitchDiff,
                enemyHealth,
                target.getArmorValue(),
                itemId(target.getMainHandItem()),
                target.isSprinting(),
                target.isUsingItem(),
                hasLineOfSight(self, target, level)
        );
    }

    /**
     * Picks the current target. With an explicit name set, matches any living entity whose
     * scoreboard name equals it (works for players and named mobs). Otherwise selects
     * the nearest attackable {@link LivingEntity} within {@code nearbyRadius} that is not the
     * controlled player — this includes mobs and other players, so both PvE and PvP targets
     * are valid sparring targets.
     */
    public LivingEntity pickTarget(LivingEntity self, ClientLevel level, Config cfg) {
        // If current target exists and is dead/dying, retain it for one tick so its death
        // is observed with health <= 0 by the reward tracker, then clear.
        if (currentTarget != null && (currentTarget.isDeadOrDying() || currentTarget.getHealth() <= 0.0f)) {
            LivingEntity deadTarget = currentTarget;
            currentTarget = null;
            return deadTarget;
        }

        double radius = cfg.nearbyRadius() > 0 ? cfg.nearbyRadius() : 32.0;

        // Keep the selected entity for its lifetime.  Re-selecting the nearest entity on
        // every observation lets two nearby mobs alternate as the target, which corrupts
        // health/distance deltas and can be exploited as artificial reward.
        if (currentTarget != null
                && currentTarget.isAlive()
                && !currentTarget.isDeadOrDying()
                && self.distanceToSqr(currentTarget) <= radius * radius
                && (targetName == null || matchesTargetName(currentTarget, targetName))) {
            return currentTarget;
        }

        List<LivingEntity> candidates = level.getEntitiesOfClass(
                LivingEntity.class,
                self.getBoundingBox().inflate(radius),
                e -> e != self && e.isAlive() && !e.isDeadOrDying());

        LivingEntity best = null;
        double bestDistSqr = Double.MAX_VALUE;
        // When auto-targeting (`/pvprl target auto`), prefer curriculum armor stands so a
        // leftover villager cannot steal the flee reference from the survival mannequin.
        LivingEntity bestMannequin = null;
        double bestMannequinDistSqr = Double.MAX_VALUE;
        for (LivingEntity other : candidates) {
            if (targetName != null) {
                if (matchesTargetName(other, targetName)) {
                    currentTarget = other;
                    return other;
                }
                continue;
            }
            double d = self.distanceToSqr(other);
            if (isCurriculumMannequin(other) && d < bestMannequinDistSqr) {
                bestMannequinDistSqr = d;
                bestMannequin = other;
            }
            if (d < bestDistSqr) {
                bestDistSqr = d;
                best = other;
            }
        }
        currentTarget = bestMannequin != null ? bestMannequin : best;
        return currentTarget;
    }

    /** Survival / movement / aim-gated curriculum armor stands used as passive training markers. */
    private static boolean isCurriculumMannequin(LivingEntity entity) {
        if (!(entity instanceof ArmorStand)) {
            return false;
        }
        if (entity.entityTags().contains(ArenaReset.SURVIVAL_MANNEQUIN_TAG)
                || entity.entityTags().contains("pvprl_movement_target")
                || entity.entityTags().contains(ArenaReset.AIM_GATED_MANNEQUIN_TAG)) {
            return true;
        }
        if (entity.getCustomName() == null) {
            return false;
        }
        String name = entity.getCustomName().getString();
        return ArenaReset.SURVIVAL_MANNEQUIN_NAME.equals(name)
                || "PvpRlMovementTarget".equals(name)
                || ArenaReset.AIM_GATED_MANNEQUIN_NAME.equals(name);
    }

    /**
     * Matches an explicit curriculum target by scoreboard name, custom-name text, or the
     * survival mannequin tag. Armor stands often expose "Armor Stand" as
     * {@link LivingEntity#getScoreboardName()} even when CustomName NBT is set, which
     * previously left survival training stuck on {@code enemy[none]}.
     */
    private static boolean matchesTargetName(LivingEntity entity, String name) {
        if (name.equalsIgnoreCase(entity.getScoreboardName())) {
            return true;
        }
        if (entity.getCustomName() != null
                && name.equalsIgnoreCase(entity.getCustomName().getString())) {
            return true;
        }
        if (ArenaReset.SURVIVAL_MANNEQUIN_NAME.equalsIgnoreCase(name)
                && (entity.entityTags().contains(ArenaReset.SURVIVAL_MANNEQUIN_TAG)
                    || (entity instanceof ArmorStand stand
                        && stand.getCustomName() != null
                        && ArenaReset.SURVIVAL_MANNEQUIN_NAME.equals(
                                stand.getCustomName().getString())))) {
            return true;
        }
        return false;
    }

    private boolean hasLineOfSight(LivingEntity self, LivingEntity target, ClientLevel level) {
        Vec3 from = self.getEyePosition();
        Vec3 to = target.getEyePosition();
        HitResult hit = level.clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, self));
        return hit.getType() == HitResult.Type.MISS;
    }

    // --- nearby -----------------------------------------------------------

    private List<EntityBrief> collectNearby(LivingEntity self, ClientLevel level, Config cfg,
                                            LivingEntity target) {
        List<EntityBrief> out = new ArrayList<>();
        if (cfg.maxNearbyEntities() <= 0 || cfg.nearbyRadius() <= 0) {
            return out;
        }
        double radiusSqr = cfg.nearbyRadius() * cfg.nearbyRadius();
        List<net.minecraft.world.entity.Entity> candidates = level.getEntities(
                self,
                self.getBoundingBox().inflate(cfg.nearbyRadius()),
                e -> e != self && e != target && e.isAlive());
        candidates.sort((a, b) -> Double.compare(self.distanceToSqr(a), self.distanceToSqr(b)));
        for (net.minecraft.world.entity.Entity e : candidates) {
            if (self.distanceToSqr(e) > radiusSqr) {
                continue;
            }
            out.add(new EntityBrief(
                    EntityType.getKey(e.getType()).toString(),
                    self.distanceTo(e),
                    Vec3Dto.of(Vec3Dto.diffVec(e.position(), self.position()))));
            if (out.size() >= cfg.maxNearbyEntities()) {
                break;
            }
        }
        return out;
    }

    // --- helpers ----------------------------------------------------------

    private static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "minecraft:air";
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String effectId(MobEffectInstance e) {
        return e.getEffect().unwrapKey()
                .map(k -> k.identifier().toString())
                .orElse("unknown");
    }

    /** Normalizes an angle in degrees to the range {@code [-180, 180]}. */
    private static float wrapDegrees(float deg) {
        float d = deg % 360.0f;
        if (d >= 180.0f) {
            d -= 360.0f;
        }
        if (d < -180.0f) {
            d += 360.0f;
        }
        return d;
    }
}
