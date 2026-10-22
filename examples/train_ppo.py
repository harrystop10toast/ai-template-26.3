#!/usr/bin/env python3
"""
Train a PPO agent against pvp-rl-env with Stable-Baselines3.

Prereqs:
  pip install stable-baselines3 gymnasium websockets numpy torch
  Launch the mod (./gradlew runClient), enter a world, then:
      /pvprl start
      /pvprl target auto   # also re-run automatically after every Gym reset

Then:
  python examples/train_ppo.py            # train
  python examples/train_ppo.py --survival --steps 200000
  python examples/train_ppo.py --play     # run the trained model

Notes
-----
- The env is real-time (one game tick per step), so throughput is bounded by the
  game clock, not CPU. Expect modest steps/sec from a single client. For serious
  training you would run multiple clients / self-play (the mod keys connections by
  id as groundwork for that) or record transitions for offline RL.
- PPO uses a 26-dim observation for standard training and 27 dimensions for
  flee-aim's explicit health-mode flag. Tune as needed.
"""
import argparse
import importlib.util
from pathlib import Path
import sys

try:
    from stable_baselines3 import PPO
    from stable_baselines3.common.monitor import Monitor
    from stable_baselines3.common.vec_env import DummyVecEnv, VecNormalize
except ModuleNotFoundError as e:
    sys.exit(
        f"\n[pvp-rl-env] Error: Missing dependency ({e}).\n"
        "Please activate the virtual environment or run with the venv python:\n\n"
        "    source venv/bin/activate\n"
        "    python3 examples/train_ppo.py\n\n"
        "Or directly:\n"
        "    ./venv/bin/python3 examples/train_ppo.py\n"
    )

from pvprl_gym_env import (AimCurriculum, MovementCurriculum, SurvivalCurriculum,
                            FleeAimCurriculum, PvpRlEnv)


def make_env(url: str, aim_only: bool, movement_only: bool,
             survival_only: bool = False,
             flee_aim_only: bool = False) -> Monitor:
    # Monitor is deliberately inside VecNormalize: its episode statistics retain
    # the raw game reward, so TensorBoard shows actual PvP return rather than the
    # normalized reward used to stabilize PPO updates.
    env = PvpRlEnv(
        url=url,
        max_steps=2400 if flee_aim_only else 2000,
        randomize_aim_on_reset=aim_only or flee_aim_only,
        aim_safe_actions=aim_only,
        movement_only=movement_only,
        survival_only=survival_only,
        flee_aim_only=flee_aim_only,
    )
    if aim_only:
        env = AimCurriculum(env, max_steps=300)
    elif movement_only:
        env = MovementCurriculum(env)
    elif survival_only:
        env = SurvivalCurriculum(env, max_steps=1200)
    elif flee_aim_only:
        # 2400 steps ≈ 2 minutes at 20 tps; the mod applies damage after 1 minute.
        env = FleeAimCurriculum(env, max_steps=2400)
    # Makes the final angular error visible as rollout/ep_aim_error_degrees_mean
    # in TensorBoard for the aim curriculum.
    if aim_only:
        info_keys = ("aim_error_degrees",)
    elif movement_only:
        info_keys = ("movement_speed", "mannequin_distance")
    elif survival_only:
        info_keys = ("survival_flee_distance", "survival_below_threshold",
                      "survival_milestone_reached", "survival_speed")
    elif flee_aim_only:
        info_keys = ("flee_aim_error_degrees", "flee_aim_healthy")
    else:
        info_keys = ()
    return Monitor(env, info_keywords=info_keys)


def make_vec_env(url: str, aim_only: bool, movement_only: bool,
                 survival_only: bool = False,
                 flee_aim_only: bool = False) -> VecNormalize:
    """Normalize heterogeneous game features and high-variance combat returns."""
    base_env = DummyVecEnv([lambda: make_env(url, aim_only, movement_only,
                                              survival_only, flee_aim_only)])
    return VecNormalize(
        base_env,
        norm_obs=True,
        norm_reward=True,
        clip_obs=10.0,
        clip_reward=10.0,
        gamma=0.995,
    )


def new_model(env: VecNormalize, tb_log: str | None,
              *, flee_aim: bool = False) -> PPO:
    """Build the common PPO architecture used by all curriculum runs.

    Flee-aim uses a compact Discrete(5) yaw-only space, so a slightly higher
    entropy bonus still explores useful turns without the old Discrete(35)
    NOOP-dilution that pinned entropy_loss near -3.
    """
    return PPO(
        "MlpPolicy", env,
        n_steps=2048, batch_size=256, n_epochs=10,
        gamma=0.995, gae_lambda=0.95, learning_rate=2.5e-4,
        ent_coef=0.01 if flee_aim else 0.005,
        policy_kwargs=dict(net_arch=dict(pi=[256, 256], vf=[256, 256])),
        tensorboard_log=tb_log, verbose=1,
    )


