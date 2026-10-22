package com.pvprl.action;

/**
 * The discrete action space for sword PvP.
 *
 * <p>Each constant carries a small set of intent flags. {@link ActionExecutor} maps these
 * flags onto the game's own key mappings and look rotation, so no combat logic is
 * reimplemented — the same virtual keys the player would press are driven instead.</p>
 *
 * <p>Movement flags are held for the action's duration; {@code attack} is edge-triggered
 * (one press). Look actions come in normal, fine, and fast resolutions so an
 * agent can turn quickly without losing its ability to make small corrections.
 * Sprint, crouch, and shield states use tri-state latches (NONE, ON, OFF) allowing the agent
 * full autonomous control over posture without hardcoded overrides.</p>
 */
public enum DiscreteAction implements Action {
    NOOP                (0, 0, 0, 0, 0, false, Sprint.NONE, Crouch.NONE,  Shield.NONE),
    FORWARD             (1, 0, 0, 0, 0, false, Sprint.NONE, Crouch.NONE,  Shield.NONE),
    BACK                (0, 1, 0, 0, 0, false, Sprint.NONE, Crouch.NONE,  Shield.NONE),
    STRAFE_LEFT         (0, 0, 1, 0, 0, false, Sprint.NONE, Crouch.NONE,  Shield.NONE),
    STRAFE_RIGHT        (0, 0, 0, 1, 0, false, Sprint.NONE, Crouch.NONE,  Shield.NONE),
    LOOK_LEFT           (0, 0, 0, 0, 0, false, Sprint.NONE, Crouch.NONE,  Shield.NONE, -1,  0),
    LOOK_RIGHT          (0, 0, 0, 0, 0, false, Sprint.NONE, Crouch.NONE,  Shield.NONE, +1,  0),
    LOOK_UP             (0, 0, 0, 0, 0, false, Sprint.NONE, Crouch.NONE,  Shield.NONE,  0, -1),
    LOOK_DOWN           (0, 0, 0, 0, 0, false, Sprint.NONE, Crouch.NONE,  Shield.NONE,  0, +1),
    JUMP                (0, 0, 0, 0, 1, false, Sprint.NONE, Crouch.NONE,  Shield.NONE),
    ATTACK              (0, 0, 0, 0, 0, true,  Sprint.NONE, Crouch.NONE,  Shield.OFF),
    BLOCK_OFF           (0, 0, 0, 0, 0, false, Sprint.NONE, Crouch.NONE,  Shield.OFF),
    BLOCK_ON            (0, 0, 0, 0, 0, false, Sprint.NONE, Crouch.NONE,  Shield.ON),
    SPRINT_ON           (1, 0, 0, 0, 0, false, Sprint.ON,   Crouch.NONE,  Shield.NONE),
    SPRINT_OFF          (0, 0, 0, 0, 0, false, Sprint.OFF,  Crouch.NONE,  Shield.NONE),
    CROUCH_ON           (0, 0, 0, 0, 0, false, Sprint.NONE, Crouch.ON,   Shield.NONE),
    CROUCH_OFF          (0, 0, 0, 0, 0, false, Sprint.NONE, Crouch.OFF,  Shield.NONE),
    FORWARD_ATTACK      (1, 0, 0, 0, 0, true,  Sprint.ON,   Crouch.NONE,  Shield.OFF),
    FORWARD_SPRINT      (1, 0, 0, 0, 0, false, Sprint.ON,   Crouch.NONE,  Shield.NONE),
    JUMP_ATTACK         (1, 0, 0, 0, 1, true,  Sprint.ON,   Crouch.NONE,  Shield.OFF),
    STRAFE_LEFT_ATTACK  (0, 0, 1, 0, 0, true,  Sprint.NONE, Crouch.NONE,  Shield.OFF),
    STRAFE_RIGHT_ATTACK (0, 0, 0, 1, 0, true,  Sprint.NONE, Crouch.NONE,  Shield.OFF),
    FORWARD_LOOK_LEFT   (1, 0, 0, 0, 0, false, Sprint.NONE, Crouch.NONE,  Shield.NONE, -1,  0),
    FORWARD_LOOK_RIGHT  (1, 0, 0, 0, 0, false, Sprint.NONE, Crouch.NONE,  Shield.NONE, +1,  0),
    // Appended so the original 24 ordinal action indices remain stable.
    LOOK_FINE_LEFT      (0, 0, 0, 0, 0, false, Sprint.NONE, Crouch.NONE,  Shield.NONE, -1,  0, LookSpeed.FINE),
    LOOK_FINE_RIGHT     (0, 0, 0, 0, 0, false, Sprint.NONE, Crouch.NONE,  Shield.NONE, +1,  0, LookSpeed.FINE),
    LOOK_FINE_UP        (0, 0, 0, 0, 0, false, Sprint.NONE, Crouch.NONE,  Shield.NONE,  0, -1, LookSpeed.FINE),
    LOOK_FINE_DOWN      (0, 0, 0, 0, 0, false, Sprint.NONE, Crouch.NONE,  Shield.NONE,  0, +1, LookSpeed.FINE),
    LOOK_FAST_LEFT      (0, 0, 0, 0, 0, false, Sprint.NONE, Crouch.NONE,  Shield.NONE, -1,  0, LookSpeed.FAST),
    LOOK_FAST_RIGHT     (0, 0, 0, 0, 0, false, Sprint.NONE, Crouch.NONE,  Shield.NONE, +1,  0, LookSpeed.FAST),
    LOOK_FAST_UP        (0, 0, 0, 0, 0, false, Sprint.NONE, Crouch.NONE,  Shield.NONE,  0, -1, LookSpeed.FAST),
    LOOK_FAST_DOWN      (0, 0, 0, 0, 0, false, Sprint.NONE, Crouch.NONE,  Shield.NONE,  0, +1, LookSpeed.FAST),
    // Dedicated non-combat movement primitive for the movement curriculum.
    JUMP_SPRINT         (1, 0, 0, 0, 1, false, Sprint.ON,   Crouch.NONE,  Shield.NONE),
    // Survival: select the hotbar golden apple (kept for action-index stability;
    // ActionExecutor treats this the same as EAT — swap + consume in one step).
    SWAP_TO_FOOD        (0, 0, 0, 0, 0, false, Sprint.NONE, Crouch.NONE,  Shield.NONE),
    // Survival: find golden apple in hotbar and eat it (single combined action).
    EAT                 (0, 0, 0, 0, 0, false, Sprint.NONE, Crouch.NONE,  Shield.NONE);

