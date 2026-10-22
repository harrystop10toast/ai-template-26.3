#!/usr/bin/env python3
"""
Gymnasium wrapper around the pvp-rl-env Fabric mod.

Bridges the mod's WebSocket protocol (see DESIGN.md) to the standard
Gymnasium step()/reset() contract so you can train with any off-the-shelf
RL library (Stable-Baselines3, CleanRL, RLlib, ...).

The mod drives the real game clock, so this env is *soft real-time*: step()
sends an action and blocks until the next observation arrives. Keep the agent's
inference fast enough to keep up with `obsEveryNTicks`.

Requires: pip install gymnasium websockets numpy
Start the mod first: /pvprl start  (then /pvprl target auto; survival
mode also re-runs target auto after every Gym reset)
"""
from __future__ import annotations

import json
from typing import Any

import numpy as np
import gymnasium as gym
from gymnasium import spaces
from websockets.sync.client import connect  # websockets >= 12

# Must match com.pvprl.action.DiscreteAction order (indices 0..34).
ACTIONS = [
    "NOOP", "FORWARD", "BACK", "STRAFE_LEFT", "STRAFE_RIGHT",
    "LOOK_LEFT", "LOOK_RIGHT", "LOOK_UP", "LOOK_DOWN", "JUMP",
    "ATTACK", "BLOCK_OFF", "BLOCK_ON", "SPRINT_ON", "SPRINT_OFF",
    "CROUCH_ON", "CROUCH_OFF", "FORWARD_ATTACK", "FORWARD_SPRINT",
    "JUMP_ATTACK", "STRAFE_LEFT_ATTACK", "STRAFE_RIGHT_ATTACK",
    "FORWARD_LOOK_LEFT", "FORWARD_LOOK_RIGHT",
    "LOOK_FINE_LEFT", "LOOK_FINE_RIGHT", "LOOK_FINE_UP", "LOOK_FINE_DOWN",
    "LOOK_FAST_LEFT", "LOOK_FAST_RIGHT", "LOOK_FAST_UP", "LOOK_FAST_DOWN",
    "JUMP_SPRINT",
    "SWAP_TO_FOOD", "EAT",
]

# Movement training keeps the full action shape, including look actions needed to
# navigate toward a distant mannequin. Combat/defence outputs become NOOP, so an
# aim checkpoint can be fine-tuned without changing its policy head.
# SPRINT_OFF is also disabled: walking is never useful in this curriculum.
MOVEMENT_SAFE_ACTIONS = ACTIONS.copy()
for _index in (10, 11, 12, 14, 17, 19, 20, 21):
    MOVEMENT_SAFE_ACTIONS[_index] = "NOOP"

# During the aiming curriculum, leave the policy output dimension unchanged but
# remove damaging actions.  This lets the learned network be resumed directly
# for combat training later, instead of requiring a fragile policy conversion.
_AIM_SAFE_ACTIONS = ACTIONS.copy()
_AIM_SAFE_ACTIONS[10] = "NOOP"                 # ATTACK
_AIM_SAFE_ACTIONS[17] = "FORWARD"              # FORWARD_ATTACK
_AIM_SAFE_ACTIONS[19] = "JUMP"                 # JUMP_ATTACK
_AIM_SAFE_ACTIONS[20] = "STRAFE_LEFT"          # STRAFE_LEFT_ATTACK
_AIM_SAFE_ACTIONS[21] = "STRAFE_RIGHT"         # STRAFE_RIGHT_ATTACK

# Survival curriculum: keep look/move/eat. Attack variants are remapped to their
# non-attack locomotion equivalents so a low-HP flee policy cannot swing at the
# armor stand (combat rewards for hitting it are also disabled in the mod).
# SWAP_TO_FOOD is aliased to EAT — combined "find golden apple and eat" path.
_SURVIVAL_SAFE_ACTIONS = ACTIONS.copy()
_SURVIVAL_SAFE_ACTIONS[10] = "NOOP"           # ATTACK
_SURVIVAL_SAFE_ACTIONS[17] = "FORWARD"        # FORWARD_ATTACK
_SURVIVAL_SAFE_ACTIONS[19] = "JUMP"           # JUMP_ATTACK
_SURVIVAL_SAFE_ACTIONS[20] = "STRAFE_LEFT"    # STRAFE_LEFT_ATTACK
_SURVIVAL_SAFE_ACTIONS[21] = "STRAFE_RIGHT"   # STRAFE_RIGHT_ATTACK
_SURVIVAL_SAFE_ACTIONS[33] = "EAT"            # SWAP_TO_FOOD → combined EAT

