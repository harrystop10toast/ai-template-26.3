package com.pvprl.obs;

/**
 * Serializable summary of one active status effect.
 *
 * @param id        registry id of the effect (e.g. {@code minecraft:speed})
 * @param amplifier effect amplifier (0 = level I)
 * @param duration  remaining duration in ticks
 */
public record EffectDto(String id, int amplifier, int duration) {}
