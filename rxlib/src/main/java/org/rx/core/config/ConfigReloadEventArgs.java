package org.rx.core.config;

import org.rx.core.EventArgs;

public final class ConfigReloadEventArgs<TConfig, TResource> extends EventArgs {
    private static final long serialVersionUID = 1781026829635476744L;

    private final String sourceId;
    private final long sourceVersion;
    private final TConfig oldConfig;
    private final TConfig newConfig;
    private final TResource oldResource;
    private final TResource newResource;
    private final boolean restarted;
    private final boolean success;
    private final Throwable error;
    private final long reloadMillis;

    public ConfigReloadEventArgs(String sourceId, long sourceVersion,
                                 TConfig oldConfig, TConfig newConfig,
                                 TResource oldResource, TResource newResource,
                                 boolean restarted, boolean success,
                                 Throwable error, long reloadMillis) {
        this.sourceId = sourceId;
        this.sourceVersion = sourceVersion;
        this.oldConfig = oldConfig;
        this.newConfig = newConfig;
        this.oldResource = oldResource;
        this.newResource = newResource;
        this.restarted = restarted;
        this.success = success;
        this.error = error;
        this.reloadMillis = reloadMillis;
    }

    public String getSourceId() {
        return sourceId;
    }

    public long getSourceVersion() {
        return sourceVersion;
    }

    public TConfig getOldConfig() {
        return oldConfig;
    }

    public TConfig getNewConfig() {
        return newConfig;
    }

    public TResource getOldResource() {
        return oldResource;
    }

    public TResource getNewResource() {
        return newResource;
    }

    public boolean isRestarted() {
        return restarted;
    }

    public boolean isSuccess() {
        return success;
    }

    public Throwable getError() {
        return error;
    }

    public long getReloadMillis() {
        return reloadMillis;
    }
}
