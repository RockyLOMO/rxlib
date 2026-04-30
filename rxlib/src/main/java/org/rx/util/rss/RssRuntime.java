package org.rx.util.rss;

import io.netty.channel.local.LocalAddress;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.RandomList;
import org.rx.core.Strings;
import org.rx.exception.InvalidException;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.dns.DnsServer;
import org.rx.net.nameserver.NameserverImpl;
import org.rx.net.socks.ShadowsocksConfig;
import org.rx.net.socks.ShadowsocksServer;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.socks.encryption.CipherKind;
import org.rx.net.socks.upstream.SocksTcpUpstream;
import org.rx.net.socks.upstream.SocksUdpUpstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.GeoManager;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.rx.core.Extends.eachQuietly;
import static org.rx.core.Extends.tryClose;
import static org.rx.core.Sys.toJsonString;
import static org.rx.util.rss.RssClient.*;

@Slf4j
final class RssRuntime implements AutoCloseable {
    final boolean enableUdp2raw;
    final int port;
    final int udp2rawPort;
    final GeoManager geoMgr = GeoManager.INSTANCE;
    final AtomicReference<RandomList<UpstreamSupport>> socksServersRef = new AtomicReference<>();
    final AtomicReference<RandomList<UpstreamSupport>> udp2rawSocksServersRef = new AtomicReference<>();
    final RssAuthenticator authenticator;
    final RssRpcApp app;
    final Map<String, ShadowServerRef> shadowServers = new LinkedHashMap<>();
    final CopyOnWriteArrayList<UpstreamSnapshot> closingUpstreamSnapshots = new CopyOnWriteArrayList<>();

    SwitchingRandomList<DnsServer.ResolveInterceptor> dnsInterceptors = new SwitchingRandomList<>();
    volatile UpstreamSnapshot upstreamSnapshot;
    volatile DnsServer dnsSvr;
    volatile NameserverImpl nameserverRef;
    volatile Upstream shadowDnsUpstream;
    volatile RssInServer inServer;
    volatile RssInServer inUdp2rawServer;
    volatile RSSConf activeConf;

    RssRuntime(Map<String, String> options, int port, RSSConf conf) {
        enableUdp2raw = "1".equals(options.get("udp2raw"));
        this.port = port;
        udp2rawPort = port + 10;

        UpstreamSnapshot snapshot = buildUpstreams(conf, geoMgr);
        socksServersRef.set(snapshot.socksServers);
        udp2rawSocksServersRef.set(snapshot.udp2rawSocksServers);
        dnsInterceptors.setDelegate(snapshot.dnsInterceptors);
        upstreamSnapshot = snapshot;
        startUpstreamHealthCheck(snapshot);

        dnsSvr = createDnsServer(conf, dnsInterceptors);
        nameserverRef = new NameserverImpl(resolveNameserverConfig(conf), dnsSvr);
        nameserver = nameserverRef;
        InetSocketAddress shadowDnsEp = Sockets.newLoopbackEndpoint(conf.shadowDnsPort);
        shadowDnsUpstream = new Upstream(new UnresolvedEndpoint(shadowDnsEp));
        Sockets.injectNameService(java.util.Collections.singletonList(shadowDnsEp));

        authenticator = new RssAuthenticator(conf.shadowUsers, conf.socksPwd.trim(), conf.memoryRetentionHours);
        inServer = createPrimaryInServer(conf);
        if (enableUdp2raw) {
            inUdp2rawServer = createUdp2rawInServer(conf);
        }
        app = new RssRpcApp(inServer.server);

        activeConf = conf;
        rssConf = conf;
        geoMgr.setGeoSiteDirectRules(conf.route.dstGeoSiteDirectRules);
        applyShadowServersPlan(buildShadowServers(conf, inServer, inUdp2rawServer, true));
        clientInit(authenticator);
        addPublicIpWhiteList();
        configureAutoWhiteListSchedule(conf, this);
    }

