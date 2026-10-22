package com.pvprl.obs;

import java.util.List;

/**
 * Full observation of the controlled player.
 *
 * @param pos           world position
 * @param vel           delta movement (velocity) this tick
 * @param yaw           head yaw in degrees
 * @param pitch         head pitch in degrees
 * @param health        current health
 * @param maxHealth     maximum health
 * @param absorption    absorption (yellow hearts)
 * @param food          food level (0-20)
 * @param saturation    saturation level
 * @param armor         armor points (0-20)
 * @param armorToughness armor toughness attribute value
 * @param selectedSlot  selected hotbar slot (0-8)
 * @param heldItem      registry id of the held main-hand item
 * @param onGround      whether standing on ground
 * @param sprinting     whether sprinting
 * @param sneaking      whether sneaking/crouching
 * @param blocking      whether actively using an item (shield block / eating / drawing)
 * @param attackStrength attack-strength charge 0.0..1.0 (1.0 = fully charged "strong" hit)
 * @param shieldCooldown cooldown remaining on shield 0.0..1.0 (e.g. after axe disable)
 * @param effects       active status effects
 */
public record SelfState(
        Vec3Dto pos,
        Vec3Dto vel,
        float yaw,
        float pitch,
        float health,
        float maxHealth,
        float absorption,
        int food,
        float saturation,
        int armor,
        double armorToughness,
        int selectedSlot,
        String heldItem,
        boolean onGround,
        boolean sprinting,
        boolean sneaking,
        boolean blocking,
        float attackStrength,
        float shieldCooldown,
        List<EffectDto> effects
) {}
