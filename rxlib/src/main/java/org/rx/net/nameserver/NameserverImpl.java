package org.rx.net.nameserver;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.RandomList;
import org.rx.core.*;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.dns.DnsClient;
import org.rx.net.dns.DnsServer;
import org.rx.net.http.HttpServer;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RemotingContext;
import org.rx.net.transport.FuryUdpClientCodec;
import org.rx.net.transport.UdpClient;
import org.rx.net.transport.UdpClientConfig;
import org.rx.net.transport.hybrid.HybridServer;
import org.rx.net.transport.hybrid.HybridSession;

import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.rx.core.Extends.*;

/**
 * Nameserver 的 registerPort 同时承载 TCP 注册入口，默认也作为 replica sync UDP 端口。
 * <p>
 * rssGateway 发布脚本依赖全局 {@code app.net.reusePortBindCount=2} 支持新旧进程同端口并行启动，
 * 不能为了解决 854 sync UDP 日志而把全局 reuseport 降为 1，否则 replace/coexist 发布会退化或失败。
 * replica sync 属于低频控制面流量，不参与 rssGateway 热切换，因此这里只在 Nameserver 自己的
 * {@link UdpClientConfig} 上固定 {@code reusePortBindCount=1}，避免 854 UDP 继承全局多 bind。
 */
@Slf4j
public class NameserverImpl implements Nameserver {
    @RequiredArgsConstructor
    @ToString
    static class DeregisterInfo implements Serializable {
        private static final long serialVersionUID = 713672672746841635L;
        final String sourceId;
        final long version;
        final String appName;
        final InetAddress address;
    }

    @RequiredArgsConstructor
    @ToString
    static class ReplicaSnapshot implements Serializable {
        private static final long serialVersionUID = -5790980112424307032L;
        final String sourceId;
        final long version;
        final Set<InetSocketAddress> serverEndpoints;
        final Map<Object, Map<String, Serializable>> attrs;
    }

    @RequiredArgsConstructor
    @ToString
    static class ReplicaFullSync implements Serializable {
        private static final long serialVersionUID = 1660036726413927370L;
        final String sourceId;
        final long version;
        final Set<InetSocketAddress> serverEndpoints;
        final Map<String, List<InetAddress>> hosts;
        final Map<Object, Map<String, Serializable>> attrs;
    }

    static final String NAME = "nameserver";
    final NameserverConfig config;
    final HybridServer rs;
    @Getter
    final DnsServer dnsServer;
    final boolean ownDnsServer;
    @Setter
    long syncDelay = 500;
    final UdpClient ss;
    final Set<InetSocketAddress> svrEps = ConcurrentHashMap.newKeySet();
    // key String -> appName or appName#ip instance key
    final Map<Object, Map<String, Serializable>> attrs = new ConcurrentHashMap<>();
    final String replicaSourceId = RxConfig.INSTANCE.getId();
    final AtomicLong replicaVersion = new AtomicLong();
    final Map<String, Long> lastAppliedReplicaVersions = new ConcurrentHashMap<String, Long>();
    ScheduledFuture<?> replicaFullSyncTask;

    int getSyncPort() {
        if (config.getSyncPort() > 0) {
            return config.getSyncPort();
        }
        return config.getRegisterPort();
    }

    public Map<String, List<InstanceInfo>> getInstances() {
        return Linq.from(rs.sessions().values()).groupByIntoMap(p -> ifNull(p.attr(APP_NAME_KEY), "NOT_REG"),
                (k, p) -> getDiscoverInfos(k, p.select(x -> x.tcpRemoteEndpoint().getAddress()).toList(), Collections.emptyList()));
    }

    public NameserverImpl(@NonNull NameserverConfig config) {
        this(config, null);
    }

