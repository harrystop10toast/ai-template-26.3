package com.pvprl.obs;

import net.minecraft.world.phys.Vec3;

/**
 * Plain 3-component vector for JSON serialization, decoupled from Minecraft's
 * {@link Vec3} so the wire format stays stable across game versions.
 */
public record Vec3Dto(double x, double y, double z) {
    /** @return a DTO copied from a Minecraft vector. */
    public static Vec3Dto of(Vec3 v) {
        return new Vec3Dto(v.x, v.y, v.z);
    }

    /** @return a DTO for the component-wise difference {@code a - b}. */
    public static Vec3Dto diff(Vec3 a, Vec3 b) {
        return new Vec3Dto(a.x - b.x, a.y - b.y, a.z - b.z);
    }

    /** @return a Minecraft {@link Vec3} for the component-wise difference {@code a - b}. */
    public static Vec3 diffVec(Vec3 a, Vec3 b) {
        return a.subtract(b);
    }
}
