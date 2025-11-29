package com.github.caiostoduto.twig.listeners;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;

import com.github.caiostoduto.twig.Twig;
import com.github.caiostoduto.twig.auth.AuthenticationEntry;
import com.github.caiostoduto.twig.auth.PlayerIdentifier;
import com.github.caiostoduto.twig.config.ConfigManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;

/**
 * Handles player interactions while in the limbo server during authentication.
 */
public class LimboHandler {
    private static final Duration AUTH_MESSAGE_INTERVAL = Duration.ofSeconds(10);
    private static final int AUTH_LINK_COLOR = 0x5965F6;

    private final Logger logger;
    private final ProxyServer proxyServer;
    private final ConfigManager configManager;
    private final Map<PlayerIdentifier, AuthenticationEntry> authQueue;
    private final Twig plugin;

    public LimboHandler(final Twig plugin, final Logger logger, final ProxyServer proxyServer,
            final ConfigManager configManager, final Map<PlayerIdentifier, AuthenticationEntry> authQueue) {
        this.plugin = plugin;
        this.logger = logger;
        this.proxyServer = proxyServer;
        this.configManager = configManager;
        this.authQueue = authQueue;
    }

    @Subscribe
    public void onCommandExecute(final CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player)) {
            return;
        }

        final Player player = (Player) event.getCommandSource();
        final Optional<ServerConnection> currentServer = player.getCurrentServer();

        if (currentServer.isEmpty()) {
            return;
        }

        if (isPlayerOnLimboServer(currentServer.get())) {
            event.setResult(CommandExecuteEvent.CommandResult.denied());
            logger.info("{} ({}) command execution `{}` was denied because they are on the limbo server.",
                    player.getUsername(), player.getRemoteAddress(), event.getCommand());
        }
    }

    @Subscribe
    public void onPlayerAvailableCommands(final PlayerAvailableCommandsEvent event) {
        final Player player = event.getPlayer();
        final Optional<ServerConnection> currentServer = player.getCurrentServer();

        if (currentServer.isEmpty()) {
            return;
        }

        if (isPlayerOnLimboServer(currentServer.get())) {
            logger.info("{} ({}) had their available commands cleared because they are on the limbo server.",
                    player.getUsername(), player.getRemoteAddress());
            event.getRootNode().getChildren().clear();
        }
    }

    @Subscribe
    public void onServerConnected(final ServerConnectedEvent event) {
        if (!isPlayerOnLimboServer(event.getServer())) {
            return;
        }

        final Player player = event.getPlayer();
        final AuthenticationEntry authEntry = authQueue.get(new PlayerIdentifier(player));

        if (authEntry == null) {
            logger.warn("{} ({}) connected to limbo server but no authentication entry was found.",
                    player.getUsername(), player.getRemoteAddress());
            return;
        }

        authEntry.setTask(proxyServer.getScheduler().buildTask(plugin, () -> {
            sendAuthenticationMessage(player, authEntry.getUrl());
        }).repeat(AUTH_MESSAGE_INTERVAL).schedule());
    }

    @Subscribe
    public void onKickedFromServer(final KickedFromServerEvent event) {
        final RegisteredServer registeredServer = event.getServer();

        if (isPlayerOnLimboServer(registeredServer)) {
            final Player player = event.getPlayer();
            logger.warn("{} ({}) was disconnected because limbo server `{}` connection failed.",
                    player.getUsername(), player.getRemoteAddress(),
                    registeredServer.getServerInfo().getName());

            final Component message = Component
                    .text(configManager.getString("not_allowed_message", "You are not whitelisted on this server!"));
            player.disconnect(message);
        }
    }

    /**
     * Checks if the player is currently on the limbo server.
     *
     * @param server the server connection to check
     * @return true if the server is the limbo server, false otherwise
     */
    private boolean isPlayerOnLimboServer(final ServerConnection server) {
        final String limboServerName = configManager.getString("proxy_limbo");
        return server.getServerInfo().getName().equals(limboServerName);
    }

    /**
     * Checks if the given server is the limbo server.
     *
     * @param server the registered server to check
     * @return true if the server is the limbo server, false otherwise
     */
    private boolean isPlayerOnLimboServer(final RegisteredServer server) {
        final String limboServerName = configManager.getString("proxy_limbo");
        return server.getServerInfo().getName().equals(limboServerName);
    }

    /**
     * Sends an authentication message to the player with a clickable link.
     *
     * @param player  the player to send the message to
     * @param authUrl the authentication URL to include in the message
     */
    private void sendAuthenticationMessage(final Player player, final String authUrl) {
        final Component message = Component.text()
                .append(Component.text("Clique aqui").color(TextColor.color(AUTH_LINK_COLOR)))
                .append(Component.text(" para verificar sua conta."))
                .clickEvent(ClickEvent.openUrl(authUrl))
                .build();
        player.sendMessage(message);
    }
}
