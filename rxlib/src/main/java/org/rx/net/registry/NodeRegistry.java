package org.rx.net.registry;

import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Strings;
import org.rx.core.Tasks;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityDatabaseImpl;
import org.rx.net.EndpointCandidate;
import org.rx.net.registry.NodeRegistryEntities.NodeAttributeEntity;
import org.rx.net.registry.NodeRegistryEntities.NodeEntity;
import org.rx.net.registry.NodeRegistryEntities.NodeServiceEntity;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static org.rx.core.Extends.quietly;

/**
 * Lightweight shared control-plane directory separating bounded hot online state (in-memory only)
 * from cold identity/ACL/metadata/attribute/service-config state in H2 ({@link EntityDatabase}).
 * <p>
 * Security-critical mutations (enrollment, credential rotation, ACL updates, disable/revoke)
 * persist reliably off-EventLoop with per-node monotonic authorization generations, immediate
 * cache invalidation, and hot session eviction. Low-frequency metadata, lastSeen, attributes,
 * and persistent service configs are key-coalesced and batch-flushed asynchronously within
 * strict in-flight + pending byte budgets.
 */
@Slf4j
public final class NodeRegistry implements AutoCloseable {
    private static final String VERIFIER_PREFIX = "pbkdf2$";
    private static final int PBKDF2_ITERATIONS = 10_000;
    private static final int PBKDF2_KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_METADATA_BYTES = 4096;
    private static final int MAX_SERVICE_CONFIG_BYTES = 2048;
    private static final int MAX_SECRET_LENGTH = 1024;
    private static final int MAX_LIVE_SERVICE_META_ENTRIES = 32;
    private static final int MAX_LIVE_SERVICE_META_VALUE_BYTES = 1024;
    private static final int NODE_LOCK_STRIPES = 64;

    @Getter
    public static final class LiveService {
        private final String nodeId;
        private final String appName;
        private final String instanceId;
        private final Map<String, String> metadata;
        private final int metadataUtf8Bytes;
        private final long registeredAtMillis;

