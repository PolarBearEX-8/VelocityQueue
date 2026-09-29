package me.queue.workers;

import com.velocitypowered.api.proxy.Player;
import me.queue.Main;
import me.queue.PlayerQueue;
import me.queue.PriorityPolicy;
import me.queue.Reloadable;
import me.queue.util.Utils;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static me.queue.util.MessageUtil.sendMessage;
import static me.queue.util.MessageUtil.translateChars;

public class QueueWorker implements Runnable, Reloadable {
    private final Main plugin;
    private final PlayerQueue normalQueue;
    private final PlayerQueue prioQueue;
    private String queueEndMessage;
    private List<String> tabHeader;
    private Component prioFooter;
    private Component normalFooter;
    private record Attempt(PlayerQueue queue, PlayerQueue.Entry entry) { }
    private record Retry(Attempt attempt, long deadline) { }
    private final Map<UUID, Attempt> connectingPlayers;
    private final Map<Player, Integer> displayedPositions = new ConcurrentHashMap<>();
    private final Map<UUID, Retry> retryAfter;
    private final LongSupplier nanoTime;
    private long antiFloodNanos;
    private int playersPerInterval;
    private final Deque<Long> transferTimes = new ArrayDeque<>();

    public QueueWorker(Main plugin) {
        this(plugin, System::nanoTime);
    }

    QueueWorker(Main plugin, LongSupplier nanoTime) {
        this.plugin = plugin;
        this.nanoTime = nanoTime;
        prioQueue = plugin.getPrioQueue();
        normalQueue = plugin.getNormalQueue();
        connectingPlayers = new ConcurrentHashMap<>();
        retryAfter = new ConcurrentHashMap<>();
        reloadConfig();
    }

    @Override
    public synchronized void run() {
        // Clean up before reserving slots or calculating any displayed positions.
        for (Player player : prioQueue.getPlayersInQueue()) ignorePlayer(player, prioQueue);
        for (Player player : normalQueue.getPlayersInQueue()) ignorePlayer(player, normalQueue);
        connectingPlayers.entrySet().removeIf(e -> !isCurrent(e.getValue()));
        retryAfter.entrySet().removeIf(e -> !isCurrent(e.getValue().attempt()));
        displayedPositions.keySet().removeIf(p -> !prioQueue.isInQueue(p) && !normalQueue.isInQueue(p));
        int available = plugin.getMainServerAvailableSlots();
        Main.MainServerStatus serverStatus = available < 0 ? Main.MainServerStatus.RESTARTING
                : available == 0 ? Main.MainServerStatus.FULL : Main.MainServerStatus.HAS_SLOT;
        // Reserve slots for outstanding requests; ping may not count them yet.
        int remaining = Math.max(0, available - connectingPlayers.size());
        boolean priorityBlocked = false;
        for (Player player : prioQueue.getPlayersInQueue()) {
            if (ignorePlayer(player, prioQueue)) continue;
            if (!plugin.getAuthMeGate().requireLogin(player)) continue;
            int queuePos = prioQueue.getQueuePosition(player);
            if (queuePos < 1) continue;
            updatePosition(player, queuePos);
            player.sendPlayerListHeaderAndFooter(parseHeader(queuePos, serverStatus), prioFooter);
            if (isConnecting(player)) continue;
            boolean bypass = PriorityPolicy.bypassesQueue(plugin, player);
            if (priorityBlocked && !bypass) continue;
            if (available >= 0 && (remaining > 0 || PriorityPolicy.bypassesPlayerLimit(plugin, player))
                    && connectQueuedPlayer(player, prioQueue, bypass)) remaining--;
            else if (!bypass) priorityBlocked = true;
        }
        boolean normalBlocked = priorityBlocked;
        for (Player player : normalQueue.getPlayersInQueue()) {
            if (ignorePlayer(player, normalQueue)) continue;
            if (!plugin.getAuthMeGate().requireLogin(player)) continue;
            int localPos = normalQueue.getQueuePosition(player);
            if (localPos < 1) continue;
            int queuePos = localPos + prioQueue.queueLength();
            updatePosition(player, queuePos);
            player.sendPlayerListHeaderAndFooter(parseHeader(queuePos, serverStatus), normalFooter);
            if (isConnecting(player) || normalBlocked) continue;
            if (remaining > 0 && connectQueuedPlayer(player, normalQueue, false)) remaining--;
            else normalBlocked = true;
        }
    }