# Flee-aim curriculum: yaw-only actions. The agent cannot change pitch or use
# fine-look actions, so exploration cannot turn into sky/ground staring.
FLEE_AIM_ACTIONS = [
    "NOOP", "LOOK_LEFT", "LOOK_RIGHT", "LOOK_FAST_LEFT", "LOOK_FAST_RIGHT",
]

# Observation vector layout (fixed order -> stable feature indices).
OBS_DIM = 26
FLEE_AIM_OBS_DIM = OBS_DIM + 1


def _encode(obs: dict[str, Any], *, include_health_mode: bool = False) -> np.ndarray:
    s = obs["self"]
    e = obs["enemy"]
    v = [
        s["pos"]["x"], s["pos"]["y"], s["pos"]["z"],
        s["vel"]["x"], s["vel"]["y"], s["vel"]["z"],
        s["yaw"], s["pitch"],
        s["health"], s["absorption"], float(s["food"]), float(s["armor"]),
        1.0 if s["onGround"] else 0.0,
        1.0 if s["sprinting"] else 0.0,
        1.0 if s["blocking"] else 0.0,
        s.get("attackStrength", 1.0),   # sword cooldown charge 0..1 (lets agent time swings)
        s.get("shieldCooldown", 0.0),   # shield disabled cooldown 0..1 (e.g. after axe hit)
        # enemy block (zeros when absent)
        1.0 if e["present"] else 0.0,
        e["relPos"]["x"], e["relPos"]["y"], e["relPos"]["z"],
        e["distance"], e["yawDiff"], e["pitchDiff"],
        e["health"],
        1.0 if e["lineOfSight"] else 0.0,
    ]
    if include_health_mode:
        # Preserve the legacy observation shape for existing non-flee checkpoints.
        # Flee-aim gets an explicit threshold signal in addition to numeric health.
        v.append(1.0 if s["health"] < s.get("maxHealth", 20.0) * 0.5 else 0.0)
    return np.asarray(v, dtype=np.float32)


