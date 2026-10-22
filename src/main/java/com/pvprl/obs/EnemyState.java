package com.pvprl.obs;

/**
 * Observation of the current target (nearest or explicitly selected enemy player),
 * expressed relative to the controlled player where it matters for control.
 *
 * <p>When no target is present, {@code present} is {@code false} and the remaining
 * fields hold neutral defaults so the wire shape stays constant for the agent.</p>
 *
 * @param present     whether a target exists
 * @param name        target display name, or empty
 * @param id          stable target UUID, or empty
 * @param relPos      target position minus self position
 * @param relVel      target velocity minus self velocity
 * @param distance    straight-line distance in blocks
 * @param yawDiff     signed yaw difference (how far self must turn horizontally), degrees
 * @param pitchDiff   signed pitch difference, degrees
 * @param health      target health
 * @param armor       target armor points
 * @param heldItem    registry id of the target's held main-hand item
 * @param sprinting   whether the target is sprinting
 * @param blocking    whether the target is actively using an item
 * @param lineOfSight whether an unobstructed eye-to-eye ray exists
 */
public record EnemyState(
        boolean present,
        String name,
        String id,
        Vec3Dto relPos,
        Vec3Dto relVel,
        double distance,
        float yawDiff,
        float pitchDiff,
        float health,
        int armor,
        String heldItem,
        boolean sprinting,
        boolean blocking,
        boolean lineOfSight
) {
    /** @return a neutral "no target" state. */
    public static EnemyState absent() {
        Vec3Dto zero = new Vec3Dto(0, 0, 0);
        return new EnemyState(false, "", "", zero, zero, 0, 0, 0, 0, 0, "", false, false, false);
    }
}
