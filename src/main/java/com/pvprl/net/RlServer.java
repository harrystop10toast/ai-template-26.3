package com.pvprl.net;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket server exposing the RL environment.
 *
 * <p>Runs its own I/O thread (managed by the Java-WebSocket library). Inbound frames are
 * parsed and enqueued; the client tick loop drains the queue on the main thread via
 * {@link #drainInbound()}. Outbound observations/events are pushed with
 * {@link #broadcastObservation}, {@link #broadcastEvent} and {@link #broadcastRaw}.</p>
 *
 * <p>Multiple concurrent connections are supported (each gets a stable id), which is the
 * groundwork for self-play with several controlled agents.</p>
 */
public final class RlServer extends WebSocketServer {

    private static final Logger LOGGER = LoggerFactory.getLogger("pvp-rl-env/net");

    private final Queue<PendingAction> inbound = new ConcurrentLinkedQueue<>();
    private final AtomicInteger connCounter = new AtomicInteger();
    private volatile boolean started;

    /**
     * @param host bind host
     * @param port bind port
     */
    public RlServer(String host, int port) {
        super(new InetSocketAddress(host, port));
        setReuseAddr(true);
    }

    @Override
    public void onStart() {
        started = true;
        LOGGER.info("RL WebSocket server listening on {}", getAddress());
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String id = "c" + connCounter.incrementAndGet();
        conn.setAttachment(id);
        LOGGER.info("RL client connected: {} ({})", id, conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        LOGGER.info("RL client disconnected: {} (code={}, remote={})",
                (String) conn.getAttachment(), code, remote);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        Protocol.Inbound parsed = Protocol.parse(message);
        if (parsed.type() == Protocol.InboundType.UNKNOWN) {
            conn.send(Protocol.error("unrecognized message: " + message));
            return;
        }
        inbound.add(new PendingAction(conn, parsed));
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        LOGGER.warn("RL server error on {}: {}",
                conn == null ? "server" : (String) conn.getAttachment(), ex.toString());
    }

    /**
     * Removes and returns all queued inbound commands. Call on the main thread.
     *
     * @return queued commands in arrival order (possibly empty)
     */
    public java.util.List<PendingAction> drainInbound() {
        java.util.List<PendingAction> out = new java.util.ArrayList<>();
        PendingAction a;
        while ((a = inbound.poll()) != null) {
            out.add(a);
        }
        return out;
    }

    /**
     * Broadcasts a serialized observation to every open connection, each wrapped with that
     * connection's id.
     *
     * @param dataJson observation JSON body
     */
    public void broadcastObservation(String dataJson) {
        for (WebSocket conn : getConnections()) {
            if (conn.isOpen()) {
                conn.send(Protocol.observation((String) conn.getAttachment(), dataJson));
            }
        }
    }

    /** Broadcasts an episode event to all connections. */
    public void broadcastEvent(String event) {
        broadcastRaw(Protocol.event(event));
    }

    /** Broadcasts a raw, already-serialized message to all open connections. */
    public void broadcastRaw(String raw) {
        for (WebSocket conn : getConnections()) {
            if (conn.isOpen()) {
                conn.send(raw);
            }
        }
    }

    /** @return whether the server socket is up. */
    public boolean isStarted() {
        return started;
    }
}
