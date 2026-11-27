package org.rx.net.nameserver;

import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.RandomList;
import org.rx.core.Constants;
import org.rx.core.Linq;
import org.rx.core.NEventArgs;
import org.rx.core.RxConfig;
import org.rx.core.Strings;
import org.rx.core.Sys;
import org.rx.core.Tasks;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.dns.DnsClient;
import org.rx.net.dns.DnsServer;
import org.rx.net.http.HttpServer;
import org.rx.net.nameserver.NameserverReplicaProtocol.DeltaOp;
import org.rx.net.nameserver.NameserverReplicaProtocol.ReplicaAck;
import org.rx.net.nameserver.NameserverReplicaProtocol.ReplicaDeltaRequest;
import org.rx.net.nameserver.NameserverReplicaProtocol.ReplicaRecord;
import org.rx.net.nameserver.NameserverReplicaProtocol.ReplicaSnapshotPage;
import org.rx.net.nameserver.NameserverReplicaProtocol.ReplicaTombstone;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RemotingContext;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.rpc.RpcServerConfig;
import org.rx.net.transport.TcpClientConfig;
import org.rx.net.transport.core.TransportConfig;
import org.rx.net.transport.core.TransportServer;
import org.rx.net.transport.core.TransportSession;

import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.rx.core.Extends.eq;
import static org.rx.core.Extends.ifNull;
import static org.rx.core.Extends.quietly;
import static org.rx.core.Extends.tryClose;

/**
 * Nameserver service registry backed by {@link DnsServer} and shared Transport Core RPC ({@code daily})
 * for both client registration/discovery and authenticated peer-to-peer cluster replication.
 */
