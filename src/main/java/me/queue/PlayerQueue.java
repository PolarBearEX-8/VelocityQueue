package me.queue;

import com.velocitypowered.api.proxy.Player;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** FIFO with atomic membership changes and a fresh ticket for every queue entry. */
public class PlayerQueue {
    public record Entry(Player player) { }
    private final Map<UUID, Entry> players = new LinkedHashMap<>();

    public synchronized void addToQueue(Player player) {
        Entry existing = players.get(player.getUniqueId());
        if (existing != null && existing.player() == player) return;
        // A reconnect is a new session and joins at the end.
        players.remove(player.getUniqueId());
        players.put(player.getUniqueId(), new Entry(player));
    }

    public synchronized Entry getEntry(Player player) {
        Entry entry = players.get(player.getUniqueId());
        return entry != null && entry.player() == player ? entry : null;
    }

    public synchronized int getQueuePosition(Player player) {
        int position = 1;
        for (Entry entry : players.values()) {
            if (entry.player() == player) return position;
            position++;
        }
        return -1;
    }

    public synchronized void removeFromQueue(Player player) {
        if (getEntry(player) != null) players.remove(player.getUniqueId());
    }

    public synchronized void removeEntry(Entry entry) {
        if (getEntry(entry.player()) == entry) players.remove(entry.player().getUniqueId());
    }

    public synchronized boolean isInQueue(Player player) {
        return getEntry(player) != null;
    }

    public synchronized Collection<Player> getPlayersInQueue() {
        return players.values().stream().map(Entry::player).toList();
    }

    public synchronized int queueLength() {
        return players.size();
    }
}
