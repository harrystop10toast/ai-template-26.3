package com.pvprl.obs;

/**
 * Compact summary of a nearby entity (not the primary target).
 *
 * @param type     entity-type registry id (e.g. {@code minecraft:zombie})
 * @param distance distance in blocks from the controlled player
 * @param relPos   position relative to the controlled player
 */
public record EntityBrief(String type, double distance, Vec3Dto relPos) {}
