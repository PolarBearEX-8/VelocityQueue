package me.queue.auth;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import me.queue.Main;
import net.kyori.adventure.title.Title;

import java.io.DataInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static me.queue.util.MessageUtil.translateChars;

/** Receives the two writeUTF fields emitted by AuthMe 5.7's VelocitySender. */
public final class AuthMeGate implements Runnable {
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("authmevelocity:main");
    private final Main plugin;
    // Store the connection's Player instance, so an old session cannot authorize a reconnect.
    private final Map<UUID, Player> authenticated = new HashMap<>();

    public AuthMeGate(Main plugin) {
        this.plugin = plugin;
    }

    public synchronized boolean isAuthenticated(Player player) {
        return authenticated.get(player.getUniqueId()) == player;
    }

    public synchronized boolean requireLogin(Player player) {
        if (isAuthenticated(player)) return true;
        removeFromQueues(player);
        showLoginTitle(player);
        return false;
    }

    public synchronized boolean enqueue(Player player) {
        if (!requireLogin(player)) return false;
        if (plugin.getPrioQueue().isInQueue(player) || plugin.getNormalQueue().isInQueue(player)) return true;
        (player.hasPermission("queue.priority") ? plugin.getPrioQueue() : plugin.getNormalQueue()).addToQueue(player);
        return true;
    }

    public synchronized void forget(Player player) {
        if (authenticated.get(player.getUniqueId()) == player) authenticated.remove(player.getUniqueId());
        removeFromQueues(player);
    }

    private void removeFromQueues(Player player) {
        plugin.getPrioQueue().removeFromQueue(player);
        plugin.getNormalQueue().removeFromQueue(player);
    }

    @Subscribe(order = PostOrder.FIRST)
    public synchronized void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) return;
        // Reject invalid messages before other authentication plugins see them.
        boolean wasAllowed = event.getResult().isAllowed();
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection connection)) return;
        Player player = connection.getPlayer();
        if (event.getTarget() != player || connection.getServer() != plugin.getQueueServer()) return;
        if (!player.isActive() || plugin.getServer().getPlayer(player.getUniqueId()).orElse(null) != player) return;
        if (player.getCurrentServer().orElse(null) != connection) return;

        try (DataInputStream input = new DataInputStream(event.dataAsInputStream())) {
            String action = input.readUTF();
            String username = input.readUTF();
            if (!player.getUsername().equalsIgnoreCase(username) || input.available() != 0) return;
            if (!java.util.Set.of("LOGIN", "LOGOUT", "REGISTER", "UNREGISTER", "FORCE_UNREGISTER").contains(action)) return;
            switch (action) {
                case "LOGIN" -> {
                    // Repeated LOGIN packets must not put a player back after /leave.
                    if (!isAuthenticated(player)) {
                        authenticated.put(player.getUniqueId(), player);
                        player.clearTitle();
                        enqueue(player);
                    }
                }
                case "LOGOUT", "UNREGISTER", "FORCE_UNREGISTER" -> {
                    forget(player);
                    player.clearTitle();
                    showLoginTitle(player);
                }
                // REGISTER alone is not proof of login; wait for AuthMe's LOGIN event.
                default -> { }
            }
            // AuthMeVelocity 4.3.0 skips handled events. Let it consume validated
            // backend messages at NORMAL order, then prevent forwarding at LAST.
            // Never reopen an event another earlier handler has already consumed.
            if (wasAllowed) event.setResult(PluginMessageEvent.ForwardResult.forward());
        } catch (IOException ignored) {
            // Malformed input must never change authentication state.
        }
    }

    @Subscribe(order = PostOrder.LAST)
    public void consumeAuthMessage(PluginMessageEvent event) {
        if (CHANNEL.equals(event.getIdentifier())) event.setResult(PluginMessageEvent.ForwardResult.handled());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        forget(event.getPlayer());
    }

    public void showLoginTitle(Player player) {
        String title = plugin.getConfig().node("authme", "login-title").getString("&ePlease login");
        String subtitle = plugin.getConfig().node("authme", "login-subtitle").getString("&7Use /login to continue");
        player.showTitle(Title.title(translateChars(title), translateChars(subtitle),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ZERO)));
    }

    @Override
    public synchronized void run() {
        for (Player player : plugin.getServer().getAllPlayers()) {
            if (player.isActive() && !isAuthenticated(player)
                    && player.getCurrentServer().map(c -> c.getServer() == plugin.getQueueServer()).orElse(false)) {
                removeFromQueues(player);
                showLoginTitle(player);
            }
        }
    }
}
