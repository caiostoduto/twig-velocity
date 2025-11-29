package com.github.caiostoduto.twig.listeners;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;

import com.github.caiostoduto.twig.auth.AuthenticationEntry;
import com.github.caiostoduto.twig.auth.PlayerIdentifier;
import com.github.caiostoduto.twig.config.ConfigManager;
import com.github.caiostoduto.twig.grpc.MinecraftBridgeClient;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent.ServerResult;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import minecraft_bridge.MinecraftBridgeOuterClass.AccessStatus;
import minecraft_bridge.MinecraftBridgeOuterClass.PlayerAccessResponse;
import net.kyori.adventure.text.Component;

public class AuthenticationLoginHandler {
    private static final String CONFIG_TWIG_UUID = "twig_uuid";
    private static final String CONFIG_PROXY_LIMBO = "proxy_limbo";
    private static final String CONFIG_NOT_ALLOWED_MESSAGE = "not_allowed_message";
    private static final String DEFAULT_NOT_ALLOWED_MESSAGE = "You are not whitelisted on this server!";

    private final Logger logger;
    private final ProxyServer proxyServer;
    private final ConfigManager configManager;
    private final MinecraftBridgeClient grpcClient;
    private final Map<PlayerIdentifier, AuthenticationEntry> authQueue;

    public AuthenticationLoginHandler(final Logger logger, final ProxyServer proxyServer,
            final ConfigManager configManager, final MinecraftBridgeClient grpcClient,
            final Map<PlayerIdentifier, AuthenticationEntry> authQueue) {
        this.logger = logger;
        this.proxyServer = proxyServer;
        this.configManager = configManager;
        this.grpcClient = grpcClient;
        this.authQueue = authQueue;
    }

    @Subscribe
    public void onPlayerChooseInitialServer(PlayerChooseInitialServerEvent event) {
        final String username = event.getPlayer().getUsername();
        final InetSocketAddress remoteAddress = event.getPlayer().getRemoteAddress();
        final String playerIpv4 = remoteAddress.getAddress().getHostAddress();
        final String targetServer = event.getInitialServer().get().getServerInfo().getName();
        final String proxyUuid = configManager.getString(CONFIG_TWIG_UUID);

        logger.info("{} ({}) is trying to join to server `{}`.", username, remoteAddress, targetServer);

        // Check player access via gRPC
        final PlayerAccessResponse response;
        try {
            response = grpcClient.checkPlayerAccess(username, playerIpv4, targetServer, proxyUuid);
        } catch (Exception e) {
            logger.error("Failed to check player access for {} ({}): {}", username, playerIpv4, e.getMessage());
            disconnectPlayerWithMessage(event.getPlayer());
            return;
        }

        handleAccessResponse(response, event.getPlayer(), username, remoteAddress, targetServer,
                (limboServer, authUrl) -> {
                    authQueue.put(new PlayerIdentifier(event.getPlayer()),
                            new AuthenticationEntry(authUrl, targetServer));
                    event.setInitialServer(limboServer);
                });
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        final String username = event.getPlayer().getUsername();
        final InetSocketAddress remoteAddress = event.getPlayer().getRemoteAddress();
        final String playerIpv4 = remoteAddress.getAddress().getHostAddress();
        final String targetServer = event.getOriginalServer().getServerInfo().getName();
        final String proxyUuid = configManager.getString(CONFIG_TWIG_UUID);

        logger.info("{} ({}) is trying to join to server `{}`.", username, remoteAddress, targetServer);

        if (targetServer.equals(configManager.getString(CONFIG_PROXY_LIMBO))) {
            // Allow joining limbo server without checks
            return;
        }

        // Check player access via gRPC
        final PlayerAccessResponse response;
        try {
            response = grpcClient.checkPlayerAccess(username, playerIpv4, targetServer, proxyUuid);
        } catch (Exception e) {
            logger.error("Failed to check player access for {} ({}): {}", username, playerIpv4, e.getMessage());
            disconnectPlayerWithMessage(event.getPlayer());
            return;
        }

        handleAccessResponse(response, event.getPlayer(), username, remoteAddress, targetServer,
                (limboServer, authUrl) -> {
                    authQueue.put(new PlayerIdentifier(event.getPlayer()),
                            new AuthenticationEntry(authUrl, targetServer));
                    event.setResult(ServerResult.allowed(limboServer));
                });

        if (response.getStatus() == AccessStatus.PROHIBITED) {
            event.setResult(ServerResult.denied());
        }
    }

    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        final PlayerIdentifier playerId = new PlayerIdentifier(event.getPlayer());
        final AuthenticationEntry authEntry = authQueue.remove(playerId);

        if (authEntry != null) {
            logger.info("Cancelling authentication task for {} ({}) on disconnect.",
                    event.getPlayer().getUsername(), event.getPlayer().getRemoteAddress());
            authEntry.cancelTask();
        }
    }

    /**
     * Disconnects a player with the configured "not allowed" message.
     */
    private void disconnectPlayerWithMessage(final Player player) {
        final Component component = Component
                .text(configManager.getString(CONFIG_NOT_ALLOWED_MESSAGE, DEFAULT_NOT_ALLOWED_MESSAGE));
        player.disconnect(component);
    }

    /**
     * Handles the access response from the gRPC server.
     * 
     * @param response         The access response
     * @param player           The player attempting to connect
     * @param username         The player's username
     * @param remoteAddress    The player's remote address
     * @param targetServer     The target server name
     * @param onSignupRequired Callback to execute when signup is required
     */
    private void handleAccessResponse(final PlayerAccessResponse response, final Player player,
            final String username, final InetSocketAddress remoteAddress, final String targetServer,
            final SignupHandler onSignupRequired) {
        final AccessStatus status = response.getStatus();

        if (status == AccessStatus.REQUIRES_SIGNUP) {
            final String limboServerName = configManager.getString(CONFIG_PROXY_LIMBO);
            final Optional<RegisteredServer> limboServer = proxyServer.getServer(limboServerName);

            if (limboServer.isEmpty()) {
                logger.warn("{} ({}) was denied access because the limbo server `{}` does not exist.",
                        username, remoteAddress, limboServerName);
                disconnectPlayerWithMessage(player);
                return;
            }

            // Ensure authentication URL is present
            if (!response.hasAuthenticationUrl()) {
                logger.error("gRPC response for {} ({}) requires signup but has no authentication URL.",
                        username, remoteAddress);
                disconnectPlayerWithMessage(player);
                return;
            }

            final String authUrl = response.getAuthenticationUrl();
            logger.info("{} ({}) was redirected to limbo server `{}` because they need to authenticate (status: {}).",
                    username, remoteAddress, limboServerName, status);

            onSignupRequired.handle(limboServer.get(), authUrl);
        } else if (status == AccessStatus.PROHIBITED) {
            logger.warn("{} ({}) was denied access to server `{}` because they are prohibited.",
                    username, remoteAddress, targetServer);
            disconnectPlayerWithMessage(player);
        }
    }

    /**
     * Functional interface for handling signup requirements.
     */
    @FunctionalInterface
    private interface SignupHandler {
        void handle(RegisteredServer limboServer, String authUrl);
    }
}