    synchronized void reload(RSSConf oldConf, RSSConf newConf) {
        RSSConf currentConf = activeConf;
        log.info("rssConf reload start version old={} new={}", currentConf == null ? null : currentConf.hashCode(), newConf.hashCode());
        UpstreamSnapshot nextUpstream = null;
        InServersPlan inServersPlan = null;
        DnsServer nextDnsServer = null;
        SwitchingRandomList<DnsServer.ResolveInterceptor> nextDnsInterceptors = null;
        NameserverImpl nextNameserver = null;
        RrpServerPlan rrpPlan = null;
        ShadowServersPlan shadowPlan = null;
        boolean restartInServers = inServerRestartRequired(currentConf, newConf);
        boolean restartDns = dnsRestartRequired(currentConf, newConf);
        boolean restartNameserver = restartDns || nameserverRestartRequired(currentConf, newConf);
        boolean rebuildShadowServers = restartInServers || shadowServerRestartRequired(currentConf, newConf);

        try {
            nextUpstream = buildUpstreams(newConf, geoMgr);
            if (restartInServers) {
                inServersPlan = prepareInServersPlan(newConf);
            }
            if (restartDns) {
                nextDnsInterceptors = new SwitchingRandomList<>();
                nextDnsInterceptors.setDelegate(nextUpstream.dnsInterceptors);
                nextDnsServer = createDnsServer(newConf, nextDnsInterceptors);
            }
            if (restartNameserver) {
                DnsServer targetDnsServer = restartDns ? nextDnsServer : dnsSvr;
                nextNameserver = new NameserverImpl(resolveNameserverConfig(newConf), targetDnsServer);
            }

            RssInServer targetInServer = restartInServers ? inServersPlan.newInServer : inServer;
            RssInServer targetUdp2rawServer = restartInServers ? inServersPlan.newUdp2rawServer : inUdp2rawServer;
            rrpPlan = prepareRrpServerPlan(newConf);
            materializePortExclusiveRrpPlan(rrpPlan);
            shadowPlan = buildShadowServers(newConf, targetInServer, targetUdp2rawServer, rebuildShadowServers);

            UpstreamSnapshot oldUpstream = upstreamSnapshot;
            DnsServer oldDnsServer = null;
            NameserverImpl oldNameserver = null;

            if (restartNameserver) {
                oldNameserver = nameserverRef;
                nameserverRef = nextNameserver;
                nameserver = nextNameserver;
                nextNameserver = null;
            }
            if (restartDns) {
                oldDnsServer = dnsSvr;
                dnsSvr = nextDnsServer;
                dnsInterceptors = nextDnsInterceptors;
                InetSocketAddress shadowDnsEp = Sockets.newLoopbackEndpoint(newConf.shadowDnsPort);
                shadowDnsUpstream = new Upstream(new UnresolvedEndpoint(shadowDnsEp));
                Sockets.injectNameService(java.util.Collections.singletonList(shadowDnsEp));
                nextDnsServer = null;
                nextDnsInterceptors = null;
            } else {
                dnsInterceptors.setDelegate(nextUpstream.dnsInterceptors);
                applyDnsConfig(dnsSvr, newConf, dnsInterceptors);
            }

            if (restartInServers) {
                commitInServersPlan(inServersPlan);
                inServersPlan = null;
            }

            socksServersRef.set(nextUpstream.socksServers);
            udp2rawSocksServersRef.set(nextUpstream.udp2rawSocksServers);
            upstreamSnapshot = nextUpstream;
            startUpstreamHealthCheck(nextUpstream);
            nextUpstream = null;

            authenticator.reload(newConf.shadowUsers, newConf.socksPwd.trim(), newConf.memoryRetentionHours);
            applyShadowServersPlan(shadowPlan);
            shadowPlan = null;
            applyLiveConfig(newConf);
            geoMgr.setGeoSiteDirectRules(newConf.route.dstGeoSiteDirectRules);
            configureDdnsSchedule(newConf);
            configureAutoWhiteListSchedule(newConf, this);
            commitRrpServerPlan(rrpPlan);
            rrpPlan = null;
            activeConf = newConf;
            rssConf = newConf;
            addPublicIpWhiteList();

            closeUpstreamsLater(oldUpstream);
            trackClosingUpstream(oldUpstream);
            closeNameserverQuietly(oldNameserver);
            closeDnsServerQuietly(oldDnsServer);
            log.info("rssConf reload ok");
        } catch (Throwable e) {
            closeUpstreamsQuietly(nextUpstream);
            rollbackInServersPlan(inServersPlan);
            closeInServersPlanQuietly(inServersPlan);
            closeNameserverQuietly(nextNameserver);
            closeDnsServerQuietly(nextDnsServer);
            rollbackRrpServerPlan(rrpPlan);
            closeRrpServerPlanQuietly(rrpPlan);
            closeShadowServersPlanQuietly(shadowPlan);
            log.error("rssConf reload failed, keep previous runtime", e);
            throw InvalidException.sneaky(e);
        }
    }

