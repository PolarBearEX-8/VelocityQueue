package me.queue.commands;


import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import lombok.RequiredArgsConstructor;
import me.queue.Main;
import me.queue.PlayerQueue;
import me.queue.Reloadable;

import java.util.List;
import java.util.Locale;

import static me.queue.util.MessageUtil.sendMessage;

@RequiredArgsConstructor
public class QueueCommand implements SimpleCommand {
    private final Main plugin;

    @Override
    public void execute(Invocation invocation) {
        String alias = invocation.alias().toLowerCase(Locale.ROOT);
        if (alias.equals("join")) {
            joinQueue(invocation.source());
            return;
        }
        if (alias.equals("leave")) {
            leaveQueue(invocation.source());
            return;
        }

        String[] args = invocation.arguments();
        if (args.length == 0) {
            joinQueue(invocation.source());
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "debug" -> debug(invocation.source(), args);
            case "leave" -> leaveQueue(invocation.source());
            case "reload" -> {
                if (!hasAdminPermission(invocation.source())) {
                    sendMessage(invocation.source(), "&cNo Permission");
                    return;
                }
                try {
                    plugin.loadConfig();
                    plugin.getReloadables().forEach(Reloadable::reloadConfig);
                    sendMessage(invocation.source(), "&b[&r&aQUEUE&b]&r&a Reloaded successfully!");
                } catch (Throwable t) {
                    sendMessage(invocation.source(), "&b[&r&aQUEUE&b]&r&c Failed to reload config, Please check the console for more details");
                    plugin.getLogger().atError().setCause(t).log("Failed to load config");
                }
            }
            case "version" -> {
                if (!hasAdminPermission(invocation.source())) {
                    sendMessage(invocation.source(), "&cNo Permission");
                    return;
                }
                sendMessage(invocation.source(), versionString());
            }
            default -> sendMessage(invocation.source(), "&b[&r&aQUEUE&b]&r&6 Usage: /queue, /join, /leave");
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!invocation.alias().equalsIgnoreCase("queue")) return List.of();
        if (hasAdminPermission(invocation.source())) return List.of("version", "reload", "debug");
        return List.of();
    }

    private void joinQueue(CommandSource source) {
        if (!(source instanceof Player player)) {
            sendMessage(source, "&cOnly players can join the queue.");
            return;
        }

        if (!plugin.getAuthMeGate().requireLogin(player)) return;

        if (isInQueue(player)) {
            sendMessage(player, configMessage("messages", "queue-already-in", "&cYou are already in the queue."));
            return;
        }

        RegisteredServer mainServer = plugin.getMainServer();
        if (mainServer != null && player.getCurrentServer()
                .map(connection -> connection.getServer().getServerInfo().getName().equals(mainServer.getServerInfo().getName()))
                .orElse(false)) {
            sendMessage(player, configMessage("messages", "server-not-full", "&aYou are already on the main server."));
            return;
        }

        RegisteredServer queueServer = plugin.getQueueServer();
        if (queueServer == null) {
            sendMessage(player, "&cQueue server is not configured.");
            return;
        }

        PlayerQueue queue = player.hasPermission("queue.priority") ? plugin.getPrioQueue() : plugin.getNormalQueue();
        if (!plugin.getAuthMeGate().enqueue(player)) return;
        int position = queue.getQueuePosition(player)
                + (queue == plugin.getNormalQueue() ? plugin.getPrioQueue().queueLength() : 0);
        sendMessage(player, configMessage("messages", "queue-joined", "&aYou joined the queue. Position: &l%d"), position);

        if (isConnectedTo(player, queueServer)) {
            return;
        }

        PlayerQueue.Entry entry = queue.getEntry(player);
        player.createConnectionRequest(queueServer).connect().whenComplete((result, throwable) -> {
            if (!player.isActive() || entry == null || queue.getEntry(player) != entry) return;
            if (throwable != null) {
                queue.removeEntry(entry);
                plugin.getLogger().atWarn().setCause(throwable).log("Failed to connect {} to queue server", player.getUsername());
                sendMessage(player, "&cFailed to connect to the queue server.");
                return;
            }
            if (!result.isSuccessful()) {
                queue.removeEntry(entry);
                sendMessage(player, "&cFailed to connect to the queue server.");
            }
        });
    }

