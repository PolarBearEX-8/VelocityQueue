package me.queue.util;

public class Utils {
    public static String getFormattedInterval(long ms) {
        long minutes = ms / 60000L % 60L;
        long hours = ms / 3600000L % 24L;
        long days = ms / 86400000L;
        if (days > 0L) return String.format("%dd %02dh %02dm", days, hours, minutes);
        if (hours > 0L) return String.format("%dh %02dm", hours, minutes);
        return String.format("%dm", minutes);
    }
}