        LiveService(String nodeId, String appName, String instanceId, Map<String, String> metadata,
                    int metadataUtf8Bytes, long registeredAtMillis) {
            this.nodeId = nodeId;
            this.appName = appName;
            this.instanceId = instanceId;
            this.metadata = metadata == null ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<String, String>(metadata));
            this.metadataUtf8Bytes = metadataUtf8Bytes;
            this.registeredAtMillis = registeredAtMillis;
        }
    }

    @Getter
    public static final class OnlineNode {
        private final String nodeId;
        private final String tenant;
        private final Set<String> aclRoles;
        private final long authGeneration;
        private volatile Object sessionRef;
        private final ConcurrentHashMap<String, Object> subsystemSessions = new ConcurrentHashMap<String, Object>();
        private volatile List<EndpointCandidate> candidates;
        private final ConcurrentHashMap<String, LiveService> liveServices = new ConcurrentHashMap<String, LiveService>();
        private volatile long onlineSinceMillis;
        private volatile long lastSeenMillis;
        private volatile long expiresAtMillis;
        private volatile boolean revoked;
        int liveServiceMetadataBytes;

        OnlineNode(String nodeId, String tenant, Set<String> aclRoles, Object sessionRef,
                   List<EndpointCandidate> candidates, long nowMillis, long ttlMillis, long authGeneration) {
            this.nodeId = nodeId;
            this.tenant = tenant;
            this.aclRoles = Collections.unmodifiableSet(new HashSet<String>(aclRoles));
            this.authGeneration = authGeneration;
            this.sessionRef = sessionRef;
            this.candidates = candidates;
            this.onlineSinceMillis = nowMillis;
            this.lastSeenMillis = nowMillis;
            this.expiresAtMillis = nowMillis + ttlMillis;
        }

        public Object getSubsystemSession(String subsystem) {
            return subsystem == null ? null : subsystemSessions.get(subsystem);
        }

        public boolean isExpired(long nowMillis) {
            return nowMillis >= expiresAtMillis;
        }
    }

    @Getter
    public static final class NodeSummary {
        private final String nodeId;
        private final String tenant;
        private final Set<String> aclRoles;
        private final boolean disabled;
        private final boolean online;
        private final String metadataJson;
        private final long lastSeenMillis;

        NodeSummary(String nodeId, String tenant, Set<String> aclRoles, boolean disabled,
                    boolean online, String metadataJson, long lastSeenMillis) {
            this.nodeId = nodeId;
            this.tenant = tenant;
            this.aclRoles = aclRoles;
            this.disabled = disabled;
            this.online = online;
            this.metadataJson = metadataJson;
            this.lastSeenMillis = lastSeenMillis;
        }
    }

    private enum PendingKind {
        LAST_SEEN,
        METADATA,
        ATTR_UPSERT,
        ATTR_DELETE,
        SERVICE_UPSERT,
        SERVICE_DELETE
    }

    private static final class PendingWrite {
        final PendingKind kind;
        final String key;
        final String nodeId;
        final String fieldA;
        final String fieldB;
        final String value;
        final long timestampMillis;
        final int estimatedBytes;

        PendingWrite(PendingKind kind, String key, String nodeId, String fieldA, String fieldB,
                     String value, long timestampMillis, int estimatedBytes) {
            this.kind = kind;
            this.key = key;
            this.nodeId = nodeId;
            this.fieldA = fieldA;
            this.fieldB = fieldB;
            this.value = value;
            this.timestampMillis = timestampMillis;
            this.estimatedBytes = Math.max(32, estimatedBytes);
        }
    }

    /** Simple synchronized weight-bounded LRU cold cache (zero external dependency, exact byte budget). */
    private static final class WeightedColdCache {
        private final int maxBytes;
        private final LinkedHashMap<String, CachedNode> map = new LinkedHashMap<String, CachedNode>(64, 0.75f, true);
        private int currentBytes;

        static final class CachedNode {
            final NodeEntity entity;
            final int weight;

            CachedNode(NodeEntity entity, int weight) {
                this.entity = entity;
                this.weight = weight;
            }
        }

        WeightedColdCache(int maxBytes) {
            this.maxBytes = Math.max(4096, maxBytes);
        }

        synchronized NodeEntity get(String nodeId) {
            CachedNode entry = map.get(nodeId);
            return entry == null ? null : copyEntity(entry.entity);
        }

        synchronized void put(NodeEntity entity) {
            if (entity == null || entity.getNodeId() == null) {
                return;
            }
            NodeEntity copy = copyEntity(entity);
            int weight = estimateEntityBytes(copy);
            CachedNode old = map.remove(copy.getNodeId());
            if (old != null) {
                currentBytes -= old.weight;
            }
            if (weight > maxBytes) {
                return;
            }
            while (currentBytes + weight > maxBytes && !map.isEmpty()) {
                Iterator<Map.Entry<String, CachedNode>> it = map.entrySet().iterator();
                if (!it.hasNext()) {
                    break;
                }
                Map.Entry<String, CachedNode> eldest = it.next();
                currentBytes -= eldest.getValue().weight;
                it.remove();
            }
            map.put(copy.getNodeId(), new CachedNode(copy, weight));
            currentBytes += weight;
        }

        synchronized void invalidate(String nodeId) {
            CachedNode removed = map.remove(nodeId);
            if (removed != null) {
                currentBytes -= removed.weight;
            }
        }

        synchronized void clear() {
            map.clear();
            currentBytes = 0;
        }

        synchronized int size() {
            return map.size();
        }

        synchronized int bytesUsed() {
            return currentBytes;
        }
    }

    private final NodeRegistryConfig config;
    private final EntityDatabase db;
    private final boolean ownDb;
    private final LongSupplier clock;
    private final WeightedColdCache coldCache;
    private final Object onlineAdmissionLock = new Object();
    private final Object[] nodeLocks = new Object[NODE_LOCK_STRIPES];
    private final ConcurrentHashMap<String, AtomicLong> authGenerations = new ConcurrentHashMap<String, AtomicLong>();
    private final ConcurrentHashMap<String, OnlineNode> onlineNodes = new ConcurrentHashMap<String, OnlineNode>();
    private final ConcurrentHashMap<String, AtomicInteger> tenantOnlineCounts = new ConcurrentHashMap<String, AtomicInteger>();
    private final AtomicInteger totalLiveServiceCount = new AtomicInteger();

    private final Semaphore authConcurrencySem;
    private final Object authRateLock = new Object();
    private double authTokens;
    private long authLastRefillMillis;

    private final Object flushLock = new Object();
    private final Object pendingLock = new Object();
    private final LinkedHashMap<String, PendingWrite> pendingWrites = new LinkedHashMap<String, PendingWrite>();
    private final LinkedHashMap<String, PendingWrite> inFlightWrites = new LinkedHashMap<String, PendingWrite>();
    private long pendingBytes;
    private long inFlightBytes;

    private final AtomicLong flushSuccessCount = new AtomicLong();
    private final AtomicLong flushFailureCount = new AtomicLong();
    private final AtomicLong totalFlushedWrites = new AtomicLong();
    private final AtomicLong droppedLowPriorityWrites = new AtomicLong();
    private final AtomicLong authRejectedCount = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile long nextFlushAllowedAtMillis;
    private volatile int consecutiveFlushFailures;
    private volatile ScheduledFuture<?> flushTask;
    private volatile ScheduledFuture<?> ttlSweepTask;
    private final CopyOnWriteArrayList<Consumer<OnlineNode>> revokeListeners = new CopyOnWriteArrayList<Consumer<OnlineNode>>();

    /**
     * Opens a {@link NodeRegistry} only when {@code config != null && config.isEnabled()}.
     * Returns {@code null} when disabled so zero H2 DBs, sockets, or timers are created.
     */
    public static NodeRegistry openIfEnabled(NodeRegistryConfig config) {
        if (config == null || !config.isEnabled()) {
            return null;
        }
        return new NodeRegistry(config);
    }

    public NodeRegistry(@NonNull NodeRegistryConfig config) {
        this(config, null, System::currentTimeMillis);
    }

    public NodeRegistry(@NonNull NodeRegistryConfig config, EntityDatabase externalDb) {
        this(config, externalDb, System::currentTimeMillis);
    }

    NodeRegistry(@NonNull NodeRegistryConfig config, EntityDatabase externalDb, LongSupplier clock) {
        this.config = config;
        this.clock = clock;
        for (int i = 0; i < nodeLocks.length; i++) {
            nodeLocks[i] = new Object();
        }
        this.authConcurrencySem = new Semaphore(Math.max(1, config.getMaxAuthConcurrency()));
        int ratePerSec = config.getMaxAuthAttemptsPerSecond();
        this.authTokens = ratePerSec > 0 ? Math.max(1, ratePerSec) : 0D;
        this.authLastRefillMillis = clock.getAsLong();

        this.coldCache = new WeightedColdCache(config.getMaxColdCacheBytes());
        if (externalDb != null) {
            this.db = externalDb;
            this.ownDb = false;
        } else {
            EntityDatabaseImpl impl = new EntityDatabaseImpl(config.getDbPath(), null, Math.max(1, config.getH2MaxConnections()));
            impl.setH2Settings("CACHE_SIZE=" + Math.max(512, config.getH2CacheSizeKiB()) + ";");
            this.db = impl;
            this.ownDb = true;
        }
        this.db.createMapping(NodeEntity.class, NodeAttributeEntity.class, NodeServiceEntity.class);
        if (config.getFlushPeriodMillis() > 0L) {
            this.flushTask = Tasks.schedulePeriod(this::flushQuietly, config.getFlushPeriodMillis());
        }
        if (config.getTtlSweepPeriodMillis() > 0L) {
            this.ttlSweepTask = Tasks.schedulePeriod(this::sweepExpiredOnlineNodes, config.getTtlSweepPeriodMillis());
        }
    }

    public NodeRegistryConfig getConfig() {
        return config;
    }

    public void setOnNodeRevoked(Consumer<OnlineNode> callback) {
        revokeListeners.clear();
        if (callback != null) {
            revokeListeners.add(callback);
        }
    }

    public void addOnNodeRevokedListener(@NonNull Consumer<OnlineNode> callback) {
        revokeListeners.addIfAbsent(callback);
    }

    public void removeOnNodeRevokedListener(Consumer<OnlineNode> callback) {
        if (callback != null) {
            revokeListeners.remove(callback);
        }
    }

    public int onlineNodeCount() {
        return onlineNodes.size();
    }

    public int totalLiveServiceCount() {
        return Math.max(0, totalLiveServiceCount.get());
    }

    public int coldCacheSize() {
        return coldCache.size();
    }

    public int coldCacheBytesUsed() {
        return coldCache.bytesUsed();
    }

    public long pendingPersistBytes() {
        synchronized (pendingLock) {
            return pendingBytes + inFlightBytes;
        }
    }

    public long flushSuccessCount() {
        return flushSuccessCount.get();
    }

    public long flushFailureCount() {
        return flushFailureCount.get();
    }

    public long totalFlushedWrites() {
        return totalFlushedWrites.get();
    }

    public long droppedLowPriorityWrites() {
        return droppedLowPriorityWrites.get();
    }

    public long authRejectedCount() {
        return authRejectedCount.get();
    }

    // region Security-critical operations (reliable sync H2 write off-EventLoop + generation bump + immediate eviction)

    /**
     * Enrolls or updates a node identity with salted PBKDF2-HMAC-SHA256 credential verifier.
     * Must NOT be invoked on a Netty EventLoop thread.
     */
    public void enrollNode(@NonNull String nodeId, @NonNull String tenant, @NonNull String rawSecret,
                           Set<String> aclRoles, String metadataJson) {
        ensureNotClosed();
        assertNotOnEventLoop("enrollNode");
        String normId = requireValidId(nodeId, "nodeId");
        String normTenant = requireValidId(tenant, "tenant");
        requireValidSecret(rawSecret, "rawSecret");
        String boundedMeta = validateUtf8Budget(metadataJson, MAX_METADATA_BYTES);
        long now = clock.getAsLong();

        OnlineNode evicted = null;
        synchronized (lockFor(normId)) {
            NodeEntity existing = db.findById(NodeEntity.class, normId);
            if (existing == null) {
                long totalCount = countAllNodes();
                if (totalCount >= Math.max(1, config.getMaxOfflineNodes())) {
                    throw new IllegalStateException("NodeRegistry offline node cap reached: " + config.getMaxOfflineNodes());
                }
            }
            NodeEntity entity = existing != null ? existing : new NodeEntity();
            entity.setNodeId(normId);
            entity.setTenant(normTenant);
            entity.setCredentialVerifier(hashSecret(rawSecret, effectivePbkdf2Iterations()));
            entity.setAclRoles(encodeRoles(aclRoles));
            entity.setDisabled(false);
            entity.setMetadataJson(boundedMeta);
            if (existing == null) {
                entity.setCreatedAtMillis(now);
            }
            entity.setUpdatedAtMillis(now);
            db.save(entity, existing == null);
            bumpGeneration(normId);
            coldCache.invalidate(normId);
            if (existing != null) {
                evicted = evictOnlineUnderNodeLock(normId);
            }
        }
        if (evicted != null) {
            notifyRevoked(evicted);
        }
    }

    public void updateCredential(@NonNull String nodeId, @NonNull String newRawSecret) {
        ensureNotClosed();
        assertNotOnEventLoop("updateCredential");
        requireValidSecret(newRawSecret, "newRawSecret");
        String normId = requireValidId(nodeId, "nodeId");
        OnlineNode evicted;
        synchronized (lockFor(normId)) {
            NodeEntity entity = db.findById(NodeEntity.class, normId);
            if (entity == null) {
                throw new IllegalArgumentException("Node not found: " + normId);
            }
            entity.setCredentialVerifier(hashSecret(newRawSecret, effectivePbkdf2Iterations()));
            entity.setUpdatedAtMillis(clock.getAsLong());
            db.save(entity, false);
            bumpGeneration(normId);
            coldCache.invalidate(normId);
            evicted = evictOnlineUnderNodeLock(normId);
        }
        if (evicted != null) {
            notifyRevoked(evicted);
        }
    }

    public void updateAcl(@NonNull String nodeId, Set<String> aclRoles) {
        ensureNotClosed();
        assertNotOnEventLoop("updateAcl");
        String normId = requireValidId(nodeId, "nodeId");
        synchronized (lockFor(normId)) {
            NodeEntity entity = db.findById(NodeEntity.class, normId);
            if (entity == null) {
                throw new IllegalArgumentException("Node not found: " + normId);
            }
            entity.setAclRoles(encodeRoles(aclRoles));
            entity.setUpdatedAtMillis(clock.getAsLong());
            db.save(entity, false);
            long newGen = bumpGeneration(normId);
            coldCache.invalidate(normId);
            OnlineNode existing = onlineNodes.get(normId);
            if (existing != null && !existing.revoked) {
                OnlineNode refreshed = new OnlineNode(existing.nodeId, existing.tenant, decodeRoles(entity.getAclRoles()),
                        existing.sessionRef, existing.candidates, existing.onlineSinceMillis,
                        Math.max(1L, existing.expiresAtMillis - existing.onlineSinceMillis), newGen);
                refreshed.lastSeenMillis = existing.lastSeenMillis;
                refreshed.expiresAtMillis = existing.expiresAtMillis;
                refreshed.liveServices.putAll(existing.liveServices);
                refreshed.liveServiceMetadataBytes = existing.liveServiceMetadataBytes;
                refreshed.subsystemSessions.putAll(existing.subsystemSessions);
                onlineNodes.replace(normId, existing, refreshed);
            }
        }
    }

    /** Immediately persists disable/enable state, bumps generation, invalidates cache, and evicts active online session. */
    public void setDisabled(@NonNull String nodeId, boolean disabled) {
        ensureNotClosed();
        assertNotOnEventLoop("setDisabled");
        String normId = requireValidId(nodeId, "nodeId");
        OnlineNode evicted = null;
        synchronized (lockFor(normId)) {
            NodeEntity entity = db.findById(NodeEntity.class, normId);
            if (entity == null) {
                throw new IllegalArgumentException("Node not found: " + normId);
            }
            entity.setDisabled(disabled);
            entity.setUpdatedAtMillis(clock.getAsLong());
            db.save(entity, false);
            bumpGeneration(normId);
            coldCache.invalidate(normId);
            if (disabled) {
                evicted = evictOnlineUnderNodeLock(normId);
            }
        }
        if (evicted != null) {
            notifyRevoked(evicted);
        }
    }

    /**
     * Authenticates a node against its salted PBKDF2 verifier off-EventLoop.
     * Fails closed (returns {@code false}) on unknown/disabled node, invalid credential,
     * rate/concurrency rejection, or H2/DB errors.
     */
    public boolean authenticate(@NonNull String nodeId, String rawSecret) {
        if (closed.get() || Strings.isEmpty(nodeId) || Strings.isEmpty(rawSecret) || rawSecret.length() > MAX_SECRET_LENGTH) {
            return false;
        }
        assertNotOnEventLoop("authenticate");
        if (!tryAcquireAuthPermit()) {
            authRejectedCount.incrementAndGet();
            return false;
        }
        try {
            String normId = nodeId.trim();
            long genBefore = currentGeneration(normId);
            NodeEntity entity;
            try {
                entity = loadNodeColdFailClosed(normId);
            } catch (Throwable dbErr) {
                log.warn("NodeRegistry fail-closed auth error for nodeId={}: {}", normId, dbErr.getMessage());
                return false;
            }
            if (entity == null || entity.isDisabled()) {
                return false;
            }
            if (!verifySecret(rawSecret, entity.getCredentialVerifier())) {
                return false;
            }
            return !closed.get() && currentGeneration(normId) == genBefore;
        } finally {
            authConcurrencySem.release();
        }
    }

    // endregion

    // region Hot Online State & Bounded Candidates / Live Services

    /**
     * Authenticates and atomically marks a node online with bounded, deduplicated candidates.
     * Enforces per-node generation consistency (preventing a concurrent disable/credential update
     * during PBKDF2 from being bypassed), atomic global/tenant online caps, and replacement eviction.
     */
    public OnlineNode markOnline(@NonNull String nodeId, @NonNull String rawSecret, Object sessionRef,
                                 List<EndpointCandidate> candidates) {
        ensureNotClosed();
        assertNotOnEventLoop("markOnline");
        String normId = requireValidId(nodeId, "nodeId");
        if (Strings.isEmpty(rawSecret) || rawSecret.length() > MAX_SECRET_LENGTH) {
            throw new SecurityException("Node authentication failed: " + normId);
        }
        if (!tryAcquireAuthPermit()) {
            authRejectedCount.incrementAndGet();
            throw new SecurityException("NodeRegistry authentication rate/concurrency limit exceeded");
        }
        OnlineNode replaced = null;
        OnlineNode created;
        long now;
        try {
            long genBefore = currentGeneration(normId);
            NodeEntity entity;
            try {
                entity = loadNodeColdFailClosed(normId);
            } catch (Throwable dbErr) {
                throw new SecurityException("Authorization unavailable (fail-closed): " + dbErr.getMessage(), dbErr);
            }
            if (entity == null || entity.isDisabled() || !verifySecret(rawSecret, entity.getCredentialVerifier())) {
                throw new SecurityException("Node authentication failed or node disabled: " + normId);
            }

            synchronized (onlineAdmissionLock) {
                synchronized (lockFor(normId)) {
                    ensureNotClosed();
                    if (currentGeneration(normId) != genBefore) {
                        throw new SecurityException("Node authorization state changed during login: " + normId);
                    }
                    now = clock.getAsLong();
                    OnlineNode existing = onlineNodes.get(normId);
                    if (existing != null && existing.isExpired(now)) {
                        evictOnlineUnderNodeLock(normId);
                        existing = null;
                    }
                    if (existing == null) {
                        int maxOnline = Math.max(1, config.getMaxOnlineNodes());
                        if (onlineNodes.size() >= maxOnline) {
                            sweepExpiredOnlineNodesInternal(now);
                            if (onlineNodes.size() >= maxOnline) {
                                throw new IllegalStateException("NodeRegistry online node limit reached: " + maxOnline);
                            }
                        }
                        int maxPerTenant = config.getMaxOnlineNodesPerTenant();
                        if (maxPerTenant > 0 && tenantOnlineCount(entity.getTenant()) >= maxPerTenant) {
                            sweepExpiredOnlineNodesInternal(now);
                            if (tenantOnlineCount(entity.getTenant()) >= maxPerTenant) {
                                throw new IllegalStateException("NodeRegistry tenant online limit reached for tenant="
                                        + entity.getTenant() + " limit=" + maxPerTenant);
                            }
                        }
                    } else {
                        replaced = existing;
                        int removedServices = existing.liveServices.size();
                        existing.liveServices.clear();
                        existing.liveServiceMetadataBytes = 0;
                        existing.revoked = true;
                        if (removedServices > 0) {
                            totalLiveServiceCount.addAndGet(-removedServices);
                        }
                        decrementTenantOnlineCount(existing.tenant);
                    }

                    List<EndpointCandidate> bounded = sanitizeCandidates(candidates, now, false);
                    created = new OnlineNode(normId, entity.getTenant(), decodeRoles(entity.getAclRoles()),
                            sessionRef, bounded, now, Math.max(1000L, config.getOnlineTtlMillis()), genBefore);
                    onlineNodes.put(normId, created);
                    incrementTenantOnlineCount(created.tenant);
                }
            }
        } finally {
            authConcurrencySem.release();
        }
        if (replaced != null && replaced.sessionRef != null && !matchesSessionRef(replaced.sessionRef, sessionRef)) {
            notifyRevoked(replaced);
        }
        queueLowPriorityWrite(new PendingWrite(PendingKind.LAST_SEEN, "ls:" + normId, normId,
                null, null, null, now, 64 + normId.length()));
        return created;
    }

    /**
     * Authenticates a node off-EventLoop and attaches a subsystem-scoped session (e.g. {@code "rendezvous"},
     * {@code "rrp-relay"}) to the node's single shared {@link OnlineNode} entry so multiple co-located services
     * sharing one {@link NodeRegistry} do not evict each other, while disable/credential revocation tears down all.
     */
    public OnlineNode authenticateAndAttachSession(@NonNull String nodeId, @NonNull String rawSecret,
                                                   @NonNull String subsystem, @NonNull Object sessionRef,
                                                   List<EndpointCandidate> candidates) {
        ensureNotClosed();
        assertNotOnEventLoop("authenticateAndAttachSession");
        String normId = requireValidId(nodeId, "nodeId");
        String normSub = requireValidId(subsystem, "subsystem");
        if (Strings.isEmpty(rawSecret) || rawSecret.length() > MAX_SECRET_LENGTH) {
            throw new SecurityException("Node authentication failed: " + normId);
        }
        if (!tryAcquireAuthPermit()) {
            authRejectedCount.incrementAndGet();
            throw new SecurityException("NodeRegistry authentication rate/concurrency limit exceeded");
        }
        OnlineNode result;
        long now;
        try {
            long genBefore = currentGeneration(normId);
            NodeEntity entity;
            try {
                entity = loadNodeColdFailClosed(normId);
            } catch (Throwable dbErr) {
                throw new SecurityException("Authorization unavailable (fail-closed): " + dbErr.getMessage(), dbErr);
            }
            if (entity == null || entity.isDisabled() || !verifySecret(rawSecret, entity.getCredentialVerifier())) {
                throw new SecurityException("Node authentication failed or node disabled: " + normId);
            }

            synchronized (onlineAdmissionLock) {
                synchronized (lockFor(normId)) {
                    ensureNotClosed();
                    if (currentGeneration(normId) != genBefore) {
                        throw new SecurityException("Node authorization state changed during login: " + normId);
                    }
                    now = clock.getAsLong();
                    OnlineNode existing = onlineNodes.get(normId);
                    if (existing != null && existing.isExpired(now)) {
                        evictOnlineUnderNodeLock(normId);
                        existing = null;
                    }
                    if (existing == null) {
                        int maxOnline = Math.max(1, config.getMaxOnlineNodes());
                        if (onlineNodes.size() >= maxOnline) {
                            sweepExpiredOnlineNodesInternal(now);
                            if (onlineNodes.size() >= maxOnline) {
                                throw new IllegalStateException("NodeRegistry online node limit reached: " + maxOnline);
                            }
                        }
                        int maxPerTenant = config.getMaxOnlineNodesPerTenant();
                        if (maxPerTenant > 0 && tenantOnlineCount(entity.getTenant()) >= maxPerTenant) {
                            sweepExpiredOnlineNodesInternal(now);
                            if (tenantOnlineCount(entity.getTenant()) >= maxPerTenant) {
                                throw new IllegalStateException("NodeRegistry tenant online limit reached for tenant="
                                        + entity.getTenant() + " limit=" + maxPerTenant);
                            }
                        }
                        List<EndpointCandidate> bounded = sanitizeCandidates(candidates, now, true);
                        result = new OnlineNode(normId, entity.getTenant(), decodeRoles(entity.getAclRoles()),
                                null, bounded, now, Math.max(1000L, config.getOnlineTtlMillis()), genBefore);
                        result.subsystemSessions.put(normSub, sessionRef);
                        onlineNodes.put(normId, result);
                        incrementTenantOnlineCount(result.tenant);
                    } else {
                        existing.subsystemSessions.put(normSub, sessionRef);
                        existing.lastSeenMillis = now;
                        existing.expiresAtMillis = now + Math.max(1000L, config.getOnlineTtlMillis());
                        if (candidates != null && !candidates.isEmpty()) {
                            existing.candidates = mergeClientUpdatedCandidates(existing.candidates, candidates, now);
                        }
                        result = existing;
                    }
                }
            }
        } finally {
            authConcurrencySem.release();
        }
        queueLowPriorityWrite(new PendingWrite(PendingKind.LAST_SEEN, "ls:" + normId, normId,
                null, null, null, now, 64 + normId.length()));
        return result;
    }

    public boolean isSubsystemSessionActive(@NonNull String nodeId, @NonNull String subsystem, Object expectedSessionRef) {
        OnlineNode node = getOnlineNode(nodeId);
        if (node == null || node.revoked) {
            return false;
        }
        Object current = node.subsystemSessions.get(subsystem.trim());
        if (current == null) {
            return false;
        }
        return expectedSessionRef == null || matchesSessionRef(current, expectedSessionRef);
    }

    public boolean heartbeatSubsystem(@NonNull String nodeId, @NonNull String subsystem, Object expectedSessionRef,
                                      List<EndpointCandidate> updatedCandidates) {
        if (closed.get()) {
            return false;
        }
        String normId = nodeId.trim();
        String normSub = subsystem.trim();
        long now;
        synchronized (lockFor(normId)) {
            OnlineNode node = onlineNodes.get(normId);
            if (node == null || node.revoked) {
                return false;
            }
            Object current = node.subsystemSessions.get(normSub);
            if (current == null || (expectedSessionRef != null && !matchesSessionRef(current, expectedSessionRef))) {
                return false;
            }
            now = clock.getAsLong();
            if (node.isExpired(now)) {
                evictOnlineUnderNodeLock(normId);
                return false;
            }
            node.lastSeenMillis = now;
            node.expiresAtMillis = now + Math.max(1000L, config.getOnlineTtlMillis());
            if (updatedCandidates != null) {
                node.candidates = mergeClientUpdatedCandidates(node.candidates, updatedCandidates, now);
            }
        }
        queueLowPriorityWrite(new PendingWrite(PendingKind.LAST_SEEN, "ls:" + normId, normId,
                null, null, null, now, 64 + normId.length()));
        return true;
    }

    public boolean detachSubsystemSession(@NonNull String nodeId, @NonNull String subsystem, Object expectedSessionRef) {
        String normId = nodeId.trim();
        String normSub = subsystem.trim();
        boolean removed = false;
        long now = 0L;
        synchronized (lockFor(normId)) {
            OnlineNode current = onlineNodes.get(normId);
            if (current == null) {
                return false;
            }
            Object attached = current.subsystemSessions.get(normSub);
            if (attached == null || (expectedSessionRef != null && !matchesSessionRef(attached, expectedSessionRef))) {
                return false;
            }
            current.subsystemSessions.remove(normSub, attached);
            removed = true;
            if (current.sessionRef == null && current.subsystemSessions.isEmpty()) {
                evictOnlineUnderNodeLock(normId);
            }
            now = clock.getAsLong();
        }
        if (removed) {
            queueLowPriorityWrite(new PendingWrite(PendingKind.LAST_SEEN, "ls:" + normId, normId,
                    null, null, null, now, 64 + normId.length()));
        }
        return removed;
    }

    /** Hot-path O(1) heartbeat refresh; zero synchronous DB writes. */
    public boolean heartbeat(@NonNull String nodeId, List<EndpointCandidate> updatedCandidates) {
        return heartbeat(nodeId, null, updatedCandidates);
    }

    /** Session-bound heartbeat refresh: stale sessions cannot extend TTL or overwrite candidates of a newer session. */
    public boolean heartbeat(@NonNull String nodeId, Object expectedSessionRef, List<EndpointCandidate> updatedCandidates) {
        if (closed.get()) {
            return false;
        }
        String normId = nodeId.trim();
        long now;
        synchronized (lockFor(normId)) {
            OnlineNode node = onlineNodes.get(normId);
            if (node == null || node.revoked) {
                return false;
            }
            if (expectedSessionRef != null && !matchesAnySessionRef(node, expectedSessionRef)) {
                return false;
            }
            now = clock.getAsLong();
            if (node.isExpired(now)) {
                evictOnlineUnderNodeLock(normId);
                return false;
            }
            node.lastSeenMillis = now;
            node.expiresAtMillis = now + Math.max(1000L, config.getOnlineTtlMillis());
            if (updatedCandidates != null) {
                node.candidates = mergeClientUpdatedCandidates(node.candidates, updatedCandidates, now);
            }
        }
        queueLowPriorityWrite(new PendingWrite(PendingKind.LAST_SEEN, "ls:" + normId, normId,
                null, null, null, now, 64 + normId.length()));
        return true;
    }

    /**
     * Records a server-observed endpoint (e.g. authenticated UDP observation or TCP peer address)
     * without allowing clients to forge verified flags. Preserves existing non-expired candidates within cap.
     */
    public boolean recordServerObservedCandidate(@NonNull String nodeId, Object expectedSessionRef,
                                                 @NonNull EndpointCandidate observed) {
        if (closed.get()) {
            return false;
        }
        String normId = nodeId.trim();
        synchronized (lockFor(normId)) {
            OnlineNode node = onlineNodes.get(normId);
            if (node == null || node.revoked) {
                return false;
            }
            if (expectedSessionRef != null && !matchesAnySessionRef(node, expectedSessionRef)) {
                return false;
            }
            long now = clock.getAsLong();
            if (node.isExpired(now)) {
                evictOnlineUnderNodeLock(normId);
                return false;
            }
            int cap = Math.max(1, config.getMaxCandidatesPerNode());
            LinkedHashMap<String, EndpointCandidate> merged = new LinkedHashMap<String, EndpointCandidate>();
            merged.put(observed.dedupeKey(), observed);
            List<EndpointCandidate> current = node.candidates;
            if (current != null) {
                for (EndpointCandidate c : current) {
                    if (merged.size() >= cap) {
                        break;
                    }
                    if (c == null || c.isExpired(now)) {
                        continue;
                    }
                    // Replace stale server-observed entries of the same observation type
                    if (c.getCandidateType() == observed.getCandidateType()
                            && c.getProtocol() == observed.getProtocol()
                            && c.getAddressFamily() == observed.getAddressFamily()
                            && c.isVerified()) {
                        continue;
                    }
                    merged.putIfAbsent(c.dedupeKey(), c);
                }
            }
            node.candidates = Collections.unmodifiableList(new ArrayList<EndpointCandidate>(merged.values()));
            return true;
        }
    }

    private static boolean matchesAnySessionRef(OnlineNode node, Object expectedSessionRef) {
        if (matchesSessionRef(node.sessionRef, expectedSessionRef)) {
            return true;
        }
        for (Object subRef : node.subsystemSessions.values()) {
            if (matchesSessionRef(subRef, expectedSessionRef)) {
                return true;
            }
        }
        return false;
    }

    /** Sanitizes untrusted client-reported candidates by forcing {@code verified = false} and capping TTL. */
    public List<EndpointCandidate> sanitizeClientReportedCandidates(List<EndpointCandidate> candidates) {
        return sanitizeCandidates(candidates, clock.getAsLong(), true);
    }

    public boolean markOffline(@NonNull String nodeId, Object expectedSessionRef) {
        String normId = nodeId.trim();
        boolean removed;
        long now;
        synchronized (lockFor(normId)) {
            OnlineNode current = onlineNodes.get(normId);
            if (current == null) {
                return false;
            }
            if (expectedSessionRef != null && !matchesSessionRef(current.sessionRef, expectedSessionRef)) {
                return false;
            }
            removed = evictOnlineUnderNodeLock(normId) != null;
            now = clock.getAsLong();
        }
        if (removed) {
            queueLowPriorityWrite(new PendingWrite(PendingKind.LAST_SEEN, "ls:" + normId, normId,
                    null, null, null, now, 64 + normId.length()));
        }
        return removed;
    }

    public boolean isSessionActive(@NonNull String nodeId, Object expectedSessionRef) {
        OnlineNode node = getOnlineNode(nodeId);
        if (node == null || node.revoked) {
            return false;
        }
        return expectedSessionRef == null || matchesSessionRef(node.sessionRef, expectedSessionRef);
    }

    public OnlineNode getOnlineNode(@NonNull String nodeId) {
        String normId = nodeId.trim();
        OnlineNode node = onlineNodes.get(normId);
        if (node == null || node.revoked) {
            return null;
        }
        if (node.isExpired(clock.getAsLong())) {
            synchronized (lockFor(normId)) {
                OnlineNode current = onlineNodes.get(normId);
                if (current == node && current.isExpired(clock.getAsLong())) {
                    evictOnlineUnderNodeLock(normId);
                }
            }
            return null;
        }
        return node;
    }

    /** Returns true only when both nodes are online, active, and requester is authorized (same tenant or admin/discover:*). */
    public boolean isPeerAuthorized(@NonNull String requesterNodeId, @NonNull String targetNodeId) {
        OnlineNode requester = getOnlineNode(requesterNodeId);
        OnlineNode target = getOnlineNode(targetNodeId);
        if (requester == null || target == null) {
            return false;
        }
        return requester.tenant.equals(target.tenant) || hasAdminOrWildcardDiscovery(requester.aclRoles);
    }

    public void registerLiveService(@NonNull String nodeId, @NonNull String appName, @NonNull String instanceId,
                                    Map<String, String> metadata) {
        registerLiveService(nodeId, null, appName, instanceId, metadata);
    }

    /**
     * Session-bound live service registration enforcing per-node service count, global service count,
     * and per-node UTF-8 metadata byte budgets.
     */
    public void registerLiveService(@NonNull String nodeId, Object expectedSessionRef,
                                    @NonNull String appName, @NonNull String instanceId,
                                    Map<String, String> metadata) {
        ensureNotClosed();
        String normId = requireValidId(nodeId, "nodeId");
        String normApp = requireValidId(appName, "appName").toLowerCase(Locale.ROOT);
        String normInst = requireValidId(instanceId, "instanceId");
        Map<String, String> sanitizedMeta = sanitizeServiceMetadata(metadata);
        int metaBytes = estimateMapUtf8Bytes(sanitizedMeta);

        synchronized (lockFor(normId)) {
            OnlineNode node = onlineNodes.get(normId);
            long now = clock.getAsLong();
            if (node == null || node.revoked || node.isExpired(now)) {
                if (node != null && node.isExpired(now)) {
                    evictOnlineUnderNodeLock(normId);
                }
                throw new IllegalStateException("Node is not online: " + normId);
            }
            if (expectedSessionRef != null && !matchesSessionRef(node.sessionRef, expectedSessionRef)) {
                throw new SecurityException("Stale session cannot register live service for node: " + normId);
            }
            String key = normApp + "#" + normInst;
            LiveService existing = node.liveServices.get(key);
            int maxPerNode = Math.max(1, config.getMaxLiveServicesPerNode());
            int maxMetaPerNode = Math.max(32, config.getMaxServiceMetadataBytesPerNode());
            int maxGlobal = Math.max(1, config.getMaxTotalLiveServices());

            if (existing == null && node.liveServices.size() >= maxPerNode) {
                throw new IllegalStateException("Node live service limit exceeded (" + maxPerNode + ") for node: " + normId);
            }
            int nextMetaBytes = node.liveServiceMetadataBytes - (existing == null ? 0 : existing.metadataUtf8Bytes) + metaBytes;
            if (nextMetaBytes > maxMetaPerNode) {
                throw new IllegalArgumentException("Node live service metadata exceeds budget ("
                        + maxMetaPerNode + " bytes): " + nextMetaBytes);
            }
            if (existing == null) {
                if (totalLiveServiceCount.incrementAndGet() > maxGlobal) {
                    totalLiveServiceCount.decrementAndGet();
                    throw new IllegalStateException("NodeRegistry global live service limit exceeded: " + maxGlobal);
                }
            }
            node.liveServices.put(key, new LiveService(node.nodeId, normApp, normInst, sanitizedMeta, metaBytes, now));
            node.liveServiceMetadataBytes = nextMetaBytes;
        }
    }

    public void deregisterLiveService(@NonNull String nodeId, @NonNull String appName, @NonNull String instanceId) {
        deregisterLiveService(nodeId, null, appName, instanceId);
    }

    public void deregisterLiveService(@NonNull String nodeId, Object expectedSessionRef,
                                      @NonNull String appName, @NonNull String instanceId) {
        String normId = nodeId.trim();
        String key = appName.trim().toLowerCase(Locale.ROOT) + "#" + instanceId.trim();
        synchronized (lockFor(normId)) {
            OnlineNode node = onlineNodes.get(normId);
            if (node == null || node.revoked) {
                return;
            }
            if (expectedSessionRef != null && !matchesSessionRef(node.sessionRef, expectedSessionRef)) {
                return;
            }
            LiveService removed = node.liveServices.remove(key);
            if (removed != null) {
                node.liveServiceMetadataBytes = Math.max(0, node.liveServiceMetadataBytes - removed.metadataUtf8Bytes);
                totalLiveServiceCount.decrementAndGet();
            }
        }
    }

    public List<LiveService> discoverLiveServices(@NonNull String requesterNodeId, @NonNull String appName) {
        return discoverLiveServices(requesterNodeId, appName, 0, Math.max(1, config.getMaxQueryPageSize()));
    }

    /** Paginated live service discovery enforcing tenant/ACL isolation and maxQueryPageSize ceiling. */
    public List<LiveService> discoverLiveServices(@NonNull String requesterNodeId, @NonNull String appName,
                                                  int offset, int limit) {
        OnlineNode requester = getOnlineNode(requesterNodeId);
        if (requester == null) {
            return Collections.emptyList();
        }
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(Math.max(1, config.getMaxQueryPageSize()), limit));
        String normApp = appName.trim().toLowerCase(Locale.ROOT);
        boolean admin = hasAdminOrWildcardDiscovery(requester.aclRoles);
        List<LiveService> out = new ArrayList<LiveService>(Math.min(safeLimit, 32));
        long now = clock.getAsLong();
        int skipped = 0;
        for (OnlineNode target : onlineNodes.values()) {
            if (target.revoked || target.isExpired(now)) {
                continue;
            }
            if (!admin && !requester.tenant.equals(target.tenant)) {
                continue;
            }
            for (LiveService svc : target.liveServices.values()) {
                if (!normApp.equals(svc.appName)) {
                    continue;
                }
                if (skipped < safeOffset) {
                    skipped++;
                    continue;
                }
                out.add(svc);
                if (out.size() >= safeLimit) {
                    return out;
                }
            }
        }
        return out;
    }

    /**
     * Returns non-expired candidates for {@code targetNodeId} ONLY when {@code requesterNodeId} is active
     * and authorized (same tenant or admin/discover:* ACL). Unauthorized peers receive an empty list.
     */
    public List<EndpointCandidate> discoverAuthorizedCandidates(@NonNull String requesterNodeId, @NonNull String targetNodeId) {
        OnlineNode requester = getOnlineNode(requesterNodeId);
        OnlineNode target = getOnlineNode(targetNodeId);
        if (requester == null || target == null) {
            return Collections.emptyList();
        }
        if (!requester.tenant.equals(target.tenant) && !hasAdminOrWildcardDiscovery(requester.aclRoles)) {
            return Collections.emptyList();
        }
        long now = clock.getAsLong();
        List<EndpointCandidate> current = target.candidates;
        if (current == null || current.isEmpty()) {
            return Collections.emptyList();
        }
        List<EndpointCandidate> valid = new ArrayList<EndpointCandidate>(current.size());
        for (EndpointCandidate c : current) {
            if (!c.isExpired(now)) {
                valid.add(c);
            }
        }
        return valid;
    }

    public void sweepExpiredOnlineNodes() {
        sweepExpiredOnlineNodesInternal(clock.getAsLong());
    }

    private void sweepExpiredOnlineNodesInternal(long now) {
        for (Map.Entry<String, OnlineNode> entry : onlineNodes.entrySet()) {
            String normId = entry.getKey();
            OnlineNode node = entry.getValue();
            if (node.isExpired(now)) {
                synchronized (lockFor(normId)) {
                    OnlineNode current = onlineNodes.get(normId);
                    if (current == node && current.isExpired(now)) {
                        evictOnlineUnderNodeLock(normId);
                    }
                }
            } else {
                List<EndpointCandidate> list = node.candidates;
                if (list != null && !list.isEmpty()) {
                    boolean anyExpired = false;
                    for (EndpointCandidate c : list) {
                        if (c.isExpired(now)) {
                            anyExpired = true;
                            break;
                        }
                    }
                    if (anyExpired) {
                        synchronized (lockFor(normId)) {
                            OnlineNode current = onlineNodes.get(normId);
                            if (current == node && !current.revoked) {
                                List<EndpointCandidate> latest = current.candidates;
                                if (latest != null) {
                                    List<EndpointCandidate> filtered = new ArrayList<EndpointCandidate>(latest.size());
                                    for (EndpointCandidate c : latest) {
                                        if (!c.isExpired(now)) {
                                            filtered.add(c);
                                        }
                                    }
                                    current.candidates = Collections.unmodifiableList(filtered);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // endregion

    // region Bounded Cold Attributes, Persistent Service Config, Paginated Queries & Coalesced Async Flush

    public void updateMetadataAsync(@NonNull String nodeId, String metadataJson) {
        ensureNotClosed();
        String normId = requireValidId(nodeId, "nodeId");
        String bounded = validateUtf8Budget(metadataJson, MAX_METADATA_BYTES);
        long now = clock.getAsLong();
        int bytes = 64 + normId.length() + (bounded == null ? 0 : bounded.getBytes(StandardCharsets.UTF_8).length);
        queueMetadataWrite(new PendingWrite(PendingKind.METADATA, "meta:" + normId, normId, null, null, bounded, now, bytes));
    }

    /**
     * Atomically checks and updates a custom attribute enforcing the per-node 8 KiB attribute UTF-8 byte budget
     * across H2, in-flight flush, and pending coalesced writes.
     */
    public void setAttribute(@NonNull String nodeId, @NonNull String key, String value) {
        ensureNotClosed();
        assertNotOnEventLoop("setAttribute");
        String normId = requireValidId(nodeId, "nodeId");
        String normKey = requireValidId(key, "attrKey");
        long now = clock.getAsLong();
        synchronized (lockFor(normId)) {
            if (value == null) {
                queueMetadataWrite(new PendingWrite(PendingKind.ATTR_DELETE, "attr:" + normId + "#" + normKey,
                        normId, normKey, null, null, now, 64 + normId.length() + normKey.length()));
                return;
            }
            byte[] valBytes = value.getBytes(StandardCharsets.UTF_8);
            if (valBytes.length > 2048) {
                throw new IllegalArgumentException("Single attribute value exceeds max 2048 UTF-8 bytes");
            }
            Map<String, String> existing = getAttributesInternal(normId);
            existing.put(normKey, value);
            int totalUtf8 = estimateMapUtf8Bytes(existing);
            if (totalUtf8 > Math.max(256, config.getMaxAttrsBytesPerNode())) {
                throw new IllegalArgumentException("Node attributes exceed per-node budget ("
                        + config.getMaxAttrsBytesPerNode() + " bytes): " + totalUtf8);
            }
            int entryBytes = 64 + normId.length() + normKey.length() + valBytes.length;
            queueMetadataWrite(new PendingWrite(PendingKind.ATTR_UPSERT, "attr:" + normId + "#" + normKey,
                    normId, normKey, null, value, now, entryBytes));
        }
    }

    public Map<String, String> getAttributes(@NonNull String nodeId) {
        ensureNotClosed();
        assertNotOnEventLoop("getAttributes");
        String normId = requireValidId(nodeId, "nodeId");
        return getAttributesInternal(normId);
    }

    private Map<String, String> getAttributesInternal(String normId) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        String table = db.tableName(NodeAttributeEntity.class);
        db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT attr_key, attr_value FROM " + table + " WHERE node_id = ?")) {
                ps.setString(1, normId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString(1), rs.getString(2));
                    }
                }
            }
        });
        // Overlay BOTH in-flight flush batch and unflushed coalesced writes for linearizable read-your-own-writes
        synchronized (pendingLock) {
            overlayAttributeWrites(result, normId, inFlightWrites);
            overlayAttributeWrites(result, normId, pendingWrites);
        }
        return result;
    }

    private static void overlayAttributeWrites(Map<String, String> result, String normId, Map<String, PendingWrite> writes) {
        for (PendingWrite w : writes.values()) {
            if (!normId.equals(w.nodeId)) {
                continue;
            }
            if (w.kind == PendingKind.ATTR_UPSERT) {
                result.put(w.fieldA, w.value);
            } else if (w.kind == PendingKind.ATTR_DELETE) {
                result.remove(w.fieldA);
            }
        }
    }

    public void setPersistentServiceConfig(@NonNull String nodeId, @NonNull String appName,
                                           @NonNull String instanceId, String configJson) {
        ensureNotClosed();
        assertNotOnEventLoop("setPersistentServiceConfig");
        String normId = requireValidId(nodeId, "nodeId");
        String normApp = requireValidId(appName, "appName").toLowerCase(Locale.ROOT);
        String normInst = requireValidId(instanceId, "instanceId");
        long now = clock.getAsLong();
        String svcId = NodeServiceEntity.idOf(normId, normApp, normInst);
        String pKey = "svc:" + svcId;
        synchronized (lockFor(normId)) {
            if (configJson == null) {
                queueMetadataWrite(new PendingWrite(PendingKind.SERVICE_DELETE, pKey, normId, normApp, normInst, null, now, 96));
                return;
            }
            String bounded = validateUtf8Budget(configJson, MAX_SERVICE_CONFIG_BYTES);
            Map<String, NodeServiceEntity> existing = getPersistentServiceConfigsMap(normId);
            int maxPersistent = Math.max(1, config.getMaxPersistentServicesPerNode());
            if (!existing.containsKey(svcId) && existing.size() >= maxPersistent) {
                throw new IllegalArgumentException("Node persistent service limit exceeded ("
                        + maxPersistent + ") for node: " + normId);
            }
            int bytes = 96 + normId.length() + normApp.length() + normInst.length()
                    + bounded.getBytes(StandardCharsets.UTF_8).length;
            queueMetadataWrite(new PendingWrite(PendingKind.SERVICE_UPSERT, pKey, normId, normApp, normInst, bounded, now, bytes));
        }
    }

    public List<NodeServiceEntity> getPersistentServiceConfigs(@NonNull String nodeId) {
        ensureNotClosed();
        assertNotOnEventLoop("getPersistentServiceConfigs");
        String normId = requireValidId(nodeId, "nodeId");
        return new ArrayList<NodeServiceEntity>(getPersistentServiceConfigsMap(normId).values());
    }

    private Map<String, NodeServiceEntity> getPersistentServiceConfigsMap(String normId) {
        Map<String, NodeServiceEntity> map = new LinkedHashMap<String, NodeServiceEntity>();
        String table = db.tableName(NodeServiceEntity.class);
        db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, node_id, app_name, instance_id, service_config_json, updated_at_millis FROM " + table + " WHERE node_id = ?")) {
                ps.setString(1, normId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        NodeServiceEntity e = new NodeServiceEntity();
                        e.setId(rs.getString(1));
                        e.setNodeId(rs.getString(2));
                        e.setAppName(rs.getString(3));
                        e.setInstanceId(rs.getString(4));
                        e.setServiceConfigJson(rs.getString(5));
                        e.setUpdatedAtMillis(rs.getLong(6));
                        map.put(e.getId(), e);
                    }
                }
            }
        });
        synchronized (pendingLock) {
            overlayServiceWrites(map, normId, inFlightWrites);
            overlayServiceWrites(map, normId, pendingWrites);
        }
        return map;
    }

    private static void overlayServiceWrites(Map<String, NodeServiceEntity> map, String normId, Map<String, PendingWrite> writes) {
        for (PendingWrite w : writes.values()) {
            if (!normId.equals(w.nodeId)) {
                continue;
            }
            String id = NodeServiceEntity.idOf(normId, w.fieldA, w.fieldB);
            if (w.kind == PendingKind.SERVICE_UPSERT) {
                NodeServiceEntity e = new NodeServiceEntity();
                e.setId(id);
                e.setNodeId(normId);
                e.setAppName(w.fieldA);
                e.setInstanceId(w.fieldB);
                e.setServiceConfigJson(w.value);
                e.setUpdatedAtMillis(w.timestampMillis);
                map.put(id, e);
            } else if (w.kind == PendingKind.SERVICE_DELETE) {
                map.remove(id);
            }
        }
    }

    /** Bounded paginated query over H2 cold store; never loads the full table into memory. */
    public List<NodeSummary> queryNodes(String tenant, int offset, int limit) {
        ensureNotClosed();
        assertNotOnEventLoop("queryNodes");
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(Math.max(1, config.getMaxQueryPageSize()), limit));
        String table = db.tableName(NodeEntity.class);
        List<NodeSummary> out = new ArrayList<NodeSummary>(safeLimit);
        long now = clock.getAsLong();
        boolean filterTenant = !Strings.isEmpty(tenant);
        String sql = filterTenant
                ? "SELECT node_id, tenant, acl_roles, disabled, metadata_json, last_seen_millis FROM " + table
                + " WHERE tenant = ? ORDER BY node_id ASC LIMIT ? OFFSET ?"
                : "SELECT node_id, tenant, acl_roles, disabled, metadata_json, last_seen_millis FROM " + table
                + " ORDER BY node_id ASC LIMIT ? OFFSET ?";
        db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = 1;
                if (filterTenant) {
                    ps.setString(idx++, tenant.trim());
                }
                ps.setInt(idx++, safeLimit);
                ps.setInt(idx, safeOffset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String id = rs.getString(1);
                        OnlineNode live = onlineNodes.get(id);
                        boolean online = live != null && !live.revoked && !live.isExpired(now);
                        long lastSeen = online ? Math.max(rs.getLong(6), live.lastSeenMillis) : rs.getLong(6);
                        out.add(new NodeSummary(id, rs.getString(2), decodeRoles(rs.getString(3)),
                                rs.getBoolean(4), online, rs.getString(5), lastSeen));
                    }
                }
            }
        });
        return out;
    }

    /** Bounded cleanup of stale offline nodes and their child attributes/service configs. */
    public int pruneOfflineNodesOlderThan(long cutoffMillis, int maxDelete) {
        ensureNotClosed();
        assertNotOnEventLoop("pruneOfflineNodesOlderThan");
        int limit = Math.max(1, Math.min(1000, maxDelete));
        String nodeTable = db.tableName(NodeEntity.class);
        String attrTable = db.tableName(NodeAttributeEntity.class);
        String svcTable = db.tableName(NodeServiceEntity.class);
        List<String> candidates = new ArrayList<String>();
        db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT node_id FROM " + nodeTable + " WHERE last_seen_millis > 0 AND last_seen_millis < ? ORDER BY last_seen_millis ASC LIMIT ?")) {
                ps.setLong(1, cutoffMillis);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String id = rs.getString(1);
                        if (!onlineNodes.containsKey(id)) {
                            candidates.add(id);
                        }
                    }
                }
            }
            if (candidates.isEmpty()) {
                return;
            }
            boolean prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement delAttr = conn.prepareStatement("DELETE FROM " + attrTable + " WHERE node_id = ?");
                     PreparedStatement delSvc = conn.prepareStatement("DELETE FROM " + svcTable + " WHERE node_id = ?");
                     PreparedStatement delNode = conn.prepareStatement("DELETE FROM " + nodeTable + " WHERE node_id = ?")) {
                    for (String id : candidates) {
                        delAttr.setString(1, id);
                        delAttr.addBatch();
                        delSvc.setString(1, id);
                        delSvc.addBatch();
                        delNode.setString(1, id);
                        delNode.addBatch();
                    }
                    delAttr.executeBatch();
                    delSvc.executeBatch();
                    delNode.executeBatch();
                }
                conn.commit();
            } catch (Throwable t) {
                conn.rollback();
                throw t;
            } finally {
                conn.setAutoCommit(prevAuto);
            }
        });
        for (String id : candidates) {
            bumpGeneration(id);
            coldCache.invalidate(id);
        }
        return candidates.size();
    }

    /**
     * Executes one serialized coalesced batch flush to H2 immediately (off-EventLoop).
     * Keeps in-flight batch accounted in {@link #pendingPersistBytes()} and visible to readers until commit completes.
     */
    public int flushNow() {
        assertNotOnEventLoop("flushNow");
        synchronized (flushLock) {
            List<PendingWrite> batch;
            synchronized (pendingLock) {
                if (pendingWrites.isEmpty()) {
                    return 0;
                }
                inFlightWrites.clear();
                inFlightWrites.putAll(pendingWrites);
                inFlightBytes = pendingBytes;
                batch = new ArrayList<PendingWrite>(pendingWrites.values());
                pendingWrites.clear();
                pendingBytes = 0L;
            }
            try {
                applyBatchToH2(batch);
                synchronized (pendingLock) {
                    inFlightWrites.clear();
                    inFlightBytes = 0L;
                }
                consecutiveFlushFailures = 0;
                nextFlushAllowedAtMillis = 0L;
                flushSuccessCount.incrementAndGet();
                totalFlushedWrites.addAndGet(batch.size());
                return batch.size();
            } catch (Throwable ex) {
                flushFailureCount.incrementAndGet();
                int fails = ++consecutiveFlushFailures;
                long base = Math.max(500L, config.getFlushRetryBackoffMillis());
                long delay = Math.min(300_000L, base << Math.min(6, fails - 1));
                nextFlushAllowedAtMillis = clock.getAsLong() + delay;
                synchronized (pendingLock) {
                    inFlightWrites.clear();
                    inFlightBytes = 0L;
                    for (PendingWrite w : batch) {
                        if (!pendingWrites.containsKey(w.key)) {
                            enqueueUnderLock(w, w.kind == PendingKind.LAST_SEEN);
                        }
                    }
                }
                throw ex;
            }
        }
    }

    void flushQuietly() {
        if (closed.get()) {
            return;
        }
        if (clock.getAsLong() < nextFlushAllowedAtMillis) {
            return;
        }
        try {
            flushNow();
        } catch (Throwable ex) {
            log.warn("NodeRegistry async H2 flush failed (attempt={}): {}", consecutiveFlushFailures, ex.getMessage());
        }
    }

    private void applyBatchToH2(List<PendingWrite> batch) {
        String nodeTable = db.tableName(NodeEntity.class);
        String attrTable = db.tableName(NodeAttributeEntity.class);
        String svcTable = db.tableName(NodeServiceEntity.class);

        db.withConnection(conn -> {
            boolean prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement updLastSeen = conn.prepareStatement(
                             "UPDATE " + nodeTable + " SET last_seen_millis = CASE WHEN last_seen_millis < ? THEN ? ELSE last_seen_millis END WHERE node_id = ?");
                     PreparedStatement updMeta = conn.prepareStatement(
                             "UPDATE " + nodeTable + " SET metadata_json = ?, updated_at_millis = ? WHERE node_id = ?");
                     PreparedStatement mergeAttr = conn.prepareStatement(
                             "MERGE INTO " + attrTable + " (id, node_id, attr_key, attr_value, updated_at_millis) KEY(id) VALUES (?, ?, ?, ?, ?)");
                     PreparedStatement delAttr = conn.prepareStatement(
                             "DELETE FROM " + attrTable + " WHERE id = ?");
                     PreparedStatement mergeSvc = conn.prepareStatement(
                             "MERGE INTO " + svcTable + " (id, node_id, app_name, instance_id, service_config_json, updated_at_millis) KEY(id) VALUES (?, ?, ?, ?, ?, ?)");
                     PreparedStatement delSvc = conn.prepareStatement(
                             "DELETE FROM " + svcTable + " WHERE id = ?")) {
                    int lsCount = 0, metaCount = 0, attrUpCount = 0, attrDelCount = 0, svcUpCount = 0, svcDelCount = 0;
                    for (PendingWrite w : batch) {
                        switch (w.kind) {
                            case LAST_SEEN:
                                updLastSeen.setLong(1, w.timestampMillis);
                                updLastSeen.setLong(2, w.timestampMillis);
                                updLastSeen.setString(3, w.nodeId);
                                updLastSeen.addBatch();
                                lsCount++;
                                break;
                            case METADATA:
                                updMeta.setString(1, w.value);
                                updMeta.setLong(2, w.timestampMillis);
                                updMeta.setString(3, w.nodeId);
                                updMeta.addBatch();
                                metaCount++;
                                coldCache.invalidate(w.nodeId);
                                break;
                            case ATTR_UPSERT:
                                mergeAttr.setString(1, NodeAttributeEntity.idOf(w.nodeId, w.fieldA));
                                mergeAttr.setString(2, w.nodeId);
                                mergeAttr.setString(3, w.fieldA);
                                mergeAttr.setString(4, w.value);
                                mergeAttr.setLong(5, w.timestampMillis);
                                mergeAttr.addBatch();
                                attrUpCount++;
                                break;
                            case ATTR_DELETE:
                                delAttr.setString(1, NodeAttributeEntity.idOf(w.nodeId, w.fieldA));
                                delAttr.addBatch();
                                attrDelCount++;
                                break;
                            case SERVICE_UPSERT:
                                mergeSvc.setString(1, NodeServiceEntity.idOf(w.nodeId, w.fieldA, w.fieldB));
                                mergeSvc.setString(2, w.nodeId);
                                mergeSvc.setString(3, w.fieldA);
                                mergeSvc.setString(4, w.fieldB);
                                mergeSvc.setString(5, w.value);
                                mergeSvc.setLong(6, w.timestampMillis);
                                mergeSvc.addBatch();
                                svcUpCount++;
                                break;
                            case SERVICE_DELETE:
                                delSvc.setString(1, NodeServiceEntity.idOf(w.nodeId, w.fieldA, w.fieldB));
                                delSvc.addBatch();
                                svcDelCount++;
                                break;
                        }
                    }
                    if (lsCount > 0) updLastSeen.executeBatch();
                    if (metaCount > 0) updMeta.executeBatch();
                    if (attrUpCount > 0) mergeAttr.executeBatch();
                    if (attrDelCount > 0) delAttr.executeBatch();
                    if (svcUpCount > 0) mergeSvc.executeBatch();
                    if (svcDelCount > 0) delSvc.executeBatch();
                }
                conn.commit();
            } catch (Throwable t) {
                conn.rollback();
                throw t;
            } finally {
                conn.setAutoCommit(prevAuto);
            }
        });
    }

    private void queueLowPriorityWrite(PendingWrite write) {
        synchronized (pendingLock) {
            enqueueUnderLock(write, true);
        }
    }

    private void queueMetadataWrite(PendingWrite write) {
        synchronized (pendingLock) {
            enqueueUnderLock(write, false);
        }
    }

    private void enqueueUnderLock(PendingWrite write, boolean lowPriority) {
        long cap = Math.max(4096L, config.getMaxPendingPersistBytes());
        PendingWrite prev = pendingWrites.get(write.key);
        long delta = write.estimatedBytes - (prev == null ? 0L : prev.estimatedBytes);
        if (pendingBytes + inFlightBytes + delta > cap) {
            if (lowPriority) {
                droppedLowPriorityWrites.incrementAndGet();
                return;
            }
            // Evict low-priority LAST_SEEN entries first to preserve metadata/attribute updates
            Iterator<Map.Entry<String, PendingWrite>> it = pendingWrites.entrySet().iterator();
            while (pendingBytes + inFlightBytes + delta > cap && it.hasNext()) {
                Map.Entry<String, PendingWrite> e = it.next();
                if (e.getValue().kind == PendingKind.LAST_SEEN) {
                    pendingBytes -= e.getValue().estimatedBytes;
                    it.remove();
                    droppedLowPriorityWrites.incrementAndGet();
                }
            }
            if (pendingBytes + inFlightBytes + delta > cap) {
                throw new IllegalStateException("NodeRegistry pending persist queue budget exceeded (" + cap + " bytes)");
            }
        }
        pendingWrites.put(write.key, write);
        pendingBytes += delta;
    }

    // endregion

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (flushTask != null) {
            flushTask.cancel(false);
            flushTask = null;
        }
        if (ttlSweepTask != null) {
            ttlSweepTask.cancel(false);
            ttlSweepTask = null;
        }
        if (!inNettyEventLoop()) {
            quietly(this::flushNow);
        }
        onlineNodes.clear();
        tenantOnlineCounts.clear();
        totalLiveServiceCount.set(0);
        coldCache.clear();
        synchronized (pendingLock) {
            pendingWrites.clear();
            inFlightWrites.clear();
            pendingBytes = 0L;
            inFlightBytes = 0L;
        }
        if (ownDb) {
            quietly(db::close);
        }
    }

    private Object lockFor(String normId) {
        return nodeLocks[(normId.hashCode() & 0x7fffffff) % nodeLocks.length];
    }

    private long currentGeneration(String normId) {
        AtomicLong gen = authGenerations.get(normId);
        return gen == null ? 0L : gen.get();
    }

    private long bumpGeneration(String normId) {
        return authGenerations.computeIfAbsent(normId, k -> new AtomicLong()).incrementAndGet();
    }

    private OnlineNode evictOnlineUnderNodeLock(String normId) {
        OnlineNode evicted = onlineNodes.remove(normId);
        if (evicted != null) {
            evicted.revoked = true;
            int removedSvcs = evicted.liveServices.size();
            evicted.liveServices.clear();
            evicted.liveServiceMetadataBytes = 0;
            if (removedSvcs > 0) {
                totalLiveServiceCount.addAndGet(-removedSvcs);
            }
            decrementTenantOnlineCount(evicted.tenant);
        }
        return evicted;
    }

    private int tenantOnlineCount(String tenant) {
        AtomicInteger counter = tenantOnlineCounts.get(tenant);
        return counter == null ? 0 : Math.max(0, counter.get());
    }

    private void incrementTenantOnlineCount(String tenant) {
        tenantOnlineCounts.computeIfAbsent(tenant, k -> new AtomicInteger()).incrementAndGet();
    }

    private void decrementTenantOnlineCount(String tenant) {
        AtomicInteger counter = tenantOnlineCounts.get(tenant);
        if (counter != null && counter.decrementAndGet() <= 0) {
            tenantOnlineCounts.remove(tenant, counter);
        }
    }

    private boolean tryAcquireAuthPermit() {
        int ratePerSec = config.getMaxAuthAttemptsPerSecond();
        if (ratePerSec > 0) {
            synchronized (authRateLock) {
                long now = clock.getAsLong();
                long elapsed = Math.max(0L, now - authLastRefillMillis);
                if (elapsed > 0L) {
                    authTokens = Math.min(ratePerSec, authTokens + (elapsed * (double) ratePerSec) / 1000.0D);
                    authLastRefillMillis = now;
                }
                if (authTokens < 1.0D) {
                    return false;
                }
                authTokens -= 1.0D;
            }
        }
        return authConcurrencySem.tryAcquire();
    }

    private NodeEntity loadNodeColdFailClosed(String nodeId) {
        long genBefore = currentGeneration(nodeId);
        NodeEntity cached = coldCache.get(nodeId);
        if (cached != null) {
            return cached;
        }
        assertNotOnEventLoop("loadNodeColdFailClosed");
        NodeEntity fromDb = db.findById(NodeEntity.class, nodeId);
        if (fromDb != null && currentGeneration(nodeId) == genBefore) {
            coldCache.put(fromDb);
        }
        return fromDb;
    }

    private long countAllNodes() {
        String table = db.tableName(NodeEntity.class);
        return db.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + table);
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    private List<EndpointCandidate> mergeClientUpdatedCandidates(List<EndpointCandidate> existing,
                                                                 List<EndpointCandidate> reported, long now) {
        int cap = Math.max(1, config.getMaxCandidatesPerNode());
        LinkedHashMap<String, EndpointCandidate> merged = new LinkedHashMap<String, EndpointCandidate>();
        // Keep server-observed verified UDP_MAPPED / TCP_OBSERVED entries unless expired
        if (existing != null) {
            for (EndpointCandidate c : existing) {
                if (c != null && !c.isExpired(now) && c.isVerified()
                        && (c.getCandidateType() == EndpointCandidate.CandidateType.UDP_MAPPED
                        || c.getCandidateType() == EndpointCandidate.CandidateType.TCP_OBSERVED)) {
                    merged.put(c.dedupeKey(), c);
                    if (merged.size() >= cap) {
                        break;
                    }
                }
            }
        }
        for (EndpointCandidate c : sanitizeCandidates(reported, now, false)) {
            if (merged.size() >= cap) {
                break;
            }
            merged.putIfAbsent(c.dedupeKey(), c);
        }
        return Collections.unmodifiableList(new ArrayList<EndpointCandidate>(merged.values()));
    }

    private List<EndpointCandidate> sanitizeCandidates(List<EndpointCandidate> candidates, long now, boolean untrustedClient) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        int cap = Math.max(1, config.getMaxCandidatesPerNode());
        long maxTtl = Math.max(1000L, config.getOnlineTtlMillis());
        Map<String, EndpointCandidate> deduped = new LinkedHashMap<String, EndpointCandidate>();
        for (EndpointCandidate c : candidates) {
            if (c == null || c.isExpired(now)) {
                continue;
            }
            EndpointCandidate effective = c;
            if (untrustedClient) {
                long clampedTtl = c.getTtlMillis() <= 0L ? maxTtl : Math.min(c.getTtlMillis(), maxTtl);
                effective = new EndpointCandidate(c.getAddressFamily(), c.getIp(), c.getPort(),
                        c.getProtocol(), c.getCandidateType(), c.getSource(), now, clampedTtl, false);
            }
            deduped.putIfAbsent(effective.dedupeKey(), effective);
            if (deduped.size() >= cap) {
                break;
            }
        }
        return Collections.unmodifiableList(new ArrayList<EndpointCandidate>(deduped.values()));
    }

    private static Map<String, String> sanitizeServiceMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Collections.emptyMap();
        }
        if (metadata.size() > MAX_LIVE_SERVICE_META_ENTRIES) {
            throw new IllegalArgumentException("Live service metadata exceeds max entries ("
                    + MAX_LIVE_SERVICE_META_ENTRIES + "): " + metadata.size());
        }
        Map<String, String> out = new LinkedHashMap<String, String>(metadata.size());
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            String k = requireValidId(e.getKey(), "serviceMetadataKey");
            String v = e.getValue() == null ? "" : validateUtf8Budget(e.getValue(), MAX_LIVE_SERVICE_META_VALUE_BYTES);
            out.put(k, v);
        }
        return out;
    }

    private static int estimateMapUtf8Bytes(Map<String, String> map) {
        int total = 0;
        for (Map.Entry<String, String> e : map.entrySet()) {
            total += e.getKey().getBytes(StandardCharsets.UTF_8).length;
            if (e.getValue() != null) {
                total += e.getValue().getBytes(StandardCharsets.UTF_8).length;
            }
        }
        return total;
    }

    private static boolean matchesSessionRef(Object actual, Object expected) {
        return actual == expected || Objects.equals(actual, expected);
    }

    private void notifyRevoked(OnlineNode evicted) {
        for (Consumer<OnlineNode> cb : revokeListeners) {
            quietly(() -> cb.accept(evicted));
        }
    }

    private void ensureNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("NodeRegistry is closed");
        }
    }

    private static boolean inNettyEventLoop() {
        Thread t = Thread.currentThread();
        if (t instanceof io.netty.util.concurrent.FastThreadLocalThread) {
            String name = t.getName();
            if (name != null && (name.contains("EventLoop") || name.contains("epoll") || name.contains("nioEventLoop"))) {
                return true;
            }
        }
        return false;
    }

    private static void assertNotOnEventLoop(String op) {
        if (inNettyEventLoop()) {
            throw new IllegalStateException("NodeRegistry blocking/KDF operation must not run on Netty EventLoop: " + op);
        }
    }

    private static boolean hasAdminOrWildcardDiscovery(Set<String> roles) {
        return roles != null && (roles.contains("admin") || roles.contains("discover:*"));
    }

    private static String requireValidId(String value, String field) {
        if (Strings.isEmpty(value) || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > 128) {
            throw new IllegalArgumentException(field + " exceeds max length 128");
        }
        return trimmed;
    }

    private static void requireValidSecret(String secret, String field) {
        if (Strings.isEmpty(secret)) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        if (secret.length() > MAX_SECRET_LENGTH) {
            throw new IllegalArgumentException(field + " exceeds max length " + MAX_SECRET_LENGTH);
        }
    }

    private static String validateUtf8Budget(String value, int maxBytes) {
        if (value == null) {
            return null;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        throw new IllegalArgumentException("Payload exceeds max budget of " + maxBytes + " bytes");
    }

    private static String encodeRoles(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String role : roles) {
            if (Strings.isEmpty(role)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(role.trim());
        }
        return sb.toString();
    }

    private static Set<String> decodeRoles(String encoded) {
        if (Strings.isEmpty(encoded)) {
            return Collections.emptySet();
        }
        Set<String> roles = new HashSet<String>();
        for (String part : encoded.split(",")) {
            if (!part.trim().isEmpty()) {
                roles.add(part.trim());
            }
        }
        return roles;
    }

    private int effectivePbkdf2Iterations() {
        return Math.max(1000, Math.min(500_000, config.getPbkdf2Iterations()));
    }

    static String hashSecret(String rawSecret) {
        return hashSecret(rawSecret, PBKDF2_ITERATIONS);
    }

    static String hashSecret(String rawSecret, int iterations) {
        int safeIter = Math.max(1000, Math.min(500_000, iterations));
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = pbkdf2(rawSecret.toCharArray(), salt, safeIter, PBKDF2_KEY_BITS);
        return VERIFIER_PREFIX + safeIter + "$" + toHex(salt) + "$" + toHex(derived);
    }

    static boolean verifySecret(String rawSecret, String storedVerifier) {
        if (Strings.isEmpty(rawSecret) || Strings.isEmpty(storedVerifier) || !storedVerifier.startsWith(VERIFIER_PREFIX)) {
            return false;
        }
        String[] parts = storedVerifier.split("\\$");
        if (parts.length != 4) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 1000 || iterations > 500_000) {
                return false;
            }
            byte[] salt = fromHex(parts[2]);
            byte[] expected = fromHex(parts[3]);
            byte[] actual = pbkdf2(rawSecret.toCharArray(), salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyBits) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyBits);
        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return skf.generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static String toHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            hex[i * 2] = digits[v >>> 4];
            hex[i * 2 + 1] = digits[v & 0x0f];
        }
        return new String(hex);
    }

    private static byte[] fromHex(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Invalid hex string");
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Invalid hex digit");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static NodeEntity copyEntity(NodeEntity src) {
        NodeEntity dst = new NodeEntity();
        dst.setNodeId(src.getNodeId());
        dst.setTenant(src.getTenant());
        dst.setCredentialVerifier(src.getCredentialVerifier());
        dst.setAclRoles(src.getAclRoles());
        dst.setDisabled(src.isDisabled());
        dst.setMetadataJson(src.getMetadataJson());
        dst.setLastSeenMillis(src.getLastSeenMillis());
        dst.setCreatedAtMillis(src.getCreatedAtMillis());
        dst.setUpdatedAtMillis(src.getUpdatedAtMillis());
        return dst;
    }

    private static int estimateEntityBytes(NodeEntity e) {
        int bytes = 128;
        if (e.getNodeId() != null) bytes += e.getNodeId().length() * 2;
        if (e.getTenant() != null) bytes += e.getTenant().length() * 2;
        if (e.getCredentialVerifier() != null) bytes += e.getCredentialVerifier().length() * 2;
        if (e.getAclRoles() != null) bytes += e.getAclRoles().length() * 2;
        if (e.getMetadataJson() != null) bytes += e.getMetadataJson().length() * 2;
        return bytes;
    }
}
