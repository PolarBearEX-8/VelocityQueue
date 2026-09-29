package me.queue.workers;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.queue.Main;
import me.queue.PlayerQueue;
import me.queue.auth.AuthMeGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class QueueWorkerTest {
    private Main plugin;
    private RegisteredServer main;
    private PlayerQueue priority;
    private PlayerQueue normal;
    private CommentedConfigurationNode config;
    private AtomicLong clock;
    private QueueWorker worker;

    @BeforeEach
    void setup() throws Exception {
        plugin = mock(Main.class);
        main = mock(RegisteredServer.class);
        priority = new PlayerQueue();
        normal = new PlayerQueue();
        config = CommentedConfigurationNode.root();
        config.node("AntiFlood").set(5);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getMainServer()).thenReturn(main);
        when(plugin.getServer()).thenReturn(mock(ProxyServer.class));
        when(plugin.getPrioQueue()).thenReturn(priority);
        when(plugin.getNormalQueue()).thenReturn(normal);
        when(plugin.getMainServerAvailableSlots()).thenReturn(100);
        AuthMeGate gate = mock(AuthMeGate.class);
        when(gate.requireLogin(any())).thenReturn(true);
        when(plugin.getAuthMeGate()).thenReturn(gate);
        clock = new AtomicLong();
        worker = new QueueWorker(plugin, clock::get);
    }

    private Player enqueue(PlayerQueue queue) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isActive()).thenReturn(true);
        when(player.hasPermission("queue.priority")).thenReturn(queue == priority);
        ConnectionRequestBuilder request = mock(ConnectionRequestBuilder.class);
        when(player.createConnectionRequest(main)).thenReturn(request);
        when(request.connect()).thenReturn(new CompletableFuture<>());
        queue.addToQueue(player);
        return player;
    }

    @Test
    void leavingPendingTransferReleasesLastReservedSlot() {
        when(plugin.getMainServerAvailableSlots()).thenReturn(1);
        Player first = enqueue(normal);
        Player second = enqueue(normal);
        worker.run();
        normal.removeFromQueue(first);
        clock.set(TimeUnit.SECONDS.toNanos(5));
        worker.run();
        verify(second).createConnectionRequest(main);
        org.junit.jupiter.api.Assertions.assertFalse(worker.isConnecting(first));
        org.junit.jupiter.api.Assertions.assertEquals(1, normal.getQueuePosition(second));
    }

    @Test
    void oldCompletionCannotClearNewAttemptAfterLeaveAndRejoin() {
        Player player = enqueue(normal);
        var request = player.createConnectionRequest(main);
        var old = request.connect();
        clearInvocations(player);
        worker.run();
        normal.removeFromQueue(player);
        normal.addToQueue(player);
        when(request.connect()).thenReturn(new CompletableFuture<>());
        clock.set(TimeUnit.SECONDS.toNanos(5));
        worker.run();
        old.completeExceptionally(new RuntimeException("old session timeout"));
        org.junit.jupiter.api.Assertions.assertTrue(worker.isConnecting(player));
        org.junit.jupiter.api.Assertions.assertTrue(normal.isInQueue(player));
        verify(plugin, never()).getLogger();
        verify(player, times(2)).createConnectionRequest(main);
    }

    @Test
    void disconnectedHeadIsPrunedBeforeReservingSlots() {
        when(plugin.getMainServerAvailableSlots()).thenReturn(1);
        Player first = enqueue(priority);
        Player second = enqueue(normal);
        worker.run();
        when(first.isActive()).thenReturn(false);
        clock.set(TimeUnit.SECONDS.toNanos(5));
        worker.run();
        org.junit.jupiter.api.Assertions.assertEquals(0, priority.queueLength());
        verify(second).createConnectionRequest(main);
    }

    @Test
    void priorityDepartureRefreshesNormalTitleOnNextWorkerTick() throws Exception {
        config.node("messages", "title-position").set("Position %d");
        when(plugin.getMainServerAvailableSlots()).thenReturn(0);
        Player vip = enqueue(priority);
        Player regular = enqueue(normal);
        worker.run();
        clearInvocations(regular);
        priority.removeFromQueue(vip);
        worker.run();
        var title = org.mockito.ArgumentCaptor.forClass(net.kyori.adventure.title.Title.class);
        verify(regular).showTitle(title.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                me.queue.util.MessageUtil.translateChars("Position 1"), title.getValue().subtitle());
    }

    @Test
    void ignoresLateFailureAfterLeavingQueue() {
        Player player = enqueue(normal);
        var future = player.createConnectionRequest(main).connect();
        clearInvocations(player);
        worker.run();
        normal.removeFromQueue(player);
        future.completeExceptionally(new RuntimeException("late timeout"));
        worker.run();
        verify(player, times(1)).createConnectionRequest(main);
        verify(plugin, never()).getLogger();
        org.junit.jupiter.api.Assertions.assertFalse(worker.isConnecting(player));
    }

    @Test
    void ignoresLateFailureAfterDisconnect() {
        Player player = enqueue(normal);
        var future = player.createConnectionRequest(main).connect();
        worker.run();
        when(player.isActive()).thenReturn(false);
        future.completeExceptionally(new RuntimeException("late timeout"));
        verify(plugin, never()).getLogger();
        org.junit.jupiter.api.Assertions.assertFalse(normal.isInQueue(player));
    }

    @Test
    void ignoresFailedResultAfterMovingToMain() {
        Player player = enqueue(normal);
        var future = player.createConnectionRequest(main).connect();
        worker.run();
        var connection = mock(com.velocitypowered.api.proxy.ServerConnection.class);
        when(connection.getServer()).thenReturn(main);
        when(main.getServerInfo()).thenReturn(new com.velocitypowered.api.proxy.server.ServerInfo(
                "main", new java.net.InetSocketAddress("localhost", 25565)));
        when(player.getCurrentServer()).thenReturn(java.util.Optional.of(connection));
        var result = mock(ConnectionRequestBuilder.Result.class);
        when(result.getStatus()).thenReturn(ConnectionRequestBuilder.Status.SERVER_DISCONNECTED);
        future.complete(result);
        verify(plugin, never()).getLogger();
        org.junit.jupiter.api.Assertions.assertFalse(normal.isInQueue(player));
    }

    @Test
    void sharesIntervalBetweenQueuesAndAllowsNextAtDeadline() {
        Player first = enqueue(priority);
        Player second = enqueue(normal);
        worker.run();
        verify(first).createConnectionRequest(main);
        verify(second, never()).createConnectionRequest(main);
        priority.removeFromQueue(first);
        clock.set(TimeUnit.SECONDS.toNanos(5) - 1);
        worker.run();
        verify(second, never()).createConnectionRequest(main);
        clock.incrementAndGet();
        worker.run();
        verify(second).createConnectionRequest(main);
    }

    @Test
    void fullServerDoesNotConsumeInterval() {
        Player player = enqueue(normal);
        when(plugin.getMainServerAvailableSlots()).thenReturn(0);
        worker.run();
        verify(player, never()).createConnectionRequest(main);
        when(plugin.getMainServerAvailableSlots()).thenReturn(100);
        worker.run();
        verify(player).createConnectionRequest(main);
    }

    @Test
    void reloadAppliesIntervalAndClampsToOneSecond() throws Exception {
        Player first = enqueue(normal);
        Player second = enqueue(normal);
        worker.run();
        normal.removeFromQueue(first);
        config.node("AntiFlood").set(0);
        worker.reloadConfig();
        worker.run();
        verify(second, never()).createConnectionRequest(main);
        clock.set(TimeUnit.SECONDS.toNanos(1));
        worker.run();
        verify(second).createConnectionRequest(main);
    }

    private void batch(double seconds, int count) throws Exception {
        config.node("AntiFlood").set(null);
        config.node("AntiFlood", "interval-seconds").set(seconds);
        config.node("AntiFlood", "players-per-interval").set(count);
        worker.reloadConfig();
    }

    @Test
    void fractionalIntervalDoesNotReleaseEarly() throws Exception {
        batch(3.5, 1);
        Player first = enqueue(normal);
        Player second = enqueue(normal);
        worker.run();
        verify(first).createConnectionRequest(main);
        clock.set(TimeUnit.MILLISECONDS.toNanos(3500) - 1);
        worker.run();
        verify(second, never()).createConnectionRequest(main);
        clock.incrementAndGet();
        worker.run();
        verify(second).createConnectionRequest(main);
    }

    @Test
    void batchPrioritizesPriorityFifoAndSharesQuotaWithNormal() throws Exception {
        batch(5, 2);
        Player regular = enqueue(normal);
        Player first = enqueue(priority);
        Player second = enqueue(priority);
        worker.run();
        var order = inOrder(first, second);
        order.verify(first).createConnectionRequest(main);
        order.verify(second).createConnectionRequest(main);
        verify(regular, never()).createConnectionRequest(main);
        clock.set(TimeUnit.SECONDS.toNanos(5));
        worker.run();
        verify(regular).createConnectionRequest(main);
    }

    @Test
    void quotaDoesNotResetOnReloadOrAccumulateWhenIdle() throws Exception {
        batch(5, 2);
        Player first = enqueue(normal);
        worker.run();
        Player second = enqueue(normal);
        Player third = enqueue(normal);
        worker.reloadConfig();
        worker.run();
        verify(second).createConnectionRequest(main);
        verify(third, never()).createConnectionRequest(main);
        clock.set(TimeUnit.SECONDS.toNanos(100));
        enqueue(normal);
        Player fifth = enqueue(normal);
        worker.run();
        verify(third).createConnectionRequest(main);
        verify(fifth, never()).createConnectionRequest(main);
    }

    @Test
    void batchReservesLastSlotUntilPendingConnectionCompletes() throws Exception {
        batch(5, 2);
        when(plugin.getMainServerAvailableSlots()).thenReturn(1);
        Player first = enqueue(normal);
        Player second = enqueue(normal);
        worker.run();
        clock.set(TimeUnit.SECONDS.toNanos(5));
        worker.run();
        verify(first).createConnectionRequest(main);
        verify(second, never()).createConnectionRequest(main);
    }

    @Test
    void bypassIgnoresAntiFloodButNotSlotLimitByDefault() throws Exception {
        batch(5, 1);
        config.node("priority", "mode").set("bypass");
        Player first = enqueue(priority);
        Player second = enqueue(priority);
        Player regular = enqueue(normal);
        when(plugin.getMainServerAvailableSlots()).thenReturn(0);
        worker.run();
        verify(first, never()).createConnectionRequest(main);
        when(plugin.getMainServerAvailableSlots()).thenReturn(100);
        worker.run();
        verify(first).createConnectionRequest(main);
        verify(second).createConnectionRequest(main);
        verify(regular).createConnectionRequest(main);
    }

    @Test
    void bypassPlayerLimitOnlyAppliesToPriorityBypassMode() throws Exception {
        config.node("priority", "bypass-player-limit").set(true);
        Player vip = enqueue(priority);
        Player regular = enqueue(normal);
        when(plugin.getMainServerAvailableSlots()).thenReturn(0);
        worker.run();
        verify(vip, never()).createConnectionRequest(main);
        config.node("priority", "mode").set("bypass");
        worker.reloadConfig();
        worker.run();
        verify(vip).createConnectionRequest(main);
        verify(regular, never()).createConnectionRequest(main);
    }

    @Test
    void bypassDoesNotAttemptOfflineBackend() throws Exception {
        config.node("priority", "mode").set("bypass");
        config.node("priority", "bypass-player-limit").set(true);
        Player vip = enqueue(priority);
        when(plugin.getMainServerAvailableSlots()).thenReturn(-1);
        worker.run();
        verify(vip, never()).createConnectionRequest(main);
    }
}
