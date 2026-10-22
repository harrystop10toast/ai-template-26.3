# pvp-rl-env — Design Document

A Fabric mod for **Minecraft 26.3** (Java 25) that exposes the game as a
reinforcement-learning environment for PvP sword combat. External training
agents connect over a WebSocket, receive structured JSON observations + rewards,
and send back discrete actions that are applied to a controlled player.

## Target versions (verified)

| Component | Version |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.163.0+26.3 |
| Loom | net.fabricmc.fabric-loom 1.18-SNAPSHOT (non-remapping) |
| Java | 25 |
| Mappings | Official Mojang |

## Design principles

- **Client-side control first.** The agent controls the local client player via
  synthetic input, so it works in singleplayer (integrated server) and on
  vanilla-compatible servers. Reward/episode logic reads client-visible state.
- **Composition over inheritance.** Small focused classes behind interfaces.
- **Cheap observations.** No allocations-per-field hot paths; one record tree
  built per sampled tick, serialized with Gson.
- **Isolated Minecraft API surface.** All direct Minecraft state reads live in
  `obs/ObservationCollector` and all state writes in `action/ActionExecutor`.
  If a Mojang getter name differs in 26.3, the fix is localized.
- **Future-proof seams.** `Action` is an interface (discrete today, continuous
  mouse-deltas later); `Policy` seam left for in-mod ONNX/DJL inference;
  network layer keyed by connection id for self-play.

## File / package structure

```
src/main/java/com/pvprl/
  PvpRlEnv.java                 Main ModInitializer: wires config, net, controller
  PvpRlEnvClient.java           ClientModInitializer: tick hook, input bridge, overlay
  config/
    Config.java                 Record: ports, tickRate, obsEveryNTicks, radii, kit...
    ConfigManager.java          Load/save/hot-reload JSON (config/pvp-rl-env.json)
  obs/
    Observation.java            Top-level record (tick, self, enemy, nearby, reward)
    SelfState.java              Record: pos/vel/look/health/food/armor/held/effects...
    EnemyState.java             Record: relative pos/vel, distance, yaw/pitch diff...
    EntityBrief.java            Record: nearby entity summary
    ObservationCollector.java   Reads MC state -> builds Observation (ISOLATED reads)
    Vec3Dto / RotationDto       Small numeric records for JSON
  action/
    Action.java                 Sealed interface (future continuous support)
    DiscreteAction.java         Enum of ~20 discrete actions implementing Action
    ActionExecutor.java         Applies an Action to the client player (ISOLATED writes)
    InputState.java             Mutable held-input snapshot bridged into KeyBindings
  reward/
    RewardTracker.java          Damage dealt/taken, kill/death, time-alive, shaped
    EpisodeEvent.java           Enum: DEATH, KILL, TIMEOUT, RESET
  net/
    RlServer.java               WebSocket server (Java-WebSocket), lifecycle
    RlConnection.java           Per-client session: inbound action queue
    Protocol.java               JSON message envelope + types (Gson)
  util/
    PvpRlCommands.java          /pvprl start [noarena]|stop|reset|target <player>
    ArenaReset.java             Safe reset: teleport, heal, clear effects, give kit
    DebugOverlay.java           HUD text summary of current observation
```

## Data flow (one controlled tick)

```
ClientTickEvent.END
  └─ EnvController.tick()
       ├─ drain inbound actions from each RlConnection -> ActionExecutor (main thread)
       ├─ RewardTracker.update()  (reads health deltas etc.)
       └─ if (tick % obsEveryNTicks == 0)
            ├─ ObservationCollector.collect() -> Observation (incl. reward snapshot)
            └─ RlServer.broadcast(Protocol.observation(obs))
```

Actions are received asynchronously on the WebSocket thread, queued, and only
**executed on the client main thread** inside the tick callback. State is never
mutated off-thread.

## Network protocol (JSON over WebSocket)

Default endpoint: `ws://127.0.0.1:8765` (configurable host/port).

