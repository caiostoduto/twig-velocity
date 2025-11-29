package com.github.caiostoduto.twig.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import minecraft_bridge.MinecraftBridgeGrpc;
import minecraft_bridge.MinecraftBridgeOuterClass.EventSubscription;
import minecraft_bridge.MinecraftBridgeOuterClass.EventType;
import minecraft_bridge.MinecraftBridgeOuterClass.PlayerAccessRequest;
import minecraft_bridge.MinecraftBridgeOuterClass.PlayerAccessResponse;
import minecraft_bridge.MinecraftBridgeOuterClass.ProxyRegistration;
import minecraft_bridge.MinecraftBridgeOuterClass.RegistrationResponse;
import minecraft_bridge.MinecraftBridgeOuterClass.ServerEvent;
import minecraft_bridge.MinecraftBridgeOuterClass.MinecraftServer;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class MinecraftBridgeClient {
    private final ManagedChannel channel;
    private final MinecraftBridgeGrpc.MinecraftBridgeBlockingStub blockingStub;
    private final MinecraftBridgeGrpc.MinecraftBridgeStub asyncStub;
    private final Logger logger;
    private volatile boolean registered = false;

    public MinecraftBridgeClient(final String host, final int port, final Logger logger) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("gRPC host cannot be null or empty");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("gRPC port must be between 1 and 65535");
        }
        if (logger == null) {
            throw new IllegalArgumentException("Logger cannot be null");
        }
        this.logger = logger;
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        this.blockingStub = MinecraftBridgeGrpc.newBlockingStub(channel);
        this.asyncStub = MinecraftBridgeGrpc.newStub(channel);
    }

    /**
     * Register the proxy with the gRPC server
     * 
     * @param proxyId     The proxy UUID
     * @param serverNames List of server names managed by this proxy
     * @return RegistrationResponse containing success status
     * @throws IllegalArgumentException if proxyId is null or empty, or serverNames
     *                                  is null
     * @throws StatusRuntimeException   if the RPC fails
     */
    public RegistrationResponse registerProxy(final String proxyId, final List<String> serverNames) {
        if (proxyId == null || proxyId.trim().isEmpty()) {
            throw new IllegalArgumentException("Proxy ID cannot be null or empty");
        }
        if (serverNames == null) {
            throw new IllegalArgumentException("Server names list cannot be null");
        }

        // Build MinecraftServer list from server names
        final List<MinecraftServer> servers = serverNames.stream()
                .map(name -> MinecraftServer.newBuilder().setName(name).build())
                .collect(java.util.stream.Collectors.toList());

        final ProxyRegistration request = ProxyRegistration.newBuilder()
                .setProxyId(proxyId)
                .addAllServers(servers)
                .build();

        try {
            final RegistrationResponse response = blockingStub.registerProxy(request);
            if (response.getSuccess()) {
                registered = true;
                logger.info("Successfully registered proxy with {} servers", serverNames.size());
            } else {
                logger.warn("Proxy registration was unsuccessful");
            }
            return response;
        } catch (StatusRuntimeException e) {
            logger.error("RPC failed during registration: {}", e.getStatus());
            throw e;
        }
    }

    /**
     * Check if the proxy is registered
     */
    public boolean isRegistered() {
        return registered;
    }

    /**
     * Check if a player has access to a server
     * 
     * @param playerName The player's username
     * @param playerIpv4 The player's IPv4 address
     * @param serverName The target server name
     * @param proxyId    The proxy UUID
     * @return PlayerAccessResponse containing access status and optional
     *         authentication URL
     * @throws IllegalArgumentException if any parameter is null or empty
     * @throws StatusRuntimeException   if the RPC fails
     */
    public PlayerAccessResponse checkPlayerAccess(final String playerName, final String playerIpv4,
            final String serverName, final String proxyId) {
        if (playerName == null || playerName.trim().isEmpty()) {
            throw new IllegalArgumentException("Player name cannot be null or empty");
        }
        if (playerIpv4 == null || playerIpv4.trim().isEmpty()) {
            throw new IllegalArgumentException("Player IPv4 cannot be null or empty");
        }
        if (serverName == null || serverName.trim().isEmpty()) {
            throw new IllegalArgumentException("Server name cannot be null or empty");
        }
        if (proxyId == null || proxyId.trim().isEmpty()) {
            throw new IllegalArgumentException("Proxy ID cannot be null or empty");
        }

        final PlayerAccessRequest request = PlayerAccessRequest.newBuilder()
                .setPlayerName(playerName)
                .setPlayerIpv4(playerIpv4)
                .setServerName(serverName)
                .setProxyId(proxyId)
                .build();

        try {
            return blockingStub.checkPlayerAccess(request);
        } catch (StatusRuntimeException e) {
            logger.error("RPC failed while checking player access for {} ({}): {}",
                    playerName, playerIpv4, e.getStatus());
            throw e;
        }
    }

    /**
     * Shutdown the gRPC channel gracefully
     * 
     * @throws InterruptedException if the shutdown is interrupted
     */
    public void shutdown() throws InterruptedException {
        registered = false;
        if (!channel.isShutdown()) {
            channel.shutdown();
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("gRPC channel did not terminate gracefully, forcing shutdown");
                channel.shutdownNow();
                if (!channel.awaitTermination(2, TimeUnit.SECONDS)) {
                    logger.error("gRPC channel did not terminate after forced shutdown");
                }
            }
        }
    }

    /**
     * Check if the channel is shutdown
     */
    public boolean isShutdown() {
        return channel.isShutdown();
    }

    /**
     * Subscribe to server events from the gRPC server
     * 
     * @param proxyId       The proxy UUID
     * @param eventCallback Callback to handle received events
     * @throws IllegalArgumentException if proxyId is null/empty or eventCallback is
     *                                  null
     * @throws StatusRuntimeException   if the subscription fails
     */
    public void subscribeEvents(final String proxyId, final Consumer<ServerEvent> eventCallback) {
        subscribeEvents(proxyId, eventCallback, null);
    }

    /**
     * Subscribe to server events from the gRPC server with reconnection callback
     * 
     * @param proxyId           The proxy UUID
     * @param eventCallback     Callback to handle received events
     * @param reconnectCallback Optional callback invoked when reconnection occurs
     * @throws IllegalArgumentException if proxyId is null/empty or eventCallback is
     *                                  null
     * @throws StatusRuntimeException   if the subscription fails
     */
    public void subscribeEvents(final String proxyId, final Consumer<ServerEvent> eventCallback,
            final Runnable reconnectCallback) {
        if (proxyId == null || proxyId.trim().isEmpty()) {
            throw new IllegalArgumentException("Proxy ID cannot be null or empty");
        }
        if (eventCallback == null) {
            throw new IllegalArgumentException("Event callback cannot be null");
        }

        final EventSubscription subscription = EventSubscription.newBuilder()
                .setProxyId(proxyId)
                .addEventTypes(EventType.PLAYER_UPDATE)
                .build();

        final StreamObserver<ServerEvent> responseObserver = new StreamObserver<ServerEvent>() {
            @Override
            public void onNext(final ServerEvent event) {
                try {
                    // Invoke the callback with the received event
                    eventCallback.accept(event);
                } catch (Exception e) {
                    logger.error("Error processing event: {}", e.getMessage(), e);
                }
            }

            @Override
            public void onError(final Throwable t) {
                logger.error("Error in event stream: {}", t.getMessage(), t);

                // Attempt to reconnect after a delay
                try {
                    TimeUnit.SECONDS.sleep(5);
                    logger.info("Attempting to reconnect to event stream...");
                    subscribeEvents(proxyId, eventCallback, reconnectCallback);

                    // Invoke reconnection callback if provided
                    if (reconnectCallback != null) {
                        try {
                            logger.info("Invoking reconnection callback...");
                            reconnectCallback.run();
                        } catch (Exception e) {
                            logger.error("Error in reconnection callback: {}", e.getMessage(), e);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("Event stream reconnection interrupted");
                }
            }

            @Override
            public void onCompleted() {
                logger.info("Event stream completed");
            }
        };

        try {
            logger.info("Subscribing to events...");
            asyncStub.subscribeEvents(subscription, responseObserver);
        } catch (StatusRuntimeException e) {
            logger.error("Failed to subscribe to events: {}", e.getStatus());
            throw e;
        }
    }
}