class PvpRlEnv(gym.Env):
    """Single-agent Gymnasium env backed by the Fabric mod over WebSocket."""

    metadata = {"render_modes": []}

    def __init__(self, url: str = "ws://127.0.0.1:8765",
                 max_steps: int = 2000, timeout: float = 10.0,
                 randomize_aim_on_reset: bool = False,
                 aim_safe_actions: bool = False,
                 movement_only: bool = False,
                 survival_only: bool = False,
                 flee_aim_only: bool = False):
        super().__init__()
        self.url = url
        self.max_steps = max_steps
        self.timeout = timeout
        self.randomize_aim_on_reset = randomize_aim_on_reset
        self.aim_safe_actions = aim_safe_actions
        self.movement_only = movement_only
        self.survival_only = survival_only
        self.flee_aim_only = flee_aim_only
        self.actions = FLEE_AIM_ACTIONS if flee_aim_only else ACTIONS
        self.action_space = spaces.Discrete(len(self.actions))
        self.last_action_name = "NOOP"
        self.observation_space = spaces.Box(
            low=-np.inf, high=np.inf,
            shape=(FLEE_AIM_OBS_DIM if flee_aim_only else OBS_DIM,),
            dtype=np.float32)
        self._ws = None
        self._seq = 0
        self._steps = 0

    # --- socket helpers ---------------------------------------------------

    def _connect(self):
        if self._ws is None:
            self._ws = connect(self.url, open_timeout=self.timeout)

    def _set_movement_training(self, enabled: bool):
        """Tell the mod to manage and explicitly target the passive mannequin."""
        self._ws.send(json.dumps({"type": "movementTraining", "enabled": enabled}))

    def _set_survival_training(self, enabled: bool):
        """Tell the mod to run the survival flee-and-eat curriculum."""
        self._ws.send(json.dumps({"type": "survivalTraining", "enabled": enabled}))

    def _set_aim_gated_training(self, enabled: bool):
        """Tell the mod to run the health-gated look-at/look-away curriculum."""
        self._ws.send(json.dumps({"type": "aimGatedTraining", "enabled": enabled}))

    def _set_target_auto(self):
        """Equivalent to `/pvprl target auto` — lock onto the nearest living entity."""
        self._ws.send(json.dumps({"type": "setTarget", "player": "auto"}))

    def _next_observation(self) -> dict[str, Any]:
        """Reads frames until an observation arrives; returns its `data`."""
        while True:
            raw = self._ws.recv(timeout=self.timeout)
            msg = json.loads(raw)
            if msg.get("type") == "observation":
                return msg["data"]
            # events/acks/errors are informational between observations

    def _send_action(self, action_name: str) -> dict[str, Any]:
        self._seq += 1
        self._ws.send(json.dumps({
            "type": "action", "id": self._seq,
            "action": action_name, "ticks": 1,
        }))
        return self._next_observation()

    def _randomize_aim(self, obs: dict[str, Any]) -> dict[str, Any]:
        """Start aim episodes with the target at a random view angle.

        The arena summons targets in front of the player, which otherwise makes
        NOOP appear optimal and gives the look actions no useful learning signal.
        Flee-aim episodes randomize yaw only and first level pitch using fine
        corrective actions that are not available to the learned policy.
        """
        yaw_steps = int(self.np_random.integers(-18, 19))
        action = "LOOK_RIGHT" if yaw_steps > 0 else "LOOK_LEFT"
        for _ in range(abs(yaw_steps)):
            obs = self._send_action(action)
        if self.flee_aim_only:
            for _ in range(180):
                pitch = float(obs["self"]["pitch"])
                if abs(pitch) <= 0.25:
                    break
                obs = self._send_action("LOOK_UP" if pitch > 0.0 else "LOOK_DOWN")
        else:
            pitch_span = 8
            pitch_steps = int(self.np_random.integers(-(pitch_span - 1), pitch_span))
            action = "LOOK_DOWN" if pitch_steps > 0 else "LOOK_UP"
            for _ in range(abs(pitch_steps)):
                obs = self._send_action(action)
        return obs

    # --- gym API ----------------------------------------------------------

    def reset(self, *, seed: int | None = None, options: dict | None = None):
        super().reset(seed=seed)
        self._connect()
        if self.movement_only:
            self._set_movement_training(True)
        if self.survival_only:
            self._set_survival_training(True)
        if self.flee_aim_only:
            self._set_aim_gated_training(True)
        self._ws.send(json.dumps({"type": "reset"}))
        self._steps = 0
        obs = self._next_observation()
        # After every episode reset, re-lock the flee reference (armor stand) the same
        # way `/pvprl target auto` does. The mod also does this on reset-complete; this
        # covers the case where the first observation arrived before the stand synced.
        # /pvprl target auto after every Gym reset so a freshly spawned mannequin locks.
        if self.survival_only or self.flee_aim_only or self.movement_only:
            self._set_target_auto()
            obs = self._send_action("NOOP")
            # One more auto after the settle NOOP — entity sync can lag one tick.
            self._set_target_auto()
            obs = self._send_action("NOOP")
        if self.randomize_aim_on_reset:
            obs = self._randomize_aim(obs)
            if self.flee_aim_only:
                self._set_target_auto()
                obs = self._send_action("NOOP")
        return _encode(obs, include_health_mode=self.flee_aim_only), {"raw": obs}

    def step(self, action: int):
        if self.flee_aim_only:
            # Yaw-only Discrete(5) — no pitch or fine-look actions are exposed.
            action_name = self.actions[int(action)]
        else:
            action_set = (MOVEMENT_SAFE_ACTIONS if self.movement_only
                          else _SURVIVAL_SAFE_ACTIONS if self.survival_only
                          else _AIM_SAFE_ACTIONS if self.aim_safe_actions else self.actions)
            action_name = action_set[int(action)]
        self.last_action_name = action_name
        obs = self._send_action(action_name)
        self._steps += 1

        reward = float(obs["reward"]["step"])
        event = obs["reward"].get("event")
        terminated = event in ("DEATH", "KILL", "EAT_SUCCESS")
        truncated = (self._steps >= self.max_steps) or (event in ("TIMEOUT", "RESET"))
        info = {"event": event, "cumulative": obs["reward"]["cumulative"], "raw": obs}
        return (_encode(obs, include_health_mode=self.flee_aim_only),
                reward, terminated, truncated, info)

    def close(self):
        if self._ws is not None:
            try:
                if self.movement_only:
                    self._set_movement_training(False)
                if self.survival_only:
                    self._set_survival_training(False)
                if self.flee_aim_only:
                    self._set_aim_gated_training(False)
                self._ws.close()
            finally:
                self._ws = None