### Mod → Client (agent)

```jsonc
// observation (every N ticks)
{ "type": "observation", "connId": "c3", "data": { /* Observation record */ } }

// episode event
{ "type": "event", "event": "DEATH" }   // KILL | TIMEOUT | RESET

// ack / error
{ "type": "ack", "ref": 42 }
{ "type": "error", "message": "unknown action: FOO" }
```

### Client (agent) → Mod

```jsonc
// discrete action
{ "type": "action", "id": 42, "action": "FORWARD_ATTACK", "ticks": 1 }

// control
{ "type": "reset" }                      // trigger ArenaReset
{ "type": "setTarget", "player": "Steve" }
```

`id` is an optional client sequence number echoed back in `ack`.
`ticks` (optional, default 1) holds the input for N ticks before releasing.

### Observation JSON shape

```jsonc
{
  "tick": 12840,
  "self": {
    "pos": {"x":0,"y":64,"z":0}, "vel": {"x":0,"y":0,"z":0},
    "yaw": 0.0, "pitch": 0.0,
    "health": 20.0, "maxHealth": 20.0, "absorption": 0.0,
    "food": 20, "saturation": 5.0,
    "armor": 0, "armorToughness": 0.0,
    "selectedSlot": 0, "heldItem": "minecraft:iron_sword",
    "onGround": true, "sprinting": false, "sneaking": false, "blocking": false,
    "effects": [{"id":"minecraft:speed","amplifier":0,"duration":200}]
  },
  "enemy": {
    "present": true, "name": "Steve", "id": "entity-uuid",
    "relPos": {"x":3,"y":0,"z":1}, "relVel": {"x":0,"y":0,"z":0},
    "distance": 3.16, "yawDiff": 12.5, "pitchDiff": -4.0,
    "health": 20.0, "armor": 5, "heldItem": "minecraft:iron_sword",
    "sprinting": false, "blocking": false, "lineOfSight": true
  },
  "nearby": [ {"type":"minecraft:zombie","distance":8.2,"relPos":{"x":5,"y":0,"z":6}} ],
  "reward": { "step": 0.01, "cumulative": 1.37,
              "damageDealt": 0, "damageTaken": 0, "event": null }
}
```

## Discrete action space (33)

```
0  NOOP                 8  LOOK_DOWN            16 CROUCH_OFF
1  FORWARD              9  JUMP                 17 FORWARD_ATTACK
2  BACK                 10 ATTACK               18 FORWARD_SPRINT
3  STRAFE_LEFT          11 BLOCK_OFF (USE)      19 JUMP_ATTACK
4  STRAFE_RIGHT         12 BLOCK_ON (BLOCK)     20 STRAFE_LEFT_ATTACK
5  LOOK_LEFT            13 SPRINT_ON            21 STRAFE_RIGHT_ATTACK
6  LOOK_RIGHT           14 SPRINT_OFF           22 FORWARD_LOOK_LEFT
7  LOOK_UP              15 CROUCH_ON            23 FORWARD_LOOK_RIGHT
24 LOOK_FINE_LEFT        28 LOOK_FAST_LEFT
25 LOOK_FINE_RIGHT       29 LOOK_FAST_RIGHT
26 LOOK_FINE_UP          30 LOOK_FAST_UP
27 LOOK_FINE_DOWN        31 LOOK_FAST_DOWN
32 JUMP_SPRINT
```

Normal look actions rotate by `config.lookStepDegrees`; fine actions rotate by
`config.fineLookStepDegrees`; and fast actions rotate by
`config.lookStepDegrees * config.fastLookMultiplier`. Movement/combat actions drive vanilla
keys directly. No hardcoded aimbot/lock or reach assists are used — the agent must learn
aiming, swing timing, and shield posture autonomously through rewards and penalties.
The `Action` type is a **sealed interface** so a `ContinuousAction(dx, dy, moveX, moveZ, buttons)`
variant can be added later without touching the executor contract.

## Rewards & Penalties