    /** Tri-state toggle for sprint: leave unchanged, force on, or force off. */
    public enum Sprint { NONE, ON, OFF }

    /** Tri-state toggle for crouch/sneak. */
    public enum Crouch { NONE, ON, OFF }

    /** Tri-state toggle for shield blocking. */
    public enum Shield { NONE, ON, OFF }

    /** Selects the angular resolution for an action that changes yaw and/or pitch. */
    public enum LookSpeed { NORMAL, FINE, FAST }

    public final boolean forward, back, left, right, jump;
    public final boolean attack;
    public final Sprint sprint;
    public final Crouch crouch;
    public final Shield shield;
    /** Direction signs for look rotation (yaw +right, pitch +down). */
    public final int lookYawSign, lookPitchSign;
    /** Angular resolution used by this look action. */
    public final LookSpeed lookSpeed;

    DiscreteAction(int fwd, int back, int left, int right, int jump,
                   boolean attack, Sprint sprint, Crouch crouch, Shield shield) {
        this(fwd, back, left, right, jump, attack, sprint, crouch, shield, 0, 0);
    }

    DiscreteAction(int fwd, int back, int left, int right, int jump,
                   boolean attack, Sprint sprint, Crouch crouch, Shield shield,
                   int lookYawSign, int lookPitchSign) {
        this(fwd, back, left, right, jump, attack, sprint, crouch, shield,
                lookYawSign, lookPitchSign, LookSpeed.NORMAL);
    }

    DiscreteAction(int fwd, int back, int left, int right, int jump,
                   boolean attack, Sprint sprint, Crouch crouch, Shield shield,
                   int lookYawSign, int lookPitchSign, LookSpeed lookSpeed) {
        this.forward = fwd != 0;
        this.back = back != 0;
        this.left = left != 0;
        this.right = right != 0;
        this.jump = jump != 0;
        this.attack = attack;
        this.sprint = sprint;
        this.crouch = crouch;
        this.shield = shield;
        this.lookYawSign = lookYawSign;
        this.lookPitchSign = lookPitchSign;
        this.lookSpeed = lookSpeed;
    }

    /**
     * Resolves an action by case-insensitive name or by ordinal index.
     * Backwards-compatible aliases are supported (e.g. USE -> BLOCK_OFF, BLOCK -> BLOCK_ON).
     *
     * @param token enum name (e.g. {@code "FORWARD_ATTACK"}) or integer index
     * @return the matching action, or {@code null} if none
     */
    public static DiscreteAction resolve(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String t = token.trim();
        for (DiscreteAction a : values()) {
            if (a.name().equalsIgnoreCase(t)) {
                return a;
            }
        }
        // Aliases for shield controls
        if (t.equalsIgnoreCase("USE") || t.equalsIgnoreCase("UNBLOCK")) {
            return BLOCK_OFF;
        }
        if (t.equalsIgnoreCase("BLOCK")) {
            return BLOCK_ON;
        }
        // Combined survival eat: find golden apple + consume.
        if (t.equalsIgnoreCase("EAT_FOOD") || t.equalsIgnoreCase("EAT_GOLDEN_APPLE")) {
            return EAT;
        }
        try {
            int idx = Integer.parseInt(t);
            if (idx >= 0 && idx < values().length) {
                return values()[idx];
            }
        } catch (NumberFormatException ignored) {
            // not an index
        }
        return null;
    }
}
