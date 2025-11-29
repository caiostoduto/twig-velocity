package com.github.caiostoduto.twig.auth;

import java.util.Objects;
import com.velocitypowered.api.proxy.Player;

/**
 * Composite key for identifying players using both username and IP address.
 * This ensures proper player identification even when multiple accounts
 * connect from different IP addresses.
 */
public class PlayerIdentifier {
    private final String username;
    private final String ipAddress;

    public PlayerIdentifier(final Player player) {
        if (player == null) {
            throw new IllegalArgumentException("Player cannot be null");
        }
        this.username = player.getUsername();
        // Use getHostAddress() to get the IP without the leading slash
        this.ipAddress = player.getRemoteAddress().getAddress().getHostAddress();
    }

    public String getUsername() {
        return username;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final PlayerIdentifier that = (PlayerIdentifier) o;
        return Objects.equals(username, that.username) && Objects.equals(ipAddress, that.ipAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(username, ipAddress);
    }

    @Override
    public String toString() {
        return username + " (" + ipAddress + ")";
    }
}
