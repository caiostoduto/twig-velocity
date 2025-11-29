package com.github.caiostoduto.twig.listeners;

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;

import com.github.caiostoduto.twig.auth.AuthenticationEntry;
import com.github.caiostoduto.twig.auth.PlayerIdentifier;
import com.github.caiostoduto.twig.config.ConfigManager;
import com.github.caiostoduto.twig.grpc.MinecraftBridgeClient;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import minecraft_bridge.MinecraftBridgeOuterClass.AccessStatus;
import minecraft_bridge.MinecraftBridgeOuterClass.PlayerAccessResponse;
import minecraft_bridge.MinecraftBridgeOuterClass.PlayerUpdateEvent;
import minecraft_bridge.MinecraftBridgeOuterClass.ServerEvent;
import net.kyori.adventure.text.Component;

public class PlayerUpdateEventHandler {
    private static final String CONFIG_KEY_LIMBO_SERVER = "proxy_limbo";
    private static final String CONFIG_KEY_PROXY_UUID = "twig_uuid";
    private static final String CONFIG_KEY_NOT_ALLOWED_MESSAGE = "not_allowed_message";
    private static final String DEFAULT_NOT_ALLOWED_MESSAGE = "You are not whitelisted on this server!";

    private final Logger logger;
    private final ProxyServer proxyServer;
    private final ConfigManager configManager;
    private final MinecraftBridgeClient grpcClient;
    private final Map<PlayerIdentifier, AuthenticationEntry> authQueue;

    public PlayerUpdateEventHandler(final Logger logger, final ProxyServer proxyServer,
            final ConfigManager configManager, final MinecraftBridgeClient grpcClient,
            final Map<PlayerIdentifier, AuthenticationEntry> authQueue) {
        this.logger = logger;
        this.proxyServer = proxyServer;
        this.configManager = configManager;
        this.grpcClient = grpcClient;
        this.authQueue = authQueue;
    }

    /**
     * Handle player_update events from the gRPC server
     * 
     * @param event The ServerEvent containing the player_update data
     */
    public void handleEvent(final ServerEvent event) {
        if (!event.hasPlayerUpdate()) {
            return;
        }

        final PlayerUpdateEvent playerUpdate = event.getPlayerUpdate();
        final String playerName = playerUpdate.getPlayerName();
        final String playerIpv4 = playerUpdate.getPlayerIpv4();

        logger.info("Received player_update event for {} ({})", playerName, playerIpv4);

        Player player = validateAndGetPlayer(playerName, playerIpv4);
        if (player == null) {
            return;
        }

        String currentServerName = getCurrentServerName(player, playerName, playerIpv4);
        if (currentServerName == null) {
            return;
        }

        String limboServerName = configManager.getString(CONFIG_KEY_LIMBO_SERVER);
        if (currentServerName.equals(limboServerName)) {
            handlePlayerInLimbo(player, playerName, playerIpv4);
        } else {
            handlePlayerInRegularServer(player, playerName, playerIpv4, currentServerName);
        }
    }

    /**
     * Validates player exists and IP matches, returns the player if valid
     */
    private Player validateAndGetPlayer(final String playerName, final String playerIpv4) {
        final Optional<Player> playerOpt = proxyServer.getPlayer(playerName);
        if (playerOpt.isEmpty()) {
            logger.warn("Player {} ({}) not found on proxy", playerName, playerIpv4);
            return null;
        }

        final Player player = playerOpt.get();
        final String actualIpv4 = player.getRemoteAddress().getAddress().getHostAddress();
        if (!actualIpv4.equals(playerIpv4)) {
            logger.warn("IP mismatch for player {}: expected {}, got {}", playerName, playerIpv4, actualIpv4);
            return null;
        }

        return player;
    }

    /**
     * Gets the current server name for a player
     */
    private String getCurrentServerName(final Player player, final String playerName, final String playerIpv4) {
        final Optional<ServerConnection> currentServerOpt = player.getCurrentServer();
        if (currentServerOpt.isEmpty()) {
            logger.warn("Player {} ({}) is not connected to any server", playerName, playerIpv4);
            return null;
        }

        return currentServerOpt.get().getServerInfo().getName();
    }

    /**
     * Handle player_update when player is currently in limbo
     * Attempts to connect player to their original server
     */
    private void handlePlayerInLimbo(final Player player, final String playerName, final String playerIpv4) {
        final PlayerIdentifier playerId = new PlayerIdentifier(player);
        final AuthenticationEntry authEntry = authQueue.get(playerId);

        if (authEntry == null) {
            logger.warn("Player {} ({}) is in limbo but has no authentication entry", playerName, playerIpv4);
            return;
        }

        final String targetServerName = authEntry.getInitialServerName();
        logger.info("Attempting to connect {} ({}) to original server `{}`", playerName, playerIpv4, targetServerName);

        AccessStatus status = checkPlayerAccessStatus(playerName, playerIpv4, targetServerName);
        if (status == null) {
            return;
        }

        switch (status) {
            case ALLOWED:
                handleAllowedPlayerInLimbo(player, playerName, playerIpv4, targetServerName, playerId, authEntry);
                break;
            case PROHIBITED:
                handleProhibitedPlayerInLimbo(player, playerName, playerIpv4, targetServerName, playerId, authEntry);
                break;
            case REQUIRES_SIGNUP:
                // Player stays in limbo - no action needed
                logger.debug("Player {} ({}) still requires signup for server `{}`", playerName, playerIpv4,
                        targetServerName);
                break;
            default:
                logger.warn("Unknown access status {} for player {} ({})", status, playerName, playerIpv4);
                break;
        }
    }

