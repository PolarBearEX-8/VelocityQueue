package me.queue;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BackendStatusTest {
    @Test
    void pendingPingNeverBlocksAndOldDataExpires() {
        AtomicLong clock = new AtomicLong();
        BackendStatus status = new BackendStatus(clock::get);
        RegisteredServer server = mock(RegisteredServer.class);
        CompletableFuture<ServerPing> first = new CompletableFuture<>(), second = new CompletableFuture<>();
        when(server.ping()).thenReturn(first, second);
        assertEquals(-1, status.available(server, 100));
        assertEquals(-1, status.available(server, 100));
        verify(server, times(1)).ping();
        ServerPing ping = mock(ServerPing.class);
        ServerPing.Players players = mock(ServerPing.Players.class);
        when(players.getOnline()).thenReturn(40);
        when(ping.getPlayers()).thenReturn(Optional.of(players));
        first.complete(ping);
        assertEquals(60, status.available(server, 100));
        clock.set(TimeUnit.SECONDS.toNanos(1));
        assertEquals(60, status.available(server, 100));
        clock.set(TimeUnit.SECONDS.toNanos(3));
        assertEquals(-1, status.available(server, 100));
        second.completeExceptionally(new RuntimeException("offline"));
        assertEquals(-1, status.available(server, 100));
    }

    @Test
    void oldBackendResponseCannotOverwriteNewTarget() {
        BackendStatus status = new BackendStatus();
        RegisteredServer old = mock(RegisteredServer.class), replacement = mock(RegisteredServer.class);
        CompletableFuture<ServerPing> oldRequest = new CompletableFuture<>();
        when(old.ping()).thenReturn(oldRequest);
        when(replacement.ping()).thenReturn(new CompletableFuture<>());
        assertEquals(-1, status.available(old, 100));
        assertEquals(-1, status.available(replacement, 100));
        oldRequest.complete(mock(ServerPing.class));
        assertEquals(-1, status.available(replacement, 100));
    }
}