class AimCurriculum(gym.Wrapper):
    """Dense, bounded objective for learning to centre a stationary target.

    This wrapper is intentionally used before Monitor/VecNormalize. TensorBoard
    therefore reports the curriculum return while PPO still receives normalized
    rewards. The target must be a non-hostile mannequin configured by the run
    config; see train_ppo.py's --aim-only help text.
    """

    def __init__(self, env: gym.Env, max_steps: int = 300):
        super().__init__(env)
        self.max_steps = max_steps
        self._steps = 0
        self._previous_error: float | None = None

    @staticmethod
    def _error(raw: dict[str, Any]) -> float | None:
        enemy = raw["enemy"]
        if not enemy["present"]:
            return None
        # The worse axis determines whether a look action improved the aim,
        # matching the Java reward tracker and avoiding a yaw-only exploit.
        return max(abs(float(enemy["yawDiff"])), abs(float(enemy["pitchDiff"])))

    def reset(self, **kwargs):
        obs, info = self.env.reset(**kwargs)
        self._steps = 0
        self._previous_error = self._error(info["raw"])
        return obs, info

    def step(self, action):
        obs, _, terminated, truncated, info = self.env.step(action)
        self._steps += 1
        error = self._error(info["raw"])
        if error is None:
            # A missing target is an environment setup fault, not an aiming success.
            reward = -2.0
            truncated = True
        else:
            progress = 0.0 if self._previous_error is None else np.clip(
                (self._previous_error - error) / 8.0, -1.0, 1.0)
            alignment = 1.0 - min(error, 180.0) / 180.0
            locked = error < 6.0 and float(info["raw"]["enemy"]["distance"]) < 4.0
            # Correct look actions receive immediate positive feedback; keeping
            # the target centred is also rewarded, but less strongly.
            reward = float(0.50 * progress + 0.10 * alignment + (1.0 if locked else 0.0) - 0.01)
            self._previous_error = error

        truncated = truncated or self._steps >= self.max_steps
        info["aim_error_degrees"] = error
        return obs, reward, terminated, truncated, info


