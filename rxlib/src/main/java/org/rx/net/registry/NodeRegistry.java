package org.rx.net.registry;

import io.netty.channel.EventLoop;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static org.rx.core.Extends.quietly;

/**
 * Lightweight shared control-plane directory separating bounded hot online state (in-memory only)
 * from cold identity/ACL/metadata/attribute/service-config state in H2 ({@link EntityDatabase}).
 * <p>
 * Security-critical mutations (enrollment, credential rotation, ACL updates, disable/revoke)
 * persist reliably off-EventLoop with immediate cache invalidation and hot eviction.
 * Low-frequency metadata, lastSeen, attributes, and persistent service configs are key-coalesced
 * and batch-flushed asynchronously within strict byte budgets.
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

    @Getter
    public static final class LiveService {
        private final String nodeId;
        private final String appName;
        private final String instanceId;
        private final Map<String, String> metadata;
        private final long registeredAtMillis;

        LiveService(String nodeId, String appName, String instanceId, Map<String, String> metadata, long registeredAtMillis) {
            this.nodeId = nodeId;
            this.appName = appName;
            this.instanceId = instanceId;
            this.metadata = metadata == null ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<String, String>(metadata));
            this.registeredAtMillis = registeredAtMillis;
        }
    }

    @Getter
    public static final class OnlineNode {
        private final String nodeId;
        private final String tenant;
        private final Set<String> aclRoles;
        private volatile Object sessionRef;
        private volatile List<EndpointCandidate> candidates;
        private final ConcurrentHashMap<String, LiveService> liveServices = new ConcurrentHashMap<String, LiveService>();
        private volatile long onlineSinceMillis;
        private volatile long lastSeenMillis;
        private volatile long expiresAtMillis;

        OnlineNode(String nodeId, String tenant, Set<String> aclRoles, Object sessionRef,
                   List<EndpointCandidate> candidates, long nowMillis, long ttlMillis) {
            this.nodeId = nodeId;
            this.tenant = tenant;
            this.aclRoles = Collections.unmodifiableSet(new HashSet<String>(aclRoles));
            this.sessionRef = sessionRef;
            this.candidates = candidates;
            this.onlineSinceMillis = nowMillis;
            this.lastSeenMillis = nowMillis;
            this.expiresAtMillis = nowMillis + ttlMillis;
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
                if (!it.hasNext()) break;
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
    private final ConcurrentHashMap<String, OnlineNode> onlineNodes = new ConcurrentHashMap<String, OnlineNode>();
    private final Object pendingLock = new Object();
    private final LinkedHashMap<String, PendingWrite> pendingWrites = new LinkedHashMap<String, PendingWrite>();
    private long pendingBytes;
    private final AtomicLong flushSuccessCount = new AtomicLong();
    private final AtomicLong flushFailureCount = new AtomicLong();
    private final AtomicLong totalFlushedWrites = new AtomicLong();
    private final AtomicLong droppedLowPriorityWrites = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile long nextFlushAllowedAtMillis;
    private volatile int consecutiveFlushFailures;
    private volatile ScheduledFuture<?> flushTask;
    private volatile ScheduledFuture<?> ttlSweepTask;
    private volatile Consumer<OnlineNode> onNodeRevoked;

    public NodeRegistry(@NonNull NodeRegistryConfig config) {
        this(config, null, System::currentTimeMillis);
    }

    public NodeRegistry(@NonNull NodeRegistryConfig config, EntityDatabase externalDb) {
        this(config, externalDb, System::currentTimeMillis);
    }

    NodeRegistry(@NonNull NodeRegistryConfig config, EntityDatabase externalDb, LongSupplier clock) {
        this.config = config;
        this.clock = clock;
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

    public void setOnNodeRevoked(Consumer<OnlineNode> callback) {
        this.onNodeRevoked = callback;
    }

    public int onlineNodeCount() {
        return onlineNodes.size();
    }

    public int coldCacheSize() {
        return coldCache.size();
    }

    public int coldCacheBytesUsed() {
        return coldCache.bytesUsed();
    }

    public long pendingPersistBytes() {
        synchronized (pendingLock) {
            return pendingBytes;
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

    // region Security-critical operations (reliable sync H2 write off-EventLoop + immediate invalidation)

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
        if (Strings.isEmpty(rawSecret)) {
            throw new IllegalArgumentException("rawSecret must not be empty");
        }
        String boundedMeta = truncateUtf8(metadataJson, MAX_METADATA_BYTES);
        long now = clock.getAsLong();

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
        entity.setCredentialVerifier(hashSecret(rawSecret));
        entity.setAclRoles(encodeRoles(aclRoles));
        entity.setDisabled(false);
        entity.setMetadataJson(boundedMeta);
        if (existing == null) {
            entity.setCreatedAtMillis(now);
        }
        entity.setUpdatedAtMillis(now);
        db.save(entity, existing == null);
        coldCache.invalidate(normId);
    }

    public void updateCredential(@NonNull String nodeId, @NonNull String newRawSecret) {
        ensureNotClosed();
        assertNotOnEventLoop("updateCredential");
        if (Strings.isEmpty(newRawSecret)) {
            throw new IllegalArgumentException("newRawSecret must not be empty");
        }
        String normId = requireValidId(nodeId, "nodeId");
        NodeEntity entity = db.findById(NodeEntity.class, normId);
        if (entity == null) {
            throw new IllegalArgumentException("Node not found: " + normId);
        }
        entity.setCredentialVerifier(hashSecret(newRawSecret));
        entity.setUpdatedAtMillis(clock.getAsLong());
        db.save(entity, false);
        coldCache.invalidate(normId);
    }

    public void updateAcl(@NonNull String nodeId, Set<String> aclRoles) {
        ensureNotClosed();
        assertNotOnEventLoop("updateAcl");
        String normId = requireValidId(nodeId, "nodeId");
        NodeEntity entity = db.findById(NodeEntity.class, normId);
        if (entity == null) {
            throw new IllegalArgumentException("Node not found: " + normId);
        }
        entity.setAclRoles(encodeRoles(aclRoles));
        entity.setUpdatedAtMillis(clock.getAsLong());
        db.save(entity, false);
        coldCache.invalidate(normId);
        // Update live online node ACL immediately if online
        OnlineNode existing = onlineNodes.get(normId);
        if (existing != null) {
            OnlineNode refreshed = new OnlineNode(existing.nodeId, existing.tenant, decodeRoles(entity.getAclRoles()),
                    existing.sessionRef, existing.candidates, existing.onlineSinceMillis,
                    Math.max(1L, existing.expiresAtMillis - existing.onlineSinceMillis));
            refreshed.lastSeenMillis = existing.lastSeenMillis;
            refreshed.expiresAtMillis = existing.expiresAtMillis;
            refreshed.liveServices.putAll(existing.liveServices);
            onlineNodes.replace(normId, existing, refreshed);
        }
    }

    /** Immediately persists disable/enable state, invalidates cache, and evicts active online session on disable. */
    public void setDisabled(@NonNull String nodeId, boolean disabled) {
        ensureNotClosed();
        assertNotOnEventLoop("setDisabled");
        String normId = requireValidId(nodeId, "nodeId");
        NodeEntity entity = db.findById(NodeEntity.class, normId);
        if (entity == null) {
            throw new IllegalArgumentException("Node not found: " + normId);
        }
        entity.setDisabled(disabled);
        entity.setUpdatedAtMillis(clock.getAsLong());
        db.save(entity, false);
        coldCache.invalidate(normId);
        if (disabled) {
            OnlineNode evicted = onlineNodes.remove(normId);
            if (evicted != null) {
                notifyRevoked(evicted);
            }
        }
    }

    /**
     * Authenticates a node against its salted PBKDF2 verifier.
     * Fails closed (returns {@code false}) on unknown node, disabled node, invalid credential, or H2/DB errors.
     */
    public boolean authenticate(@NonNull String nodeId, String rawSecret) {
        if (closed.get() || Strings.isEmpty(nodeId) || Strings.isEmpty(rawSecret)) {
            return false;
        }
        NodeEntity entity;
        try {
            entity = loadNodeColdFailClosed(nodeId.trim());
        } catch (Throwable dbErr) {
            log.warn("NodeRegistry fail-closed auth error for nodeId={}: {}", nodeId, dbErr.getMessage());
            return false;
        }
        if (entity == null || entity.isDisabled()) {
            return false;
        }
        return verifySecret(rawSecret, entity.getCredentialVerifier());
    }

    // endregion

    // region Hot Online State & Bounded Candidates / Live Services

    /**
     * Authenticates and marks a node online with bounded, deduplicated candidates.
     * Throws {@link SecurityException} on auth/disabled/DB failure (fail-closed) or {@link IllegalStateException} when online cap is reached.
     */
    public OnlineNode markOnline(@NonNull String nodeId, @NonNull String rawSecret, Object sessionRef,
                                 List<EndpointCandidate> candidates) {
        ensureNotClosed();
        String normId = requireValidId(nodeId, "nodeId");
        NodeEntity entity;
        try {
            entity = loadNodeColdFailClosed(normId);
        } catch (Throwable dbErr) {
            throw new SecurityException("Authorization unavailable (fail-closed): " + dbErr.getMessage(), dbErr);
        }
        if (entity == null || entity.isDisabled() || !verifySecret(rawSecret, entity.getCredentialVerifier())) {
            throw new SecurityException("Node authentication failed or node disabled: " + normId);
        }

        if (!onlineNodes.containsKey(normId) && onlineNodes.size() >= Math.max(1, config.getMaxOnlineNodes())) {
            sweepExpiredOnlineNodes();
            if (!onlineNodes.containsKey(normId) && onlineNodes.size() >= Math.max(1, config.getMaxOnlineNodes())) {
                throw new IllegalStateException("NodeRegistry online node limit reached: " + config.getMaxOnlineNodes());
            }
        }
        long now = clock.getAsLong();
        List<EndpointCandidate> bounded = sanitizeCandidates(candidates, now);
        OnlineNode node = new OnlineNode(normId, entity.getTenant(), decodeRoles(entity.getAclRoles()),
                sessionRef, bounded, now, Math.max(1000L, config.getOnlineTtlMillis()));
        onlineNodes.put(normId, node);
        queueLowPriorityWrite(new PendingWrite(PendingKind.LAST_SEEN, "ls:" + normId, normId,
                null, null, null, now, 64 + normId.length()));
        return node;
    }

    /** Hot-path O(1) heartbeat refresh; zero synchronous DB writes. */
    public boolean heartbeat(@NonNull String nodeId, List<EndpointCandidate> updatedCandidates) {
        if (closed.get()) {
            return false;
        }
        OnlineNode node = onlineNodes.get(nodeId.trim());
        if (node == null) {
            return false;
        }
        long now = clock.getAsLong();
        if (node.isExpired(now)) {
            onlineNodes.remove(node.nodeId, node);
            return false;
        }
        node.lastSeenMillis = now;
        node.expiresAtMillis = now + Math.max(1000L, config.getOnlineTtlMillis());
        if (updatedCandidates != null) {
            node.candidates = sanitizeCandidates(updatedCandidates, now);
        }
        // Coalesced by nodeId; never writes every heartbeat immediately
        queueLowPriorityWrite(new PendingWrite(PendingKind.LAST_SEEN, "ls:" + node.nodeId, node.nodeId,
                null, null, null, now, 64 + node.nodeId.length()));
        return true;
    }

    public boolean markOffline(@NonNull String nodeId, Object expectedSessionRef) {
        String normId = nodeId.trim();
        OnlineNode current = onlineNodes.get(normId);
        if (current == null) {
            return false;
        }
        if (expectedSessionRef != null && current.sessionRef != expectedSessionRef) {
            return false;
        }
        boolean removed = onlineNodes.remove(normId, current);
        if (removed) {
            current.liveServices.clear();
            long now = clock.getAsLong();
            queueLowPriorityWrite(new PendingWrite(PendingKind.LAST_SEEN, "ls:" + normId, normId,
                    null, null, null, now, 64 + normId.length()));
        }
        return removed;
    }

    public OnlineNode getOnlineNode(@NonNull String nodeId) {
        OnlineNode node = onlineNodes.get(nodeId.trim());
        if (node == null) {
            return null;
        }
        if (node.isExpired(clock.getAsLong())) {
            onlineNodes.remove(node.nodeId, node);
            return null;
        }
        return node;
    }

    public void registerLiveService(@NonNull String nodeId, @NonNull String appName, @NonNull String instanceId,
                                    Map<String, String> metadata) {
        ensureNotClosed();
        OnlineNode node = getOnlineNode(nodeId);
        if (node == null) {
            throw new IllegalStateException("Node is not online: " + nodeId);
        }
        String normApp = requireValidId(appName, "appName").toLowerCase(Locale.ROOT);
        String normInst = requireValidId(instanceId, "instanceId");
        node.liveServices.put(normApp + "#" + normInst,
                new LiveService(node.nodeId, normApp, normInst, metadata, clock.getAsLong()));
    }

    public void deregisterLiveService(@NonNull String nodeId, @NonNull String appName, @NonNull String instanceId) {
        OnlineNode node = onlineNodes.get(nodeId.trim());
        if (node == null) {
            return;
        }
        node.liveServices.remove(appName.trim().toLowerCase(Locale.ROOT) + "#" + instanceId.trim());
    }

    public List<LiveService> discoverLiveServices(@NonNull String requesterNodeId, @NonNull String appName) {
        OnlineNode requester = getOnlineNode(requesterNodeId);
        if (requester == null) {
            return Collections.emptyList();
        }
        String normApp = appName.trim().toLowerCase(Locale.ROOT);
        boolean admin = hasAdminOrWildcardDiscovery(requester.aclRoles);
        List<LiveService> out = new ArrayList<LiveService>();
        long now = clock.getAsLong();
        for (OnlineNode target : onlineNodes.values()) {
            if (target.isExpired(now)) {
                continue;
            }
            if (!admin && !requester.tenant.equals(target.tenant)) {
                continue;
            }
            for (LiveService svc : target.liveServices.values()) {
                if (normApp.equals(svc.appName)) {
                    out.add(svc);
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
        long now = clock.getAsLong();
        for (Map.Entry<String, OnlineNode> entry : onlineNodes.entrySet()) {
            OnlineNode node = entry.getValue();
            if (node.isExpired(now)) {
                if (onlineNodes.remove(entry.getKey(), node)) {
                    node.liveServices.clear();
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
                        List<EndpointCandidate> filtered = new ArrayList<EndpointCandidate>(list.size());
                        for (EndpointCandidate c : list) {
                            if (!c.isExpired(now)) {
                                filtered.add(c);
                            }
                        }
                        node.candidates = Collections.unmodifiableList(filtered);
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
        String bounded = truncateUtf8(metadataJson, MAX_METADATA_BYTES);
        long now = clock.getAsLong();
        int bytes = 64 + normId.length() + (bounded == null ? 0 : bounded.getBytes(StandardCharsets.UTF_8).length);
        queueMetadataWrite(new PendingWrite(PendingKind.METADATA, "meta:" + normId, normId, null, null, bounded, now, bytes));
    }

    /**
     * Updates a custom attribute enforcing the per-node 8 KiB attribute UTF-8 byte budget.
     * Coalesced into the bounded async H2 batch queue.
     */
    public void setAttribute(@NonNull String nodeId, @NonNull String key, String value) {
        ensureNotClosed();
        assertNotOnEventLoop("setAttribute");
        String normId = requireValidId(nodeId, "nodeId");
        String normKey = requireValidId(key, "attrKey");
        long now = clock.getAsLong();
        if (value == null) {
            queueMetadataWrite(new PendingWrite(PendingKind.ATTR_DELETE, "attr:" + normId + "#" + normKey,
                    normId, normKey, null, null, now, 64 + normId.length() + normKey.length()));
            return;
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > 2048) {
            throw new IllegalArgumentException("Single attribute value exceeds max 2048 UTF-8 bytes");
        }
        Map<String, String> existing = getAttributes(normId);
        existing.put(normKey, value);
        int totalUtf8 = 0;
        for (Map.Entry<String, String> e : existing.entrySet()) {
            totalUtf8 += e.getKey().getBytes(StandardCharsets.UTF_8).length
                    + e.getValue().getBytes(StandardCharsets.UTF_8).length;
        }
        if (totalUtf8 > Math.max(256, config.getMaxAttrsBytesPerNode())) {
            throw new IllegalArgumentException("Node attributes exceed per-node budget ("
                    + config.getMaxAttrsBytesPerNode() + " bytes): " + totalUtf8);
        }
        int entryBytes = 64 + normId.length() + normKey.length() + value.getBytes(StandardCharsets.UTF_8).length;
        queueMetadataWrite(new PendingWrite(PendingKind.ATTR_UPSERT, "attr:" + normId + "#" + normKey,
                normId, normKey, null, value, now, entryBytes));
    }

    public Map<String, String> getAttributes(@NonNull String nodeId) {
        ensureNotClosed();
        assertNotOnEventLoop("getAttributes");
        String normId = requireValidId(nodeId, "nodeId");
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
        // Overlay unflushed coalesced writes so callers see read-your-own-writes consistency
        synchronized (pendingLock) {
            for (PendingWrite w : pendingWrites.values()) {
                if (!normId.equals(w.nodeId)) continue;
                if (w.kind == PendingKind.ATTR_UPSERT) {
                    result.put(w.fieldA, w.value);
                } else if (w.kind == PendingKind.ATTR_DELETE) {
                    result.remove(w.fieldA);
                }
            }
        }
        return result;
    }

    public void setPersistentServiceConfig(@NonNull String nodeId, @NonNull String appName,
                                           @NonNull String instanceId, String configJson) {
        ensureNotClosed();
        String normId = requireValidId(nodeId, "nodeId");
        String normApp = requireValidId(appName, "appName").toLowerCase(Locale.ROOT);
        String normInst = requireValidId(instanceId, "instanceId");
        long now = clock.getAsLong();
        String pKey = "svc:" + NodeServiceEntity.idOf(normId, normApp, normInst);
        if (configJson == null) {
            queueMetadataWrite(new PendingWrite(PendingKind.SERVICE_DELETE, pKey, normId, normApp, normInst, null, now, 96));
            return;
        }
        String bounded = truncateUtf8(configJson, MAX_SERVICE_CONFIG_BYTES);
        int bytes = 96 + normId.length() + normApp.length() + normInst.length() + bounded.getBytes(StandardCharsets.UTF_8).length;
        queueMetadataWrite(new PendingWrite(PendingKind.SERVICE_UPSERT, pKey, normId, normApp, normInst, bounded, now, bytes));
    }

    public List<NodeServiceEntity> getPersistentServiceConfigs(@NonNull String nodeId) {
        ensureNotClosed();
        assertNotOnEventLoop("getPersistentServiceConfigs");
        String normId = requireValidId(nodeId, "nodeId");
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
            for (PendingWrite w : pendingWrites.values()) {
                if (!normId.equals(w.nodeId)) continue;
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
        return new ArrayList<NodeServiceEntity>(map.values());
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
                        boolean online = live != null && !live.isExpired(now);
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
            coldCache.invalidate(id);
        }
        return candidates.size();
    }

    /** Executes one coalesced batch flush to H2 immediately (off-EventLoop). Returns flushed write count. */
    public int flushNow() {
        assertNotOnEventLoop("flushNow");
        List<PendingWrite> batch;
        synchronized (pendingLock) {
            if (pendingWrites.isEmpty()) {
                return 0;
            }
            batch = new ArrayList<PendingWrite>(pendingWrites.values());
            pendingWrites.clear();
            pendingBytes = 0L;
        }
        try {
            applyBatchToH2(batch);
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
            // Re-queue failed writes if still within pending byte budget (newer overwrites win)
            synchronized (pendingLock) {
                for (PendingWrite w : batch) {
                    if (!pendingWrites.containsKey(w.key)) {
                        enqueueUnderLock(w, w.kind == PendingKind.LAST_SEEN);
                    }
                }
            }
            throw ex;
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
        if (pendingBytes + delta > cap) {
            if (lowPriority) {
                droppedLowPriorityWrites.incrementAndGet();
                return;
            }
            // Evict low-priority LAST_SEEN entries first to preserve metadata/attribute updates
            Iterator<Map.Entry<String, PendingWrite>> it = pendingWrites.entrySet().iterator();
            while (pendingBytes + delta > cap && it.hasNext()) {
                Map.Entry<String, PendingWrite> e = it.next();
                if (e.getValue().kind == PendingKind.LAST_SEEN) {
                    pendingBytes -= e.getValue().estimatedBytes;
                    it.remove();
                    droppedLowPriorityWrites.incrementAndGet();
                }
            }
            if (pendingBytes + delta > cap) {
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
        coldCache.clear();
        synchronized (pendingLock) {
            pendingWrites.clear();
            pendingBytes = 0L;
        }
        if (ownDb) {
            quietly(db::close);
        }
    }

    private NodeEntity loadNodeColdFailClosed(String nodeId) {
        NodeEntity cached = coldCache.get(nodeId);
        if (cached != null) {
            return cached;
        }
        assertNotOnEventLoop("loadNodeColdFailClosed");
        NodeEntity fromDb = db.findById(NodeEntity.class, nodeId);
        if (fromDb != null) {
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

    private List<EndpointCandidate> sanitizeCandidates(List<EndpointCandidate> candidates, long now) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        int cap = Math.max(1, config.getMaxCandidatesPerNode());
        Map<String, EndpointCandidate> deduped = new LinkedHashMap<String, EndpointCandidate>();
        for (EndpointCandidate c : candidates) {
            if (c == null || c.isExpired(now)) {
                continue;
            }
            deduped.putIfAbsent(c.dedupeKey(), c);
            if (deduped.size() >= cap) {
                break;
            }
        }
        return Collections.unmodifiableList(new ArrayList<EndpointCandidate>(deduped.values()));
    }

    private void notifyRevoked(OnlineNode evicted) {
        Consumer<OnlineNode> cb = onNodeRevoked;
        if (cb != null) {
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
            throw new IllegalStateException("NodeRegistry blocking H2 operation must not run on Netty EventLoop: " + op);
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

    private static String truncateUtf8(String value, int maxBytes) {
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
            if (Strings.isEmpty(role)) continue;
            if (sb.length() > 0) sb.append(',');
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

    static String hashSecret(String rawSecret) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = pbkdf2(rawSecret.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS);
        return VERIFIER_PREFIX + PBKDF2_ITERATIONS + "$" + toHex(salt) + "$" + toHex(derived);
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
