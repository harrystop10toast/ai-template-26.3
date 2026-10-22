package com.pvprl.reward;

/**
 * Terminal or notable episode events emitted alongside rewards.
 */
public enum EpisodeEvent {
    /** The controlled player died. */
    DEATH,
    /** The current target died (attributed as a kill). */
    KILL,
    /** The episode exceeded its time budget. */
    TIMEOUT,
    /** The environment was reset. */
    RESET,
    /** The agent successfully ate a golden apple (survival curriculum terminal). */
    EAT_SUCCESS
}
