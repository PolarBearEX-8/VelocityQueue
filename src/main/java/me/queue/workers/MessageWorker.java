package me.queue.workers;

import me.queue.Main;
import me.queue.PlayerQueue;
import me.queue.Reloadable;
import me.queue.util.Utils;

import static me.queue.util.MessageUtil.*;

public class MessageWorker implements Runnable, Reloadable {
    private final Main plugin;
    private final PlayerQueue prioQueue;
    private final PlayerQueue normalQueue;
    private String queuePositionMessage;
    private String titlePositionMessage;
    private String actionBarPriorityWaitMessage;
    private String actionBarSkippedLastHourMessage;
    private String actionBarNonPriorityWaitMessage;
    private int actionBarMessageIndex;

    public MessageWorker(Main plugin) {
        this.plugin = plugin;
        prioQueue = plugin.getPrioQueue();
        normalQueue = plugin.getNormalQueue();
        reloadConfig();
    }

    @Override
    public void run() {
        String actionBarMessage = parseActionBarMessage();
        prioQueue.getPlayersInQueue().forEach(p -> {
            if (!p.isActive() || !plugin.getAuthMeGate().isAuthenticated(p)) return;
            int pos = prioQueue.getQueuePosition(p);
            if (pos < 1) return;
            sendMessage(p, queuePositionMessage, pos);
            sendActionBar(p, actionBarMessage);
            sendTitle(p, "", titlePositionMessage, pos);
        });
        normalQueue.getPlayersInQueue().forEach(p -> {
            if (!p.isActive() || !plugin.getAuthMeGate().isAuthenticated(p)) return;
            int localPos = normalQueue.getQueuePosition(p);
            if (localPos < 1) return;
            int pos = localPos + prioQueue.queueLength();
            sendMessage(p, queuePositionMessage, pos);
            sendActionBar(p, actionBarMessage);
            sendTitle(p, "", titlePositionMessage, pos);
        });
    }

    @Override
    public void reloadConfig() {
        queuePositionMessage = plugin.getConfig().node("messages", "queue-position").getString("");
        titlePositionMessage = plugin.getConfig().node("messages", "title-position")
                .getString("&6Position in queue: &l%d");
        actionBarPriorityWaitMessage = plugin.getConfig().node("messages", "action-bar-priority-wait")
                .getString("&6Priority Queue Estimated Wait: &l%priority_wait%");
        actionBarSkippedLastHourMessage = plugin.getConfig().node("messages", "action-bar-skipped-last-hour")
                .getString("&6Player who have skipped the queue in the last hour: &l%skipped_last_hour%");
        actionBarNonPriorityWaitMessage = plugin.getConfig().node("messages", "action-bar-non-priority-wait")
                .getString("&6Non-Priority Queue Estimated Wait: &l%non_priority_wait%");
    }

    private String parseActionBarMessage() {
        long priorityWait = prioQueue.queueLength() * 60000L;
        long nonPriorityWait = (prioQueue.queueLength() + normalQueue.queueLength()) * 60000L;
        String raw = switch (actionBarMessageIndex++ % 3) {
            case 0 -> actionBarPriorityWaitMessage;
            case 1 -> actionBarSkippedLastHourMessage;
            default -> actionBarNonPriorityWaitMessage;
        };
        return raw.replace("%priority_wait%", Utils.getFormattedInterval(priorityWait))
                .replace("%non_priority_wait%", Utils.getFormattedInterval(nonPriorityWait))
                .replace("%skipped_last_hour%", String.valueOf(plugin.getPriorityQueueSkipsLastHour()));
    }
}