class MovementCurriculum(gym.Wrapper):
    """Reward sprint-jumping progress toward the explicitly targeted mannequin."""

    def __init__(self, env: PvpRlEnv):
        super().__init__(env)
        self._previous_distance: float | None = None
        self._target_id: str | None = None
        self._arrived = False
        self._stalled_steps = 0

    def reset(self, **kwargs):
        obs, info = self.env.reset(**kwargs)
        enemy = info["raw"]["enemy"]
        self._previous_distance = float(enemy["distance"]) if enemy["present"] else None
        self._target_id = enemy["id"] if enemy["present"] else None
        self._arrived = False
        self._stalled_steps = 0
        return obs, info

    def step(self, action):
        obs, _, terminated, truncated, info = self.env.step(action)
        raw = info["raw"]
        velocity = raw["self"]["vel"]
        speed = float(np.hypot(float(velocity["x"]), float(velocity["z"])))
        enemy = raw["enemy"]
        if not enemy["present"]:
            # The server will provide the freshly spawned mannequin on its next tick.
            reward = -0.05
            self._previous_distance = None
        else:
            distance = float(enemy["distance"])
            if enemy["id"] != self._target_id:
                # A timed or death-triggered replacement starts a fresh approach.
                self._target_id = enemy["id"]
                self._previous_distance = distance
                self._arrived = False
                self._stalled_steps = 0
            progress = 0.0 if self._previous_distance is None else float(np.clip(
                self._previous_distance - distance, -1.0, 1.0))
            proximity = 1.0 / (1.0 + distance)
            sprinting = bool(raw["self"]["sprinting"])
            sprint_bonus = 0.04 if sprinting else 0.0
            jump_sprint_bonus = (0.08 if self.env.last_action_name == "JUMP_SPRINT"
                                 and sprinting and speed > 0.15 else 0.0)
            # Walking is unused in PvP. Punish horizontal motion without sprint when
            # Minecraft would allow sprint (food > 6, not sneaking/blocking).
            can_sprint = (int(raw["self"]["food"]) > 6
                          and not bool(raw["self"].get("sneaking", False))
                          and not bool(raw["self"]["blocking"]))
            walk_penalty = (-0.08 if can_sprint and not sprinting and speed > 0.08
                            else 0.0)
            if distance > 2.0 and progress <= 0.01:
                self._stalled_steps += 1
            else:
                self._stalled_steps = 0
            # Speed alone permits running in circles. Distance progress, a larger
            # near-target signal, and an increasing stall penalty make movement
            # directional using the mannequin relPos/yawDiff/pitchDiff observation.
            not_arrived_penalty = -0.05 * min(1.0, distance / 100.0)
            stalled_penalty = -0.01 * min(10, self._stalled_steps) if distance > 2.0 else 0.0
            arrival_bonus = 0.0
            if distance <= 2.0 and not self._arrived:
                arrival_bonus = 5.0
                self._arrived = True
            reward = (speed * 2.0 + progress * 2.0 + proximity * 1.5
                      + sprint_bonus + jump_sprint_bonus + walk_penalty
                      + not_arrived_penalty + stalled_penalty + arrival_bonus
                      - 0.002)
            self._previous_distance = distance
        info["movement_speed"] = speed
        info["mannequin_distance"] = (float("nan") if not enemy["present"]
                                       else float(enemy["distance"]))
        return obs, reward, terminated, truncated, info