    /**
     * Handle player who is now allowed to join from limbo
     */
    private void handleAllowedPlayerInLimbo(final Player player, final String playerName, final String playerIpv4,
            final String targetServerName, final PlayerIdentifier playerId,
            final AuthenticationEntry authEntry) {
        final Optional<RegisteredServer> targetServer = proxyServer.getServer(targetServerName);
        if (targetServer.isEmpty()) {
            logger.error("Target server `{}` not found for player {} ({})", targetServerName, playerName, playerIpv4);
            return;
        }

        logger.info("Player {} ({}) is now allowed to join `{}`, connecting...", playerName, playerIpv4,
                targetServerName);

        cleanupAuthEntry(playerId, authEntry);
        player.createConnectionRequest(targetServer.get()).fireAndForget();
    }

    /**
     * Handle player who is prohibited from joining
     */
    private void handleProhibitedPlayerInLimbo(final Player player, final String playerName, final String playerIpv4,
            final String targetServerName, final PlayerIdentifier playerId,
            final AuthenticationEntry authEntry) {
        logger.warn("Player {} ({}) is now prohibited from joining `{}`", playerName, playerIpv4, targetServerName);

        cleanupAuthEntry(playerId, authEntry);
        disconnectPlayer(player);
    }

    /**
     * Handle player_update when player is on a regular server
     * Checks if player still has permission to be there
     */
    private void handlePlayerInRegularServer(final Player player, final String playerName, final String playerIpv4,
            final String currentServerName) {
        logger.info("Checking permissions for {} ({}) on server `{}`", playerName, playerIpv4, currentServerName);

        final AccessStatus status = checkPlayerAccessStatus(playerName, playerIpv4, currentServerName);
        if (status == null) {
            return;
        }

        if (status != AccessStatus.ALLOWED) {
            logger.warn("Player {} ({}) no longer has permission for server `{}`, disconnecting...",
                    playerName, playerIpv4, currentServerName);
            disconnectPlayer(player);
        } else {
            logger.info("Player {} ({}) still has permission for server `{}`", playerName, playerIpv4,
                    currentServerName);
        }
    }

    /**
     * Check player access status via gRPC client
     * 
     * @return AccessStatus if successful, null if error occurred
     */
    private AccessStatus checkPlayerAccessStatus(final String playerName, final String playerIpv4,
            final String serverName) {
        final String proxyUuid = configManager.getString(CONFIG_KEY_PROXY_UUID);

        try {
            final PlayerAccessResponse response = grpcClient.checkPlayerAccess(playerName, playerIpv4, serverName,
                    proxyUuid);
            return response.getStatus();
        } catch (Exception e) {
            logger.error("Failed to check player access for {} ({}) on server `{}`: {}",
                    playerName, playerIpv4, serverName, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Cleanup authentication entry by canceling task and removing from queue
     */
    private void cleanupAuthEntry(final PlayerIdentifier playerId, final AuthenticationEntry authEntry) {
        authEntry.cancelTask();
        authQueue.remove(playerId);
    }

    /**
     * Disconnect player with configured message
     */
    private void disconnectPlayer(final Player player) {
        final String message = configManager.getString(CONFIG_KEY_NOT_ALLOWED_MESSAGE, DEFAULT_NOT_ALLOWED_MESSAGE);
        final Component disconnectMessage = Component.text(message);
        player.disconnect(disconnectMessage);
    }

    /**
     * Check all connected players and verify their access status
     * This method should be called when reconnecting to gRPC after a disconnection
     */
    public void checkAllPlayers() {
        logger.info("Checking access status for all connected players after reconnection...");

        int playerCount = 0;
        for (Player player : proxyServer.getAllPlayers()) {
            playerCount++;
            final String playerName = player.getUsername();
            final String playerIpv4 = player.getRemoteAddress().getAddress().getHostAddress();

            // Get current server
            final String currentServerName = getCurrentServerName(player, playerName, playerIpv4);
            if (currentServerName == null) {
                continue;
            }

            final String limboServerName = configManager.getString(CONFIG_KEY_LIMBO_SERVER);

            // Skip limbo server players - they're already being handled by the auth queue
            if (currentServerName.equals(limboServerName)) {
                logger.debug("Skipping player {} ({}) in limbo server", playerName, playerIpv4);
                continue;
            }

            // Check access for players on regular servers
            logger.debug("Verifying access for player {} ({}) on server `{}`",
                    playerName, playerIpv4, currentServerName);

            final AccessStatus status = checkPlayerAccessStatus(playerName, playerIpv4, currentServerName);
            if (status == null) {
                logger.warn("Failed to check access for player {} ({}) on server `{}`",
                        playerName, playerIpv4, currentServerName);
                continue;
            }

            if (status != AccessStatus.ALLOWED) {
                logger.warn("Player {} ({}) no longer has permission for server `{}` (status: {}), disconnecting...",
                        playerName, playerIpv4, currentServerName, status);
                disconnectPlayer(player);
            } else {
                logger.debug("Player {} ({}) verified on server `{}`",
                        playerName, playerIpv4, currentServerName);
            }
        }

        logger.info("Completed checking {} player(s) after reconnection", playerCount);
    }
}
