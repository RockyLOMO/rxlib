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
    public static final int DEFAULT_MAX_ONLINE_NODES_PER_TENANT = 1024;
    public static final int DEFAULT_MAX_CANDIDATES_PER_NODE = 16;
    public static final int DEFAULT_MAX_ATTRS_BYTES_PER_NODE = 8 * 1024;
    public static final int DEFAULT_MAX_LIVE_SERVICES_PER_NODE = 32;
    public static final int DEFAULT_MAX_PERSISTENT_SERVICES_PER_NODE = 32;
    public static final int DEFAULT_MAX_SERVICE_METADATA_BYTES_PER_NODE = 8 * 1024;
    public static final int DEFAULT_MAX_TOTAL_LIVE_SERVICES = 16 * 1024;
    public static final int DEFAULT_MAX_AUTH_ATTEMPTS_PER_SECOND = 128;
    public static final int DEFAULT_MAX_AUTH_CONCURRENCY = 16;
    public static final int DEFAULT_PBKDF2_ITERATIONS = 10_000;
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
    private int maxOnlineNodesPerTenant = DEFAULT_MAX_ONLINE_NODES_PER_TENANT;
    private int maxCandidatesPerNode = DEFAULT_MAX_CANDIDATES_PER_NODE;
    private int maxAttrsBytesPerNode = DEFAULT_MAX_ATTRS_BYTES_PER_NODE;
    private int maxLiveServicesPerNode = DEFAULT_MAX_LIVE_SERVICES_PER_NODE;
    private int maxPersistentServicesPerNode = DEFAULT_MAX_PERSISTENT_SERVICES_PER_NODE;
    private int maxServiceMetadataBytesPerNode = DEFAULT_MAX_SERVICE_METADATA_BYTES_PER_NODE;
    private int maxTotalLiveServices = DEFAULT_MAX_TOTAL_LIVE_SERVICES;
    /** Auth token bucket rate limit (0 disables rate limit for local benchmarks). */
    private int maxAuthAttemptsPerSecond = DEFAULT_MAX_AUTH_ATTEMPTS_PER_SECOND;
    /** Max concurrent PBKDF2/H2 auth validations off-EventLoop. */
    private int maxAuthConcurrency = DEFAULT_MAX_AUTH_CONCURRENCY;
    /** PBKDF2-HMAC-SHA256 iteration count for newly enrolled/rotated credentials (1000..500000). */
    private int pbkdf2Iterations = DEFAULT_PBKDF2_ITERATIONS;
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