def initialize_from_policy(source_path: Path, env: VecNormalize, tb_log: str | None) -> PPO:
    """Transfer an older compatible policy into the current, larger action space.

    The original aim checkpoint has 24 actions.  Those action IDs are preserved at
    the beginning of the current 33-action list, so all shared directional and
    movement logits can be copied exactly.  Newly added actions retain their normal
    random initialization; optimizer state is deliberately reset for the new task.
    """
    source = PPO.load(str(source_path), device="cpu")
    target = new_model(env, tb_log)
    if source.observation_space.shape != target.observation_space.shape:
        raise ValueError(
            f"observation shapes differ: {source.observation_space.shape} vs "
            f"{target.observation_space.shape}")
    if source.action_space.n > target.action_space.n:
        raise ValueError(
            f"source has {source.action_space.n} actions but target has only "
            f"{target.action_space.n}")

    source_state = source.policy.state_dict()
    target_state = target.policy.state_dict()
    copied = []
    for name, value in source_state.items():
        if name in target_state and target_state[name].shape == value.shape:
            target_state[name] = value.to(target_state[name].device)
            copied.append(name)

    # The action head is the only deliberately different tensor (e.g. 24 x 256 -> 35 x 256).
    # Copy the shared action IDs in order; this keeps LOOK_* behavior from the aim model.
    shared_actions = source.action_space.n
    for name in ("action_net.weight", "action_net.bias"):
        target_state[name][:shared_actions] = source_state[name].to(target_state[name].device)
    target.policy.load_state_dict(target_state)
    target.num_timesteps = 0
    print(f"initialized from {source_path}: copied {len(copied)} full tensors and "
          f"{shared_actions} action-head rows; new action rows remain initialized.")
    return target


