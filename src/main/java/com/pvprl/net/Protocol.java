package com.pvprl.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * JSON message envelope and (de)serialization helpers for the RL protocol.
 *
 * <p>All messages are JSON objects with a {@code "type"} discriminator. This class owns the
 * wire-format vocabulary so the server and connection code stay free of ad-hoc JSON.</p>
 *
 * <h2>Client (agent) → Mod</h2>
 * <pre>{@code
 * {"type":"action","id":42,"action":"FORWARD_ATTACK","ticks":1}
 * {"type":"reset"}
 * {"type":"setTarget","player":"Steve"}
 * {"type":"movementTraining","enabled":true}
 * {"type":"survivalTraining","enabled":true}
 * {"type":"aimGatedTraining","enabled":true}
 * }</pre>
 *
 * <h2>Mod → Client (agent)</h2>
 * <pre>{@code
 * {"type":"observation","connId":"c3","data":{ ... }}
 * {"type":"event","event":"DEATH"}
 * {"type":"ack","ref":42}
 * {"type":"error","message":"unknown action: FOO"}
 * }</pre>
 */
public final class Protocol {
    private static final Gson GSON = new Gson();

    private Protocol() {
    }

    /** Inbound message kinds the mod understands. */
    public enum InboundType {
        ACTION, RESET, SET_TARGET, MOVEMENT_TRAINING, SURVIVAL_TRAINING, AIM_GATED_TRAINING, UNKNOWN
    }

    /**
     * A parsed inbound message. Fields are populated per {@link #type}; irrelevant fields
     * are left at their defaults.
     *
     * @param type   discriminator
     * @param id     optional client sequence id (echoed in acks); -1 if absent
     * @param action action token (name or index) for {@link InboundType#ACTION}
     * @param ticks  hold duration for an action; defaults to 1
     * @param player target entity scoreboard name for {@link InboundType#SET_TARGET}
     * @param enabled requested state for {@link InboundType#MOVEMENT_TRAINING}
     */
    public record Inbound(InboundType type, long id, String action, int ticks, String player,
                          boolean enabled) {}

    /**
     * Parses a raw inbound JSON message. Never throws; malformed input yields
     * {@link InboundType#UNKNOWN}.
     *
     * @param raw the raw text frame
     * @return the parsed message
     */
    public static Inbound parse(String raw) {
        try {
            JsonObject o = JsonParser.parseString(raw).getAsJsonObject();
            String type = o.has("type") ? o.get("type").getAsString() : "";
            long id = o.has("id") ? o.get("id").getAsLong() : -1L;
            return switch (type) {
                case "action" -> new Inbound(
                        InboundType.ACTION, id,
                        o.has("action") ? o.get("action").getAsString() : null,
                        o.has("ticks") ? o.get("ticks").getAsInt() : 1,
                        null, false);
                case "reset" -> new Inbound(InboundType.RESET, id, null, 1, null, false);
                case "setTarget" -> new Inbound(
                        InboundType.SET_TARGET, id, null, 1,
                        o.has("player") ? o.get("player").getAsString() : null, false);
                case "movementTraining" -> new Inbound(
                        InboundType.MOVEMENT_TRAINING, id, null, 1, null,
                        o.has("enabled") && o.get("enabled").getAsBoolean());
                case "survivalTraining" -> new Inbound(
                        InboundType.SURVIVAL_TRAINING, id, null, 1, null,
                        o.has("enabled") && o.get("enabled").getAsBoolean());
                case "aimGatedTraining" -> new Inbound(
                        InboundType.AIM_GATED_TRAINING, id, null, 1, null,
                        o.has("enabled") && o.get("enabled").getAsBoolean());
                default -> new Inbound(InboundType.UNKNOWN, id, null, 1, null, false);
            };
        } catch (Exception e) {
            return new Inbound(InboundType.UNKNOWN, -1L, null, 1, null, false);
        }
    }

    /**
     * Wraps an already-serialized observation JSON body in an observation envelope.
     *
     * @param connId   id of the connection the observation is addressed to
     * @param dataJson the observation JSON (from the serializer)
     * @return a full observation message
     */
    public static String observation(String connId, String dataJson) {
        return "{\"type\":\"observation\",\"connId\":" + GSON.toJson(connId)
                + ",\"data\":" + dataJson + "}";
    }

    /** @return an episode-event message. */
    public static String event(String event) {
        return "{\"type\":\"event\",\"event\":" + GSON.toJson(event) + "}";
    }

    /** @return an acknowledgement message echoing a client sequence id. */
    public static String ack(long ref) {
        return "{\"type\":\"ack\",\"ref\":" + ref + "}";
    }

    /** @return an error message. */
    public static String error(String message) {
        return "{\"type\":\"error\",\"message\":" + GSON.toJson(message) + "}";
    }
}