    private void debug(CommandSource source, String[] args) {
        if (!hasAdminPermission(source)) {
            sendMessage(source, "&cNo Permission");
            return;
        }
        if (args.length != 2) {
            source.sendMessage(net.kyori.adventure.text.Component.text("Usage: /queue debug <player>"));
            return;
        }
        Player player = plugin.getServer().getPlayer(args[1]).orElse(null);
        if (player == null) {
            source.sendMessage(net.kyori.adventure.text.Component.text("Player is not online on this proxy."));
            return;
        }
        String current = player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("none");
        String login = plugin.getQueueServer() == null ? "NOT CONFIGURED" : plugin.getQueueServer().getServerInfo().getName();
        String main = plugin.getMainServer() == null ? "NOT CONFIGURED" : plugin.getMainServer().getServerInfo().getName();
        source.sendMessage(net.kyori.adventure.text.Component.text("[queue] " + player.getUsername()
                + ": current=" + current + ", login=" + login + ", main=" + main
                + ", authenticated=" + plugin.getAuthMeGate().isAuthenticated(player)));
        source.sendMessage(net.kyori.adventure.text.Component.text("[queue] priority-position="
                + plugin.getPrioQueue().getQueuePosition(player) + ", normal-position="
                + plugin.getNormalQueue().getQueuePosition(player) + " (-1=not queued), priority-count="
                + plugin.getPrioQueue().queueLength() + ", normal-count=" + plugin.getNormalQueue().queueLength()));
        source.sendMessage(net.kyori.adventure.text.Component.text("[queue] " + plugin.queueDiagnostic(player)));
    }

    private void leaveQueue(CommandSource source) {
        if (!(source instanceof Player player)) {
            sendMessage(source, "&cOnly players can leave the queue.");
            return;
        }

        if (!removeFromQueue(player)) {
            sendMessage(player, configMessage("messages", "queue-not-in", "&cYou are not in the queue."));
            return;
        }

        sendMessage(player, configMessage("messages", "queue-left", "&aYou left the queue."));
        player.clearTitle();
    }

    private void sendQueueStatus(CommandSource source) {
        if (!(source instanceof Player player)) {
            sendMessage(source, versionString());
            return;
        }

        int prioPosition = plugin.getPrioQueue().getQueuePosition(player);
        if (prioPosition != -1) {
            sendMessage(player, configMessage("messages", "queue-status", "&6Position in queue: &l%d"), prioPosition);
            return;
        }

        int normalPosition = plugin.getNormalQueue().getQueuePosition(player);
        if (normalPosition != -1) {
            sendMessage(player, configMessage("messages", "queue-status", "&6Position in queue: &l%d"), normalPosition);
            return;
        }

        sendMessage(player, configMessage("messages", "queue-not-in", "&cYou are not in the queue."));
    }

    private boolean isInQueue(Player player) {
        return plugin.getPrioQueue().isInQueue(player) || plugin.getNormalQueue().isInQueue(player);
    }

    private boolean removeFromQueue(Player player) {
        boolean removed = false;
        if (plugin.getPrioQueue().isInQueue(player)) {
            plugin.getPrioQueue().removeFromQueue(player);
            removed = true;
        }
        if (plugin.getNormalQueue().isInQueue(player)) {
            plugin.getNormalQueue().removeFromQueue(player);
            removed = true;
        }
        return removed;
    }

    private boolean isConnectedTo(Player player, RegisteredServer server) {
        return player.getCurrentServer()
                .map(connection -> connection.getServer().getServerInfo().getName().equals(server.getServerInfo().getName()))
                .orElse(false);
    }

    private boolean hasAdminPermission(CommandSource source) {
        return source.hasPermission("queue.admin");
    }

    private String configMessage(Object... pathAndDefault) {
        Object[] path = new Object[pathAndDefault.length - 1];
        System.arraycopy(pathAndDefault, 0, path, 0, path.length);
        String fallback = pathAndDefault[pathAndDefault.length - 1].toString();
        return plugin.getConfig().node(path).getString(fallback);
    }

    private String versionString() {
        return String.format("&b[&r&aQUEUE&b]&r&6 Version&r&a %s&r&6 by&r&a zeb.co", plugin.getClass().getAnnotation(Plugin.class).version());
    }
}