@Slf4j
public class NameserverImpl implements Nameserver {
    static final String NAME = "nameserver";
    static final Set<String> REPLICA_SENSITIVE_METHODS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("replicaApplyDelta", "replicaApplySnapshotPage")));
    private static final AtomicLong EPOCH_GEN = new AtomicLong(System.currentTimeMillis() << 16);
    private static final AtomicLong SNAPSHOT_ID_GEN = new AtomicLong();
    private static final SecureRandom NONCE_RANDOM = new SecureRandom();

    static final class SnapshotStaging {
        final long epoch;
        final long version;
        final long snapshotId;
        final int totalPages;
        final Set<Integer> receivedPages = new HashSet<Integer>();
        final Map<String, ReplicaRecord> records = new LinkedHashMap<String, ReplicaRecord>();
        final Map<String, ReplicaTombstone> tombstones = new HashMap<String, ReplicaTombstone>();
        final Map<String, Map<String, Serializable>> appAttrs = new HashMap<String, Map<String, Serializable>>();
        Set<InetSocketAddress> serverEndpoints;

        SnapshotStaging(long epoch, long version, long snapshotId, int totalPages) {
            this.epoch = epoch;
            this.version = version;
            this.snapshotId = snapshotId;
            this.totalPages = totalPages;
        }
    }

    static final class SourcePartition {
        final String sourceId;
        long epoch;
        long lastAppliedVersion;
        final Map<String, ReplicaRecord> instances = new LinkedHashMap<String, ReplicaRecord>();
        final Map<String, Map<String, Serializable>> appAttrs = new HashMap<String, Map<String, Serializable>>();
        final Map<String, ReplicaTombstone> tombstones = new HashMap<String, ReplicaTombstone>();
        SnapshotStaging staging;

        SourcePartition(String sourceId, long epoch) {
            this.sourceId = sourceId;
            this.epoch = epoch;
        }
    }

    final class PeerReplicaClient {
        final InetSocketAddress endpoint;
        final Object queueLock = new Object();
        final Deque<ReplicaDeltaRequest> pendingDeltas = new ArrayDeque<ReplicaDeltaRequest>();
        final AtomicBoolean flushing = new AtomicBoolean();
        final AtomicBoolean closed = new AtomicBoolean();
        volatile boolean fullSyncPending = true;
        volatile Nameserver facade;
        volatile int consecutiveFailures;
        volatile long nextAttemptAtMillis;
        volatile ScheduledFuture<?> retryTask;

        PeerReplicaClient(InetSocketAddress endpoint) {
            this.endpoint = endpoint;
        }

        void enqueueDelta(ReplicaDeltaRequest delta) {
            if (closed.get() || NameserverImpl.this.closed) {
                return;
            }
            synchronized (queueLock) {
                if (!fullSyncPending) {
                    int cap = Math.max(16, config.getReplicaMaxPendingDeltasPerPeer());
                    if (pendingDeltas.size() >= cap) {
                        pendingDeltas.clear();
                        fullSyncPending = true;
                    } else {
                        pendingDeltas.addLast(delta);
                    }
                }
            }
            scheduleFlush();
        }

        void markFullSyncPending() {
            if (closed.get() || NameserverImpl.this.closed) {
                return;
            }
            synchronized (queueLock) {
                pendingDeltas.clear();
                fullSyncPending = true;
            }
            scheduleFlush();
        }

        void onPeerReconnected() {
            if (closed.get() || NameserverImpl.this.closed) {
                return;
            }
            cancelRetryTask();
            consecutiveFailures = 0;
            nextAttemptAtMillis = 0L;
            markFullSyncPending();
        }

        void scheduleFlush() {
            if (closed.get() || NameserverImpl.this.closed) {
                return;
            }
            long now = System.currentTimeMillis();
            long waitMillis = nextAttemptAtMillis - now;
            if (waitMillis > 0) {
                scheduleRetry(waitMillis);
                return;
            }
            if (!flushing.compareAndSet(false, true)) {
                return;
            }
            Tasks.run(this::drainLoop);
        }

        private void scheduleRetry(long delayMillis) {
            if (closed.get() || NameserverImpl.this.closed) {
                return;
            }
            synchronized (queueLock) {
                if (closed.get() || NameserverImpl.this.closed) {
                    return;
                }
                if (retryTask != null && !retryTask.isDone()) {
                    return;
                }
                retryTask = Tasks.setTimeout(() -> {
                    synchronized (queueLock) {
                        retryTask = null;
                    }
                    scheduleFlush();
                }, Math.max(20L, delayMillis));
            }
        }

        private void cancelRetryTask() {
            synchronized (queueLock) {
                if (retryTask != null) {
                    retryTask.cancel(false);
                    retryTask = null;
                }
            }
        }

        private Nameserver getOrCreateFacade() {
            Nameserver current = facade;
            if (current != null) {
                return current;
            }
            synchronized (this) {
                current = facade;
                if (current != null) {
                    return current;
                }
                TransportConfig transport = TransportConfig.dailyClient(endpoint);
                transport.setPeerId(replicaSourceId);
                TcpClientConfig tcpConf = transport.getTcpClientConfig();
                int timeoutMillis = Math.max(500, config.getReplicaSyncTimeoutMillis());
                tcpConf.setConnectTimeoutMillis(timeoutMillis);
                tcpConf.setWaitConnectMillis(Math.min(timeoutMillis, 1000));
                tcpConf.setEnableReconnect(true);
                RpcClientConfig<Nameserver> rpcConf = new RpcClientConfig<Nameserver>(transport);
                rpcConf.setRequestTimeoutMillis(timeoutMillis);
                rpcConf.setSensitiveMethods(REPLICA_SENSITIVE_METHODS);
                rpcConf.setInitHandler((ns, rc) -> {
                    rc.onReconnected.add((s, e) -> onPeerReconnected());
                });
                current = Remoting.createFacade(Nameserver.class, rpcConf);
                facade = current;
                return current;
            }
        }

        private void drainLoop() {
            try {
                while (!closed.get() && !NameserverImpl.this.closed) {
                    long waitMillis = nextAttemptAtMillis - System.currentTimeMillis();
                    if (waitMillis > 0) {
                        scheduleRetry(waitMillis);
                        return;
                    }
                    boolean doFull;
                    ReplicaDeltaRequest delta = null;
                    synchronized (queueLock) {
                        doFull = fullSyncPending;
                        if (!doFull) {
                            delta = pendingDeltas.peekFirst();
                            if (delta == null) {
                                return;
                            }
                        }
                    }

                    String token = effectiveReplicaAuthToken();
                    if (Strings.isEmpty(token)) {
                        log.debug("ns: skip replica push to {} because replicaAuthToken is empty", Sockets.toString(endpoint));
                        return;
                    }

                    try {
                        Nameserver peer = getOrCreateFacade();
                        if (doFull) {
                            List<ReplicaSnapshotPage> pages = buildLocalSnapshotPages(token);
                            long sentVersion = pages.isEmpty() ? 0L : pages.get(0).getVersion();
                            for (ReplicaSnapshotPage page : pages) {
                                ReplicaAck ack = peer.replicaApplySnapshotPage(page);
                                if (ack == null || !ack.isAccepted()) {
                                    throw new IllegalStateException("Peer rejected snapshot page: " + ack);
                                }
                            }
                            synchronized (queueLock) {
                                // Clear fullSyncPending only if no newer local version was queued during push;
                                // if newer deltas were queued they remain handled by next loop iteration.
                                if (localPartition.lastAppliedVersion <= sentVersion) {
                                    fullSyncPending = false;
                                } else {
                                    // Keep fullSyncPending = true to cover any delta dropped while fullSyncPending was set.
                                    fullSyncPending = true;
                                }
                            }
                        } else {
                            ReplicaDeltaRequest signed = signDeltaCopy(token, delta);
                            ReplicaAck ack = peer.replicaApplyDelta(signed);
                            synchronized (queueLock) {
                                if (pendingDeltas.peekFirst() == delta) {
                                    pendingDeltas.pollFirst();
                                }
                                if (ack != null && ack.isNeedFullSync()) {
                                    pendingDeltas.clear();
                                    fullSyncPending = true;
                                }
                            }
                        }
                        consecutiveFailures = 0;
                        nextAttemptAtMillis = 0L;
                    } catch (Throwable ex) {
                        if (closed.get() || NameserverImpl.this.closed) {
                            return;
                        }
                        int failures = ++consecutiveFailures;
                        long base = Math.max(50L, config.getReplicaRetryBaseMillis());
                        long max = Math.max(base, config.getReplicaRetryMaxMillis());
                        long shift = Math.min(10, Math.max(0, failures - 1));
                        long delay = Math.min(max, base << shift);
                        nextAttemptAtMillis = System.currentTimeMillis() + delay;
                        synchronized (queueLock) {
                            pendingDeltas.clear();
                            fullSyncPending = true;
                        }
                        log.warn("ns: replica RPC to {} failed (attempt={}, backoff={}ms): {}",
                                Sockets.toString(endpoint), failures, delay, ex.getMessage());
                        scheduleRetry(delay);
                        return;
                    }
                }
            } finally {
                flushing.set(false);
                if (!closed.get() && !NameserverImpl.this.closed) {
                    boolean hasMore;
                    synchronized (queueLock) {
                        hasMore = fullSyncPending || !pendingDeltas.isEmpty();
                    }
                    if (hasMore && System.currentTimeMillis() >= nextAttemptAtMillis) {
                        scheduleFlush();
                    }
                }
            }
        }

        void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            cancelRetryTask();
            synchronized (queueLock) {
                pendingDeltas.clear();
            }
            Nameserver current = facade;
            facade = null;
            if (current != null) {
                tryClose(current);
            }
        }
    }

    final NameserverConfig config;
    final TransportServer rs;
    @Getter
    final DnsServer dnsServer;
    final boolean ownDnsServer;
    @Getter
    final String replicaSourceId;
    @Getter
    final long replicaEpoch;
    final Object stateLock = new Object();
    final SourcePartition localPartition;
    final Map<String, SourcePartition> remotePartitions = new ConcurrentHashMap<String, SourcePartition>();
    final Map<String, Set<InetAddress>> managedDnsHosts = new HashMap<String, Set<InetAddress>>();
    final Map<String, Map<String, Serializable>> pendingInstanceAttrs = new HashMap<String, Map<String, Serializable>>();
    final Set<InetSocketAddress> svrEps = ConcurrentHashMap.newKeySet();
    final Map<InetSocketAddress, PeerReplicaClient> peerClients = new ConcurrentHashMap<InetSocketAddress, PeerReplicaClient>();
    ScheduledFuture<?> replicaFullSyncTask;
    volatile boolean closed;

    static long nextEpoch() {
        long now = System.currentTimeMillis() << 16;
        return EPOCH_GEN.accumulateAndGet(now, (prev, cur) -> Math.max(prev + 1L, cur));
    }

    static long nextNonce() {
        return NONCE_RANDOM.nextLong();
    }

    public NameserverImpl(@NonNull NameserverConfig config) {
        this(config, null);
    }

    public NameserverImpl(@NonNull NameserverConfig config, DnsServer dnsServer) {
        this.config = config;
        this.ownDnsServer = dnsServer == null;
        this.dnsServer = ownDnsServer ? new DnsServer(config.getDnsPort()) : dnsServer;
        if (ownDnsServer) {
            this.dnsServer.setTtl(config.getDnsTtl());
        }
        String configuredNodeId = config.getNodeId();
        this.replicaSourceId = !Strings.isEmpty(configuredNodeId)
                ? configuredNodeId.trim()
                : RxConfig.INSTANCE.getId() + ":" + config.getRegisterPort();
        this.replicaEpoch = nextEpoch();
        this.localPartition = new SourcePartition(replicaSourceId, replicaEpoch);

        TransportConfig serverTransport = TransportConfig.dailyServer(config.getRegisterPort());
        serverTransport.setPeerId(replicaSourceId);
        RpcServerConfig rpcServerConfig = new RpcServerConfig(serverTransport);
        rpcServerConfig.setSensitiveMethods(REPLICA_SENSITIVE_METHODS);
        this.rs = Remoting.register(this, rpcServerConfig);
        this.rs.onDisconnected.add((s, e) -> {
            TransportSession session = e.getValue();
            String appName = session.attr(APP_NAME_KEY);
            if (appName == null) {
                return;
            }
            String instanceId = session.attr(INSTANCE_ID_KEY);
            if (instanceId == null) {
                instanceId = resolveSessionInstanceId(session, null);
            }
            if (hasOtherConnectedSession(session, appName, instanceId)) {
                return;
            }
            doLocalDeregister(appName, instanceId);
        });

        Set<InetSocketAddress> initialPeers = resolveReplicaEndpoints(
                Linq.from(config.getReplicaEndpoints()).select(Sockets::parseEndpoint).selectMany(Sockets::newAllEndpoints).toSet());
        if (!initialPeers.isEmpty()) {
            svrEps.addAll(initialPeers);
            synchronized (stateLock) {
                reconcileNameserverHostsLocked();
            }
            ensurePeerClients(initialPeers, true);
        }

        long fullSyncPeriodMillis = config.getReplicaFullSyncPeriodMillis();
        if (fullSyncPeriodMillis > 0) {
            replicaFullSyncTask = Tasks.schedulePeriod(this::syncFullSnapshot, fullSyncPeriodMillis);
        }
        registerHttpPage();
    }

    void registerHttpPage() {
        quietly(() -> {
            HttpServer server = HttpServer.getDefault();
            if (server != null) {
                server.requestAsync(NameserverHttpHandler.PAGE_PATH, new NameserverHttpHandler(this));
                log.info("ns: http page registered on {}", NameserverHttpHandler.PAGE_PATH);
            }
        });
    }

    @Override
    public void close() {
        closed = true;
        if (replicaFullSyncTask != null) {
            replicaFullSyncTask.cancel(false);
            replicaFullSyncTask = null;
        }
        for (PeerReplicaClient peer : peerClients.values()) {
            quietly(peer::close);
        }
        peerClients.clear();
        quietly(rs::close);
        if (ownDnsServer) {
            quietly(dnsServer::close);
        }
    }

    public Map<String, List<InstanceInfo>> getInstances() {
        Map<String, List<InstanceInfo>> result = new LinkedHashMap<String, List<InstanceInfo>>();
        synchronized (stateLock) {
            for (ReplicaRecord record : allActiveRecordsLocked()) {
                List<InstanceInfo> list = result.computeIfAbsent(record.getAppName(), k -> new ArrayList<InstanceInfo>());
                list.add(toInstanceInfo(record, Collections.<String>emptyList()));
            }
        }
        return result;
    }

    public long getLocalVersion() {
        synchronized (stateLock) {
            return localPartition.lastAppliedVersion;
        }
    }

    public void syncRegister(Set<InetSocketAddress> serverEndpoints) {
        if (serverEndpoints == null || serverEndpoints.isEmpty()) {
            return;
        }
        Set<InetSocketAddress> resolvedEndpoints = resolveReplicaEndpoints(serverEndpoints);
        if (resolvedEndpoints.isEmpty()) {
            return;
        }
        boolean added = svrEps.addAll(resolvedEndpoints);
        if (!added && resolvedEndpoints.containsAll(svrEps)) {
            ensurePeerClients(resolvedEndpoints, false);
            return;
        }
        Set<InetSocketAddress> snapshot = new HashSet<InetSocketAddress>(svrEps);
        ReplicaDeltaRequest delta = null;
        synchronized (stateLock) {
            reconcileNameserverHostsLocked();
            if (added) {
                long prevVersion = localPartition.lastAppliedVersion;
                long nextVersion = ++localPartition.lastAppliedVersion;
                delta = ReplicaDeltaRequest.syncEndpoints(replicaSourceId, replicaEpoch, nextVersion, prevVersion, nextNonce(), snapshot);
            }
        }
        publishEventAsync(EVENT_CLIENT_SYNC, new NEventArgs<Set<InetSocketAddress>>(snapshot));
        ensurePeerClients(snapshot, true);
        if (delta != null) {
            broadcastDelta(delta);
        }
    }

    Set<InetSocketAddress> resolveReplicaEndpoints(Set<InetSocketAddress> endpoints) {
        Set<InetSocketAddress> result = new HashSet<InetSocketAddress>();
        if (endpoints == null || endpoints.isEmpty()) {
            return result;
        }
        for (InetSocketAddress endpoint : endpoints) {
            if (endpoint == null) {
                continue;
            }
            if (!endpoint.isUnresolved()) {
                result.add(endpoint);
                continue;
            }
            String host = endpoint.getHostString();
            if (Sockets.isValidIp(host)) {
                result.add(new InetSocketAddress(Sockets.parseIpAddress(host), endpoint.getPort()));
                continue;
            }
            try {
                List<InetAddress> addresses = DnsClient.directClient().resolveAll(host);
                for (InetAddress address : addresses) {
                    result.add(new InetSocketAddress(address, endpoint.getPort()));
                }
            } catch (Throwable e) {
                log.warn("ns: Ignore unresolved replica endpoint {}", endpoint, e);
            }
        }
        return result;
    }

    @Override
    public int register(@NonNull String appName, int weight, Set<InetSocketAddress> serverEndpoints) {
        return register(appName, weight, null, serverEndpoints);
    }

    @Override
    public int register(@NonNull String appName, int weight, String instanceId, Set<InetSocketAddress> serverEndpoints) {
        Sys.logCtx("clientSize", rs.sessions().size());

        RemotingContext ctx = RemotingContext.context();
        TransportSession session = ctx.getClient();
        String normApp = normalizeAppName(appName);
        String resolvedInstanceId = resolveSessionInstanceId(session, instanceId);
        session.attr(APP_NAME_KEY, normApp);
        session.attr(INSTANCE_ID_KEY, resolvedInstanceId);
        InetAddress addr = session.remoteEndpoint().getAddress();
        Sys.logCtx("remoteAddr", addr);

        doLocalRegister(normApp, resolvedInstanceId, weight, addr);
        syncRegister(serverEndpoints);
        return config.getDnsPort();
    }

    void doLocalRegister(String appName, String instanceId, int weight, InetAddress addr) {
        String normApp = normalizeAppName(appName);
        String key = instanceKey(normApp, instanceId);
        AppChangedEventArgs event = null;
        ReplicaDeltaRequest delta;
        synchronized (stateLock) {
            ReplicaRecord existing = localPartition.instances.get(key);
            Map<String, Serializable> mergedAttrs = new HashMap<String, Serializable>();
            if (existing != null && existing.getAttributes() != null) {
                mergedAttrs.putAll(existing.getAttributes());
            }
            Map<String, Serializable> pending = pendingInstanceAttrs.remove(key);
            if (pending != null) {
                mergedAttrs.putAll(pending);
            }
            ReplicaRecord updated = new ReplicaRecord(normApp, instanceId, addr, weight, mergedAttrs);
            localPartition.instances.put(key, updated);
            localPartition.tombstones.remove(key);
            reconcileAppDnsHostsLocked(normApp);

            long prevVersion = localPartition.lastAppliedVersion;
            long nextVersion = ++localPartition.lastAppliedVersion;
            delta = ReplicaDeltaRequest.upsert(replicaSourceId, replicaEpoch, nextVersion, prevVersion, nextNonce(), updated.copy());

            if (existing == null || !Objects.equals(existing.getAddress(), addr)) {
                event = new AppChangedEventArgs(normApp, addr, true, instanceId, new HashMap<String, Serializable>(mergedAttrs));
            }
        }
        if (event != null) {
            publishEventAsync(EVENT_APP_ADDRESS_CHANGED, event);
        }
        broadcastDelta(delta);
    }

    @Override
    public void deregister() {
        RemotingContext ctx = RemotingContext.context();
        TransportSession session = ctx.getClient();
        String appName = session.attr(APP_NAME_KEY);
        if (appName == null) {
            throw new InvalidException("Must register first");
        }
        String instanceId = resolveSessionInstanceId(session, null);
        doLocalDeregister(appName, instanceId);
    }

    void doLocalDeregister(String appName, String instanceId) {
        String normApp = normalizeAppName(appName);
        String key = instanceKey(normApp, instanceId);
        AppChangedEventArgs event = null;
        ReplicaDeltaRequest delta = null;
        synchronized (stateLock) {
            pendingInstanceAttrs.remove(key);
            ReplicaRecord removed = localPartition.instances.remove(key);
            if (removed != null) {
                log.info("ns: deregister {} instance={}", normApp, instanceId);
                long prevVersion = localPartition.lastAppliedVersion;
                long nextVersion = ++localPartition.lastAppliedVersion;
                long expiresAt = System.currentTimeMillis() + Math.max(1000L, config.getReplicaTombstoneTtlMillis());
                putTombstoneLocked(localPartition, new ReplicaTombstone(normApp, instanceId, replicaEpoch, nextVersion, expiresAt));
                reconcileAppDnsHostsLocked(normApp);
                event = new AppChangedEventArgs(normApp, removed.getAddress(), false, instanceId,
                        removed.getAttributes() == null ? Collections.<String, Serializable>emptyMap()
                                : new HashMap<String, Serializable>(removed.getAttributes()));
                delta = ReplicaDeltaRequest.remove(replicaSourceId, replicaEpoch, nextVersion, prevVersion, nextNonce(), normApp, instanceId);
            }
        }
        if (event != null) {
            publishEventAsync(EVENT_APP_ADDRESS_CHANGED, event);
        }
        if (delta != null) {
            broadcastDelta(delta);
        }
    }

    @Override
    public <T extends Serializable> void attr(@NonNull String appName, @NonNull String key, T value) {
        String normApp = normalizeAppName(appName);
        ReplicaDeltaRequest delta;
        synchronized (stateLock) {
            Map<String, Serializable> appMap = localPartition.appAttrs.computeIfAbsent(normApp, k -> new HashMap<String, Serializable>());
            if (value == null) {
                appMap.remove(key);
            } else {
                appMap.put(key, value);
            }
            long prevVersion = localPartition.lastAppliedVersion;
            long nextVersion = ++localPartition.lastAppliedVersion;
            delta = ReplicaDeltaRequest.setAppAttr(replicaSourceId, replicaEpoch, nextVersion, prevVersion, nextNonce(), normApp, key, value);
        }
        broadcastDelta(delta);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends Serializable> T attr(@NonNull String appName, @NonNull String key) {
        String normApp = normalizeAppName(appName);
        synchronized (stateLock) {
            Map<String, Serializable> localMap = localPartition.appAttrs.get(normApp);
            if (localMap != null && localMap.containsKey(key)) {
                return (T) localMap.get(key);
            }
            for (SourcePartition partition : remotePartitions.values()) {
                Map<String, Serializable> remoteMap = partition.appAttrs.get(normApp);
                if (remoteMap != null && remoteMap.containsKey(key)) {
                    return (T) remoteMap.get(key);
                }
            }
            return null;
        }
    }

    @Override
    public <T extends Serializable> void instanceAttr(@NonNull String appName, @NonNull String key, T value) {
        RemotingContext ctx = RemotingContext.context();
        TransportSession session = ctx.getClient();
        String normApp = normalizeAppName(appName);
        String instanceId = resolveSessionInstanceId(session, null);
        String instKey = instanceKey(normApp, instanceId);
        ReplicaDeltaRequest delta = null;
        synchronized (stateLock) {
            ReplicaRecord record = localPartition.instances.get(instKey);
            if (record == null) {
                Map<String, Serializable> pending = pendingInstanceAttrs.computeIfAbsent(instKey, k -> new HashMap<String, Serializable>());
                if (value == null) {
                    pending.remove(key);
                } else {
                    pending.put(key, value);
                }
            } else {
                if (value == null) {
                    record.getAttributes().remove(key);
                } else {
                    record.getAttributes().put(key, value);
                }
                long prevVersion = localPartition.lastAppliedVersion;
                long nextVersion = ++localPartition.lastAppliedVersion;
                delta = ReplicaDeltaRequest.upsert(replicaSourceId, replicaEpoch, nextVersion, prevVersion, nextNonce(), record.copy());
            }
        }
        if (delta != null) {
            broadcastDelta(delta);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends Serializable> T instanceAttr(@NonNull String appName, @NonNull String key) {
        RemotingContext ctx = RemotingContext.context();
        TransportSession session = ctx.getClient();
        String normApp = normalizeAppName(appName);
        String instanceId = resolveSessionInstanceId(session, null);
        String instKey = instanceKey(normApp, instanceId);
        synchronized (stateLock) {
            ReplicaRecord record = localPartition.instances.get(instKey);
            if (record != null && record.getAttributes() != null) {
                return (T) record.getAttributes().get(key);
            }
            Map<String, Serializable> pending = pendingInstanceAttrs.get(instKey);
            return pending == null ? null : (T) pending.get(key);
        }
    }

    Map<String, Serializable> instanceAttrsSnapshot(String appName, String instanceId) {
        String instKey = instanceKey(normalizeAppName(appName), instanceId);
        synchronized (stateLock) {
            ReplicaRecord record = localPartition.instances.get(instKey);
            if (record != null && record.getAttributes() != null) {
                return new HashMap<String, Serializable>(record.getAttributes());
            }
            for (SourcePartition partition : remotePartitions.values()) {
                ReplicaRecord remote = partition.instances.get(instKey);
                if (remote != null && remote.getAttributes() != null) {
                    return new HashMap<String, Serializable>(remote.getAttributes());
                }
            }
            Map<String, Serializable> pending = pendingInstanceAttrs.get(instKey);
            return pending == null ? null : new HashMap<String, Serializable>(pending);
        }
    }

    @Override
    public List<InetAddress> discover(@NonNull String appName) {
        return dnsServer.getHosts(appName);
    }

    @Override
    public List<InetAddress> discoverAll(@NonNull String appName, boolean exceptCurrent) {
        List<InetAddress> hosts = dnsServer.getAllHosts(appName);
        if (hosts.isEmpty()) {
            return hosts;
        }
        if (exceptCurrent) {
            RemotingContext ctx = RemotingContext.context();
            hosts.remove(ctx.getClient().remoteEndpoint().getAddress());
        }
        return hosts;
    }

    @Override
    public List<InstanceInfo> discover(@NonNull String appName, List<String> instanceAttrKeys) {
        return discoverInstances(appName, false, instanceAttrKeys, true);
    }

    @Override
    public List<InstanceInfo> discoverAll(@NonNull String appName, boolean exceptCurrent, List<String> instanceAttrKeys) {
        return discoverInstances(appName, exceptCurrent, instanceAttrKeys, false);
    }

    private List<InstanceInfo> discoverInstances(String appName, boolean exceptCurrent, List<String> instanceAttrKeys, boolean weightedSubset) {
        String normApp = normalizeAppName(appName);
        String currentInstanceId = null;
        InetAddress currentAddress = null;
        if (exceptCurrent) {
            RemotingContext ctx = RemotingContext.context();
            TransportSession session = ctx.getClient();
            currentInstanceId = session.attr(INSTANCE_ID_KEY);
            currentAddress = session.remoteEndpoint().getAddress();
        }
        List<ReplicaRecord> matched = new ArrayList<ReplicaRecord>();
        Set<InetAddress> coveredAddresses = new HashSet<InetAddress>();
        synchronized (stateLock) {
            collectAppRecords(localPartition, normApp, matched, coveredAddresses);
            for (SourcePartition partition : remotePartitions.values()) {
                collectAppRecords(partition, normApp, matched, coveredAddresses);
            }
        }
        List<InstanceInfo> result = new ArrayList<InstanceInfo>();
        for (ReplicaRecord record : matched) {
            if (exceptCurrent && isCurrentInstance(record, currentInstanceId, currentAddress)) {
                continue;
            }
            result.add(toInstanceInfo(record, instanceAttrKeys));
        }
        // Include any static DNS hosts not managed as registered instances
        List<InetAddress> dnsHosts = weightedSubset ? dnsServer.getHosts(normApp) : dnsServer.getAllHosts(normApp);
        for (InetAddress addr : dnsHosts) {
            if (coveredAddresses.contains(addr)) {
                continue;
            }
            if (exceptCurrent && Objects.equals(addr, currentAddress)) {
                continue;
            }
            Map<String, Serializable> values = new HashMap<String, Serializable>();
            String ping = heartbeatText(heartbeatRttMillis(null, addr));
            if (ping != null && (CollectionUtils.isEmpty(instanceAttrKeys) || instanceAttrKeys.contains("ping"))) {
                values.put("ping", ping);
            }
            result.add(new InstanceInfo(addr, null, values));
        }
        return result;
    }

    private boolean isCurrentInstance(ReplicaRecord record, String currentInstanceId, InetAddress currentAddress) {
        if (currentInstanceId != null) {
            return currentInstanceId.equals(record.getInstanceId());
        }
        return Objects.equals(currentAddress, record.getAddress());
    }

    private InstanceInfo toInstanceInfo(ReplicaRecord record, List<String> instanceAttrKeys) {
        Map<String, Serializable> srcAttrs = record.getAttributes() == null
                ? Collections.<String, Serializable>emptyMap()
                : record.getAttributes();
        Map<String, Serializable> values = new HashMap<String, Serializable>();
        Iterable<String> keys = !CollectionUtils.isEmpty(instanceAttrKeys) ? instanceAttrKeys : srcAttrs.keySet();
        for (String key : keys) {
            values.put(key, srcAttrs.get(key));
        }
        String ping = heartbeatText(heartbeatRttMillis(record.getInstanceId(), record.getAddress()));
        if (ping != null && (CollectionUtils.isEmpty(instanceAttrKeys) || instanceAttrKeys.contains("ping"))) {
            values.put("ping", ping);
        }
        return new InstanceInfo(record.getAddress(), record.getInstanceId(), values);
    }

    @Override
    public ReplicaAck replicaApplyDelta(@NonNull ReplicaDeltaRequest request) {
        verifyReplicaAuthorization(request.getSourceId(), request.getSourceEpoch(), request.getVersion(),
                token -> NameserverReplicaProtocol.verifyDelta(token, request));
        if (request.getOp() == null) {
            throw new IllegalArgumentException("Replica delta op is required");
        }
        List<AppChangedEventArgs> events = new ArrayList<AppChangedEventArgs>();
        Set<InetSocketAddress> newEndpointsToWatch = null;
        ReplicaAck ack;
        synchronized (stateLock) {
            SourcePartition partition = remotePartitions.computeIfAbsent(
                    request.getSourceId(), id -> new SourcePartition(id, 0L));
            long now = System.currentTimeMillis();
            pruneExpiredTombstonesLocked(partition, now);

            long incomingEpoch = request.getSourceEpoch();
            long incomingVersion = request.getVersion();
            if (incomingEpoch < partition.epoch) {
                log.debug("ns: ignore stale epoch delta from {} epoch {} < {}", request.getSourceId(), incomingEpoch, partition.epoch);
                return new ReplicaAck(false, false, partition.epoch, partition.lastAppliedVersion);
            }
            if (incomingEpoch == partition.epoch && incomingVersion <= partition.lastAppliedVersion) {
                // Idempotent duplicate / older version
                return new ReplicaAck(true, false, partition.epoch, partition.lastAppliedVersion);
            }

            boolean needFullSync = false;
            if (incomingEpoch > partition.epoch) {
                // Source restarted with a newer epoch: drop old incarnation state immediately
                clearPartitionStateLocked(partition, events);
                partition.epoch = incomingEpoch;
                partition.lastAppliedVersion = 0L;
                partition.staging = null;
                if (request.getBaseVersion() > 0L) {
                    needFullSync = true;
                }
            } else if (request.getBaseVersion() > 0L && request.getBaseVersion() != partition.lastAppliedVersion) {
                // Gap detected in sequence; request a full snapshot to converge atomically
                return new ReplicaAck(false, true, partition.epoch, partition.lastAppliedVersion);
            }

            switch (request.getOp()) {
                case UPSERT_INSTANCE:
                    ReplicaRecord rec = request.getRecord();
                    if (rec == null || Strings.isEmpty(rec.getAppName()) || Strings.isEmpty(rec.getInstanceId()) || rec.getAddress() == null) {
                        throw new IllegalArgumentException("Invalid UPSERT_INSTANCE record");
                    }
                    String normApp = normalizeAppName(rec.getAppName());
                    String instKey = instanceKey(normApp, rec.getInstanceId());
                    ReplicaTombstone tomb = partition.tombstones.get(instKey);
                    if (tomb != null && !tomb.isExpired(now)
                            && (tomb.getSourceEpoch() > incomingEpoch
                            || (tomb.getSourceEpoch() == incomingEpoch && tomb.getVersion() >= incomingVersion))) {
                        // Tombstone is newer than or equal to this upsert; ignore
                        break;
                    }
                    partition.tombstones.remove(instKey);
                    ReplicaRecord copy = new ReplicaRecord(normApp, rec.getInstanceId(), rec.getAddress(), rec.getWeight(), rec.getAttributes());
                    ReplicaRecord old = partition.instances.put(instKey, copy);
                    reconcileAppDnsHostsLocked(normApp);
                    if (old == null || !Objects.equals(old.getAddress(), copy.getAddress())) {
                        events.add(new AppChangedEventArgs(normApp, copy.getAddress(), true, copy.getInstanceId(),
                                new HashMap<String, Serializable>(copy.getAttributes())));
                    }
                    break;
                case REMOVE_INSTANCE:
                    if (Strings.isEmpty(request.getAppName()) || Strings.isEmpty(request.getInstanceId())) {
                        throw new IllegalArgumentException("Invalid REMOVE_INSTANCE request");
                    }
                    String remApp = normalizeAppName(request.getAppName());
                    String remKey = instanceKey(remApp, request.getInstanceId());
                    ReplicaRecord removed = partition.instances.remove(remKey);
                    long expiresAt = now + Math.max(1000L, config.getReplicaTombstoneTtlMillis());
                    putTombstoneLocked(partition, new ReplicaTombstone(remApp, request.getInstanceId(), incomingEpoch, incomingVersion, expiresAt));
                    if (removed != null) {
                        reconcileAppDnsHostsLocked(remApp);
                        events.add(new AppChangedEventArgs(remApp, removed.getAddress(), false, removed.getInstanceId(),
                                removed.getAttributes() == null ? Collections.<String, Serializable>emptyMap()
                                        : new HashMap<String, Serializable>(removed.getAttributes())));
                    }
                    break;
                case SET_APP_ATTR:
                    if (Strings.isEmpty(request.getAppName()) || Strings.isEmpty(request.getAttrKey())) {
                        throw new IllegalArgumentException("Invalid SET_APP_ATTR request");
                    }
                    String attrApp = normalizeAppName(request.getAppName());
                    Map<String, Serializable> appMap = partition.appAttrs.computeIfAbsent(attrApp, k -> new HashMap<String, Serializable>());
                    if (request.getAttrValue() == null) {
                        appMap.remove(request.getAttrKey());
                    } else {
                        appMap.put(request.getAttrKey(), request.getAttrValue());
                    }
                    break;
                case SYNC_ENDPOINTS:
                    if (request.getServerEndpoints() != null && !request.getServerEndpoints().isEmpty()) {
                        Set<InetSocketAddress> resolved = resolveReplicaEndpoints(request.getServerEndpoints());
                        if (svrEps.addAll(resolved)) {
                            reconcileNameserverHostsLocked();
                            newEndpointsToWatch = new HashSet<InetSocketAddress>(svrEps);
                        }
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported delta op: " + request.getOp());
            }

            if (!needFullSync) {
                partition.lastAppliedVersion = incomingVersion;
            }
            ack = new ReplicaAck(true, needFullSync, partition.epoch, partition.lastAppliedVersion);
        }

        if (newEndpointsToWatch != null) {
            publishEventAsync(EVENT_CLIENT_SYNC, new NEventArgs<Set<InetSocketAddress>>(newEndpointsToWatch));
            ensurePeerClients(newEndpointsToWatch, true);
        }
        for (AppChangedEventArgs ev : events) {
            publishEventAsync(EVENT_APP_ADDRESS_CHANGED, ev);
        }
        return ack;
    }

    @Override
    public ReplicaAck replicaApplySnapshotPage(@NonNull ReplicaSnapshotPage page) {
        verifyReplicaAuthorization(page.getSourceId(), page.getSourceEpoch(), page.getVersion(),
                token -> NameserverReplicaProtocol.verifySnapshotPage(token, page));

        int maxPages = Math.max(1, config.getReplicaMaxSnapshotPages());
        int maxPageSize = Math.max(1, config.getReplicaMaxSnapshotPageSize());
        if (page.getTotalPages() < 1 || page.getTotalPages() > maxPages) {
            throw new IllegalArgumentException("Invalid snapshot totalPages: " + page.getTotalPages());
        }
        if (page.getPageIndex() < 0 || page.getPageIndex() >= page.getTotalPages()) {
            throw new IllegalArgumentException("Invalid snapshot pageIndex: " + page.getPageIndex());
        }
        if (page.getRecords() != null && page.getRecords().size() > maxPageSize) {
            throw new IllegalArgumentException("Snapshot page exceeds maxPageSize: " + page.getRecords().size());
        }
        int maxTombstones = Math.max(16, config.getReplicaMaxTombstonesPerSource());
        if (page.getTombstones() != null && page.getTombstones().size() > maxTombstones) {
            throw new IllegalArgumentException("Snapshot page exceeds maxTombstones: " + page.getTombstones().size());
        }

        List<AppChangedEventArgs> events = new ArrayList<AppChangedEventArgs>();
        Set<InetSocketAddress> newEndpointsToWatch = null;
        ReplicaAck ack;
        synchronized (stateLock) {
            SourcePartition partition = remotePartitions.computeIfAbsent(
                    page.getSourceId(), id -> new SourcePartition(id, 0L));
            long now = System.currentTimeMillis();
            pruneExpiredTombstonesLocked(partition, now);

            long incomingEpoch = page.getSourceEpoch();
            long incomingVersion = page.getVersion();
            if (incomingEpoch < partition.epoch) {
                return new ReplicaAck(false, false, partition.epoch, partition.lastAppliedVersion);
            }
            if (incomingEpoch == partition.epoch && incomingVersion < partition.lastAppliedVersion) {
                return new ReplicaAck(false, false, partition.epoch, partition.lastAppliedVersion);
            }
            if (incomingEpoch == partition.epoch && incomingVersion == partition.lastAppliedVersion
                    && partition.staging == null) {
                // Idempotent replay of already committed snapshot
                return new ReplicaAck(true, false, partition.epoch, partition.lastAppliedVersion);
            }

            SnapshotStaging staging = partition.staging;
            if (staging == null || staging.epoch != incomingEpoch || staging.version != incomingVersion
                    || staging.snapshotId != page.getSnapshotId() || staging.totalPages != page.getTotalPages()) {
                staging = new SnapshotStaging(incomingEpoch, incomingVersion, page.getSnapshotId(), page.getTotalPages());
                partition.staging = staging;
            }

            if (page.getRecords() != null) {
                for (ReplicaRecord rec : page.getRecords()) {
                    if (rec == null || Strings.isEmpty(rec.getAppName()) || Strings.isEmpty(rec.getInstanceId()) || rec.getAddress() == null) {
                        continue;
                    }
                    String normApp = normalizeAppName(rec.getAppName());
                    staging.records.put(instanceKey(normApp, rec.getInstanceId()),
                            new ReplicaRecord(normApp, rec.getInstanceId(), rec.getAddress(), rec.getWeight(), rec.getAttributes()));
                }
            }
            if (page.getTombstones() != null) {
                for (ReplicaTombstone tomb : page.getTombstones()) {
                    if (tomb == null || Strings.isEmpty(tomb.getAppName()) || Strings.isEmpty(tomb.getInstanceId()) || tomb.isExpired(now)) {
                        continue;
                    }
                    String normApp = normalizeAppName(tomb.getAppName());
                    staging.tombstones.put(instanceKey(normApp, tomb.getInstanceId()),
                            new ReplicaTombstone(normApp, tomb.getInstanceId(), tomb.getSourceEpoch(), tomb.getVersion(), tomb.getExpiresAtMillis()));
                }
            }
            if (page.getAppAttrs() != null) {
                for (Map.Entry<String, Map<String, Serializable>> entry : page.getAppAttrs().entrySet()) {
                    if (Strings.isEmpty(entry.getKey()) || entry.getValue() == null) {
                        continue;
                    }
                    staging.appAttrs.put(normalizeAppName(entry.getKey()), new HashMap<String, Serializable>(entry.getValue()));
                }
            }
            if (page.getServerEndpoints() != null && !page.getServerEndpoints().isEmpty()) {
                if (staging.serverEndpoints == null) {
                    staging.serverEndpoints = new HashSet<InetSocketAddress>();
                }
                staging.serverEndpoints.addAll(page.getServerEndpoints());
            }

            staging.receivedPages.add(page.getPageIndex());
            if (staging.receivedPages.size() == staging.totalPages) {
                // Commit staged snapshot atomically for ONLY this sourceId's authoritative partition
                Set<String> affectedApps = new LinkedHashSet<String>();
                Map<String, ReplicaRecord> oldInstances = new LinkedHashMap<String, ReplicaRecord>(partition.instances);
                for (ReplicaRecord oldRec : oldInstances.values()) {
                    affectedApps.add(oldRec.getAppName());
                }

                // Merge tombstones (preserve any existing newer tombstones if same epoch)
                Map<String, ReplicaTombstone> nextTombstones = new HashMap<String, ReplicaTombstone>();
                if (incomingEpoch == partition.epoch) {
                    for (Map.Entry<String, ReplicaTombstone> e : partition.tombstones.entrySet()) {
                        if (!e.getValue().isExpired(now)) {
                            nextTombstones.put(e.getKey(), e.getValue());
                        }
                    }
                }
                for (Map.Entry<String, ReplicaTombstone> e : staging.tombstones.entrySet()) {
                    ReplicaTombstone existingTomb = nextTombstones.get(e.getKey());
                    ReplicaTombstone cand = e.getValue();
                    if (existingTomb == null
                            || cand.getSourceEpoch() > existingTomb.getSourceEpoch()
                            || (cand.getSourceEpoch() == existingTomb.getSourceEpoch() && cand.getVersion() >= existingTomb.getVersion())) {
                        nextTombstones.put(e.getKey(), cand);
                    }
                }

                Map<String, ReplicaRecord> nextInstances = new LinkedHashMap<String, ReplicaRecord>();
                for (Map.Entry<String, ReplicaRecord> e : staging.records.entrySet()) {
                    ReplicaTombstone tomb = nextTombstones.get(e.getKey());
                    if (tomb != null && !tomb.isExpired(now)
                            && (tomb.getSourceEpoch() > incomingEpoch
                            || (tomb.getSourceEpoch() == incomingEpoch && tomb.getVersion() >= incomingVersion))) {
                        continue;
                    }
                    nextInstances.put(e.getKey(), e.getValue());
                    affectedApps.add(e.getValue().getAppName());
                }

                partition.instances.clear();
                partition.instances.putAll(nextInstances);
                partition.appAttrs.clear();
                partition.appAttrs.putAll(staging.appAttrs);
                partition.tombstones.clear();
                partition.tombstones.putAll(nextTombstones);
                pruneExpiredTombstonesLocked(partition, now);
                partition.epoch = incomingEpoch;
                partition.lastAppliedVersion = incomingVersion;
                partition.staging = null;

                for (String app : affectedApps) {
                    reconcileAppDnsHostsLocked(app);
                }

                for (Map.Entry<String, ReplicaRecord> e : oldInstances.entrySet()) {
                    if (!nextInstances.containsKey(e.getKey())) {
                        ReplicaRecord oldRec = e.getValue();
                        events.add(new AppChangedEventArgs(oldRec.getAppName(), oldRec.getAddress(), false, oldRec.getInstanceId(),
                                oldRec.getAttributes() == null ? Collections.<String, Serializable>emptyMap()
                                        : new HashMap<String, Serializable>(oldRec.getAttributes())));
                    }
                }
                for (Map.Entry<String, ReplicaRecord> e : nextInstances.entrySet()) {
                    ReplicaRecord oldRec = oldInstances.get(e.getKey());
                    ReplicaRecord newRec = e.getValue();
                    if (oldRec == null || !Objects.equals(oldRec.getAddress(), newRec.getAddress())) {
                        events.add(new AppChangedEventArgs(newRec.getAppName(), newRec.getAddress(), true, newRec.getInstanceId(),
                                new HashMap<String, Serializable>(newRec.getAttributes())));
                    }
                }

                if (staging.serverEndpoints != null && !staging.serverEndpoints.isEmpty()) {
                    Set<InetSocketAddress> resolved = resolveReplicaEndpoints(staging.serverEndpoints);
                    if (svrEps.addAll(resolved)) {
                        reconcileNameserverHostsLocked();
                        newEndpointsToWatch = new HashSet<InetSocketAddress>(svrEps);
                    }
                }
            }
            ack = new ReplicaAck(true, false, partition.epoch, partition.lastAppliedVersion);
        }

        if (newEndpointsToWatch != null) {
            publishEventAsync(EVENT_CLIENT_SYNC, new NEventArgs<Set<InetSocketAddress>>(newEndpointsToWatch));
            ensurePeerClients(newEndpointsToWatch, true);
        }
        for (AppChangedEventArgs ev : events) {
            publishEventAsync(EVENT_APP_ADDRESS_CHANGED, ev);
        }
        return ack;
    }

    interface TokenVerifier {
        boolean verify(String token);
    }

    private void verifyReplicaAuthorization(String sourceId, long epoch, long version, TokenVerifier verifier) {
        if (Strings.isEmpty(sourceId) || epoch <= 0L || version < 0L) {
            throw new IllegalArgumentException("Invalid replica header");
        }
        if (replicaSourceId.equals(sourceId)) {
            throw new IllegalArgumentException("Reject self-loop replica source: " + sourceId);
        }
        String expectedToken = effectiveReplicaAuthToken();
        if (Strings.isEmpty(expectedToken)) {
            throw new SecurityException("Nameserver replica auth token is not configured");
        }
        if (!verifier.verify(expectedToken)) {
            throw new SecurityException("Nameserver replica authentication failed for source: " + sourceId);
        }
        Set<String> authorized = config.getAuthorizedReplicaPeers();
        if (authorized != null && !authorized.isEmpty()) {
            String remoteIp = currentRemoteIp();
            if (!authorized.contains(sourceId) && (remoteIp == null || !authorized.contains(remoteIp))) {
                throw new SecurityException("Unauthorized replica peer source=" + sourceId + " remoteIp=" + remoteIp);
            }
        }
    }

    private String currentRemoteIp() {
        try {
            RemotingContext ctx = RemotingContext.context();
            TransportSession session = ctx == null ? null : ctx.getClient();
            InetSocketAddress remote = session == null ? null : session.remoteEndpoint();
            return remote != null && remote.getAddress() != null ? remote.getAddress().getHostAddress() : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    String effectiveReplicaAuthToken() {
        String configured = config.getReplicaAuthToken();
        if (!Strings.isEmpty(configured)) {
            return configured;
        }
        return RxConfig.INSTANCE.getRtoken();
    }

    void syncFullSnapshot() {
        if (closed) {
            return;
        }
        ensurePeerClients(new HashSet<InetSocketAddress>(svrEps), false);
        for (PeerReplicaClient peer : peerClients.values()) {
            peer.markFullSyncPending();
        }
    }

    List<ReplicaSnapshotPage> buildLocalSnapshotPages(String authToken) {
        List<ReplicaRecord> allRecords;
        List<ReplicaTombstone> activeTombstones;
        Map<String, Map<String, Serializable>> appAttrsCopy;
        Set<InetSocketAddress> epsCopy = new HashSet<InetSocketAddress>(svrEps);
        long version;
        long now = System.currentTimeMillis();
        synchronized (stateLock) {
            pruneExpiredTombstonesLocked(localPartition, now);
            version = localPartition.lastAppliedVersion;
            allRecords = new ArrayList<ReplicaRecord>(localPartition.instances.size());
            for (ReplicaRecord rec : localPartition.instances.values()) {
                allRecords.add(rec.copy());
            }
            activeTombstones = new ArrayList<ReplicaTombstone>(localPartition.tombstones.size());
            for (ReplicaTombstone t : localPartition.tombstones.values()) {
                if (!t.isExpired(now)) {
                    activeTombstones.add(new ReplicaTombstone(t.getAppName(), t.getInstanceId(),
                            t.getSourceEpoch(), t.getVersion(), t.getExpiresAtMillis()));
                }
            }
            appAttrsCopy = new HashMap<String, Map<String, Serializable>>(localPartition.appAttrs.size());
            for (Map.Entry<String, Map<String, Serializable>> e : localPartition.appAttrs.entrySet()) {
                appAttrsCopy.put(e.getKey(), new HashMap<String, Serializable>(e.getValue()));
            }
        }

        int pageSize = Math.max(1, config.getReplicaMaxSnapshotPageSize());
        int maxPages = Math.max(1, config.getReplicaMaxSnapshotPages());
        int totalPages = Math.max(1, (allRecords.size() + pageSize - 1) / pageSize);
        if (totalPages > maxPages) {
            log.warn("ns: local snapshot records {} exceed max pages {}x{}; truncating", allRecords.size(), maxPages, pageSize);
            totalPages = maxPages;
        }
        long snapshotId = SNAPSHOT_ID_GEN.incrementAndGet();
        List<ReplicaSnapshotPage> pages = new ArrayList<ReplicaSnapshotPage>(totalPages);
        for (int i = 0; i < totalPages; i++) {
            int from = i * pageSize;
            int to = Math.min(allRecords.size(), from + pageSize);
            ReplicaSnapshotPage page = new ReplicaSnapshotPage();
            page.setSourceId(replicaSourceId);
            page.setSourceEpoch(replicaEpoch);
            page.setVersion(version);
            page.setSnapshotId(snapshotId);
            page.setPageIndex(i);
            page.setTotalPages(totalPages);
            page.setNonce(nextNonce());
            if (from < to) {
                page.setRecords(new ArrayList<ReplicaRecord>(allRecords.subList(from, to)));
            } else {
                page.setRecords(new ArrayList<ReplicaRecord>());
            }
            if (i == 0) {
                page.setTombstones(activeTombstones);
                page.setAppAttrs(appAttrsCopy);
                page.setServerEndpoints(epsCopy);
            }
            if (!Strings.isEmpty(authToken)) {
                page.setAuthTag(NameserverReplicaProtocol.signSnapshotPage(authToken, page));
            }
            pages.add(page);
        }
        return pages;
    }

    private ReplicaDeltaRequest signDeltaCopy(String token, ReplicaDeltaRequest src) {
        ReplicaDeltaRequest copy = new ReplicaDeltaRequest();
        copy.setSourceId(src.getSourceId());
        copy.setSourceEpoch(src.getSourceEpoch());
        copy.setVersion(src.getVersion());
        copy.setBaseVersion(src.getBaseVersion());
        copy.setNonce(nextNonce());
        copy.setOp(src.getOp());
        copy.setRecord(src.getRecord());
        copy.setAppName(src.getAppName());
        copy.setInstanceId(src.getInstanceId());
        copy.setAttrKey(src.getAttrKey());
        copy.setAttrValue(src.getAttrValue());
        copy.setServerEndpoints(src.getServerEndpoints());
        copy.setAuthTag(NameserverReplicaProtocol.signDelta(token, copy));
        return copy;
    }

    private void ensurePeerClients(Set<InetSocketAddress> endpoints, boolean triggerInitialSync) {
        if (closed || endpoints == null || endpoints.isEmpty()) {
            return;
        }
        int maxPeers = Math.max(1, config.getReplicaMaxPeers());
        for (InetSocketAddress ep : endpoints) {
            if (ep == null || ep.isUnresolved() || isLocalReplicaEndpoint(ep)) {
                continue;
            }
            PeerReplicaClient existing = peerClients.get(ep);
            if (existing != null) {
                continue;
            }
            if (peerClients.size() >= maxPeers) {
                log.warn("ns: replica peer cap {} reached, skip {}", maxPeers, Sockets.toString(ep));
                continue;
            }
            PeerReplicaClient created = new PeerReplicaClient(ep);
            if (peerClients.putIfAbsent(ep, created) == null && triggerInitialSync) {
                created.markFullSyncPending();
            }
        }
    }

    private void broadcastDelta(ReplicaDeltaRequest delta) {
        if (closed || delta == null) {
            return;
        }
        ensurePeerClients(new HashSet<InetSocketAddress>(svrEps), false);
        for (PeerReplicaClient peer : peerClients.values()) {
            peer.enqueueDelta(delta);
        }
    }

    boolean isLocalReplicaEndpoint(InetSocketAddress endpoint) {
        int localPort = rs != null && rs.isStarted() ? rs.listenPort() : config.getRegisterPort();
        if (endpoint == null || endpoint.isUnresolved() || endpoint.getPort() != localPort) {
            return false;
        }
        InetAddress address = endpoint.getAddress();
        if (address == null) {
            return false;
        }
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()) {
            return true;
        }
        try {
            return NetworkInterface.getByInetAddress(address) != null;
        } catch (Throwable e) {
            log.debug("ns: check local replica endpoint failed {}", endpoint, e);
            return false;
        }
    }

    private void clearPartitionStateLocked(SourcePartition partition, List<AppChangedEventArgs> events) {
        if (partition.instances.isEmpty() && partition.appAttrs.isEmpty() && partition.tombstones.isEmpty()) {
            return;
        }
        Set<String> affectedApps = new LinkedHashSet<String>();
        for (ReplicaRecord oldRec : partition.instances.values()) {
            affectedApps.add(oldRec.getAppName());
            events.add(new AppChangedEventArgs(oldRec.getAppName(), oldRec.getAddress(), false, oldRec.getInstanceId(),
                    oldRec.getAttributes() == null ? Collections.<String, Serializable>emptyMap()
                            : new HashMap<String, Serializable>(oldRec.getAttributes())));
        }
        partition.instances.clear();
        partition.appAttrs.clear();
        partition.tombstones.clear();
        for (String app : affectedApps) {
            reconcileAppDnsHostsLocked(app);
        }
    }

    private void putTombstoneLocked(SourcePartition partition, ReplicaTombstone tombstone) {
        String key = instanceKey(tombstone.getAppName(), tombstone.getInstanceId());
        partition.tombstones.put(key, tombstone);
        pruneExpiredTombstonesLocked(partition, System.currentTimeMillis());
        int cap = Math.max(16, config.getReplicaMaxTombstonesPerSource());
        while (partition.tombstones.size() > cap) {
            String oldestKey = null;
            long oldestVersion = Long.MAX_VALUE;
            for (Map.Entry<String, ReplicaTombstone> entry : partition.tombstones.entrySet()) {
                if (entry.getValue().getVersion() < oldestVersion) {
                    oldestVersion = entry.getValue().getVersion();
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey == null) {
                break;
            }
            partition.tombstones.remove(oldestKey);
        }
    }

    private void pruneExpiredTombstonesLocked(SourcePartition partition, long nowMillis) {
        Iterator<Map.Entry<String, ReplicaTombstone>> it = partition.tombstones.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isExpired(nowMillis)) {
                it.remove();
            }
        }
    }

    private void reconcileNameserverHostsLocked() {
        Map<InetAddress, Integer> desired = new LinkedHashMap<InetAddress, Integer>();
        for (InetSocketAddress ep : svrEps) {
            if (ep != null && !ep.isUnresolved() && ep.getAddress() != null) {
                desired.put(ep.getAddress(), RandomList.DEFAULT_WEIGHT);
            }
        }
        reconcileDnsHostsLocked(NAME, desired);
    }

    private void reconcileAppDnsHostsLocked(String normAppName) {
        Map<InetAddress, Integer> desired = new LinkedHashMap<InetAddress, Integer>();
        collectActiveAddresses(localPartition, normAppName, desired);
        for (SourcePartition partition : remotePartitions.values()) {
            collectActiveAddresses(partition, normAppName, desired);
        }
        reconcileDnsHostsLocked(normAppName, desired);
    }

    private void reconcileDnsHostsLocked(String normAppName, Map<InetAddress, Integer> desired) {
        Set<InetAddress> prev = managedDnsHosts.get(normAppName);
        if (prev != null && !prev.isEmpty()) {
            List<InetAddress> toRemove = null;
            for (InetAddress oldAddr : prev) {
                if (!desired.containsKey(oldAddr)) {
                    if (toRemove == null) {
                        toRemove = new ArrayList<InetAddress>();
                    }
                    toRemove.add(oldAddr);
                }
            }
            if (toRemove != null) {
                dnsServer.removeHosts(normAppName, toRemove);
            }
        }
        for (Map.Entry<InetAddress, Integer> entry : desired.entrySet()) {
            dnsServer.addHosts(normAppName, entry.getValue(), Collections.singletonList(entry.getKey()));
        }
        if (desired.isEmpty()) {
            managedDnsHosts.remove(normAppName);
        } else {
            managedDnsHosts.put(normAppName, new HashSet<InetAddress>(desired.keySet()));
        }
    }

    private static void collectActiveAddresses(SourcePartition partition, String normAppName, Map<InetAddress, Integer> out) {
        for (ReplicaRecord rec : partition.instances.values()) {
            if (normAppName.equals(rec.getAppName()) && rec.getAddress() != null) {
                Integer prev = out.get(rec.getAddress());
                int weight = Math.max(1, rec.getWeight());
                if (prev == null || weight > prev) {
                    out.put(rec.getAddress(), weight);
                }
            }
        }
    }

    private static void collectAppRecords(SourcePartition partition, String normAppName,
                                          List<ReplicaRecord> out, Set<InetAddress> coveredAddresses) {
        for (ReplicaRecord rec : partition.instances.values()) {
            if (normAppName.equals(rec.getAppName())) {
                out.add(rec.copy());
                if (rec.getAddress() != null) {
                    coveredAddresses.add(rec.getAddress());
                }
            }
        }
    }

    private List<ReplicaRecord> allActiveRecordsLocked() {
        List<ReplicaRecord> all = new ArrayList<ReplicaRecord>();
        for (ReplicaRecord rec : localPartition.instances.values()) {
            all.add(rec.copy());
        }
        for (SourcePartition partition : remotePartitions.values()) {
            for (ReplicaRecord rec : partition.instances.values()) {
                all.add(rec.copy());
            }
        }
        return all;
    }

    private boolean hasOtherConnectedSession(TransportSession closedSession, String appName, String instanceId) {
        for (TransportSession other : rs.sessions().values()) {
            if (other == closedSession || !other.isConnected()) {
                continue;
            }
            if (eq(other.attr(APP_NAME_KEY), appName) && eq(other.attr(INSTANCE_ID_KEY), instanceId)) {
                return true;
            }
        }
        return false;
    }

    private String resolveSessionInstanceId(TransportSession session, String explicitInstanceId) {
        if (!Strings.isEmpty(explicitInstanceId)) {
            return explicitInstanceId.trim();
        }
        String existing = session.attr(INSTANCE_ID_KEY);
        if (!Strings.isEmpty(existing)) {
            return existing;
        }
        String remotePeerId = session.remotePeerId();
        if (!Strings.isEmpty(remotePeerId)) {
            return remotePeerId;
        }
        return "sess-" + session.sessionId();
    }

    static String normalizeAppName(String appName) {
        String s = appName.trim().toLowerCase(Locale.ROOT);
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    static String instanceKey(String normAppName, String instanceId) {
        return normAppName + "#" + instanceId;
    }

    long heartbeatRttMillis(String instanceId, InetAddress address) {
        long best = -1L;
        for (TransportSession session : rs.sessions().values()) {
            if (instanceId != null) {
                if (!instanceId.equals(session.attr(INSTANCE_ID_KEY))) {
                    continue;
                }
            } else {
                InetSocketAddress endpoint = session.remoteEndpoint();
                if (endpoint == null || address == null || !address.equals(endpoint.getAddress())) {
                    continue;
                }
            }
            long rtt = session.heartbeatRttMillis();
            if (rtt >= 0 && (best < 0 || rtt < best)) {
                best = rtt;
            }
        }
        return best;
    }

    static String heartbeatText(long rttMillis) {
        return rttMillis < 0 ? null : Long.toString(rttMillis) + "ms";
    }
}
