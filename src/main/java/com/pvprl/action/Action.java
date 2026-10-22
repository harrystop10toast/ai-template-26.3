package com.pvprl.action;

/**
 * An action the agent can request. Sealed so the executor can exhaustively handle
 * every variant, and so a continuous-control variant can be added later without
 * changing the discrete contract.
 *
 * <p>Current implementations:</p>
 * <ul>
 *   <li>{@link DiscreteAction} — a fixed discrete action space (movement/combat/look).</li>
 * </ul>
 *
 * <p>Planned future variant (documented here as the extension seam): a
 * {@code ContinuousAction(double moveX, double moveZ, double yawDelta, double pitchDelta,
 * boolean attack, boolean use)} record for raw mouse/stick control. Adding it only
 * requires permitting it below and handling it in {@link ActionExecutor}.</p>
 */
public sealed interface Action permits DiscreteAction {

    /**
     * @return a stable, human-readable identifier for logging and the wire protocol
     */
    String name();
}