    void addPublicIpWhiteList() {
        try {
            InetAddress addr = InetAddress.getByName(geoMgr.getPublicIp());
            eachQuietly(socksServersRef.get(), p -> p.getFacade().addWhiteList(addr));
        } catch (Throwable e) {
            log.warn("rss auto whitelist failed", e);
        }
    }

    @SneakyThrows
    void await() {
        app.await();
    }

    @Override
    public synchronized void close() {
        configureAutoWhiteListSchedule(null, this);
        configureDdnsSchedule(null);
        for (ShadowServerRef ref : shadowServers.values()) {
            closeShadowServerQuietly(ref);
        }
        shadowServers.clear();
        closeInServerQuietly(inServer);
        closeInServerQuietly(inUdp2rawServer);
        closeNameserverQuietly(nameserverRef);
        closeDnsServerQuietly(dnsSvr);
        closeUpstreamsQuietly(upstreamSnapshot);
        for (UpstreamSnapshot snapshot : closingUpstreamSnapshots) {
            closeUpstreamsQuietly(snapshot);
        }
        closingUpstreamSnapshots.clear();
    }

    List<UpstreamSnapshot> upstreamSnapshots() {
        UpstreamSnapshot current = upstreamSnapshot;
        List<UpstreamSnapshot> snapshots = new ArrayList<>(1 + closingUpstreamSnapshots.size());
        if (current != null && !current.closed) {
            snapshots.add(current);
        }
        for (UpstreamSnapshot snapshot : closingUpstreamSnapshots) {
            if (snapshot == null || snapshot.closed) {
                closingUpstreamSnapshots.remove(snapshot);
                continue;
            }
            if (snapshot != current) {
                snapshots.add(snapshot);
            }
        }
        return snapshots;
    }

    private void trackClosingUpstream(UpstreamSnapshot snapshot) {
        if (snapshot != null && snapshot.closing && !snapshot.closed) {
            closingUpstreamSnapshots.addIfAbsent(snapshot);
        }
    }

    private RssInServer createPrimaryInServer(RSSConf conf) {
        return createPrimaryInServer(conf, inServer != null);
    }

    private RssInServer createPrimaryInServer(RSSConf conf, boolean uniqueLocalAddress) {
        SocksConfig inConf = new SocksConfig(resolveRuntimeListenAddress(conf, port, "rss-in-", uniqueLocalAddress));
        configureInboundConfig(conf, inConf, false);
        log.info("rssConf socksBindPort={}, inListenAddress={}", conf.socksBindPort, inConf.getListenAddress());
        return createInSvr(conf, inConf, authenticator, this::firstRoute, socksServersRef, geoMgr);
    }

    private RssInServer createUdp2rawInServer(RSSConf conf) {
        return createUdp2rawInServer(conf, inUdp2rawServer != null);
    }