    public boolean isConnecting(Player player) {
        Attempt attempt = connectingPlayers.get(player.getUniqueId());
        return attempt != null && attempt.entry().player() == player && isCurrent(attempt);
    }

    public synchronized String diagnostic(Player player) {
        long now = nanoTime.getAsLong();
        Retry retry = retryAfter.get(player.getUniqueId());
        long retrySeconds = retry != null && isCurrent(retry.attempt())
                ? Math.max(0, (long) Math.ceil((retry.deadline() - now) / 1_000_000_000.0)) : 0;
        long used = transferTimes.stream().filter(t -> now - t < antiFloodNanos).count();
        return "connecting=" + isConnecting(player) + ", pending=" + connectingPlayers.size()
                + ", retry-seconds=" + retrySeconds + ", antiflood-used=" + used + "/" + playersPerInterval;
    }

    @Override
    public synchronized void reloadConfig() {
        try {
            // Accept the old scalar AntiFlood value until the operator updates their config.
            var antiFlood = plugin.getConfig().node("AntiFlood");
            double legacySeconds = antiFlood.isMap() ? 1 : antiFlood.getDouble(1);
            double seconds = plugin.getConfig().node("AntiFlood", "interval-seconds").getDouble(legacySeconds);
            if (!Double.isFinite(seconds)) seconds = 1;
            antiFloodNanos = (long) Math.ceil(Math.max(1, seconds) * TimeUnit.SECONDS.toNanos(1));
            playersPerInterval = Math.max(1, plugin.getConfig().node("AntiFlood", "players-per-interval").getInt(1));
            queueEndMessage = plugin.getConfig().node("messages", "queue-end").getString("");
            List<String> headerList = plugin.getConfig().node("tablist", "header").getList(String.class);
            tabHeader = (headerList != null) ? headerList : new ArrayList<>();
            
            List<String> prioFooterList = plugin.getConfig().node("tablist", "priority-queue-footer").getList(String.class);
            prioFooter = parseFooter(prioFooterList != null ? prioFooterList : new ArrayList<>());
            
            List<String> normalFooterList = plugin.getConfig().node("tablist", "normal-queue-footer").getList(String.class);
            normalFooter = parseFooter(normalFooterList != null ? normalFooterList : new ArrayList<>());
        } catch (Throwable t) {
            plugin.getLogger().atError().setCause(t).log("Failed to load config. Please check stacktrace for more info");
        }
    }

    private boolean isCurrent(Attempt attempt) {
        return attempt.entry().player().isActive()
                && attempt.queue().getEntry(attempt.entry().player()) == attempt.entry();
    }

    private void updatePosition(Player player, int position) {
        Integer previous = displayedPositions.put(player, position);
        if (previous == null || previous != position) {
            me.queue.util.MessageUtil.sendTitle(player, "",
                    plugin.getConfig().node("messages", "title-position").getString("&6Position in queue: &l%d"), position);
        }
    }

    private boolean connectQueuedPlayer(Player player, PlayerQueue queue, boolean bypass) {
        if (ignorePlayer(player, queue)) return false;
        if (!plugin.getAuthMeGate().requireLogin(player) || plugin.getMainServer() == null) return false;
        PlayerQueue.Entry entry = queue.getEntry(player);
        if (entry == null) return false;
        long now = nanoTime.getAsLong();
        Retry retry = retryAfter.get(player.getUniqueId());
        if (retry != null && retry.attempt().entry() == entry && now - retry.deadline() < 0) return false;
        while (!transferTimes.isEmpty() && now - transferTimes.peekFirst() >= antiFloodNanos) {
            transferTimes.removeFirst();
        }
        if (!bypass && transferTimes.size() >= playersPerInterval) return false;
        Attempt attempt = new Attempt(queue, entry);
        if (connectingPlayers.putIfAbsent(player.getUniqueId(), attempt) != null) return false;
        if (!bypass) transferTimes.addLast(now);
        try {
            sendMessage(player, queueEndMessage);
            player.createConnectionRequest(plugin.getMainServer()).connect()
                    .whenComplete((result, error) -> finish(attempt, result, error));
        } catch (RuntimeException e) {
            finish(attempt, null, e);
        }
        return true;
    }

