package org.rx.net.registry;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * Configuration and strict memory/storage budgets for the lightweight {@link NodeRegistry}.
 * Disabled by default so ordinary RSS deployments start zero NodeRegistry DBs, sockets, or timers.
 */
@Getter
@Setter
@ToString
public class NodeRegistryConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final int DEFAULT_MAX_ONLINE_NODES = 1024;
    public static final int DEFAULT_MAX_CANDIDATES_PER_NODE = 16;
    public static final int DEFAULT_MAX_ATTRS_BYTES_PER_NODE = 8 * 1024;
    public static final int DEFAULT_MAX_COLD_CACHE_BYTES = 2 * 1024 * 1024;
    public static final int DEFAULT_MAX_PENDING_PERSIST_BYTES = 2 * 1024 * 1024;
    public static final int DEFAULT_H2_CACHE_SIZE_KIB = 8 * 1024;
    public static final long DEFAULT_FLUSH_PERIOD_MILLIS = 60_000L;
    public static final long DEFAULT_ONLINE_TTL_MILLIS = 90_000L;
    public static final long DEFAULT_TTL_SWEEP_PERIOD_MILLIS = 15_000L;

    private boolean enabled;
    /** Separate H2 file path (never forced to share RSS traffic DB). */
    private String dbPath = "./data/node-registry";
    private int h2CacheSizeKiB = DEFAULT_H2_CACHE_SIZE_KIB;
    private int h2MaxConnections = 4;
    private int maxOnlineNodes = DEFAULT_MAX_ONLINE_NODES;
    private int maxCandidatesPerNode = DEFAULT_MAX_CANDIDATES_PER_NODE;
    private int maxAttrsBytesPerNode = DEFAULT_MAX_ATTRS_BYTES_PER_NODE;
    private int maxColdCacheBytes = DEFAULT_MAX_COLD_CACHE_BYTES;
    private int maxPendingPersistBytes = DEFAULT_MAX_PENDING_PERSIST_BYTES;
    private int maxOfflineNodes = 100_000;
    private int maxQueryPageSize = 100;
    private long flushPeriodMillis = DEFAULT_FLUSH_PERIOD_MILLIS;
    private long flushRetryBackoffMillis = 5_000L;
    private long onlineTtlMillis = DEFAULT_ONLINE_TTL_MILLIS;
    private long ttlSweepPeriodMillis = DEFAULT_TTL_SWEEP_PERIOD_MILLIS;
    /** Historical IP retention is disabled by default for privacy and bounded disk usage. */
    private boolean recordHistoryIp;
}
