package me.queue.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.queue.Main;
import me.queue.PlayerQueue;
import me.queue.PriorityPolicy;
import me.queue.Reloadable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static me.queue.util.MessageUtil.sendMessage;

public class PreConnectListener implements Reloadable {
    private final Main plugin;
    private final PlayerQueue normalQueue;
    private final PlayerQueue prioQueue;
    private final Set<UUID> serverNotFullNotified;
    private String serverFullMessage;
    private String serverNotFullMessage;

    public PreConnectListener(Main plugin) {
        this.plugin = plugin;
        normalQueue = plugin.getNormalQueue();
        prioQueue = plugin.getPrioQueue();
        serverNotFullNotified = ConcurrentHashMap.newKeySet();
        reloadConfig();
    }

    @Subscribe(order = PostOrder.LAST)
    public void onPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        RegisteredServer server = event.getResult().getServer().orElse(null);
        if (server == null) return;
        RegisteredServer mainServer = plugin.getMainServer();
        RegisteredServer queueServer = plugin.getQueueServer();
        // Returning to the AuthMe backend starts a fresh authentication check.
        if (server == queueServer && player.getCurrentServer()
                .map(c -> c.getServer() != queueServer).orElse(false)) {
            plugin.getAuthMeGate().forget(player);
        }
        if (!plugin.getAuthMeGate().isAuthenticated(player)) {
            if (server != queueServer || queueServer == null) {
                boolean alreadyOnQueue = player.getCurrentServer().map(c -> c.getServer() == queueServer).orElse(false);
                event.setResult(queueServer == null || alreadyOnQueue
                        ? ServerPreConnectEvent.ServerResult.denied()
                        : ServerPreConnectEvent.ServerResult.allowed(queueServer));
            }
            plugin.getAuthMeGate().requireLogin(player);
            return;
        }
        if (mainServer == null || queueServer == null) return;
        if (player.hasPermission("queue.bypass")) return;
        
        if (server.getServerInfo().getName().equals(mainServer.getServerInfo().getName())) {
            if (PriorityPolicy.bypassesPlayerLimit(plugin, player)) return;
            if (!plugin.isQueueTransfer(player) && !PriorityPolicy.bypassesQueue(plugin, player)) {
                addToQueue(player);
                boolean alreadyOnQueue = player.getCurrentServer().map(c -> c.getServer() == queueServer).orElse(false);
                event.setResult(alreadyOnQueue ? ServerPreConnectEvent.ServerResult.denied()
                        : ServerPreConnectEvent.ServerResult.allowed(queueServer));
                return;
            }
            if (!plugin.doesServerHaveSlot()) {
                sendMessage(player, serverFullMessage);
                addToQueue(player);
                event.setResult(ServerPreConnectEvent.ServerResult.allowed(queueServer));
            } else {
                sendServerNotFullMessage(player);
            }
        }
    }

    @Subscribe
    public void onServerConnect(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        RegisteredServer server = event.getServer();
        RegisteredServer queueServer = plugin.getQueueServer();
        RegisteredServer mainServer = plugin.getMainServer();
        if (mainServer != null && server.getServerInfo().getName().equals(mainServer.getServerInfo().getName())) {
            plugin.invalidateMainServerStatus();
            removeFromQueue(player);
            player.clearTitle();
            return;
        }
        if (queueServer != null && server.getServerInfo().getName().equals(queueServer.getServerInfo().getName())) {
            addToQueue(player);
        } else {
            removeFromQueue(player);
            player.clearTitle();
        }
    }

    @Subscribe
    public void onDisconnect(com.velocitypowered.api.event.connection.DisconnectEvent event) {
        serverNotFullNotified.remove(event.getPlayer().getUniqueId());
    }

    private void addToQueue(Player player) {
        plugin.getAuthMeGate().enqueue(player);
    }

    private void removeFromQueue(Player player) {
        if (prioQueue.isInQueue(player)) prioQueue.removeFromQueue(player);
        if (normalQueue.isInQueue(player)) normalQueue.removeFromQueue(player);
    }

    private void sendServerNotFullMessage(Player player) {
        if (serverNotFullNotified.add(player.getUniqueId())) {
            sendMessage(player, serverNotFullMessage);
        }
    }

    @Override
    public void reloadConfig() {
        try {
            serverFullMessage = plugin.getConfig().node("messages", "server-full").getString("");
            serverNotFullMessage = plugin.getConfig().node("messages", "server-not-full").getString("");
        } catch (Throwable t) {
            plugin.getLogger().atError().setCause(t).log("Failed to load config. Please check stacktrace for more info");
        }
    }
}