    private synchronized void finish(Attempt attempt,
            com.velocitypowered.api.proxy.ConnectionRequestBuilder.Result result, Throwable error) {
        Player player = attempt.entry().player();
        UUID id = player.getUniqueId();
        // Identity, not UUID alone: an old completion must not clear a newer request.
        if (connectingPlayers.get(id) != attempt) return;
        connectingPlayers.remove(id);
        if (error == null && result != null && result.isSuccessful()) {
            PlayerQueue.Entry current = attempt.queue().getEntry(player);
            if (!player.isActive() || (current != null && current != attempt.entry())
                    || (current == null && !isConnectedToMain(player))) return;
            attempt.queue().removeEntry(attempt.entry());
            player.clearTitle();
            if (attempt.queue() == prioQueue) plugin.recordPriorityQueueSkip();
            retryAfter.remove(id);
            return;
        }
        if (ignorePlayer(player, attempt.queue()) || !isCurrent(attempt)) return;
        long delay = 30;
        if (error != null) {
            plugin.getLogger().atWarn().setCause(error).log("Failed to connect {} from queue", player.getUsername());
        } else if (result != null) {
            switch (result.getStatus()) {
                case CONNECTION_CANCELLED, CONNECTION_IN_PROGRESS -> delay = 3;
                case ALREADY_CONNECTED -> {
                    attempt.queue().removeEntry(attempt.entry());
                    retryAfter.remove(id);
                    return;
                }
                case SERVER_DISCONNECTED -> plugin.getLogger().warn(
                        "Failed to connect {} from queue: status={}, reason={}", player.getUsername(),
                        result.getStatus(), result.getReasonComponent().map(Component::toString).orElse("no reason component"));
                default -> delay = 10;
            }
        }
        retryAfter.put(id, new Retry(attempt, nanoTime.getAsLong() + TimeUnit.SECONDS.toNanos(delay)));
    }

    private boolean ignorePlayer(Player player, PlayerQueue queue) {
        boolean onMain = isConnectedToMain(player);
        boolean inactive = !player.isActive();
        if (onMain || inactive) {
            normalQueue.removeFromQueue(player);
            prioQueue.removeFromQueue(player);
        }
        if (onMain || inactive || !queue.isInQueue(player)) {
            retryAfter.remove(player.getUniqueId());
            return true;
        }
        return false;
    }

    private boolean isConnectedToMain(Player player) {
        return plugin.getMainServer() != null && player.getCurrentServer()
                .map(connection -> connection.getServer().getServerInfo().getName().equals(plugin.getMainServer().getServerInfo().getName()))
                .orElse(false);
    }

    private Component parseHeader(int posInQueue, Main.MainServerStatus serverStatus) {
        List<String> processed = new ArrayList<>();
        String status = switch (serverStatus) {
            case HAS_SLOT -> plugin.getConfig().node("messages", "server-not-full").getString("");
            case FULL -> plugin.getConfig().node("messages", "server-full").getString("");
            case RESTARTING -> plugin.getConfig().node("messages", "server-restarting").getString("&6Server restarting");
        };
        for (String line : tabHeader) {
            String raw = line.replace("%position%", String.valueOf(posInQueue));
            raw = raw.replace("%wait%", Utils.getFormattedInterval(posInQueue * 60000L));
            raw = raw.replace("%online%", String.valueOf(plugin.getServer().getPlayerCount()));
            raw = raw.replace("%status%", status);
            processed.add(raw);
        }
        return translateChars(String.join("\n", processed));
    }

    private Component parseFooter(List<String> input) {
        String raw = String.join("\n", input);
        return translateChars(raw);
    }
}
