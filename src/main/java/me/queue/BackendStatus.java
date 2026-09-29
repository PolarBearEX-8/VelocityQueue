package me.queue;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** A slow backend must not block queue cleanup, UI updates, or pre-connect events. */
public final class BackendStatus {
    private final LongSupplier clock;
    private RegisteredServer target;
    private CompletableFuture<ServerPing> pending;
    private int online = -1;
    private long updated;
    private long requested;
    private boolean started;
    private String lastResult = "not checked";

    public synchronized String diagnostic() {
        return "ping=" + (pending == null ? "idle" : "pending") + ", last=" + lastResult;
    }

    public BackendStatus() { this(System::nanoTime); }
    BackendStatus(LongSupplier clock) { this.clock = clock; }

    public synchronized void invalidate() {
        target = null;
        pending = null;
        online = -1;
        started = false;
        lastResult = "waiting for fresh ping";
    }

    public synchronized int available(RegisteredServer server, int limit) {
        if (server == null) return -1;
        if (target != server) {
            target = server;
            pending = null;
            online = -1;
            started = false;
            lastResult = "not checked";
        }
        long now = clock.getAsLong();
        if (pending == null && (!started || now - requested >= TimeUnit.SECONDS.toNanos(1))) {
            started = true;
            requested = now;
            try {
                CompletableFuture<ServerPing> request = server.ping().orTimeout(3, TimeUnit.SECONDS);
                pending = request;
                request.whenComplete((ping, error) -> complete(server, request, ping, error));
            } catch (RuntimeException e) {
                lastResult = e.getClass().getSimpleName() + ": " + e.getMessage();
                online = -1;
                pending = null;
            }
        }
        return online < 0 || now - updated >= TimeUnit.SECONDS.toNanos(3)
                ? -1 : Math.max(0, limit - online);
    }

    private synchronized void complete(RegisteredServer server, CompletableFuture<ServerPing> request,
            ServerPing ping, Throwable error) {
        if (target != server || pending != request) return;
        pending = null;
        online = error == null && ping != null ? ping.getPlayers().map(ServerPing.Players::getOnline).orElse(-1) : -1;
        lastResult = error != null ? error.getClass().getSimpleName() + ": " + error.getMessage()
                : online < 0 ? "ping has no player count" : "online=" + online;
        updated = clock.getAsLong();
    }
}