def main():
    import os
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="ws://127.0.0.1:8765")
    ap.add_argument("--steps", type=int, default=100_000)
    ap.add_argument("--model", default="pvprl_ppo_v4.zip",
                    help="model path (v4 action space; default: %(default)s)")
    ap.add_argument("--play", action="store_true")
    ap.add_argument("--fresh", action="store_true",
                    help="ignore any saved model and start training from scratch")
    ap.add_argument("--init-from", type=Path,
                    help="initialize a new compatible policy from this PPO checkpoint (for example, pvprl_aim_ppo.zip)")
    ap.add_argument("--aim-only", action="store_true",
                    help="train aiming first: requires mannequin + targetResistance config and /pvprl reload")
    ap.add_argument("--movement-only", action="store_true",
                    help="train only traversal, including sprint-jumping, using horizontal speed as the reward")
    ap.add_argument("--survival", action="store_true",
                    help="train health-gated survival: below 5 hearts face opposite + sprint away; "
                         "looking at / lingering near the armor stand is punished (fades out by 10 "
                         "blocks); eat golden apple at ≥10 blocks for a large reward. "
                         "Every reset runs target auto. Healthy HP uses fight rewards.")
    ap.add_argument("--tb-log", default="pvprl_tb",
                    help="TensorBoard log directory (default: %(default)s)")
    ap.add_argument("--tb-name", default="pvp_aim",
                    help="name shown for this training run in TensorBoard (default: %(default)s)")
    ap.add_argument("--no-tensorboard", action="store_true",
                    help="disable TensorBoard event logging")
    ap.add_argument("--flee-aim", action="store_true",
                    help="train health-gated flee-aim: start at 20 HP, then take 11 damage halfway "
                         "through each episode to train LOOK-AWAY below 50% health. "
                         "Mannequin within 10 blocks. Yaw-only Discrete(5). "
                         "Step 1 of a basic fleeing algorithm.")
    args = ap.parse_args()

    if args.aim_only and args.movement_only:
        ap.error("--aim-only and --movement-only cannot be used together")
    if args.survival and (args.aim_only or args.movement_only):
        ap.error("--survival cannot be used with --aim-only or --movement-only")
    if args.flee_aim and (args.aim_only or args.movement_only or args.survival):
        ap.error("--flee-aim cannot be used with --aim-only, --movement-only, or --survival")

    # Survival curriculum uses a separate model so it does not overwrite the main
    # PvP/aim/movement checkpoints. The user can still override with --model.
    if args.survival and args.model == "pvprl_ppo_v4.zip":
        args.model = "pvprl_survival_ppo.zip"
    if args.survival and args.tb_name == "pvp_aim":
        args.tb_name = "pvp_survival"
    # Flee-aim curriculum uses a separate model too (compact yaw-only Discrete(5)).
    if args.flee_aim and args.model == "pvprl_ppo_v4.zip":
        args.model = "pvprl_flee_aim_ppo.zip"
    if args.flee_aim and args.tb_name == "pvp_aim":
        args.tb_name = "pvp_flee_aim"

    env = make_vec_env(args.url, args.aim_only, args.movement_only,
                       args.survival, args.flee_aim)
    vec_stats = Path(args.model).with_suffix(".vecnormalize.pkl")

    if args.init_from:
        init_stats = args.init_from.with_suffix(".vecnormalize.pkl")
        if not args.init_from.exists():
            ap.error(f"--init-from checkpoint not found: {args.init_from}")
        if not init_stats.exists():
            ap.error(f"matching normalization statistics not found: {init_stats}")
        # Directional behavior depends on the same feature normalization used
        # during aim training; resume those running statistics before adapting.
        env = VecNormalize.load(str(init_stats), env.venv)
        # VecNormalize serializes its old action-space metadata.  The statistics
        # are valid because the observation shape is unchanged, but the current
        # movement environment deliberately has a larger action space.
        env.action_space = env.venv.action_space
        env.training = True
        env.norm_reward = True

    # SB3 writes all rollout/train scalars as TensorBoard events.  Fail clearly when it was
    # requested but missing instead of silently producing a run with no live dashboard.
    tb_log = None
    if not args.no_tensorboard:
        if importlib.util.find_spec("tensorboard") is None:
            sys.exit("TensorBoard is required for this run but is not installed. Run:\n\n"
                     "    ./venv/bin/python3 -m pip install tensorboard\n\n"
                     "Or pass --no-tensorboard to train without dashboard logging.")
        tb_path = Path(args.tb_log).expanduser().resolve()
        tb_path.mkdir(parents=True, exist_ok=True)
        tb_log = str(tb_path)
        print("TensorBoard logging enabled:")
        print(f"    ./venv/bin/python3 -m tensorboard.main --logdir {tb_log} --port 6006")
        print("Then open http://localhost:6006/")

    if args.play:
        try:
            if not vec_stats.exists():
                raise FileNotFoundError(f"missing normalization statistics: {vec_stats}")
            env = VecNormalize.load(str(vec_stats), env.venv)
            env.training = False
            env.norm_reward = False
            model = PPO.load(args.model, env=env)
        except Exception as e:
            sys.exit(f"Failed to load {args.model}: {e}. Try training first.")
        obs = env.reset()
        while True:
            action, _ = model.predict(obs, deterministic=True)
            obs, _, done, _ = env.step(action)
            if bool(done[0]):
                obs = env.reset()

    # The previous raw-observation checkpoint is not compatible with VecNormalize.
    # The v4 default also leaves old action-space checkpoints untouched. Subsequent v4 runs resume automatically,
    # but only with their matching normalization state so feature scales never
    # change underneath an already-trained policy.
    model = None
    if args.init_from:
        if os.path.exists(args.model) and not args.fresh:
            env.close()
            raise SystemExit(f"{args.model} already exists. Add --fresh to replace it with --init-from.")
        print(f"initializing movement policy from {args.init_from}")
        model = initialize_from_policy(args.init_from, env, tb_log)
    elif os.path.exists(args.model) and not args.fresh:
        print(f"resuming from {args.model}")
        try:
            if not vec_stats.exists():
                raise FileNotFoundError(f"missing {vec_stats}")
            env = VecNormalize.load(str(vec_stats), env.venv)
            model = PPO.load(args.model, env=env, tensorboard_log=tb_log)
            # Older flee-aim checkpoints used a larger action space.
            if args.flee_aim and model.action_space.n != env.action_space.n:
                env.close()
                raise SystemExit(
                    f"{args.model} has action_space.n={model.action_space.n} but "
                    f"flee-aim now uses Discrete({env.action_space.n}). "
                    "Re-train with --fresh (entropy was stuck near -3 on the old space).")
        except SystemExit:
            raise
        except Exception as e:
            env.close()
            raise SystemExit(f"Cannot safely resume: {e}. Use --fresh to start a new run.")

    if model is None:
        print("starting a fresh model")
        model = new_model(env, tb_log, flee_aim=args.flee_aim)

    # Autosave every 5k steps so a Ctrl+C / crash / Minecraft close does not lose progress.
    from stable_baselines3.common.callbacks import CheckpointCallback
    autosave = CheckpointCallback(save_freq=5000, save_path="./checkpoints",
                                  name_prefix="pvprl_ppo_v4", save_vecnormalize=True)
    try:
        model.learn(total_timesteps=args.steps, reset_num_timesteps=False,
                    callback=autosave, tb_log_name=args.tb_name)
    except KeyboardInterrupt:
        print("interrupted — saving before exit")
    finally:
        model.save(args.model)
        env.save(str(vec_stats))
        print(f"saved {args.model}")
        env.close()


if __name__ == "__main__":
    main()
