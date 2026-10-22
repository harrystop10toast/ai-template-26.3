package com.pvprl.net;

import org.java_websocket.WebSocket;

/**
 * A decoded inbound command captured off-thread (on the WebSocket thread) and queued for
 * application on the client main thread.
 *
 * @param conn    the originating connection (for acks / errors / targeted replies)
 * @param message the parsed protocol message
 */
public record PendingAction(WebSocket conn, Protocol.Inbound message) {}
