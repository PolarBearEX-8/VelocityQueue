package me.queue.util;

import com.velocitypowered.api.command.CommandSource;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;

import java.time.Duration;

public final class MessageUtil {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private MessageUtil() {
    }

    public static Component translateChars(String input) {
        if (input == null) return Component.empty();
        return MINI_MESSAGE.deserialize(convertToMiniMessageFormat(input));
    }

    public static String convertToMiniMessageFormat(String input) {
        input = input.replaceAll("&#([A-Fa-f0-9]{6})", "<reset><#$1>");

        input = input.replace("&l", "<bold>");
        input = input.replace("&o", "<italic>");
        input = input.replace("&n", "<underlined>");
        input = input.replace("&m", "<strikethrough>");
        input = input.replace("&k", "<obfuscated>");
        input = input.replace("&r", "<reset>");
        input = input.replace("&0", "<reset><black>");
        input = input.replace("&1", "<reset><dark_blue>");
        input = input.replace("&2", "<reset><dark_green>");
        input = input.replace("&3", "<reset><dark_aqua>");
        input = input.replace("&4", "<reset><dark_red>");
        input = input.replace("&5", "<reset><dark_purple>");
        input = input.replace("&6", "<reset><gold>");
        input = input.replace("&7", "<reset><gray>");
        input = input.replace("&8", "<reset><dark_gray>");
        input = input.replace("&9", "<reset><blue>");
        input = input.replace("&a", "<reset><green>");
        input = input.replace("&b", "<reset><aqua>");
        input = input.replace("&c", "<reset><red>");
        input = input.replace("&d", "<reset><light_purple>");
        input = input.replace("&e", "<reset><yellow>");
        input = input.replace("&f", "<reset><white>");

        return input;
    }

    public static void sendMessage(CommandSource player, String message, Object... args) {
        if (message == null || message.isEmpty()) return;
        player.sendMessage(translateChars(String.format(message, args)));
    }

    public static void sendActionBar(com.velocitypowered.api.proxy.Player player, String message, Object... args) {
        if (message == null || message.isEmpty()) return;
        player.sendActionBar(translateChars(String.format(message, args)));
    }

    public static void sendTitle(com.velocitypowered.api.proxy.Player player, String title, String subtitle, Object... args) {
        if ((title == null || title.isEmpty()) && (subtitle == null || subtitle.isEmpty())) return;
        Title titleObj = Title.title(
                translateChars(formatOrEmpty(title, args)),
                translateChars(formatOrEmpty(subtitle, args)),
                Title.Times.times(Duration.ZERO, Duration.ofHours(1), Duration.ZERO)
        );
        player.showTitle(titleObj);
    }

    public static String legacyTranslate(String input) {
        if (input == null) return "";
        return input.replace('&', '\u00A7');
    }

    private static String formatOrEmpty(String input, Object... args) {
        if (input == null || input.isEmpty()) return "";
        return String.format(input, args);
    }
}
