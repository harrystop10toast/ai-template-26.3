# pvp-rl-env

A Fabric mod for **Minecraft 26.3** (Java 25) that turns the game into a
reinforcement-learning environment for PvP sword combat. External agents connect
over a WebSocket and exchange structured JSON observations + rewards for discrete
actions. See [DESIGN.md](DESIGN.md) for architecture and the full protocol.

## Versions

Minecraft `26.3` · Fabric Loader `0.19.5` · Fabric API `0.163.0+26.3` ·
Loom `net.fabricmc.fabric-loom 1.18-SNAPSHOT` (non-remapping) · Java `25` ·
official Mojang mappings. Java-WebSocket `1.6.0` is bundled into the jar via `include`.

## Build

```bash
./gradlew build
```

The output mod jar is `build/libs/pvp-rl-env-1.0.0.jar`.

## Run in-game

```bash
./gradlew runClient
```

Then in a world (singleplayer or a server where you have command perms):

```
/pvprl start              # open ws://127.0.0.1:8765 and begin ticking
/pvprl start noarena      # keep the current player/world/mobs intact across Gym episode resets
/pvprl summon             # spawn sparring target in front of you
/pvprl opponent           # compatibility alias: train through your own survival player
/pvprl mob <mobType>      # set sparring mob (e.g. /pvprl mob minecraft:vindicator)
/pvprl target auto        # or: /pvprl target <playerName>
/pvprl debug              # toggle a per-step observation/action/reward-ledger readout in chat
/pvprl reset              # teleport to arena, heal, clear effects, give kit
/pvprl reload             # hot-reload config/pvp-rl-env.json
/pvprl status             # print current state
/pvprl stop
```

`/pvprl summon` spawns the configured sparring entity a few blocks ahead of you;
`/pvprl target auto` then locks onto the nearest living entity.

### Train through your survival player

The policy always controls your actual local survival player with normal game inputs. No
stand-in entity is needed for PPO training. `/pvprl opponent` remains only as a compatibility
alias that selects this same local-player mode and does not change any entity or world state.

```
/pvprl opponent
/pvprl start noarena
./venv/bin/python3 examples/train_ppo.py --steps 200000 \
  --model pvprl_aim_ppo.zip --tb-name pvp_combat
```

> **Note:** `ws://127.0.0.1:8765` is a raw WebSocket endpoint, not a web page.
> Opening it in a browser will fail ("site can't be reached"). Connect with the
> Python client (below) or any WebSocket client instead. The mod only drives the
> player while an agent is connected and sending actions — with nothing connected
> it leaves your keyboard alone and does nothing on its own.

## Connect an agent

### 1. Python Environment Setup

Using Python 3.13 (recommended, as PyTorch and Stable-Baselines3 provide pre-built wheels):

```bash
# If venv is not already set up:
python3.13 -m venv venv

# Activate the virtual environment
source venv/bin/activate

# Install dependencies
pip install -r requirements.txt
```

### 2. Run Example Scripted Client

```bash
python examples/example_client.py ws://127.0.0.1:8765
```

### 3. Train or Play with PPO Agent

```bash
# Train normalized PPO v2 (saves pvprl_ppo_v2.zip plus matching normalization state)
python examples/train_ppo.py

# Or run inference using the trained policy in-game
python examples/train_ppo.py --play
```

The original `pvprl_ppo.zip` uses unnormalized observations and is intentionally
left untouched. The new default model name starts a separate v2 run; later
invocations resume it automatically. Use `--fresh` only when you deliberately
want to replace the current v2 policy, and keep the `.vecnormalize.pkl` file
beside every v2 model.

### Aiming curriculum (recommended before combat)

The checked-in development config can use a durable, stationary target. Reload it
in-game, reset the arena, then train an aim-only policy:

```bash
/pvprl reload
/pvprl reset
./venv/bin/python3 examples/train_ppo.py --aim-only --fresh --steps 200000 \
  --model pvprl_aim_ppo.zip --tb-name pvp_aim
```

