package org.rx.core.config;

import org.rx.core.EventArgs;

public final class ConfigChangedEventArgs<TConfig> extends EventArgs {
    private static final long serialVersionUID = -6377536391466746047L;

    private final String sourceId;
    private final String sourcePath;
    private final long version;
    private final TConfig oldConfig;
    private final TConfig newConfig;
    private final long loadMillis;

    public ConfigChangedEventArgs(String sourceId, String sourcePath, long version,
                                  TConfig oldConfig, TConfig newConfig, long loadMillis) {
        this.sourceId = sourceId;
        this.sourcePath = sourcePath;
        this.version = version;
        this.oldConfig = oldConfig;
        this.newConfig = newConfig;
        this.loadMillis = loadMillis;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public long getVersion() {
        return version;
    }

    public TConfig getOldConfig() {
        return oldConfig;
    }

    public TConfig getNewConfig() {
        return newConfig;
    }

    public long getLoadMillis() {
        return loadMillis;
    }
}