    private RssInServer createUdp2rawInServer(RSSConf conf, boolean uniqueLocalAddress) {
        SocksConfig inTunConf = new SocksConfig(resolveRuntimeListenAddress(conf, udp2rawPort, "rss-in-tun-", uniqueLocalAddress));
        configureInboundConfig(conf, inTunConf, true);
        return createInSvr(conf, inTunConf, authenticator, this::firstRoute, udp2rawSocksServersRef, geoMgr);
    }

    private SocketAddress resolveRuntimeListenAddress(RSSConf conf, int listenPort, String localNamePrefix, boolean uniqueLocalAddress) {
        if (conf.socksBindPort || !uniqueLocalAddress) {
            return resolveClientInListenAddress(conf, listenPort, localNamePrefix);
        }
        return new LocalAddress(localNamePrefix + listenPort + "-" + System.nanoTime());
    }

    private InServersPlan prepareInServersPlan(RSSConf conf) {
        InServersPlan plan = new InServersPlan(activeConf != null && activeConf.socksBindPort && conf.socksBindPort);
        try {
            if (plan.portExclusive) {
                plan.oldInServer = inServer;
                plan.oldUdp2rawServer = inUdp2rawServer;
                plan.oldDetached = true;
                closeInServerQuietly(plan.oldInServer);
                closeInServerQuietly(plan.oldUdp2rawServer);
            }
            plan.newInServer = createPrimaryInServer(conf, true);
            if (enableUdp2raw) {
                plan.newUdp2rawServer = createUdp2rawInServer(conf, true);
            }
            return plan;
        } catch (Throwable e) {
            rollbackInServersPlan(plan);
            closeInServersPlanQuietly(plan);
            throw InvalidException.sneaky(e);
        }
    }

    private void commitInServersPlan(InServersPlan plan) {
        if (plan == null) {
            return;
        }
        RssInServer oldInServer = plan.portExclusive ? null : inServer;
        RssInServer oldUdp2rawServer = plan.portExclusive ? null : inUdp2rawServer;
        inServer = plan.newInServer;
        inUdp2rawServer = plan.newUdp2rawServer;
        app.setServer(inServer.server);
        plan.newInServer = null;
        plan.newUdp2rawServer = null;
        plan.oldInServer = null;
        plan.oldUdp2rawServer = null;
        plan.oldDetached = false;
        plan.committed = true;
        closeInServerQuietly(oldInServer);
        closeInServerQuietly(oldUdp2rawServer);
    }

    private void rollbackInServersPlan(InServersPlan plan) {
        if (plan == null || plan.committed) {
            return;
        }
        closeInServerQuietly(plan.newInServer);
        closeInServerQuietly(plan.newUdp2rawServer);
        plan.newInServer = null;
        plan.newUdp2rawServer = null;
        if (!plan.oldDetached) {
            return;
        }
        try {
            RssInServer restoredInServer = createPrimaryInServer(activeConf, true);
            RssInServer restoredUdp2rawServer = enableUdp2raw ? createUdp2rawInServer(activeConf, true) : null;
            inServer = restoredInServer;
            inUdp2rawServer = restoredUdp2rawServer;
            app.setServer(restoredInServer.server);
        } catch (Throwable restoreError) {
            log.error("restore rss in server failed", restoreError);
        } finally {
            plan.oldInServer = null;
            plan.oldUdp2rawServer = null;
            plan.oldDetached = false;
        }
    }

    private void closeInServersPlanQuietly(InServersPlan plan) {
        if (plan == null || plan.committed) {
            return;
        }
        closeInServerQuietly(plan.newInServer);
        closeInServerQuietly(plan.newUdp2rawServer);
        plan.newInServer = null;
        plan.newUdp2rawServer = null;
    }

    private void firstRoute(SocksProxyServer server, SocksContext e) {
        UnresolvedEndpoint dstEp = e.getFirstDestination();
        if (dstEp.getPort() == SocksRpcContract.DNS_PORT) {
            e.setUpstream(shadowDnsUpstream);
            e.setHandled(true);
        }
    }

    private void applyLiveConfig(RSSConf conf) {
        applyInServerConfig(conf, inServer);
        applyInServerConfig(conf, inUdp2rawServer);
    }

