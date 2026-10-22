package com.pvprl.reward;

/**
 * Immutable per-step reward summary embedded in each observation.
 *
 * <p>Introduced with the observation system so the wire format already carries a
 * reward block; the full {@link RewardTracker} (later milestone) populates it with
 * real values. Until then {@link #zero()} supplies neutral values.</p>
 *
 * @param step         reward attributed to this step
 * @param cumulative   episode-cumulative reward
 * @param damageDealt  damage dealt to the target this step
 * @param damageTaken  damage taken this step
 * @param event        terminal/episode event name, or {@code null}
 */
public record RewardSnapshot(
        double step,
        double cumulative,
        float damageDealt,
        float damageTaken,
        String event
) {
    /** @return a neutral reward snapshot. */
    public static RewardSnapshot zero() {
        return new RewardSnapshot(0.0, 0.0, 0.0f, 0.0f, null);
    }
}
