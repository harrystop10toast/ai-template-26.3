package com.pvprl.action;

import com.pvprl.config.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies {@link Action}s to the controlled client player.
 *
 * <p>This is the single place in the mod that writes player/input state. Rather than
 * reimplement combat, it drives the game's own {@link KeyMapping}s (attack/use/movement)
 * and sets look rotation directly, so vanilla tick logic performs the actual swings,
 * blocks and movement. That keeps behaviour correct across game updates and localizes any
 * future API change to this class.</p>
 *
 * <p>Must be called on the client main thread (end-of-tick). An action can be held for a
 * number of ticks; held movement keys are released automatically when the hold expires.</p>
 */
public final class ActionExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger("pvp-rl-env/action");

    private DiscreteAction active = DiscreteAction.NOOP;
    private int holdTicksRemaining = 0;
    private boolean sprintLatched = false;
    private boolean crouchLatched = false;
    private boolean shieldLatched = false;
    /**
     * JUMP_SPRINT / JUMP_ATTACK must already be sprinting on the ground before the jump,
     * or Minecraft treats it as a normal walk-jump. When the agent requests a sprint-jump
     * from standstill, we hold forward+sprint for a wind-up tick (or more) and only then
     * pulse jump once {@link LocalPlayer#isSprinting()} is true.
     */
    private boolean pendingSprintJump = false;
    /** Ticks spent waiting for sprint before giving up on a pending sprint-jump. */
    private int sprintJumpWindupTicks = 0;
    private static final int MAX_SPRINT_JUMP_WINDUP_TICKS = 10;
    /** True while we are actively driving inputs; when false we leave the keyboard alone. */
    private boolean driving = false;
    /** Charge level of the most recent swing (-1 = none since last consumed). */
    private float lastAttackStrength = -1.0f;
    /** Whether an attack was attempted while the player had their shield raised. */
    private boolean attackWhileBlocking = false;
    /** Whether shield was commanded while disabled on cooldown (e.g. from an axe hit). */
    private boolean shieldOnCooldownAttempt = false;
    /** Latched while the agent is actively eating (EAT action). Released when use completes. */
    private boolean eatingLatched = false;
    /** True once vanilla {@code isUsingItem} has been observed during the current eat latch. */
    private boolean eatUseStarted = false;
    /** True if the eating animation completed since the last consume call. */
    private boolean eatCompleted = false;
    /**
     * When true (survival flee curriculum at low HP), attack inputs are stripped so the
     * agent cannot farm combat rewards on the passive armor stand.
     */
    private boolean suppressAttacks = false;
    /** Movement curriculum holds forward continuously while its sprint latch is active. */
    private boolean holdForwardWhileSprinting;
    /**
     * Absolute pitch clamp in degrees applied after every look step. Minecraft allows
     * ±90°; sword PvP sight training clamps tighter so the agent cannot stare at the
     * sky or ground. {@code 90} restores the vanilla full range.
     */
    private float maxPitchDegrees = 90.0f;
    /** Default PvP / aim-gated pitch clamp (eye-level band with room to track a torso). */
    public static final float PVP_MAX_PITCH_DEGREES = 40.0f;

    /**
     * Requests an action, replacing any currently held one. This also takes control of the
     * input bindings until the hold expires (and any sprint/crouch latch clears).
     *
     * @param action    the action to apply
     * @param holdTicks number of ticks to hold movement inputs (clamped to >= 1)
     */
    public void request(DiscreteAction action, int holdTicks) {
        DiscreteAction resolved = action == null ? DiscreteAction.NOOP : action;
        if (suppressAttacks && resolved.attack) {
            // Keep locomotion / look from composite attack actions; drop the swing only.
            resolved = stripAttack(resolved);
        }
        this.active = resolved;
        this.holdTicksRemaining = Math.max(1, holdTicks);
        this.driving = true;
    }

    /** Enables/disables attack suppression for the survival flee curriculum. */
    public void setSuppressAttacks(boolean suppressAttacks) {
        this.suppressAttacks = suppressAttacks;
    }

    /** @return whether attack inputs are currently suppressed. */
    public boolean suppressAttacks() {
        return suppressAttacks;
    }

    /** Keeps forward input held during a sprint attempt in movement-only training. */
    public void setHoldForwardWhileSprinting(boolean enabled) {
        holdForwardWhileSprinting = enabled;
    }

    /**
     * Clamps look pitch to {@code ±maxPitchDegrees} (1..90). Use
     * {@link #PVP_MAX_PITCH_DEGREES} for sight/flee-aim training, or {@code 90} to
     * restore the full Minecraft range.
     */
    public void setMaxPitchDegrees(float maxPitchDegrees) {
        this.maxPitchDegrees = Math.max(1.0f, Math.min(90.0f, Math.abs(maxPitchDegrees)));
    }

    /** @return current absolute pitch clamp in degrees. */
    public float maxPitchDegrees() {
        return maxPitchDegrees;
    }

    /** Maps an attacking discrete action to its non-attack locomotion/look equivalent. */
    private static DiscreteAction stripAttack(DiscreteAction a) {
        return switch (a) {
            case ATTACK -> DiscreteAction.NOOP;
            case FORWARD_ATTACK -> DiscreteAction.FORWARD;
            case JUMP_ATTACK -> DiscreteAction.JUMP;
            case STRAFE_LEFT_ATTACK -> DiscreteAction.STRAFE_LEFT;
            case STRAFE_RIGHT_ATTACK -> DiscreteAction.STRAFE_RIGHT;
            default -> a.attack ? DiscreteAction.NOOP : a;
        };
    }

    /** @return the most recently requested action, for diagnostics only. */
    public DiscreteAction activeAction() {
        return active;
    }

    /**
     * Drives the currently active action for one tick. Call once per client tick.
     *
     * <p>Critically, when there is no active action and no sprint/crouch latch, this method
     * does nothing — it does <em>not</em> force the movement keys to {@code false}. That lets
     * a human keep playing normally when no agent is sending actions; the executor only
     * touches the keyboard while it is actually driving.</p>
     *
     * @param mc  the Minecraft client
     * @param cfg active configuration (look step size)
     */
    public void tick(Minecraft mc, Config cfg) {
        LocalPlayer player = mc.player;
        Options opt = mc.options;
        if (player == null || opt == null) {
            return;
        }

        boolean holdActive = holdTicksRemaining > 0;
        boolean latched = sprintLatched || crouchLatched || shieldLatched
                || pendingSprintJump || eatingLatched;

        // Idle: nothing to drive. Release once (to clear any lingering press) then stay out
        // of the way so the human player retains full control of the keyboard.
        if (!holdActive && !latched) {
            if (driving) {
                releaseAll(mc);
            }
            return;
        }

        DiscreteAction a = holdActive ? active : DiscreteAction.NOOP;

        // Start / continue the combined find-apple+eat latch before gating other inputs.
        boolean eatRequested = active == DiscreteAction.EAT || active == DiscreteAction.SWAP_TO_FOOD;
        if (eatRequested) {
            swapToFood(player);
            eatingLatched = true;
            shieldLatched = false;
            crouchLatched = false;
        }

        // While eating: only locomotion (move / jump / sprint) is applied. Look, attack,
        // shield, crouch, and re-triggered EAT are treated as NOOP so the use animation
        // is not cancelled — the agent can still flee while finishing the apple.
        boolean eating = eatingLatched;

        // Sprint latch (sticky until explicitly toggled off by agent).
        switch (a.sprint) {
            case ON -> sprintLatched = true;
            case OFF -> {
                sprintLatched = false;
                pendingSprintJump = false;
                sprintJumpWindupTicks = 0;
            }
            case NONE -> { /* keep latch */ }
        }
        // Queue a true sprint-jump: jump is deferred until sprint is already active.
        if (a.jump && a.sprint == DiscreteAction.Sprint.ON) {
            if (!pendingSprintJump) {
                sprintJumpWindupTicks = 0;
            }
            pendingSprintJump = true;
            sprintLatched = true;
        }

        // Forward actions always run. In movement training, a latched sprint also
        // keeps W held through look/noop actions so the agent cannot interrupt its run.
        boolean forceForward = holdForwardWhileSprinting && sprintLatched;
        boolean forward = a.forward || pendingSprintJump || forceForward;
        boolean back = a.back && !forceForward;
        boolean wasSprinting = player.isSprinting();
        boolean sprinting = sprintLatched || forward;
        opt.keySprint.setDown(sprinting);
        player.setSprinting(sprinting);

        // Jump only once already sprinting on the ground; otherwise this tick is wind-up.
        boolean jump = a.jump;
        if (pendingSprintJump) {
            if (player.onGround() && wasSprinting) {
                jump = true;
                pendingSprintJump = false;
                sprintJumpWindupTicks = 0;
            } else if (!player.onGround()) {
                jump = false;
                pendingSprintJump = false;
                sprintJumpWindupTicks = 0;
            } else if (++sprintJumpWindupTicks > MAX_SPRINT_JUMP_WINDUP_TICKS) {
                // Sprint never engaged (hunger, collision, etc.) — abandon rather than stall.
                jump = false;
                pendingSprintJump = false;
                sprintJumpWindupTicks = 0;
            } else {
                jump = false;
            }
        }

        // Movement keys stay available during eating.
        opt.keyUp.setDown(forward);
        opt.keyDown.setDown(back);
        opt.keyLeft.setDown(a.left);
        opt.keyRight.setDown(a.right);
        opt.keyJump.setDown(jump);

        if (!eating) {
            // Crouch latch (sticky until explicitly toggled off by agent).
            switch (a.crouch) {
                case ON -> crouchLatched = true;
                case OFF -> crouchLatched = false;
                case NONE -> { /* keep latch */ }
            }
        }
        opt.keyShift.setDown(crouchLatched);
        player.setShiftKeyDown(crouchLatched);

        if (!eating) {
            // Shield latch: controlled directly by the AI via BLOCK_ON / BLOCK_OFF actions.
            switch (a.shield) {
                case ON -> shieldLatched = true;
                case OFF -> shieldLatched = false;
                case NONE -> { /* keep latch */ }
            }
        } else {
            shieldLatched = false;
        }

        net.minecraft.world.item.ItemStack shieldItem = player.getOffhandItem().is(Items.SHIELD)
                ? player.getOffhandItem() : player.getMainHandItem();
        boolean shieldDisabled = player.getCooldowns().isOnCooldown(shieldItem);

        if (shieldLatched && shieldDisabled) {
            // Shield is disabled (e.g. from an axe hit)! Cannot raise shield.
            shieldOnCooldownAttempt = true;
            shieldLatched = false;
        }

        if (eating) {
            // Keep right-click held until vanilla finishes the eat animation.
            // Movement keys above stay live; look/attack/shield/crouch are NOOP'd.
            opt.keyUse.setDown(true);
            if (mc.gameMode != null && !player.isUsingItem()) {
                mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            }
            if (player.isUsingItem()) {
                eatUseStarted = true;
            } else if (eatUseStarted) {
                // Animation finished after a real use — survival success signal.
                eatCompleted = true;
                eatingLatched = false;
                eatUseStarted = false;
                opt.keyUse.setDown(false);
            } else if (!eatRequested) {
                // Never started (no apple / interrupted before use) — drop latch quietly.
                eatingLatched = false;
                opt.keyUse.setDown(false);
            }
        } else {
            opt.keyUse.setDown(shieldLatched && !shieldDisabled);
            if (shieldLatched && !shieldDisabled) {
                if (mc.gameMode != null && !player.isUsingItem()) {
                    InteractionHand hand = player.getOffhandItem().is(Items.SHIELD)
                            ? InteractionHand.OFF_HAND
                            : InteractionHand.MAIN_HAND;
                    mc.gameMode.useItem(player, hand);
                }
            } else if (player.isUsingItem() && mc.gameMode != null) {
                mc.gameMode.releaseUsingItem(player);
            }

            // Attack: when the AI commands an attack, execute vanilla melee swing.
            if (a.attack) {
                shieldLatched = false;
                opt.keyUse.setDown(false);
                if (player.isUsingItem() && mc.gameMode != null) {
                    mc.gameMode.releaseUsingItem(player);
                }
                performAttack(mc, player);
            }

            // Look rotation: completely driven by the agent's actions.
            if (a.lookYawSign != 0 || a.lookPitchSign != 0) {
                float step = switch (a.lookSpeed) {
                    case FINE -> (float) cfg.fineLookStepDegrees();
                    case FAST -> (float) (cfg.lookStepDegrees() * cfg.fastLookMultiplier());
                    case NORMAL -> (float) cfg.lookStepDegrees();
                };
                player.setYRot(player.getYRot() + a.lookYawSign * step);
                float newPitch = player.getXRot() + a.lookPitchSign * step;
                player.setXRot(Math.max(-maxPitchDegrees, Math.min(maxPitchDegrees, newPitch)));
            } else if (maxPitchDegrees < 90.0f) {
                // Snap back if something else (reset / teleport) left pitch out of band.
                float pitch = player.getXRot();
                if (pitch < -maxPitchDegrees || pitch > maxPitchDegrees) {
                    player.setXRot(Math.max(-maxPitchDegrees, Math.min(maxPitchDegrees, pitch)));
                }
            }
        }

        if (holdTicksRemaining > 0) {
            holdTicksRemaining--;
        }
    }

    /**
     * Drives the currently active action for one tick.
     * Deprecated overload retained for backwards compatibility; target is no longer used for auto-aim.
     *
     * @param mc     the Minecraft client
     * @param cfg    active configuration
     * @param target unused (auto-aim cursor lock removed)
     */
    public void tick(Minecraft mc, Config cfg, LivingEntity target) {
        tick(mc, cfg);
    }

    /**
     * Releases all driven inputs. Used on stop/reset so no key stays stuck down.
     *
     * @param mc the Minecraft client
     */
    public void releaseAll(Minecraft mc) {
        Options opt = mc.options;
        if (opt == null) {
            return;
        }
        opt.keyUp.setDown(false);
        opt.keyDown.setDown(false);
        opt.keyLeft.setDown(false);
        opt.keyRight.setDown(false);
        opt.keyJump.setDown(false);
        opt.keySprint.setDown(false);
        opt.keyShift.setDown(false);
        opt.keyUse.setDown(false);
        opt.keyAttack.setDown(false);
        if (mc.gameMode != null && mc.player != null && mc.player.isUsingItem()) {
            mc.gameMode.releaseUsingItem(mc.player);
        }
        active = DiscreteAction.NOOP;
        holdTicksRemaining = 0;
        sprintLatched = false;
        crouchLatched = false;
        shieldLatched = false;
        pendingSprintJump = false;
        sprintJumpWindupTicks = 0;
        attackWhileBlocking = false;
        shieldOnCooldownAttempt = false;
        eatingLatched = false;
        eatUseStarted = false;
        eatCompleted = false;
        driving = false;
    }

    /**
     * Performs a melee attack adhering strictly to vanilla Minecraft mechanics.
     *
     * <p>The swing is performed whenever commanded (no hardcoded weapon-cooldown gate),
     * allowing the agent to discover optimal cooldown timing through rewards/penalties.
     * Attacks only hit entities directly under the crosshair within reach (vanilla pick);
     * no artificial aimbot or cone-expansion algorithms are used.</p>
     */
    private void performAttack(Minecraft mc, LocalPlayer player) {
        if (mc.gameMode == null) {
            return;
        }
        float charge = player.getAttackStrengthScale(0.0f);
        lastAttackStrength = charge;
        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);

        Entity target = mc.crosshairPickEntity;
        if (target != null && isAttackable(target, player)) {
            mc.gameMode.attack(player, target);
        }
    }

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

    /**
     * Returns whether an attack was attempted while holding up a shield since the last call,
     * resetting the flag.
     *
     * @return {@code true} if an attack was attempted while blocking
     */
    public boolean consumeAttackWhileBlocking() {
        boolean val = attackWhileBlocking;
        attackWhileBlocking = false;
        return val;
    }

    /**
     * Returns whether shield blocking was attempted while the shield is on cooldown (disabled by axe),
     * resetting the flag.
     *
     * @return {@code true} if blocking was attempted while shield is disabled
     */
    public boolean consumeShieldOnCooldownAttempt() {
        boolean val = shieldOnCooldownAttempt;
        shieldOnCooldownAttempt = false;
        return val;
    }

    /**
     * Returns the attack-strength charge of the most recent swing performed since the last
     * call, or {@code -1} if no attack happened. Consuming resets the record so each swing is
     * counted once.
     *
     * @return charge in [0,1] of the last swing, or -1 if none since the previous call
     */
    public float consumeAttackStrength() {
        float s = lastAttackStrength;
        lastAttackStrength = -1.0f;
        return s;
    }

    /**
     * Selects the hotbar slot containing a golden apple (regular or enchanted), giving
     * preference to the currently selected slot if it already holds one. If no golden apple
     * is found in the hotbar, the slot is left unchanged.
     *
     * @param player the controlled local player
     */
    private void swapToFood(LocalPlayer player) {
        var inventory = player.getInventory();
        int currentSlot = inventory.getSelectedSlot();
        // Already holding food?
        if (isGoldenApple(inventory.getItem(currentSlot))) {
            return;
        }
        for (int i = 0; i < 9; i++) {
            if (isGoldenApple(inventory.getItem(i))) {
                inventory.setSelectedSlot(i);
                // Sync the hotbar change to the server so the item is actually held.
                player.connection.send(
                        new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(i));
                return;
            }
        }
        LOGGER.warn("[pvp-rl] EAT: no golden apple found in hotbar");
    }

    private static boolean isGoldenApple(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        return item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE;
    }

    /**
     * Returns whether the eating animation completed since the last call, resetting the flag.
     * Used by the reward/episode layer to detect a successful golden apple consumption.
     *
     * @return {@code true} if eating finished since the last consume
     */
    public boolean consumeEatCompleted() {
        boolean val = eatCompleted;
        eatCompleted = false;
        return val;
    }

    private static boolean isAttackable(Entity e, LocalPlayer self) {
        return e instanceof LivingEntity && e != self && e.isAlive();
    }

    /**
     * @param mc the Minecraft client
     * @return {@code true} if the crosshair is currently on an attackable living entity — the
     *         exact condition under which {@link #performAttack} would land a hit. Used by the
     *         reward layer to directly reward precise aiming.
     */
    public static boolean isCrosshairOnTarget(Minecraft mc) {
        return mc.player != null && isAttackable(mc.crosshairPickEntity, mc.player);
    }
}
