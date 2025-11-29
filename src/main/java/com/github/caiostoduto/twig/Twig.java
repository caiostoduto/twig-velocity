package com.github.caiostoduto.twig;

import com.github.caiostoduto.twig.auth.AuthenticationEntry;
import com.github.caiostoduto.twig.auth.PlayerIdentifier;
import com.github.caiostoduto.twig.config.ConfigManager;
import com.github.caiostoduto.twig.grpc.MinecraftBridgeClient;
import com.github.caiostoduto.twig.listeners.AuthenticationLoginHandler;
import com.github.caiostoduto.twig.listeners.LimboHandler;
import com.github.caiostoduto.twig.listeners.PlayerUpdateEventHandler;
import com.google.inject.Inject;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Plugin(id = "twig", name = "Twig", version = BuildConstants.VERSION, url = "https://github.com/caiostoduto/twig-velocity", authors = {
        "Caio Stoduto" })
public class Twig {
    @Inject
    private Logger logger;
    @Inject
    private ProxyServer proxyServer;

    private final Path dataDirectory;
    private ConfigManager configManager;
    private MinecraftBridgeClient grpcClient;

    // Hash table for authentication queue using composite key (username + IP)
    private final Map<PlayerIdentifier, AuthenticationEntry> authQueue = new HashMap<>();

    @Inject
    public Twig(@DataDirectory final Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) throws IOException {
        // Create the plugin folder if it doesn't exist
        if (!Files.exists(dataDirectory)) {
            Files.createDirectories(dataDirectory);
            logger.info("Created plugin directory: {}", dataDirectory);
        }

        // Initialize config manager and load config
        configManager = new ConfigManager(dataDirectory);
        configManager.load();

        // Retrieve proxy UUID from config
        final String proxyUuid = configManager.getString("twig_uuid");
        logger.info("Twig UUID: {}", proxyUuid);

        // Initialize gRPC client
        String grpcHost = configManager.getString("grpc_host", "127.0.0.1");
        int grpcPort = configManager.getInt("grpc_port", 50051);
        grpcClient = new MinecraftBridgeClient(grpcHost, grpcPort, logger);
        logger.info("gRPC client initialized: {}:{}", grpcHost, grpcPort);

        // Collect server names from Velocity
        List<String> serverNames = proxyServer.getAllServers().stream()
                .map(server -> server.getServerInfo().getName())
                .filter(server -> !server.equals(configManager.getString("proxy_limbo")))
                .collect(Collectors.toList());

        // Start async task to register proxy with retry logic
        proxyServer.getScheduler().buildTask(this, () -> {
            attemptProxyRegistration(proxyUuid, serverNames);
        }).schedule();

        // Register event listeners
        AuthenticationLoginHandler loginHandler = new AuthenticationLoginHandler(
                logger, proxyServer, configManager, grpcClient, authQueue);
        proxyServer.getEventManager().register(this, loginHandler);

        LimboHandler limboHandler = new LimboHandler(this, logger, proxyServer, configManager, authQueue);
        proxyServer.getEventManager().register(this, limboHandler);

        logger.info("Twig plugin initialized successfully!");
    }

    /**
     * Attempt to register the proxy with the gRPC server
     * Retries indefinitely with exponential backoff on failure
     */
    private void attemptProxyRegistration(final String proxyId, final List<String> serverNames) {
        int retryDelay = 1; // Initial delay in seconds
        final int maxDelay = 60; // Maximum delay in seconds

        while (!grpcClient.isRegistered() && !grpcClient.isShutdown()) {
            try {
                logger.info("Attempting to register proxy with {} servers...", serverNames.size());
                grpcClient.registerProxy(proxyId, serverNames);

                // After successful registration, subscribe to events
                if (grpcClient.isRegistered()) {
                    subscribeToEvents(proxyId);
                }

                return; // Registration successful
            } catch (Exception e) {
                logger.error("Failed to register proxy: {}. Retrying in {} seconds...",
                        e.getMessage(), retryDelay);

                try {
                    TimeUnit.SECONDS.sleep(retryDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    logger.warn("Proxy registration interrupted");
                    return;
                }

                // Exponential backoff with cap
                retryDelay = Math.min(retryDelay * 2, maxDelay);
            }
        }
    }

    /**
     * Subscribe to gRPC server events
     */
    private void subscribeToEvents(final String proxyId) {
        // Create player update event handler
        final PlayerUpdateEventHandler playerUpdateHandler = new PlayerUpdateEventHandler(
                logger, proxyServer, configManager, grpcClient, authQueue);

        // Subscribe to events with the handler callback and reconnection callback
        grpcClient.subscribeEvents(proxyId, playerUpdateHandler::handleEvent, () -> {
            // When reconnected, check all players to ensure they still have access
            logger.info("gRPC event stream reconnected, checking all players...");
            playerUpdateHandler.checkAllPlayers();
        });
        logger.info("Subscribed to player_update events");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (grpcClient != null && !grpcClient.isShutdown()) {
            try {
                logger.info("Shutting down gRPC client...");
                grpcClient.shutdown();
            } catch (InterruptedException e) {
                logger.error("Failed to shutdown gRPC client", e);
            }
        }
    }
}