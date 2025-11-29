package com.github.caiostoduto.twig.auth;

import com.velocitypowered.api.scheduler.ScheduledTask;

/**
 * Represents an authentication entry for a player waiting in limbo.
 * Contains the authentication URL and the intended destination server.
 */
public class AuthenticationEntry {
    private final String url;
    private final String initialServerName;
    private ScheduledTask task;

    public AuthenticationEntry(final String url, final String initialServerName) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("Authentication URL cannot be null or empty");
        }
        if (initialServerName == null || initialServerName.trim().isEmpty()) {
            throw new IllegalArgumentException("Initial server name cannot be null or empty");
        }
        this.url = url;
        this.initialServerName = initialServerName;
    }

    public String getUrl() {
        return url;
    }

    public String getInitialServerName() {
        return initialServerName;
    }

    public ScheduledTask getTask() {
        return task;
    }

    public void setTask(final ScheduledTask task) {
        this.task = task;
    }

    /**
     * Cancels the scheduled task if it exists.
     */
    public void cancelTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
