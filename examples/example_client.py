#!/usr/bin/env python3
"""
Minimal example agent for the pvp-rl-env Fabric mod.

Connects to the mod's WebSocket, prints observations, and sends a trivial
scripted policy (turn toward the enemy and attack when close). Replace the
`policy()` function with your RL agent.

Protocol (see DESIGN.md):
  Mod -> agent:  {"type":"observation","connId":"c1","data":{...}}
                 {"type":"event","event":"DEATH|KILL|TIMEOUT|RESET"}
                 {"type":"ack","ref":<id>}  /  {"type":"error","message":...}
  agent -> Mod:  {"type":"action","id":<n>,"action":"FORWARD_ATTACK","ticks":1}
                 {"type":"reset"}  /  {"type":"setTarget","player":"Steve"}

Requires: pip install websockets
Usage:    python example_client.py [ws://127.0.0.1:8765]
"""
import asyncio
import json
import sys

try:
    import websockets
except ImportError:
    sys.exit("pip install websockets")


def policy(obs: dict) -> str:
    """Return a discrete action name from an observation dict."""
    enemy = obs.get("enemy", {})
    if not enemy.get("present"):
        return "LOOK_RIGHT"            # search for a target
    yaw = enemy.get("yawDiff", 0.0)
    dist = enemy.get("distance", 99.0)
    if yaw > 15:
        return "FORWARD_LOOK_RIGHT" if dist > 3.0 else "LOOK_RIGHT"
    if yaw < -15:
        return "FORWARD_LOOK_LEFT" if dist > 3.0 else "LOOK_LEFT"
    if dist > 3.0:
        return "FORWARD_SPRINT"        # close the gap
    return "FORWARD_ATTACK"            # in range: swing


async def main(url: str) -> None:
    seq = 0
    async with websockets.connect(url) as ws:
        print(f"connected to {url}")
        async for raw in ws:
            msg = json.loads(raw)
            kind = msg.get("type")
            if kind == "observation":
                obs = msg["data"]
                action = policy(obs)
                seq += 1
                await ws.send(json.dumps(
                    {"type": "action", "id": seq, "action": action, "ticks": 1}))
                print(f"t={obs['tick']} hp={obs['self']['health']:.0f} "
                      f"rew={obs['reward']['cumulative']:.2f} -> {action}")
            elif kind == "event":
                print(f"EVENT: {msg['event']}")
            elif kind == "error":
                print(f"ERROR: {msg['message']}")


if __name__ == "__main__":
    endpoint = sys.argv[1] if len(sys.argv) > 1 else "ws://127.0.0.1:8765"
    try:
        asyncio.run(main(endpoint))
    except KeyboardInterrupt:
        pass