    private void applyInServerConfig(RSSConf conf, RssInServer server) {
        if (server == null) {
            return;
        }
        server.outConfRef.set(createOutboundConfig(conf, server.inConf));
    }

    private ShadowServersPlan buildShadowServers(RSSConf conf, RssInServer targetInServer, RssInServer targetUdp2rawServer, boolean forceRebuild) {
        ShadowServersPlan plan = new ShadowServersPlan();
        List<ShadowRestart> samePortRestarts = new ArrayList<>();
        try {
            SocketAddress inSvrAddress = targetInServer.inConf.getListenAddress();
            SocketAddress inUdp2rawAddress = targetUdp2rawServer == null ? null : targetUdp2rawServer.inConf.getListenAddress();
            for (ShadowUser usr : conf.shadowUsers) {
                String username = usr.getUsername();
                AuthenticEndpoint endpoint = resolveShadowEndpoint(inSvrAddress, inUdp2rawAddress,
                        conf.hysteriaClient, username, usr.getSocksUser());
                ShadowServerRef oldRef = shadowServers.get(username);
                if (oldRef != null && oldRef.matches(usr, endpoint, forceRebuild)) {
                    plan.next.put(username, oldRef);
                    continue;
                }

                if (oldRef != null && oldRef.ssPort == usr.getSsPort()) {
                    samePortRestarts.add(new ShadowRestart(usr, oldRef, endpoint));
                    continue;
                }
                ShadowServerRef ref = createShadowServer(conf, usr, endpoint);
                plan.created.add(ref);
                plan.next.put(username, ref);
            }
            for (ShadowRestart restart : samePortRestarts) {
                // 同端口密码变化无法双实例并行绑定，只能接受短暂不可用窗口。
                closeShadowServerQuietly(restart.oldRef);
                plan.stopped.add(restart);
                ShadowServerRef ref = createShadowServer(conf, restart.user, restart.endpoint);
                plan.created.add(ref);
                plan.next.put(restart.user.getUsername(), ref);
            }
            return plan;
        } catch (Throwable e) {
            closeShadowServersPlanQuietly(plan);
            throw InvalidException.sneaky(e);
        }
    }

    private void applyShadowServersPlan(ShadowServersPlan plan) {
        if (plan == null) {
            return;
        }
        replaceShadowServers(plan.next);
        plan.committed = true;
    }

    private void closeShadowServersPlanQuietly(ShadowServersPlan plan) {
        if (plan == null || plan.committed) {
            return;
        }
        for (ShadowServerRef ref : plan.created) {
            closeShadowServerQuietly(ref);
        }
        for (ShadowRestart restart : plan.stopped) {
            restoreShadowServerQuietly(restart.oldRef);
        }
    }

    private void restoreShadowServerQuietly(ShadowServerRef oldRef) {
        try {
            ShadowUser user = new ShadowUser();
            user.setUsername(oldRef.username);
            user.setSsPort(oldRef.ssPort);
            user.setSsPwd(oldRef.ssPwd);
            ShadowServerRef restored = createShadowServer(activeConf, user, oldRef.endpoint);
            shadowServers.put(restored.username, restored);
        } catch (Throwable e) {
            log.error("restore shadow server failed user={} port={}", oldRef.username, oldRef.ssPort, e);
        }
    }

    private void replaceShadowServers(Map<String, ShadowServerRef> next) {
        for (Map.Entry<String, ShadowServerRef> entry : shadowServers.entrySet()) {
            ShadowServerRef oldRef = entry.getValue();
            ShadowServerRef nextRef = next.get(entry.getKey());
            if (oldRef != nextRef) {
                closeShadowServerQuietly(oldRef);
            }
        }
        shadowServers.clear();
        shadowServers.putAll(next);
    }