class SurvivalCurriculum(gym.Wrapper):
    """Health-gated survival: flee+eat when low HP, fight when healthy.

    Below 5 hearts (10 HP):
      - Reward opening distance and sprint / JUMP_SPRINT while fleeing.
      - Reward facing opposite the target (|yawDiff| → 180°).
      - Punish looking at the target OR lingering near it; both fade linearly
        with distance and are fully off at ≥ 10 blocks.
      - Punish EAT inside 10 blocks; heavy reward for finishing the apple at ≥ 10.
      - Mod refreshes the episode every 60 seconds.

    At or above 5 hearts:
      - Pass through the mod's normal combat step reward (engage as before).

    Monitor info keys are always written.
    """

    SAFE_DISTANCE = 10.0          # punishments fully off; eat rewarded from here
    LOW_HEALTH_THRESHOLD = 10.0   # 5 hearts = 10 health points

    def __init__(self, env: PvpRlEnv, max_steps: int = 1200):
        # 1200 steps ≈ 60s at 20 tps; matches the mod's 1-minute survival refresh.
        super().__init__(env)
        self.max_steps = max_steps
        self._steps = 0
        self._start_distance: float | None = None
        self._max_distance: float = 0.0
        self._reached_safe_distance: bool = False
        self._previous_distance: float | None = None
        self._previous_yaw_diff: float | None = None

    @classmethod
    def _near_factor(cls, distance: float) -> float:
        """1 at distance 0 → 0 at SAFE_DISTANCE (and beyond)."""
        if distance >= cls.SAFE_DISTANCE:
            return 0.0
        return float(np.clip(1.0 - distance / cls.SAFE_DISTANCE, 0.0, 1.0))

    def _write_info(self, info: dict, *, health: float, speed: float,
                    eat_success: bool = False) -> None:
        """Always populate Monitor info_keywords before returning from step()."""
        info["survival_flee_distance"] = self._max_distance
        info["survival_below_threshold"] = health < self.LOW_HEALTH_THRESHOLD
        info["survival_milestone_reached"] = self._reached_safe_distance
        info["survival_speed"] = speed
        if eat_success:
            info["survival_eat_success"] = True

    def reset(self, **kwargs):
        obs, info = self.env.reset(**kwargs)
        self._steps = 0
        enemy = info["raw"]["enemy"]
        self._start_distance = float(enemy["distance"]) if enemy["present"] else None
        self._max_distance = self._start_distance or 0.0
        self._reached_safe_distance = False
        self._previous_distance = self._start_distance
        self._previous_yaw_diff = float(enemy["yawDiff"]) if enemy["present"] else None
        return obs, info

    def step(self, action):
        obs, env_reward, terminated, truncated, info = self.env.step(action)
        raw = info["raw"]
        self._steps += 1
        event = info.get("event")
        enemy = raw["enemy"]
        self_state = raw["self"]
        health = float(self_state["health"])
        speed = float(np.hypot(float(self_state["vel"]["x"]), float(self_state["vel"]["z"])))
        sprinting = bool(self_state["sprinting"])
        below_threshold = health < self.LOW_HEALTH_THRESHOLD
        distance = float(enemy["distance"]) if enemy["present"] else float("nan")
        action_name = self.env.last_action_name
        eating = action_name in ("EAT", "SWAP_TO_FOOD", "EAT_FOOD", "EAT_GOLDEN_APPLE")

        if event == "EAT_SUCCESS":
            if not enemy["present"]:
                reward = 25.0
            elif distance < self.SAFE_DISTANCE:
                reward = -8.0   # early eat: still inside the 10-block danger radius
            else:
                reward = 80.0   # heavy reward: ate at a safe flee distance
            terminated = True
            self._write_info(info, health=health, speed=speed, eat_success=True)
            return obs, reward, terminated, truncated, info

        if not enemy["present"]:
            reward = -0.02
            self._previous_distance = None
            self._previous_yaw_diff = None
            truncated = truncated or self._steps >= self.max_steps
            self._write_info(info, health=health, speed=speed)
            return obs, reward, terminated, truncated, info

        yaw_diff = float(enemy["yawDiff"])
        abs_yaw = abs(yaw_diff)  # 0 = facing target, 180 = directly opposite
        dist_progress = 0.0
        if self._previous_distance is not None:
            dist_progress = float(np.clip(distance - self._previous_distance, -1.0, 1.0))
        moving_away = dist_progress > 0.05
        facing_away = abs_yaw >= 90.0
        near = self._near_factor(distance)

        # ── Healthy: keep normal combat reward (engage as before) ────────
        if not below_threshold:
            reward = float(env_reward)
            if eating:
                reward -= 0.5
            self._max_distance = max(self._max_distance, distance)
            self._previous_distance = distance
            self._previous_yaw_diff = yaw_diff
            truncated = truncated or self._steps >= self.max_steps
            self._write_info(info, health=health, speed=speed)
            return obs, reward, terminated, truncated, info

        # ── Low HP: sprint away, don't stare/linger inside 10 blocks, then eat ─
        flee_reward = dist_progress * 3.0
        if distance >= self.SAFE_DISTANCE and not self._reached_safe_distance:
            flee_reward += 12.0
            self._reached_safe_distance = True
        self._max_distance = max(self._max_distance, distance)

        # Face opposite the threat.
        look_away_reward = (abs_yaw / 180.0) ** 2 * 0.20
        if abs_yaw >= 160.0:
            look_away_reward += 0.08
        look_away_progress = 0.0
        if self._previous_yaw_diff is not None:
            look_away_progress = float(np.clip(
                abs_yaw - abs(self._previous_yaw_diff), -2.0, 2.0))
        look_away_progress_reward = look_away_progress * 0.35

        # Looking at / staying near the target: both fade to 0 at SAFE_DISTANCE.
        # look_at_amount is 1 when yawDiff≈0, 0 when facing fully away.
        look_at_amount = max(0.0, 1.0 - abs_yaw / 180.0)
        look_punish = -0.35 * near * look_at_amount
        stay_punish = -0.25 * near

        # Sprint-flee shaping (mirrors MovementCurriculum bonuses, flee direction).
        sprint_bonus = 0.0
        if moving_away and facing_away:
            sprint_bonus += speed * 2.0
            if sprinting:
                sprint_bonus += 0.04
            if (action_name == "JUMP_SPRINT" and sprinting and speed > 0.15):
                sprint_bonus += 0.08
        if sprinting and speed > 0.15 and dist_progress < -0.05:
            sprint_bonus -= 0.12  # sprinting toward the threat

        can_sprint = (int(self_state["food"]) > 6
                      and not bool(self_state.get("sneaking", False))
                      and not bool(self_state.get("blocking", False)))
        walk_penalty = (-0.08 if can_sprint and not sprinting and speed > 0.08
                        and moving_away else 0.0)

        # Combined EAT: punish inside 10 blocks; strong shaping once safe.
        eat_reward = 0.0
        if eating:
            eat_reward = -1.5 if distance < self.SAFE_DISTANCE else 2.0

        attack_punish = -1.5 if "ATTACK" in action_name else 0.0
        step_penalty = -0.003

        # Never pass through combat env_reward here — aim/on-target/chase would
        # teach the agent to attack the armor stand while low HP.
        reward = (flee_reward + look_away_reward + look_away_progress_reward
                  + look_punish + stay_punish + sprint_bonus + walk_penalty
                  + eat_reward + attack_punish + step_penalty)

        self._previous_distance = distance
        self._previous_yaw_diff = yaw_diff

        truncated = truncated or self._steps >= self.max_steps
        self._write_info(info, health=health, speed=speed)
        return obs, reward, terminated, truncated, info