    public NameserverImpl(@NonNull NameserverConfig config, DnsServer dnsServer) {
        this.config = config;
        ownDnsServer = dnsServer == null;
        this.dnsServer = ownDnsServer ? new DnsServer(config.getDnsPort()) : dnsServer;
        if (ownDnsServer) {
            this.dnsServer.setTtl(config.getDnsTtl());
        }
        svrEps.addAll(resolveReplicaEndpoints(Linq.from(config.getReplicaEndpoints()).select(Sockets::parseEndpoint).selectMany(Sockets::newAllEndpoints).toSet()));

        rs = Remoting.registerHybrid(this, config.getRegisterPort(), false);
        rs.onDisconnected.add((s, e) -> {
            HybridSession session = e.getValue();
            String appName = session.attr(APP_NAME_KEY);
            if (appName == null) {
                return;
            }

            doDeregister(appName, session.tcpRemoteEndpoint().getAddress(), true, true);
        });
        FuryUdpClientCodec syncCodec = FuryUdpClientCodec.createDefault();
        if (config.getUdpCodecAllowPrefixes() != null) {
            for (String prefix : new ArrayList<>(config.getUdpCodecAllowPrefixes())) {
                syncCodec.allowPrefix(prefix);
            }
        }
        UdpClientConfig syncConfig = new UdpClientConfig();
        syncConfig.setCodec(syncCodec);
        // Nameserver replica sync 是低频控制面流量，固定单 bind，避免继承全局 SO_REUSEPORT 多路绑定。
        syncConfig.setReusePortBindCount(1);
        ss = new UdpClient(getSyncPort(), syncConfig);
        ss.onReceive.add((s, e) -> {
            Object packet = e.getValue().packet;
            log.info("ns: [{}] Replica {}", getSyncPort(), packet);
            if (tryAs(packet, ReplicaFullSync.class, this::applyFullSync)) {
                return;
            }
            if (tryAs(packet, ReplicaSnapshot.class, this::applySnapshot)) {
                return;
            }
            if (!tryAs(packet, Map.class, p -> {
                Map<Object, Map<String, Serializable>> sync = (Map<Object, Map<String, Serializable>>) p;
                for (Map.Entry<Object, Map<String, Serializable>> entry : sync.entrySet()) {
                    attrs(entry.getKey()).putAll(entry.getValue());
                }
            }) && !tryAs(packet, DeregisterInfo.class, p -> {
                if (acceptReplicaVersion(p.sourceId, p.version)) {
                    doDeregister(p.appName, p.address, false, false);
                }
            })) {
                syncRegister((Set<InetSocketAddress>) packet);
            }
        });
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
        if (replicaFullSyncTask != null) {
            replicaFullSyncTask.cancel(false);
            replicaFullSyncTask = null;
        }
        quietly(rs::close);
        quietly(ss::close);
        if (ownDnsServer) {
            quietly(dnsServer::close);
        }
    }