`RewardTracker` maintains per-episode accumulators and produces a per-step
`RewardSnapshot`, designed with calibrated incentives and anti-exploit mechanics:
- `damageDealt` (+7.0 per damage) / `damageTaken` (−4.0 per damage): Dealing damage is heavily rewarded, providing strong positive reinforcement for landing hits.
- `killReward` (+120.0) / `deathPenalty` (-60.0): Balanced terminal payoff; winning is strongly rewarded while losing is penalizing without creating a runaway negative value basin.
- `stepPenalty` (-0.005 / tick): Creates gentle urgency, eliminating the "staring contest" exploit.
- **Aim Tracking**:
  - `aimWeight` (0.04, full-range alignment guidance plus bounded immediate feedback when a look action reduces/increases angular error)
  - `notFacingPenalty` (0.03, penalty after 45° that continues to ramp up through the rear hemisphere)
  - `onTargetReward` (0.05, bonus when crosshair is directly on target)
  - `strikePositionReward` (0.02, bonus when positioned and aimed in melee range)
- **Potential-Based Distance Shaping (PBRS)**:
  - Guided approach closing bonus when outside melee reach (> 3.0 blocks), with retreat
    penalty suppressed for an attack that causes knockback. Knockback itself has no reward,
    so it cannot be farmed by repeatedly pushing a durable target away.
- **Combat Timing & Precision**:
  - `chargedHitBonus` (2.5 $\times$ damage, scales with weapon cooldown bar 0..1)
  - `earlySwingPenalty` (0.04, gentle penalty for premature spam-clicks)
  - `missPenalty` (0.02, gentle penalty for air-swings not aimed at target)
- **Shield & Posture Management**:
  - `shieldBlockReward` (5.0 base for deflecting hits, diminishing by $0.7^k$, 20-tick cooldown)
  - `shieldHoldingCost` (0.005 / tick active holding cost, making permanent turtling undesirable)
  - `shieldMisusePenalty` (0.01 / tick extra penalty if turtling > 4 blocks away)
  - Attack actions automatically drop shield to strike (`Shield.OFF`), preventing self-blocking lockups.
- **Mobility**:
  - Knockback bonus (+2.0 $\times$ distance), and `fleePenalty` (0.05) for disengaging > 6 blocks with back turned.

## Config (`config/pvp-rl-env.json`, hot-reloadable)

```jsonc
{
  "enabled": true,
  "host": "127.0.0.1", "port": 8765,
  "obsEveryNTicks": 1,
  "nearbyRadius": 16.0, "maxNearbyEntities": 8,
  "lookStepDegrees": 8.0,
  "fineLookStepDegrees": 0.5,
  "fastLookMultiplier": 2.0,
  "arena": { "x": 0, "y": -60, "z": 0, "healOnReset": true, "clearEffects": true,
             "kit": ["minecraft:wooden_sword[enchantments={knockback:1}]", "minecraft:shield"],
             "targetEntity": "minecraft:vindicator", "targetSpawnDistance": 1.5 },
  "reward": {
    "damageWeight": 4.0, "damageDealtBonus": 1.0,
    "killReward": 150.0, "deathPenalty": -350.0, "stepPenalty": 0.005,
    "shapedAim": true, "shapedDistance": true,
    "aimWeight": 0.02, "notFacingPenalty": 0.15, "onTargetReward": 0.03, "strikePositionReward": 0.005,
    "chargedHitBonus": 3.0, "earlySwingPenalty": 0.15, "missPenalty": 0.08,
    "shieldBlockReward": 2.0, "shieldHoldingCost": 0.015, "shieldMisusePenalty": 0.03,
    "shieldAttackPenalty": 0.35, "shieldCooldownPenalty": 0.15, "fleePenalty": 0.5
  }
}
```

## Build & verify

`./gradlew build` after each milestone. The compiler validates every Minecraft /
Fabric symbol against the real 26.3 jars, so any mis-remembered Mojang getter
surfaces immediately and is fixed in the isolated collector/executor.
