package me.queue.auth;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import me.queue.Main;
import me.queue.PlayerQueue;
import me.queue.commands.QueueCommand;
import me.queue.listeners.PreConnectListener;
import me.queue.workers.MessageWorker;
import me.queue.workers.QueueWorker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthMeGateTest {
    private Main plugin;
    private ProxyServer proxy;
    private Player player;
    private RegisteredServer login;
    private RegisteredServer main;
    private ServerConnection connection;
    private PlayerQueue normal;
    private PlayerQueue priority;
    private AuthMeGate gate;

    @BeforeEach
    void setup() {
        plugin = mock(Main.class);
        proxy = mock(ProxyServer.class);
        player = mock(Player.class);
        login = backend("login", 25566);
        main = backend("main", 25567);
        connection = mock(ServerConnection.class);
        normal = new PlayerQueue();
        priority = new PlayerQueue();
        when(plugin.getServer()).thenReturn(proxy);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(plugin.getConfig()).thenReturn(CommentedConfigurationNode.root());
        when(plugin.getQueueServer()).thenReturn(login);
        when(plugin.getMainServer()).thenReturn(main);
        when(plugin.getNormalQueue()).thenReturn(normal);
        when(plugin.getPrioQueue()).thenReturn(priority);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getUsername()).thenReturn("TestPlayer");
        when(player.isActive()).thenReturn(true);
        when(player.getCurrentServer()).thenReturn(Optional.of(connection));
        when(connection.getPlayer()).thenReturn(player);
        when(connection.getServer()).thenReturn(login);
        when(proxy.getPlayer(player.getUniqueId())).thenReturn(Optional.of(player));
        when(proxy.getAllPlayers()).thenReturn(List.of(player));
        gate = new AuthMeGate(plugin);
        when(plugin.getAuthMeGate()).thenReturn(gate);
    }

    private RegisteredServer backend(String name, int port) {
        RegisteredServer server = mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(new ServerInfo(name, new InetSocketAddress("localhost", port)));
        return server;
    }

    private byte[] packet(String action, String username) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(action);
            out.writeUTF(username);
        }
        return bytes.toByteArray();
    }

    private PluginMessageEvent message(String action) throws Exception {
        PluginMessageEvent event = new PluginMessageEvent(connection, player, AuthMeGate.CHANNEL, packet(action, "TestPlayer"));
        gate.onPluginMessage(event);
        gate.consumeAuthMessage(event);
        return event;
    }

    @Test
    void validatedLoginRemainsVisibleToAuthMeVelocityThenIsConsumed() throws Exception {
        PluginMessageEvent event = new PluginMessageEvent(connection, player, AuthMeGate.CHANNEL, packet("LOGIN", "TestPlayer"));
        gate.onPluginMessage(event);
        assertTrue(gate.isAuthenticated(player));
        assertTrue(event.getResult().isAllowed(), "AuthMeVelocity ignores handled packets at NORMAL order");
        gate.consumeAuthMessage(event);
        assertFalse(event.getResult().isAllowed(), "Authentication packets must not reach clients");
    }

    @Test
    void spoofedOrMalformedMessagesAreBlockedBeforeAuthMeVelocity() throws Exception {
        for (PluginMessageEvent event : List.of(
                new PluginMessageEvent(player, connection, AuthMeGate.CHANNEL, packet("LOGIN", "TestPlayer")),
                new PluginMessageEvent(connection, player, AuthMeGate.CHANNEL, packet("LOGIN", "SomeoneElse")),
                new PluginMessageEvent(connection, player, AuthMeGate.CHANNEL, packet("UNKNOWN", "TestPlayer")),
                new PluginMessageEvent(connection, player, AuthMeGate.CHANNEL, new byte[]{0, 10, 1}))) {
            gate.onPluginMessage(event);
            assertFalse(event.getResult().isAllowed());
        }
        assertFalse(gate.isAuthenticated(player));
    }

    @Test
    void previouslyHandledMessageIsNeverReopened() throws Exception {
        PluginMessageEvent event = new PluginMessageEvent(connection, player, AuthMeGate.CHANNEL, packet("LOGIN", "TestPlayer"));
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        gate.onPluginMessage(event);
        assertFalse(event.getResult().isAllowed());
    }

    @Test
    void waitsOnLoginServerWithoutEnteringQueueAndShowsTitle() {
        new PreConnectListener(plugin).onServerConnect(new ServerConnectedEvent(player, login, null));
        assertFalse(gate.isAuthenticated(player));
        assertEquals(0, normal.queueLength());
        ArgumentCaptor<Title> title = ArgumentCaptor.forClass(Title.class);
        verify(player).showTitle(title.capture());
        assertEquals(me.queue.util.MessageUtil.translateChars("&ePlease login"), title.getValue().title());
    }

    @Test
    void successfulLoginClearsPromptAndEnqueuesNormalPlayer() throws Exception {
        assertFalse(message("LOGIN").getResult().isAllowed());
        assertTrue(gate.isAuthenticated(player));
        assertEquals(1, normal.getQueuePosition(player));
        verify(player).clearTitle();
    }

    @Test
    void priorityAlsoRequiresLogin() throws Exception {
        when(player.hasPermission("queue.priority")).thenReturn(true);
        assertFalse(gate.enqueue(player));
        assertEquals(0, priority.queueLength());
        message("LOGIN");
        assertEquals(1, priority.getQueuePosition(player));
        assertEquals(0, normal.queueLength());
    }

    @Test
    void registerAndUnknownMessagesDoNotAuthenticate() throws Exception {
        message("REGISTER");
        message("UNKNOWN");
        assertFalse(gate.isAuthenticated(player));
        assertEquals(0, normal.queueLength());
    }

    @Test
    void clientCannotSpoofLoginOrForwardItToAuthMe() throws Exception {
        PluginMessageEvent event = new PluginMessageEvent(player, connection, AuthMeGate.CHANNEL, packet("LOGIN", "TestPlayer"));
        gate.onPluginMessage(event);
        assertFalse(event.getResult().isAllowed());
        assertFalse(gate.isAuthenticated(player));
    }

    @Test
    void wrongBackendCannotAuthenticate() throws Exception {
        when(connection.getServer()).thenReturn(main);
        message("LOGIN");
        assertFalse(gate.isAuthenticated(player));
    }

    @Test
    void differentUsernameCannotAuthenticateCarrier() throws Exception {
        gate.onPluginMessage(new PluginMessageEvent(connection, player, AuthMeGate.CHANNEL, packet("LOGIN", "SomeoneElse")));
        assertFalse(gate.isAuthenticated(player));
    }

    @Test
    void malformedAndTrailingDataCannotAuthenticate() throws Exception {
        gate.onPluginMessage(new PluginMessageEvent(connection, player, AuthMeGate.CHANNEL, new byte[]{0, 10, 1}));
        byte[] valid = packet("LOGIN", "TestPlayer");
        gate.onPluginMessage(new PluginMessageEvent(connection, player, AuthMeGate.CHANNEL, java.util.Arrays.copyOf(valid, valid.length + 1)));
        assertFalse(gate.isAuthenticated(player));
    }

    @Test
    void oldBackendConnectionCannotAuthenticate() throws Exception {
        when(player.getCurrentServer()).thenReturn(Optional.of(mock(ServerConnection.class)));
        message("LOGIN");
        assertFalse(gate.isAuthenticated(player));
    }

    @Test
    void stalePlayerCannotAuthenticateNewSessionWithSameUuid() throws Exception {
        Player replacement = mock(Player.class);
        UUID id = player.getUniqueId();
        when(replacement.getUniqueId()).thenReturn(id);
        when(proxy.getPlayer(player.getUniqueId())).thenReturn(Optional.of(replacement));
        message("LOGIN");
        assertFalse(gate.isAuthenticated(player));
        assertFalse(gate.isAuthenticated(replacement));
    }

    @Test
    void logoutAndUnregisterRemoveAuthenticationAndQueue() throws Exception {
        for (String action : List.of("LOGOUT", "UNREGISTER", "FORCE_UNREGISTER")) {
            message("LOGIN");
            assertTrue(gate.isAuthenticated(player));
            message(action);
            assertFalse(gate.isAuthenticated(player));
            assertEquals(0, normal.queueLength());
        }
        verify(player, times(3)).showTitle(any(Title.class));
    }

    @Test
    void repeatedLoginDoesNotUndoManualLeave() throws Exception {
        message("LOGIN");
        normal.removeFromQueue(player);
        message("LOGIN");
        assertEquals(0, normal.queueLength());
        assertTrue(gate.isAuthenticated(player));
    }

    @Test
    void disconnectCleanupRequiresLoginAgain() throws Exception {
        message("LOGIN");
        gate.forget(player);
        assertFalse(gate.isAuthenticated(player));
        assertEquals(0, normal.queueLength());
    }

    @Test
    void initialMainConnectionRedirectsToLoginEvenWithBypassAndFreeSlots() {
        when(player.getCurrentServer()).thenReturn(Optional.empty());
        when(player.hasPermission("queue.bypass")).thenReturn(true);
        when(plugin.doesServerHaveSlot()).thenReturn(true);
        ServerPreConnectEvent event = new ServerPreConnectEvent(player, main);
        new PreConnectListener(plugin).onPreConnect(event);
        assertSame(login, event.getResult().getServer().orElseThrow());
        assertEquals(0, normal.queueLength());
        verify(plugin, never()).doesServerHaveSlot();
    }

    @Test
    void transferOutOfLoginIsDeniedUntilAuthentication() {
        ServerPreConnectEvent event = new ServerPreConnectEvent(player, main);
        new PreConnectListener(plugin).onPreConnect(event);
        assertFalse(event.getResult().isAllowed());
    }

    @Test
    void missingLoginServerFailsClosed() {
        when(plugin.getQueueServer()).thenReturn(null);
        ServerPreConnectEvent event = new ServerPreConnectEvent(player, main);
        new PreConnectListener(plugin).onPreConnect(event);
        assertFalse(event.getResult().isAllowed());
    }

    @Test
    void authenticatedPlayerCanTransferToMain() throws Exception {
        message("LOGIN");
        when(plugin.isQueueTransfer(player)).thenReturn(true);
        when(plugin.doesServerHaveSlot()).thenReturn(true);
        ServerPreConnectEvent event = new ServerPreConnectEvent(player, main);
        new PreConnectListener(plugin).onPreConnect(event);
        assertSame(main, event.getResult().getServer().orElseThrow());
    }

    @Test
    void directNormalConnectionCannotSkipQueueWhenSlotsAreFree() throws Exception {
        message("LOGIN");
        when(plugin.doesServerHaveSlot()).thenReturn(true);
        ServerPreConnectEvent event = new ServerPreConnectEvent(player, main);
        new PreConnectListener(plugin).onPreConnect(event);
        assertFalse(event.getResult().isAllowed());
        assertTrue(normal.isInQueue(player));
    }

    @Test
    void priorityBypassStillRequiresLoginAndRespectsConfiguredLimit() throws Exception {
        when(player.hasPermission("queue.priority")).thenReturn(true);
        plugin.getConfig().node("priority", "mode").set("bypass");
        plugin.getConfig().node("priority", "bypass-player-limit").set(true);
        PreConnectListener listener = new PreConnectListener(plugin);
        ServerPreConnectEvent unauthenticated = new ServerPreConnectEvent(player, main);
        listener.onPreConnect(unauthenticated);
        assertFalse(unauthenticated.getResult().isAllowed());
        message("LOGIN");
        ServerPreConnectEvent unlimited = new ServerPreConnectEvent(player, main);
        listener.onPreConnect(unlimited);
        assertSame(main, unlimited.getResult().getServer().orElseThrow());
        plugin.getConfig().node("priority", "bypass-player-limit").set(false);
        ServerPreConnectEvent full = new ServerPreConnectEvent(player, main);
        listener.onPreConnect(full);
        assertSame(login, full.getResult().getServer().orElseThrow());
        when(plugin.doesServerHaveSlot()).thenReturn(true);
        ServerPreConnectEvent free = new ServerPreConnectEvent(player, main);
        listener.onPreConnect(free);
        assertSame(main, free.getResult().getServer().orElseThrow());
    }

    @Test
    void returningToLoginRequiresFreshAuthMeConfirmation() throws Exception {
        message("LOGIN");
        when(connection.getServer()).thenReturn(main);
        ServerPreConnectEvent event = new ServerPreConnectEvent(player, login);
        new PreConnectListener(plugin).onPreConnect(event);
        assertFalse(gate.isAuthenticated(player));
        assertEquals(0, normal.queueLength());
    }

    @Test
    void joinCommandsCannotEnqueueBeforeLogin() {
        for (String alias : List.of("queue", "join")) {
            SimpleCommand.Invocation invocation = mock(SimpleCommand.Invocation.class);
            when(invocation.alias()).thenReturn(alias);
            when(invocation.arguments()).thenReturn(new String[0]);
            when(invocation.source()).thenReturn(player);
            new QueueCommand(plugin).execute(invocation);
        }
        assertEquals(0, normal.queueLength());
        verify(player, never()).createConnectionRequest(any());
    }

    @Test
    void workerRemovesUnauthenticatedEntriesWithoutConnecting() {
        normal.addToQueue(player);
        when(plugin.getMainServerStatus()).thenReturn(Main.MainServerStatus.HAS_SLOT);
        new QueueWorker(plugin).run();
        assertEquals(0, normal.queueLength());
        verify(player, never()).createConnectionRequest(any());
        verify(player, never()).sendPlayerListHeaderAndFooter(any(Component.class), any(Component.class));
    }

    @Test
    void queueMessagesDoNotOverwriteLoginPrompt() {
        normal.addToQueue(player);
        new MessageWorker(plugin).run();
        verify(player, never()).showTitle(any(Title.class));
        verify(player, never()).sendActionBar(any(Component.class));
    }

    @Test
    void promptRefreshStopsAfterLogin() throws Exception {
        gate.run();
        verify(player).showTitle(any(Title.class));
        message("LOGIN");
        clearInvocations(player);
        gate.run();
        verify(player, never()).showTitle(any(Title.class));
    }
}