This starts each episode at a random yaw/pitch offset, disables damaging actions,
and rewards corrective turns and a centred crosshair. It retains the normal
33-action policy shape. After it consistently holds the target in the centre,
switch `targetEntity` back to `minecraft:husk` and `targetResistance` to `false`,
run `/pvprl reload` and `/pvprl reset`, then fine-tune the same model without
`--aim-only`:

```bash
./venv/bin/python3 examples/train_ppo.py --steps 200000 \
  --model pvprl_aim_ppo.zip --tb-name pvp_combat
```

### Live TensorBoard dashboard

`requirements.txt` includes TensorBoard. Install the project dependencies, then run the
dashboard in a second terminal while training:

```bash
./venv/bin/python3 -m pip install -r requirements.txt
./venv/bin/python3 -m tensorboard.main --logdir pvprl_tb --port 6006
```

Open <http://localhost:6006/>. The **Scalars** page shows `rollout/ep_rew_mean`,
`rollout/ep_len_mean`, policy entropy, KL divergence, losses, and explained variance as
they are written. Training logs to `pvprl_tb` by default; choose another directory or a
distinct run label with `--tb-log <dir>` and `--tb-name <name>`.

## Configuration

`config/pvp-rl-env.json` is created on first launch and is hot-reloadable via
`/pvprl reload`. It controls host/port, observation cadence (`obsEveryNTicks`),
nearby-entity radius/cap, look step degrees, the arena/kit, and reward weights.

## Verify

- `./gradlew build` — compiles all sources against the real 26.3 jars (EXIT 0).
- The reward computation and observation JSON schema are covered by a
  deterministic check during development; the wire format is exactly the shape
  documented in DESIGN.md.
- End-to-end: `runClient`, `/pvprl start`, run the example client, and watch the
  observation stream + reward respond to combat.

### Movement curriculum

For a non-combat traversal policy, use movement-only mode. It immediately creates
one invulnerable, explicitly targeted mannequin at a random location 12–100 blocks
away, without summoning the configured combat target (for example, a villager),
then replaces the mannequin every 30 seconds. The observation exposes its `relPos`,
`distance`, `yawDiff`, and `pitchDiff`, so the existing aim policy can supply the
directional behavior. Combat and defence requests are mapped to `NOOP`; navigation,
look, sprint, and `JUMP_SPRINT` remain available. The curriculum rewards horizontal
speed, sprinting/sprint-jumping, closing distance, and proximity:

During movement-only training, once sprint is latched, forward remains held through
look and `NOOP` actions. This prevents one-tick policy choices from interrupting a
sprint attempt; the agent can still steer and choose when to jump.
The same input behavior is enabled for standard PvP sessions started with
`/pvprl start`, so combat training can also maintain a sprint across look/`NOOP`
actions until the policy explicitly chooses `SPRINT_OFF`.

```bash
./venv/bin/python3 examples/train_ppo.py --movement-only --fresh --steps 200000 \
  --model pvprl_movement_ppo.zip --tb-name pvp_movement
```

To retain directional behavior from a compatible aim policy, initialize the
movement model from it. This copies the shared action head and the complete
trained network, while only newer action rows start fresh:

```bash
./venv/bin/python3 examples/train_ppo.py --movement-only --fresh --init-from pvprl_aim_ppo.zip \
  --steps 200000 --model pvprl_movement_ppo.zip --tb-name pvp_movement
```

## Status / scope

Client-side control (singleplayer integrated server or permitted multiplayer).
Multi-resolution discrete action space (33 actions). Fine look actions preserve aiming
precision while fast look actions turn at a configurable multiple of the normal step. The `Action` type is a sealed interface so a
continuous mouse-delta variant can be added later; the network layer is keyed by
connection id as the groundwork for self-play.

## License

CC0-1.0.