    private ShadowServerRef createShadowServer(RSSConf conf, ShadowUser usr, AuthenticEndpoint endpoint) {
        ShadowsocksConfig ssConf = new ShadowsocksConfig(Sockets.newAnyEndpoint(usr.getSsPort()),
                CipherKind.AES_256_GCM.getCipherName(), usr.getSsPwd());
        configureShadowConfig(conf, ssConf);
        enableShadowIngressReusePort(ssConf);
        ShadowsocksServer ssSvr = new ShadowsocksServer(ssConf);
        SocksConfig toInConf = new SocksConfig();
        toInConf.setOptimalSettings(RssSupport.IN_OPS);
        UpstreamSupport svrSupport = new UpstreamSupport(endpoint, null);
        ShadowServerRef ref = new ShadowServerRef(usr.getUsername(), usr.getSsPort(), usr.getSsPwd(), endpoint, ssSvr, svrSupport);
        ssSvr.onTcpRoute.replace((s, e) -> {
            UnresolvedEndpoint dstEp = e.getFirstDestination();
            if (rssConf.hasDebugFlag()) {
                log.info("SS TCP route {} => {}[{}]", e.getSource(), svrSupport.getEndpoint(), dstEp);
            }
            e.setUpstream(new SocksTcpUpstream(dstEp, toInConf, svrSupport));
        });
        ssSvr.onUdpRoute.replace((s, e) -> {
            UnresolvedEndpoint dstEp = e.getFirstDestination();
            if (rssConf.hasDebugFlag()) {
                log.info("SS UDP route {} => {}[{}]", e.getSource(), svrSupport.getEndpoint(), dstEp);
            }
            e.setUpstream(new SocksUdpUpstream(dstEp, toInConf, svrSupport));
        });
        return ref;
    }

    static final class RssInServer {
        final SocksProxyServer server;
        final SocksConfig inConf;
        final AtomicReference<SocksConfig> outConfRef;
        final AtomicBoolean sourceSteeringEnabled;

        RssInServer(SocksProxyServer server, SocksConfig inConf, AtomicReference<SocksConfig> outConfRef,
                    AtomicBoolean sourceSteeringEnabled) {
            this.server = server;
            this.inConf = inConf;
            this.outConfRef = outConfRef;
            this.sourceSteeringEnabled = sourceSteeringEnabled;
        }

        void disableSourceSteering() {
            sourceSteeringEnabled.set(false);
        }
    }

    static final class InServersPlan {
        final boolean portExclusive;
        RssInServer newInServer;
        RssInServer newUdp2rawServer;
        RssInServer oldInServer;
        RssInServer oldUdp2rawServer;
        boolean oldDetached;
        boolean committed;

        InServersPlan(boolean portExclusive) {
            this.portExclusive = portExclusive;
        }
    }

    static final class ShadowServerRef {
        final String username;
        final int ssPort;
        final String ssPwd;
        final AuthenticEndpoint endpoint;
        final ShadowsocksServer server;
        final UpstreamSupport svrSupport;

        ShadowServerRef(String username, int ssPort, String ssPwd, AuthenticEndpoint endpoint,
                        ShadowsocksServer server, UpstreamSupport svrSupport) {
            this.username = username;
            this.ssPort = ssPort;
            this.ssPwd = ssPwd;
            this.endpoint = endpoint;
            this.server = server;
            this.svrSupport = svrSupport;
        }

        boolean matches(ShadowUser user, AuthenticEndpoint nextEndpoint, boolean forceRebuild) {
            return !forceRebuild
                    && user != null
                    && ssPort == user.getSsPort()
                    && Strings.hashEquals(ssPwd, user.getSsPwd())
                    && Strings.hashEquals(toJsonString(endpoint), toJsonString(nextEndpoint));
        }
    }

    static final class ShadowRestart {
        final ShadowUser user;
        final ShadowServerRef oldRef;
        final AuthenticEndpoint endpoint;

        ShadowRestart(ShadowUser user, ShadowServerRef oldRef, AuthenticEndpoint endpoint) {
            this.user = user;
            this.oldRef = oldRef;
            this.endpoint = endpoint;
        }
    }