class FleeAimCurriculum(gym.Wrapper):
    """Health-gated look-only curriculum for basic flee-aim / sight training.

    When HP ≥ 50% of maxHealth: reward aiming at the mannequin (alignment +
    turn progress + locked bonus), punish looking away.

    When HP < 50%: reward facing away from the mannequin (yaw anti-alignment +
    turn-away progress + locked-away bonus), punish looking at it.

    The policy has yaw-only actions; pitch is leveled at reset and strongly
    penalized if it drifts away from the horizon.
    No movement / combat — look actions only. Each two-minute mod episode starts
    at 20 HP and applies 11 damage after one minute, so the agent learns both
    health-gated behaviors in every episode.
    """

    LOW_HEALTH_FRACTION = 0.5  # HP < 50% of maxHealth → face away
    PITCH_FREE_DEGREES = 2.0
    PITCH_PENALTY_SCALE = 3.0

    def __init__(self, env: PvpRlEnv, max_steps: int = 2400):
        # 2400 steps ≈ 2 minutes; the mod applies midpoint damage after 1200 ticks.
        super().__init__(env)
        self.max_steps = max_steps
        self._steps = 0
        self._previous_error: float | None = None
        self._previous_yaw_error: float | None = None

    @staticmethod
    def _error(raw: dict[str, Any]) -> float | None:
        enemy = raw["enemy"]
        if not enemy["present"]:
            return None
        return max(abs(float(enemy["yawDiff"])), abs(float(enemy["pitchDiff"])))

    @staticmethod
    def _yaw_error(raw: dict[str, Any]) -> float | None:
        """Yaw-only error — used for the flee (HP < 50%) reward to prevent the
        degenerate exploit of staring at the floor to farm flee reward."""
        enemy = raw["enemy"]
        if not enemy["present"]:
            return None
        return abs(float(enemy["yawDiff"]))

    @classmethod
    def _pitch_penalty(cls, abs_pitch: float) -> float:
        """0 within ±2° of level; linearly penalize pitch deviation up to 40°."""
        excess = max(0.0, abs_pitch - cls.PITCH_FREE_DEGREES)
        return -cls.PITCH_PENALTY_SCALE * min(1.0, excess / (40.0 - cls.PITCH_FREE_DEGREES))

    def reset(self, **kwargs):
        obs, info = self.env.reset(**kwargs)
        self._steps = 0
        self._previous_error = self._error(info["raw"])
        self._previous_yaw_error = self._yaw_error(info["raw"])
        return obs, info

    def step(self, action):
        obs, _, terminated, truncated, info = self.env.step(action)
        raw = info["raw"]
        self._steps += 1
        self_state = raw["self"]
        health = float(self_state["health"])
        max_health = float(self_state.get("maxHealth", 20.0))
        if max_health <= 0.0:
            max_health = 20.0
        healthy = health >= max_health * self.LOW_HEALTH_FRACTION
        error = self._error(raw)
        yaw_error = self._yaw_error(raw)
        abs_pitch = abs(float(self_state["pitch"]))
        pitch_pen = self._pitch_penalty(abs_pitch)

        if error is None:
            reward = -0.5 + pitch_pen
            truncated = truncated or self._steps >= self.max_steps
            info["flee_aim_error_degrees"] = None
            info["flee_aim_healthy"] = healthy
            return obs, reward, terminated, truncated, info

        alignment = 1.0 - min(error, 180.0) / 180.0
        # Yaw-only alignment for the flee case: looking up/down must NOT count
        # as "facing away" — the agent must turn its back horizontally.
        yaw_alignment = 1.0 - min(yaw_error, 180.0) / 180.0
        anti_alignment = 1.0 - yaw_alignment

        if healthy:
            # ── HP ≥ 50%: MUST aim AT the mannequin ─────────────────────────
            # Use yaw directly so pitch error cannot obscure whether the agent is
            # facing toward the target. Strongly penalize looking away at full HP.
            aim_progress = 0.0 if self._previous_yaw_error is None else np.clip(
                (self._previous_yaw_error - yaw_error) / 8.0, -1.0, 1.0)
            locked = 2.5 if yaw_error < 10.0 and abs(float(raw["enemy"]["pitchDiff"])) < 20.0 else 0.0
            away_penalty = -2.5 * max(0.0, (yaw_error - 20.0) / 160.0)
            noop_pen = -0.15 if yaw_error > 20.0 and self.env.last_action_name == "NOOP" else 0.0
            reward = float(0.80 * aim_progress + 1.25 * yaw_alignment + locked
                           + away_penalty + noop_pen + pitch_pen)
        else:
            # ── HP < 50%: MUST face AWAY (yaw-only) + keep pitch near eye-level
            away_progress = 0.0 if self._previous_yaw_error is None else np.clip(
                (yaw_error - self._previous_yaw_error) / 8.0, -1.0, 1.0)
            locked_away = 1.5 if yaw_error > 150.0 else 0.0
            wrong_dir = -0.60 * yaw_alignment if yaw_error < 90.0 else 0.0
            noop_pen = -0.08 if self.env.last_action_name == "NOOP" else 0.0
            reward = float(0.60 * away_progress + 0.40 * anti_alignment
                           + locked_away + wrong_dir + noop_pen)
            if yaw_error > 150.0:
                reward = max(reward, 0.8)
            reward += pitch_pen

        self._previous_error = error
        self._previous_yaw_error = yaw_error
        truncated = truncated or self._steps >= self.max_steps
        info["flee_aim_error_degrees"] = error
        info["flee_aim_healthy"] = healthy
        return obs, reward, terminated, truncated, info


if __name__ == "__main__":
    # Smoke test with a random policy (mod must be running + /pvprl start).
    env = PvpRlEnv()
    obs, info = env.reset()
    print("obs dim:", obs.shape, "| actions:", env.action_space.n)
    total = 0.0
    for _ in range(200):
        a = env.action_space.sample()
        obs, r, term, trunc, info = env.step(a)
        total += r
        if term or trunc:
            print("episode end:", info["event"], "return=%.2f" % total)
            obs, info = env.reset()
            total = 0.0
    env.close()
