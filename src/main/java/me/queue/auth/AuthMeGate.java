package me.queue.auth;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import me.queue.Main;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

import java.io.DataInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import static me.queue.util.MessageUtil.translateChars;

/** Receives the two writeUTF fields emitted by AuthMe 5.7's VelocitySender. */
public final class AuthMeGate implements Runnable {
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("authmevelocity:main");
    public static final MinecraftChannelIdentifier ACCOUNT_CHANNEL = MinecraftChannelIdentifier.from("queue:account");
    private enum RegistrationStage { UNKNOWN, REGISTERED, UNREGISTERED }
    private record AccountStatus(Player player, RegistrationStage stage, long readyAtNanos) { }
    private record ShownLoginTitle(Player player, Component title, Component subtitle, long shownAtNanos) { }

    private final Main plugin;
    // Store the connection's Player instance, so an old session cannot authorize a reconnect.
    private final Map<UUID, Player> authenticated = new HashMap<>();
    private final Map<UUID, AccountStatus> accountStatuses = new HashMap<>();
    private final Map<UUID, ShownLoginTitle> shownLoginTitles = new HashMap<>();

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
        AccountStatus status = accountStatuses.get(player.getUniqueId());
        if (status != null && status.player() == player) {
            accountStatuses.remove(player.getUniqueId());
        }
        shownLoginTitles.remove(player.getUniqueId());
        removeFromQueues(player);
    }

    private void removeFromQueues(Player player) {
        plugin.getPrioQueue().removeFromQueue(player);
        plugin.getNormalQueue().removeFromQueue(player);
    }

    @Subscribe(order = PostOrder.FIRST)
    public synchronized void onPluginMessage(PluginMessageEvent event) {
        if (ACCOUNT_CHANNEL.equals(event.getIdentifier())) {
            onAccountMessage(event);
            return;
        }
        if (!CHANNEL.equals(event.getIdentifier())) return;
        // Reject invalid messages before other authentication plugins see them.
        boolean wasAllowed = event.getResult().isAllowed();
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        Player player = trustedBackendPlayer(event);
        if (player == null) return;

        try (DataInputStream input = new DataInputStream(event.dataAsInputStream())) {
            String action = input.readUTF();
            String username = input.readUTF();
            if (!player.getUsername().equalsIgnoreCase(username) || input.available() != 0) return;
            if (!java.util.Set.of("LOGIN", "LOGOUT", "REGISTER", "UNREGISTER", "FORCE_UNREGISTER").contains(action)) return;
            switch (action) {
                case "LOGIN" -> {
                    // Repeated LOGIN packets must not put a player back after /leave.
                    setRegistrationStage(player, RegistrationStage.REGISTERED);
                    if (!isAuthenticated(player)) {
                        authenticated.put(player.getUniqueId(), player);
                        fadeLoginTitle(player);
                        enqueue(player);
                    }
                }
                case "REGISTER" -> {
                    setRegistrationStage(player, RegistrationStage.REGISTERED);
                    if (!isAuthenticated(player)) showLoginTitle(player);
                }
                case "LOGOUT" -> {
                    unauthenticate(player);
                    showLoginTitle(player);
                }
                case "UNREGISTER", "FORCE_UNREGISTER" -> {
                    unauthenticate(player);
                    setRegistrationStage(player, RegistrationStage.UNREGISTERED);
                    showLoginTitle(player);
                }
                default -> { } // REGISTER alone does not authorize queue access.
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
        if (CHANNEL.equals(event.getIdentifier()) || ACCOUNT_CHANNEL.equals(event.getIdentifier())) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
        }
    }

    private void onAccountMessage(PluginMessageEvent event) {
        boolean wasAllowed = event.getResult().isAllowed();
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!wasAllowed) return;
        Player player = trustedBackendPlayer(event);
        if (player == null) return;
        try (DataInputStream input = new DataInputStream(event.dataAsInputStream())) {
            String action = input.readUTF();
            String username = input.readUTF();
            if (!player.getUsername().equalsIgnoreCase(username) || input.available() != 0) return;
            if (action.equals("REGISTERED")) {
                setCheckedRegistrationStage(player, RegistrationStage.REGISTERED);
                if (!isAuthenticated(player)) showLoginTitle(player);
            } else if (action.equals("UNREGISTERED")) {
                unauthenticate(player);
                setCheckedRegistrationStage(player, RegistrationStage.UNREGISTERED);
                showLoginTitle(player);
            }
        } catch (IOException ignored) {
            // Malformed account state must never change login or queue state.
        }
    }

    private Player trustedBackendPlayer(PluginMessageEvent event) {
        if (!(event.getSource() instanceof ServerConnection connection)) return null;
        Player player = connection.getPlayer();
        if (event.getTarget() != player || connection.getServer() != plugin.getQueueServer()) return null;
        if (!player.isActive() || plugin.getServer().getPlayer(player.getUniqueId()).orElse(null) != player) return null;
        return player.getCurrentServer().orElse(null) == connection ? player : null;
    }

    private void setRegistrationStage(Player player, RegistrationStage stage) {
        setRegistrationStage(player, stage, 0);
    }

    private void setCheckedRegistrationStage(Player player, RegistrationStage stage) {
        long delayMillis = configuredMillis("account-check-delay-ms", 500, 60_000);
        setRegistrationStage(player, stage, delayMillis);
    }

    private long configuredMillis(String key, long fallback, long max) {
        return Math.max(0, Math.min(max, plugin.getConfig().node("authme", key).getLong(fallback)));
    }

    private void setRegistrationStage(Player player, RegistrationStage stage, long delayMillis) {
        AccountStatus current = accountStatuses.get(player.getUniqueId());
        if (current != null && current.player() == player && current.stage() == stage) {
            if (delayMillis > 0 || System.nanoTime() - current.readyAtNanos() >= 0) return;
        }
        long readyAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
        accountStatuses.put(player.getUniqueId(), new AccountStatus(player, stage, readyAt));
    }

    private RegistrationStage getRegistrationStage(Player player) {
        AccountStatus status = accountStatuses.get(player.getUniqueId());
        return status != null && status.player() == player
                && System.nanoTime() - status.readyAtNanos() >= 0 ? status.stage() : RegistrationStage.UNKNOWN;
    }

    private void unauthenticate(Player player) {
        if (authenticated.get(player.getUniqueId()) == player) authenticated.remove(player.getUniqueId());
        removeFromQueues(player);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        forget(event.getPlayer());
    }

    public synchronized void showLoginTitle(Player player) {
        String title;
        String subtitle;
        switch (getRegistrationStage(player)) {
            case REGISTERED -> {
                title = plugin.getConfig().node("authme", "login-title").getString("&ePlease login");
                subtitle = plugin.getConfig().node("authme", "login-subtitle").getString("&7/login <password>");
            }
            case UNREGISTERED -> {
                title = plugin.getConfig().node("authme", "register-title").getString("&ePlease register");
                subtitle = plugin.getConfig().node("authme", "register-subtitle")
                        .getString("&7/register <create your password> <re-type your password>");
            }
            default -> {
                title = plugin.getConfig().node("authme", "checking-title").getString("&eChecking account");
                subtitle = plugin.getConfig().node("authme", "checking-subtitle").getString("&7Please wait");
            }
        }
        Component titleComponent = translateChars(title);
        Component subtitleComponent = translateChars(subtitle);
        long now = System.nanoTime();
        ShownLoginTitle previous = shownLoginTitles.get(player.getUniqueId());
        long stayMillis = Math.max(1_000, configuredMillis("prompt-stay-ms", 3_600_000, 86_400_000));
        if (previous != null && previous.player() == player && previous.title().equals(titleComponent)
                && previous.subtitle().equals(subtitleComponent)
                && now - previous.shownAtNanos() < TimeUnit.MILLISECONDS.toNanos(stayMillis)) return;
        shownLoginTitles.put(player.getUniqueId(), new ShownLoginTitle(player, titleComponent, subtitleComponent, now));
        long fadeInMillis = configuredMillis("prompt-fade-in-ms", 500, 10_000);
        player.showTitle(Title.title(titleComponent, subtitleComponent,
                Title.Times.times(Duration.ofMillis(fadeInMillis), Duration.ofMillis(stayMillis), Duration.ZERO)));
    }

    private void fadeLoginTitle(Player player) {
        ShownLoginTitle shown = shownLoginTitles.remove(player.getUniqueId());
        if (shown == null || shown.player() != player) {
            player.clearTitle();
            return;
        }
        long fadeMillis = configuredMillis("login-fade-out-ms", 600, 10_000);
        if (fadeMillis == 0) {
            player.clearTitle();
            return;
        }
        player.showTitle(Title.title(shown.title(), shown.subtitle(),
                Title.Times.times(Duration.ZERO, Duration.ZERO, Duration.ofMillis(fadeMillis))));
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
