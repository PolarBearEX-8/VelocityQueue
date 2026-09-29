package me.queue.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.server.ServerPing;
import me.queue.Main;
import me.queue.Reloadable;

import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static me.queue.util.MessageUtil.legacyTranslate;

public class ProxyPingListener implements Reloadable {
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("%(.*?)%");

    private final Main plugin;
    private List<String> playerList;
    private boolean enabled;
    private String versionName;
    private final Map<String, Supplier<Integer>> mappings;

    public ProxyPingListener(Main plugin) {
        this.plugin = plugin;
        mappings = new HashMap<>();
        mappings.put("%priority%", plugin.getPrioQueue()::queueLength);
        mappings.put("%regular%", plugin.getNormalQueue()::queueLength);
        mappings.put("%totalinqueue%", this::getQueueTotal);
        mappings.put("%maxplayers%", plugin::getMaxSlots);
        mappings.put("%online%", () -> plugin.getServer().getPlayerCount());
        reloadConfig();
    }

    @Subscribe(priority = 500)
    public void onProxyPing(ProxyPingEvent event) {
        if (!enabled) return;

        ServerPing og = event.getPing();
        int playerCount = plugin.getServer().getPlayerCount();
        int maxPlayers = og.getPlayers().map(ServerPing.Players::getMax).orElse(playerCount + 1);
        List<ServerPing.SamplePlayer> sampleList = genSampleList();

        String versionText = (versionName == null || versionName.isEmpty())
                ? og.getVersion().getName()
                : replacePlaceholders(versionName);

        ServerPing newPing = new ServerPing(
                new ServerPing.Version(og.getVersion().getProtocol(), legacyTranslate(versionText)),
                new ServerPing.Players(playerCount, maxPlayers, sampleList),
                og.getDescriptionComponent(),
                og.getFavicon().orElse(null),
                og.getModinfo().orElse(null));

        event.setPing(newPing);
    }

    @Override
    public void reloadConfig() {
        try {
            versionName = plugin.getConfig().node("custom-query", "protocol-message").getString();
            playerList = plugin.getConfig().node("custom-query", "query").getList(String.class);
            if (playerList == null) playerList = List.of();
            enabled = plugin.getConfig().node("custom-query", "enable").getBoolean(true);
        } catch (Throwable t) {
            plugin.getLogger().atError().setCause(t).log("Failed to load config. Please check stacktrace for more info");
        }
    }

    public int getQueueTotal() {
        return plugin.getPrioQueue().queueLength() + plugin.getNormalQueue().queueLength();
    }

    private List<ServerPing.SamplePlayer> genSampleList() {
        List<ServerPing.SamplePlayer> buf = new ArrayList<>();
        for (String raw : playerList) {
            raw = replacePlaceholders(raw);
            ServerPing.SamplePlayer sp = new ServerPing.SamplePlayer(legacyTranslate(raw), UUID.randomUUID());
            buf.add(sp);
        }
        return buf;
    }

    private String replacePlaceholders(String input) {
        if (input == null || input.isEmpty()) return "";
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(input);
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            String placeholder = matcher.group().toLowerCase(Locale.ROOT);
            Supplier<Integer> supplier = mappings.get(placeholder);
            if (supplier == null) continue;
            String value = supplier.get().toString();
            matcher.appendReplacement(output, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(output);
        return output.toString();
    }
}
