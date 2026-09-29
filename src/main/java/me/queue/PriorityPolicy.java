package me.queue;

import com.velocitypowered.api.proxy.Player;

/** Shared rules for automatic transfers and direct connection requests. */
public final class PriorityPolicy {
    private PriorityPolicy() { }

    public static boolean bypassesQueue(Main plugin, Player player) {
        return player.hasPermission("queue.priority")
                && "bypass".equalsIgnoreCase(plugin.getConfig().node("priority", "mode").getString("queue"));
    }

    public static boolean bypassesPlayerLimit(Main plugin, Player player) {
        return bypassesQueue(plugin, player)
                && plugin.getConfig().node("priority", "bypass-player-limit").getBoolean(false);
    }
}
