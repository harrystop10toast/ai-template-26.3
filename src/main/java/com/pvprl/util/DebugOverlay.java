package com.pvprl.util;

import com.pvprl.obs.Observation;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Map;

/**
 * Lightweight debug readout of the current observation.
 *
 * <p>Rather than depend on version-sensitive HUD rendering internals, the overlay prints a
 * compact one-line summary to the client chat for every emitted observation via
 * {@code LocalPlayer.sendSystemMessage}. This is robust across game versions and good enough
 * for sanity-checking what the agent sees.</p>
 */
public final class DebugOverlay {

    private boolean enabled;
    /** Debug is intentionally unthrottled: each agent step needs its own reward ledger. */
    private int everyNTicks = 1;
    private int counter;

    /** Enables or disables the readout. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** @return whether the readout is enabled. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Prints one detailed summary for every emitted observation if enabled.
     *
     * @param mc  the Minecraft client
     * @param obs the most recent observation, or {@code null} if none yet
     */
    public void maybePrint(Minecraft mc, Observation obs, String action,
                           Map<String, Double> rewardComponents) {
        maybePrint(mc, obs, action, rewardComponents, null);
    }

    /**
     * @param mode optional curriculum tag shown in chat (e.g. {@code FLEE} / {@code FIGHT})
     */
    public void maybePrint(Minecraft mc, Observation obs, String action,
                           Map<String, Double> rewardComponents, String mode) {
        if (!enabled || obs == null || mc.player == null) {
            return;
        }
        if (++counter < everyNTicks) {
            return;
        }
        counter = 0;
        mc.player.sendSystemMessage(Component.literal(
                summarize(obs, action, rewardComponents, mode)));
    }

    private static String summarize(Observation o, String action,
                                    Map<String, Double> rewardComponents, String mode) {
        String enemy = o.enemy().present()
                ? String.format("enemy[d=%.1f yaw=%.0f pitch=%.0f hp=%.0f los=%b]",
                        o.enemy().distance(), o.enemy().yawDiff(),
                        o.enemy().pitchDiff(), o.enemy().health(), o.enemy().lineOfSight())
                : "enemy[none]";
        // Only print non-zero components so the flee ledger stays readable in chat.
        StringBuilder rewards = new StringBuilder();
        for (Map.Entry<String, Double> component : rewardComponents.entrySet()) {
            if (Math.abs(component.getValue()) < 0.00005) {
                continue;
            }
            if (rewards.length() > 0) {
                rewards.append(' ');
            }
            rewards.append(component.getKey())
                    .append('=')
                    .append(String.format("%+.3f", component.getValue()));
        }
        String modeTag = (mode == null || mode.isBlank()) ? "" : (" mode=" + mode);
        return String.format("[pvp-rl] t=%d hp=%.0f food=%d %s%s total=%+.3f cumul=%+.3f r{%s} act=%s",
                o.tick(), o.self().health(), o.self().food(), enemy, modeTag,
                o.reward().step(), o.reward().cumulative(), rewards, action);
    }
}