    public synchronized void syncRegister(@NonNull Set<InetSocketAddress> serverEndpoints) {
        Set<InetSocketAddress> resolvedEndpoints = resolveReplicaEndpoints(serverEndpoints);
        if (!svrEps.addAll(resolvedEndpoints) && resolvedEndpoints.containsAll(svrEps)) {
            return;
        }

        dnsServer.addHosts(NAME, RandomList.DEFAULT_WEIGHT, Linq.from(svrEps).select(InetSocketAddress::getAddress).toList());
        Set<InetSocketAddress> snapshot = new HashSet<InetSocketAddress>(svrEps);
        publishEventAsync(EVENT_CLIENT_SYNC, new NEventArgs<Set<InetSocketAddress>>(snapshot));
        ReplicaSnapshot packet = new ReplicaSnapshot(replicaSourceId, nextReplicaVersion(), snapshot, null);
        Tasks.setTimeout(() -> {
            for (InetSocketAddress ssAddr : resolvedEndpoints) {
                sendReplicaPacket(ssAddr, packet);
            }
        }, syncDelay, svrEps, Constants.TIMER_REPLACE_FLAG);
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
                log.warn("ns: Ignore unresolved replica endpoint {}, UDP sync requires resolved recipient", endpoint, e);
            }
        }
        return result;
    }

    public void syncDeregister(@NonNull DeregisterInfo deregisterInfo) {
        Tasks.setTimeout(() -> {
            for (InetSocketAddress ssAddr : svrEps) {
                sendReplicaPacket(ssAddr, deregisterInfo);
            }
        }, syncDelay, DeregisterInfo.class, Constants.TIMER_REPLACE_FLAG);
    }

    public void syncAttributes() {
        ReplicaSnapshot packet = new ReplicaSnapshot(replicaSourceId, nextReplicaVersion(), null, snapshotAttrs());
        Tasks.setTimeout(() -> {
            for (InetSocketAddress ssAddr : svrEps) {
                sendReplicaPacket(ssAddr, packet);
            }
        }, syncDelay, attrs, Constants.TIMER_REPLACE_FLAG);
    }

    @Override
    public int register(@NonNull String appName, int weight, Set<InetSocketAddress> serverEndpoints) {
        Sys.logCtx("clientSize", rs.sessions().size());

        RemotingContext ctx = RemotingContext.context();
        ctx.getClient().attr(APP_NAME_KEY, appName);
        InetAddress addr = ctx.getClient().tcpRemoteEndpoint().getAddress();
        Sys.logCtx("remoteAddr", addr);
        doRegister(appName, weight, addr);

        syncRegister(serverEndpoints);
        return config.getDnsPort();
    }

    void doRegister(String appName, int weight, InetAddress addr) {
        if (dnsServer.addHosts(appName, weight, Collections.singletonList(addr))) {
            publishEventAsync(EVENT_APP_ADDRESS_CHANGED, new AppChangedEventArgs(appName, addr, true, instanceAttrs(appName, addr)));
        }
    }

    @Override
    public void deregister() {
        RemotingContext ctx = RemotingContext.context();
        String appName = ctx.getClient().attr(APP_NAME_KEY);
        if (appName == null) {
            throw new InvalidException("Must register first");
        }

        doDeregister(appName, ctx.getClient().tcpRemoteEndpoint().getAddress(), false, true);
    }

    void doDeregister(String appName, InetAddress addr, boolean isDisconnected, boolean shouldSync) {
        //Multiple instances of same app and same ip, such as k8s rolling updates.
        int c = Linq.from(rs.sessions().values()).count(p -> eq(p.attr(APP_NAME_KEY), appName) && p.tcpRemoteEndpoint().getAddress().equals(addr));
        if (c == (isDisconnected ? 0 : 1)) {
            log.info("ns: deregister {}", appName);
            if (dnsServer.removeHosts(appName, Collections.singletonList(addr))) {
                publishEventAsync(EVENT_APP_ADDRESS_CHANGED, new AppChangedEventArgs(appName, addr, false, instanceAttrs(appName, addr)));
            }
            if (shouldSync) {
                syncDeregister(new DeregisterInfo(replicaSourceId, nextReplicaVersion(), appName, addr));
            }
        }
    }

    @Override
    public <T extends Serializable> void attr(String appName, String key, T value) {
        attrs(appName).put(key, value);
        syncAttributes();
    }

    @Override
    public <T extends Serializable> T attr(String appName, String key) {
        return (T) attrs(appName).get(key);
    }

    Map<String, Serializable> attrs(Object key) {
        return attrs.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
    }

    @Override
    public <T extends Serializable> void instanceAttr(String appName, String key, T value) {
        RemotingContext ctx = RemotingContext.context();
        attrs(instanceKey(appName, ctx.getClient().tcpRemoteEndpoint().getAddress())).put(key, value);
        syncAttributes();
    }

    @Override
    public <T extends Serializable> T instanceAttr(String appName, String key) {
        RemotingContext ctx = RemotingContext.context();
        return (T) attrs(instanceKey(appName, ctx.getClient().tcpRemoteEndpoint().getAddress())).get(key);
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
            hosts.remove(ctx.getClient().tcpRemoteEndpoint().getAddress());
        }
        return hosts;
    }

    @Override
    public List<InstanceInfo> discover(@NonNull String appName, List<String> instanceAttrKeys) {
        List<InetAddress> hosts = dnsServer.getHosts(appName);
        return getDiscoverInfos(appName, hosts, instanceAttrKeys);
    }

    @Override
    public List<InstanceInfo> discoverAll(@NonNull String appName, boolean exceptCurrent, List<String> instanceAttrKeys) {
        List<InetAddress> hosts = dnsServer.getAllHosts(appName);
        if (hosts.isEmpty()) {
            return Collections.emptyList();
        }
        if (exceptCurrent) {
            RemotingContext ctx = RemotingContext.context();
            hosts.remove(ctx.getClient().tcpRemoteEndpoint().getAddress());
        }
        return getDiscoverInfos(appName, hosts, instanceAttrKeys);
    }

    List<InstanceInfo> getDiscoverInfos(String appName, List<InetAddress> hosts, List<String> instanceAttrKeys) {
        return Linq.from(hosts).select(p -> {
            Map<String, Serializable> attrs = instanceAttrs(appName, p);
            Map<String, Serializable> values = new HashMap<String, Serializable>();
            Iterable<String> keys = !CollectionUtils.isEmpty(instanceAttrKeys) ? instanceAttrKeys : attrs.keySet();
            for (String key : keys) {
                values.put(key, attrs.get(key));
            }
            String ping = heartbeatText(heartbeatRttMillis(p));
            if (ping != null && (CollectionUtils.isEmpty(instanceAttrKeys) || instanceAttrKeys.contains("ping"))) {
                values.put("ping", ping);
            }
            return new InstanceInfo(p, (String) attrs.get(RxConfig.ConfigNames.APP_ID), values);
        }).toList();
    }

    String instanceKey(String appName, InetAddress address) {
        return appName + "#" + address.getHostAddress();
    }

    Map<String, Serializable> instanceAttrs(String appName, InetAddress address) {
        return attrs(instanceKey(appName, address));
    }

    long nextReplicaVersion() {
        return replicaVersion.incrementAndGet();
    }

    boolean acceptReplicaVersion(String sourceId, long version) {
        if (sourceId == null || version <= 0) {
            return true;
        }
        Long last = lastAppliedReplicaVersions.get(sourceId);
        if (last != null && version <= last) {
            log.debug("ns: ignore stale replica {} version {} <= {}", sourceId, version, last);
            return false;
        }
        lastAppliedReplicaVersions.put(sourceId, version);
        return true;
    }

    Map<Object, Map<String, Serializable>> snapshotAttrs() {
        Map<Object, Map<String, Serializable>> snapshot = new HashMap<Object, Map<String, Serializable>>();
        for (Map.Entry<Object, Map<String, Serializable>> entry : attrs.entrySet()) {
            snapshot.put(entry.getKey(), new HashMap<String, Serializable>(entry.getValue()));
        }
        return snapshot;
    }

    Map<String, List<InetAddress>> snapshotHosts() {
        Map<String, List<InetAddress>> snapshot = new HashMap<String, List<InetAddress>>();
        for (Map.Entry<String, RandomList<InetAddress>> entry : dnsServer.getHosts().entrySet()) {
            snapshot.put(entry.getKey(), new ArrayList<InetAddress>(entry.getValue()));
        }
        return snapshot;
    }

    void syncFullSnapshot() {
        if (svrEps.isEmpty()) {
            return;
        }
        ReplicaFullSync packet = new ReplicaFullSync(replicaSourceId, nextReplicaVersion(), new HashSet<InetSocketAddress>(svrEps),
                snapshotHosts(), snapshotAttrs());
        for (InetSocketAddress ssAddr : packet.serverEndpoints) {
            quietly(() -> sendReplicaPacket(ssAddr, packet));
        }
    }

    void sendReplicaPacket(InetSocketAddress endpoint, Object packet) throws TimeoutException {
        InetSocketAddress syncEndpoint = Sockets.newEndpoint(endpoint, getSyncPort());
        if (isLocalSyncEndpoint(syncEndpoint)) {
            log.debug("ns: skip replica self sync {}", Sockets.toString(syncEndpoint));
            return;
        }
        ss.sendAsync(syncEndpoint, packet);
    }

    boolean isLocalSyncEndpoint(InetSocketAddress endpoint) {
        if (endpoint == null || endpoint.isUnresolved() || endpoint.getPort() != getSyncPort()) {
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
            log.debug("ns: check local sync endpoint failed {}", endpoint, e);
            return false;
        }
    }

    void applySnapshot(ReplicaSnapshot packet) {
        if (!acceptReplicaVersion(packet.sourceId, packet.version)) {
            return;
        }
        if (packet.serverEndpoints != null) {
            syncRegister(packet.serverEndpoints);
        }
        mergeAttrs(packet.attrs);
    }

    void applyFullSync(ReplicaFullSync packet) {
        if (!acceptReplicaVersion(packet.sourceId, packet.version)) {
            return;
        }
        if (packet.serverEndpoints != null) {
            syncRegister(packet.serverEndpoints);
        }
        if (packet.hosts != null) {
            dnsServer.getHosts().clear();
            for (Map.Entry<String, List<InetAddress>> entry : packet.hosts.entrySet()) {
                dnsServer.addHosts(entry.getKey(), RandomList.DEFAULT_WEIGHT, entry.getValue());
            }
        }
        mergeAttrs(packet.attrs);
    }

    void mergeAttrs(Map<Object, Map<String, Serializable>> sync) {
        if (sync == null) {
            return;
        }
        for (Map.Entry<Object, Map<String, Serializable>> entry : sync.entrySet()) {
            attrs(entry.getKey()).putAll(entry.getValue());
        }
    }

    long heartbeatRttMillis(InetAddress address) {
        if (address == null) {
            return -1L;
        }
        long best = -1L;
        for (HybridSession session : rs.sessions().values()) {
            InetSocketAddress endpoint = session.tcpRemoteEndpoint();
            if (endpoint == null || !address.equals(endpoint.getAddress())) {
                continue;
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