    static final class ShadowServersPlan {
        final Map<String, ShadowServerRef> next = new LinkedHashMap<>();
        final List<ShadowServerRef> created = new ArrayList<>();
        final List<ShadowRestart> stopped = new ArrayList<>();
        boolean committed;
    }

    static final class SwitchingRandomList<T> extends RandomList<T> {
        private static final long serialVersionUID = -6755889658100733405L;
        final AtomicReference<RandomList<T>> delegate = new AtomicReference<>(new RandomList<T>());

        void setDelegate(RandomList<T> next) {
            delegate.set(next == null ? new RandomList<T>() : next);
        }

        @Override
        public <S> T next(S steeringKey, int ttl) {
            return delegate.get().next(steeringKey, ttl);
        }

        @Override
        public <S> T next(S steeringKey, int ttl, boolean isSliding) {
            return delegate.get().next(steeringKey, ttl, isSliding);
        }

        @Override
        public T next() {
            return delegate.get().next();
        }

        @Override
        public List<T> aliveList() {
            return delegate.get().aliveList();
        }

        @Override
        public int getWeight(T element) {
            return delegate.get().getWeight(element);
        }

        @Override
        public int size() {
            return delegate.get().size();
        }

        @Override
        public T get(int index) {
            return delegate.get().get(index);
        }

        @Override
        public java.util.Iterator<T> iterator() {
            return delegate.get().iterator();
        }

        @Override
        public List<T> readOnlySnapshot() {
            return delegate.get().readOnlySnapshot();
        }
    }

    static final class UpstreamSnapshot {
        final RandomList<UpstreamSupport> socksServers;
        final RandomList<UpstreamSupport> udp2rawSocksServers;
        final RandomList<DnsServer.ResolveInterceptor> dnsInterceptors;
        final List<AuthenticEndpoint> configuredSocksServers;
        final List<AuthenticEndpoint> configuredUdp2rawSocksServers;
        volatile ScheduledFuture<?> healthTask;
        volatile long closeCheckMillis;
        volatile long closeDeadlineMillis;
        volatile boolean closing;
        volatile boolean closed;

        UpstreamSnapshot(RandomList<UpstreamSupport> socksServers,
                         RandomList<UpstreamSupport> udp2rawSocksServers,
                         RandomList<DnsServer.ResolveInterceptor> dnsInterceptors) {
            this(socksServers, udp2rawSocksServers, dnsInterceptors, null, null);
        }

        UpstreamSnapshot(RandomList<UpstreamSupport> socksServers,
                         RandomList<UpstreamSupport> udp2rawSocksServers,
                         RandomList<DnsServer.ResolveInterceptor> dnsInterceptors,
                         List<AuthenticEndpoint> configuredSocksServers,
                         List<AuthenticEndpoint> configuredUdp2rawSocksServers) {
            this.socksServers = socksServers;
            this.udp2rawSocksServers = udp2rawSocksServers;
            this.dnsInterceptors = dnsInterceptors;
            this.configuredSocksServers = copyConfiguredEndpoints(configuredSocksServers);
            this.configuredUdp2rawSocksServers = copyConfiguredEndpoints(configuredUdp2rawSocksServers);
        }

        private static List<AuthenticEndpoint> copyConfiguredEndpoints(List<AuthenticEndpoint> endpoints) {
            if (endpoints == null || endpoints.isEmpty()) {
                return Collections.emptyList();
            }
            return Collections.unmodifiableList(new ArrayList<AuthenticEndpoint>(endpoints));
        }
    }

    static final class RrpServerPlan {
        final boolean changed;
        final boolean enabled;
        final boolean samePort;
        final String token;
        final Integer port;
        org.rx.net.socks.RrpServer newServer;
        org.rx.net.socks.RrpServer oldServer;
        String oldToken;
        Integer oldPort;
        boolean oldDetached;

        RrpServerPlan(boolean changed, boolean enabled, boolean samePort, String token, Integer port) {
            this.changed = changed;
            this.enabled = enabled;
            this.samePort = samePort;
            this.token = token;
            this.port = port;
        }
    }
}
