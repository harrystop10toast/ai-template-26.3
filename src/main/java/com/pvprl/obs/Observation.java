package com.pvprl.obs;

import com.pvprl.reward.RewardSnapshot;

import java.util.List;

/**
 * Top-level structured observation emitted every N ticks and serialized to JSON.
 *
 * @param tick   client tick counter at capture time
 * @param self   full state of the controlled player
 * @param enemy  state of the current target (see {@link EnemyState#present()})
 * @param nearby compact summaries of other nearby entities (may be empty)
 * @param reward reward/episode snapshot for this step
 */
public record Observation(
        long tick,
        SelfState self,
        EnemyState enemy,
        List<EntityBrief> nearby,
        RewardSnapshot reward
) {}
