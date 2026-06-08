package org.rx.util.rss;

import io.netty.channel.local.LocalAddress;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.RandomList;
import org.rx.core.Strings;
import org.rx.core.Tasks;
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
import org.rx.net.socks.upstream.Upstream;
import java.net.InetSocketAddress;
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
    final int port;
    final int udp2rawPort;
    final AtomicReference<RandomList<UpstreamSupport>> socksServersRef = new AtomicReference<>();
    final AtomicReference<RandomList<UpstreamSupport>> udp2rawSocksServersRef = new AtomicReference<>();
    final AtomicReference<Map<String, RandomList<UpstreamSupport>>> userSocksServersRef = new AtomicReference<>();
    final AtomicReference<Map<String, RandomList<UpstreamSupport>>> udp2rawUserSocksServersRef = new AtomicReference<>();
    final RssAuthenticator authenticator;
    final RssRpcApp app;
    final Map<String, ShadowServerRef> shadowServers = new LinkedHashMap<>();
    final CopyOnWriteArrayList<UpstreamSnapshot> closingUpstreamSnapshots = new CopyOnWriteArrayList<>();
    final AtomicBoolean drainStarted = new AtomicBoolean();

    SwitchingRandomList<DnsServer.ResolveInterceptor> dnsInterceptors = new SwitchingRandomList<>();
    volatile UpstreamSnapshot upstreamSnapshot;
    volatile DnsServer dnsSvr;
    volatile NameserverImpl nameserverRef;
    volatile Upstream shadowDnsUpstream;
    volatile RssInServer inServer;
    volatile RssInServer inUdp2rawServer;
    volatile RssClientConf activeConf;

    RssRuntime(int port, RssClientConf conf) {
        this.port = port;
        udp2rawPort = port + 10;

        UpstreamSnapshot snapshot = buildUpstreams(conf);
        socksServersRef.set(snapshot.socksServers);
        udp2rawSocksServersRef.set(snapshot.udp2rawSocksServers);
        userSocksServersRef.set(snapshot.userSocksServers);
        udp2rawUserSocksServersRef.set(snapshot.udp2rawUserSocksServers);
        dnsInterceptors.setDelegate(snapshot.dnsInterceptors);
        upstreamSnapshot = snapshot;
        startUpstreamHealthCheck(snapshot);

        dnsSvr = createDnsServer(conf, dnsInterceptors);
        nameserverRef = new NameserverImpl(resolveNameserverConfig(conf), dnsSvr);
        nameserver = nameserverRef;
        InetSocketAddress shadowDnsEp = Sockets.newLoopbackEndpoint(conf.shadowDnsPort);
        shadowDnsUpstream = new Upstream(shadowDnsEp);
        Sockets.injectNameService(java.util.Collections.singletonList(shadowDnsEp));

        authenticator = new RssAuthenticator(conf.shadowUsers, conf.socksPwd.trim(), conf.memoryRetentionHours);
        inServer = createPrimaryInServer(conf);
        if (hasUdp2rawSocksServer(conf)) {
            inUdp2rawServer = createUdp2rawInServer(conf);
        }
        app = new RssRpcApp(inServer.server, inUdp2rawServer != null ? inUdp2rawServer.server : null);

        activeConf = conf;
        rssConf = conf;
        applyShadowServersPlan(buildShadowServers(conf, inServer, inUdp2rawServer, true));
        clientInit(authenticator);
        addPublicIpWhiteList();
        configureAutoWhiteListSchedule(conf, this);
    }

    synchronized void reload(RssClientConf oldConf, RssClientConf newConf) {
        RssClientConf currentConf = activeConf;
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
            nextUpstream = buildUpstreams(newConf);
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
                shadowDnsUpstream = new Upstream(shadowDnsEp);
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
            userSocksServersRef.set(nextUpstream.userSocksServers);
            udp2rawUserSocksServersRef.set(nextUpstream.udp2rawUserSocksServers);
            upstreamSnapshot = nextUpstream;
            startUpstreamHealthCheck(nextUpstream);
            nextUpstream = null;

            authenticator.reload(newConf.shadowUsers, newConf.socksPwd.trim(), newConf.memoryRetentionHours);
            applyShadowServersPlan(shadowPlan);
            shadowPlan = null;
            applyLiveConfig(newConf);
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
            InetAddress addr = Sockets.parseIpAddress(Sockets.getPublicIp());
            eachQuietly(socksServersRef.get(), p -> p.getFacade().addWhiteList(addr, SocksRpcContract.rpcToken()));
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

    synchronized void beginDrainAndExit(String reason, long maxWaitMillis) {
        if (!drainStarted.compareAndSet(false, true)) {
            log.info("rss runtime drain already started reason={}", reason);
            return;
        }

        long boundedWaitMillis = Math.max(0L, maxWaitMillis);
        long deadlineMillis = System.currentTimeMillis() + boundedWaitMillis;
        log.warn("rss runtime drain start reason={} maxWaitMillis={}", reason, boundedWaitMillis);
        configureAutoWhiteListSchedule(null, this);
        configureDdnsSchedule(null);
        closeInServerQuietly(inServer);
        closeInServerQuietly(inUdp2rawServer);
        for (ShadowServerRef ref : shadowServers.values()) {
            closeShadowServerQuietly(ref);
        }
        closeNameserverQuietly(nameserverRef);
        closeDnsServerQuietly(dnsSvr);
        tryClose(RssClient.rrpServer);
        RssClient.rrpServer = null;
        RssClient.rrpToken = null;
        RssClient.rrpPort = null;
        tryClose(RssClient.httpServer);
        RssClient.httpServer = null;
        cancelUpstreamHealthCheck(upstreamSnapshot);
        for (UpstreamSnapshot snapshot : closingUpstreamSnapshots) {
            cancelUpstreamHealthCheck(snapshot);
        }
        scheduleDrainExit(deadlineMillis);
    }

    private void scheduleDrainExit(final long deadlineMillis) {
        Tasks.setTimeout(() -> checkDrainExit(deadlineMillis), UPSTREAM_CLOSE_CHECK_MILLIS);
    }

    private void checkDrainExit(long deadlineMillis) {
        int active = drainActiveConnectionCount();
        long now = System.currentTimeMillis();
        if (active <= 0 || now >= deadlineMillis) {
            if (active > 0) {
                log.warn("rss runtime drain force exit activeConnections={}", active);
            } else {
                log.info("rss runtime drain complete");
            }
            closeUpstreamsQuietly(upstreamSnapshot);
            for (UpstreamSnapshot snapshot : closingUpstreamSnapshots) {
                closeUpstreamsQuietly(snapshot);
            }
            tryClose(RssClient.trafficStore);
            RssClient.trafficStore = null;
            System.exit(0);
            return;
        }
        log.info("rss runtime drain wait activeConnections={}", active);
        scheduleDrainExit(deadlineMillis);
    }

    int drainActiveConnectionCount() {
        int count = activeConnectionCount(upstreamSnapshot);
        for (UpstreamSnapshot snapshot : closingUpstreamSnapshots) {
            count += activeConnectionCount(snapshot);
        }
        if (inServer != null) {
            count += inServer.server.activeChannelCount();
        }
        if (inUdp2rawServer != null) {
            count += inUdp2rawServer.server.activeChannelCount();
        }
        for (ShadowServerRef ref : shadowServers.values()) {
            count += ref.server.activeChannelCount();
            for (UpstreamSupport support : ref.routePlan.supports.readOnlySnapshot()) {
                count += support.activeConnectionCount();
            }
        }
        return count;
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

    private RssInServer createPrimaryInServer(RssClientConf conf) {
        return createPrimaryInServer(conf, inServer != null);
    }

    private RssInServer createPrimaryInServer(RssClientConf conf, boolean uniqueLocalAddress) {
        SocksConfig inConf = new SocksConfig(resolveRuntimeListenAddress(conf, port, "rss-in-", uniqueLocalAddress));
        configureInboundConfig(conf, inConf, false);
        log.info("rssConf socksBindPort={}, inListenAddress={}", conf.socksBindPort, inConf.getListenAddress());
        return createInSvr(conf, inConf, authenticator, this::firstRoute, socksServersRef, userSocksServersRef);
    }

    private RssInServer createUdp2rawInServer(RssClientConf conf) {
        return createUdp2rawInServer(conf, inUdp2rawServer != null);
    }

    private RssInServer createUdp2rawInServer(RssClientConf conf, boolean uniqueLocalAddress) {
        SocksConfig inTunConf = new SocksConfig(resolveRuntimeListenAddress(conf, udp2rawPort, "rss-in-tun-", uniqueLocalAddress));
        configureInboundConfig(conf, inTunConf, true);
        return createInSvr(conf, inTunConf, authenticator, this::firstRoute,
                udp2rawSocksServersRef, udp2rawUserSocksServersRef);
    }

    private SocketAddress resolveRuntimeListenAddress(RssClientConf conf, int listenPort, String localNamePrefix, boolean uniqueLocalAddress) {
        if (conf.socksBindPort || !uniqueLocalAddress) {
            return resolveClientInListenAddress(conf, listenPort, localNamePrefix);
        }
        return new LocalAddress(localNamePrefix + listenPort + "-" + System.nanoTime());
    }

    private InServersPlan prepareInServersPlan(RssClientConf conf) {
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
            if (hasUdp2rawSocksServer(conf)) {
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
        app.setUdp2rawServer(inUdp2rawServer != null ? inUdp2rawServer.server : null);
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
            RssInServer restoredUdp2rawServer = hasUdp2rawSocksServer(activeConf) ? createUdp2rawInServer(activeConf, true) : null;
            inServer = restoredInServer;
            inUdp2rawServer = restoredUdp2rawServer;
            app.setServer(restoredInServer.server);
            app.setUdp2rawServer(restoredUdp2rawServer != null ? restoredUdp2rawServer.server : null);
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
        InetSocketAddress dstEp = e.getFirstDestination();
        if (dstEp.getPort() == SocksRpcContract.DNS_PORT) {
            e.setUpstream(shadowDnsUpstream);
            e.setHandled(true);
        }
    }

    private void applyLiveConfig(RssClientConf conf) {
        applyInServerConfig(conf, inServer);
        applyInServerConfig(conf, inUdp2rawServer);
    }

    private void applyInServerConfig(RssClientConf conf, RssInServer server) {
        if (server == null) {
            return;
        }
        server.outConfRef.set(createOutboundConfig(conf, server.inConf));
    }

    private ShadowServersPlan buildShadowServers(RssClientConf conf, RssInServer targetInServer,
                                                 RssInServer targetUdp2rawServer, boolean forceRebuild) {
        ShadowServersPlan plan = new ShadowServersPlan();
        List<ShadowRestart> samePortRestarts = new ArrayList<>();
        try {
            SocketAddress inSvrAddress = targetInServer.inConf.getListenAddress();
            SocketAddress inUdp2rawAddress = targetUdp2rawServer == null ? null : targetUdp2rawServer.inConf.getListenAddress();
            for (ShadowUser usr : conf.shadowUsers) {
                String username = usr.getUsername();
                ShadowRoutePlan routePlan = resolveShadowRoutePlan(conf, usr, inSvrAddress, inUdp2rawAddress);
                ShadowServerRef oldRef = shadowServers.get(username);
                if (oldRef != null && oldRef.matches(usr, routePlan.signature, forceRebuild)) {
                    oldRef.updateRouteMatcher(usr, conf);
                    plan.next.put(username, oldRef);
                    continue;
                }

                if (oldRef != null && oldRef.ssPort == usr.getSsPort()) {
                    samePortRestarts.add(new ShadowRestart(usr, oldRef, routePlan));
                    continue;
                }
                ShadowServerRef ref = createShadowServer(conf, usr, routePlan);
                plan.created.add(ref);
                plan.next.put(username, ref);
            }
            for (ShadowRestart restart : samePortRestarts) {
                // 同端口密码变化无法双实例并行绑定，只能接受短暂不可用窗口。
                closeShadowServerQuietly(restart.oldRef);
                plan.stopped.add(restart);
                ShadowServerRef ref = createShadowServer(conf, restart.user, restart.routePlan);
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
            user.setRouteMatcher(oldRef.routeMatcher);
            UserRule route = new UserRule();
            route.setSrcSteeringTTL(oldRef.srcSteeringTTL);
            user.setRoute(route);
            ShadowServerRef restored = createShadowServer(activeConf, user, oldRef.routePlan);
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

    private ShadowRoutePlan resolveShadowRoutePlan(RssClientConf conf, ShadowUser usr,
                                                   SocketAddress inSvrAddress, SocketAddress inUdp2rawAddress) {
        ServerRouteMode mode = userRouteMode(usr, conf.socksServers);
        if (mode == ServerRouteMode.TCP_CLIENT) {
            RandomList<UpstreamSupport> directServers = buildUserTcpClientServers(usr, conf.socksServers);
            if (directServers.isEmpty()) {
                throw new InvalidException("No tcpClient upstream user={}", usr.getUsername());
            }
            return ShadowRoutePlan.direct(directServers);
        }
        if (mode == ServerRouteMode.MIXED) {
            if (inUdp2rawAddress == null) {
                throw new InvalidException("No udp2raw in server user={}", usr.getUsername());
            }
            // 先按两类上游总权重选择本地入口，再由入口内按用户池二次选择具体上游。
            ShadowRoutePlan mixedRoutePlan = ShadowRoutePlan.mixedLocal(usr, conf.socksServers,
                    inSvrAddress, inUdp2rawAddress);
            if (mixedRoutePlan.supports.isEmpty()) {
                throw new InvalidException("No mixed local upstream user={}", usr.getUsername());
            }
            return mixedRoutePlan;
        }
        if (mode == ServerRouteMode.UDP2RAW && inUdp2rawAddress == null) {
            throw new InvalidException("No udp2raw in server user={}", usr.getUsername());
        }
        AuthenticEndpoint endpoint = resolveShadowEndpoint(inSvrAddress, inUdp2rawAddress,
                usr.getUsername(), mode == ServerRouteMode.UDP2RAW);
        return ShadowRoutePlan.viaLocal(endpoint);
    }

    private ShadowServerRef createShadowServer(RssClientConf conf, ShadowUser usr, ShadowRoutePlan routePlan) {
        ShadowsocksConfig ssConf = new ShadowsocksConfig(Sockets.newAnyEndpoint(usr.getSsPort()),
                CipherKind.AES_256_GCM.getCipherName(), usr.getSsPwd());
        configureShadowConfig(conf, ssConf);
        enableShadowIngressReusePort(ssConf);
        ShadowsocksServer ssSvr = new ShadowsocksServer(ssConf);
        SocksConfig toInConf = new SocksConfig();
        toInConf.setOptimalSettings(RssSupport.IN_OPS);
        ShadowServerRef ref = new ShadowServerRef(usr.getUsername(), usr.getSsPort(), usr.getSsPwd(),
                routePlan, ssSvr, usr.getRouteMatcher(), sourceSteeringTtl(usr, conf));
        ssSvr.onTcpRoute.replace((s, e) -> {
            InetSocketAddress dstEp = e.getFirstDestination();
            RssClientConf currentConf = rssConf;
            boolean routeLog = currentConf != null && currentConf.hasRouteFlag();
            long userRuleBegin = routeLog ? System.nanoTime() : 0L;
            UserRuleMatcher matcher = ref.routeMatcher;
            boolean userRoute = matcher != null;
            RouteAction userRuleAction = matchRoute(matcher, dstEp.getHostString(), dstEp.getPort(), e.getSource());
            if (userRuleAction == RouteAction.BLOCK) {
                if (routeLog) {
                    log.info("SS TCP route {} BLOCK <- {} {}",
                            dstEp, userRoute ? "user:route" : "defaultRoute",
                            org.rx.core.Sys.formatNanosElapsed(System.nanoTime() - userRuleBegin));
                }
                throw new InvalidException("rss route rule block user={} trans=SS-TCP dst={}",
                        ref.username, dstEp);
            }
            if (userRuleAction == RouteAction.DIRECT) {
                if (routeLog) {
                    log.info("SS TCP route {} DIRECT <- {} {}",
                            dstEp, userRoute ? "user:route" : "defaultRoute",
                            org.rx.core.Sys.formatNanosElapsed(System.nanoTime() - userRuleBegin));
                }
                e.setUpstream(new Upstream(dstEp));
                return;
            }
            if (userRuleAction == RouteAction.PROXY && routeLog) {
                log.info("SS TCP route {} PROXY <- {} {}",
                        dstEp, userRoute ? "user:route" : "defaultRoute",
                        org.rx.core.Sys.formatNanosElapsed(System.nanoTime() - userRuleBegin));
            }
            UpstreamSupport svrSupport = routePlan.nextSupport(sourceAddress(e.getSource()), dstEp, ref.srcSteeringTTL);
            if (currentConf != null && currentConf.hasDebugFlag()) {
                log.info("SS TCP route {} => {}[{}]", e.getSource(), svrSupport.getEndpoint(), dstEp);
            }
            e.setUpstream(new SocksTcpUpstream(dstEp, toInConf, svrSupport));
        });
        ssSvr.onUdpRoute.replace((s, e) -> {
            InetSocketAddress dstEp = e.getFirstDestination();
            RssClientConf currentConf = rssConf;
            boolean routeLog = currentConf != null && currentConf.hasRouteFlag();
            long userRuleBegin = routeLog ? System.nanoTime() : 0L;
            UserRuleMatcher matcher = ref.routeMatcher;
            boolean userRoute = matcher != null;
            RouteAction userRuleAction = matchRoute(matcher, dstEp.getHostString(), dstEp.getPort(), e.getSource());
            if (userRuleAction == RouteAction.BLOCK) {
                if (routeLog) {
                    log.info("SS UDP route {} BLOCK <- {} {}",
                            dstEp, userRoute ? "user:route" : "defaultRoute",
                            org.rx.core.Sys.formatNanosElapsed(System.nanoTime() - userRuleBegin));
                }
                throw new InvalidException("rss route rule block user={} trans=SS-UDP dst={}",
                        ref.username, dstEp);
            }
            if (userRuleAction == RouteAction.DIRECT) {
                if (routeLog) {
                    log.info("SS UDP route {} DIRECT <- {} {}",
                            dstEp, userRoute ? "user:route" : "defaultRoute",
                            org.rx.core.Sys.formatNanosElapsed(System.nanoTime() - userRuleBegin));
                }
                e.setUpstream(new Upstream(dstEp));
                return;
            }
            if (userRuleAction == RouteAction.PROXY && routeLog) {
                log.info("SS UDP route {} PROXY <- {} {}",
                        dstEp, userRoute ? "user:route" : "defaultRoute",
                        org.rx.core.Sys.formatNanosElapsed(System.nanoTime() - userRuleBegin));
            }
            UpstreamSupport svrSupport = routePlan.nextSupport(sourceAddress(e.getSource()), dstEp, ref.srcSteeringTTL);
            if (currentConf != null && currentConf.hasDebugFlag()) {
                log.info("SS UDP route {} => {}[{}]", e.getSource(), svrSupport.getEndpoint(), dstEp);
            }
            e.setUpstream(createUdpRouteUpstream(dstEp, toInConf, svrSupport));
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

    static final class ShadowRoutePlan {
        final String signature;
        final RandomList<UpstreamSupport> supports;

        private ShadowRoutePlan(String signature, RandomList<UpstreamSupport> supports) {
            this.signature = signature;
            this.supports = supports;
        }

        static ShadowRoutePlan viaLocal(AuthenticEndpoint endpoint) {
            RandomList<UpstreamSupport> supports = new RandomList<>();
            UpstreamSupport support = new UpstreamSupport(endpoint, null);
            support.setConfiguredWeight(1);
            supports.add(support, 1);
            return new ShadowRoutePlan(toJsonString(endpoint), supports);
        }

        static ShadowRoutePlan mixedLocal(ShadowUser user, List<RssClientConf.SocksServer> socksServers,
                                          SocketAddress inSvrAddress, SocketAddress inUdp2rawAddress) {
            int socksWeight = 0;
            int udp2rawWeight = 0;
            if (user != null && user.getSocksServers() != null && socksServers != null) {
                for (String serverId : user.getSocksServers()) {
                    if (serverId == null) {
                        continue;
                    }
                    for (RssClientConf.SocksServer socksServer : socksServers) {
                        if (socksServer == null || !serverId.equals(socksServer.getId())) {
                            continue;
                        }
                        int weight = weightOf(socksServer);
                        if (weight <= 0) {
                            break;
                        }
                        ServerRouteMode mode = routeMode(socksServer);
                        if (mode == ServerRouteMode.SOCKS) {
                            socksWeight += weight;
                        } else if (mode == ServerRouteMode.UDP2RAW) {
                            udp2rawWeight += weight;
                        }
                        break;
                    }
                }
            }

            RandomList<UpstreamSupport> supports = new RandomList<>();
            List<String> signatureParts = new ArrayList<>();
            if (socksWeight > 0) {
                AuthenticEndpoint endpoint = resolveShadowEndpoint(inSvrAddress, inUdp2rawAddress,
                        user.getUsername(), false);
                UpstreamSupport support = new UpstreamSupport(endpoint, null);
                support.setConfiguredWeight(socksWeight);
                supports.add(support, socksWeight);
                signatureParts.add("SOCKS:" + socksWeight + "@" + endpoint);
            }
            if (udp2rawWeight > 0) {
                AuthenticEndpoint endpoint = resolveShadowEndpoint(inSvrAddress, inUdp2rawAddress,
                        user.getUsername(), true);
                UpstreamSupport support = new UpstreamSupport(endpoint, null);
                support.setConfiguredWeight(udp2rawWeight);
                supports.add(support, udp2rawWeight);
                signatureParts.add("UDP2RAW:" + udp2rawWeight + "@" + endpoint);
            }
            return new ShadowRoutePlan(toJsonString(signatureParts), supports);
        }

        static ShadowRoutePlan direct(RandomList<UpstreamSupport> supports) {
            List<String> signatureParts = new ArrayList<>();
            for (UpstreamSupport support : supports.readOnlySnapshot()) {
                signatureParts.add(support.getConfiguredWeight() + "@" + support.getEndpoint());
            }
            return new ShadowRoutePlan(toJsonString(signatureParts), supports);
        }

        UpstreamSupport nextSupport(InetAddress srcHost, InetSocketAddress dstEp, int steeringTtl) {
            return srcHost != null && useSourceSteering(steeringTtl, dstEp)
                    ? supports.next(srcHost, steeringTtl, true)
                    : supports.next();
        }
    }

    static final class ShadowServerRef {
        final String username;
        final int ssPort;
        final String ssPwd;
        final ShadowRoutePlan routePlan;
        final ShadowsocksServer server;
        volatile UserRuleMatcher routeMatcher;
        volatile int srcSteeringTTL;

        ShadowServerRef(String username, int ssPort, String ssPwd, ShadowRoutePlan routePlan,
                        ShadowsocksServer server, UserRuleMatcher routeMatcher, int srcSteeringTTL) {
            this.username = username;
            this.ssPort = ssPort;
            this.ssPwd = ssPwd;
            this.routePlan = routePlan;
            this.server = server;
            this.routeMatcher = routeMatcher;
            this.srcSteeringTTL = Math.max(0, srcSteeringTTL);
        }

        void updateRouteMatcher(ShadowUser user, RssClientConf conf) {
            routeMatcher = user == null ? null : user.getRouteMatcher();
            srcSteeringTTL = sourceSteeringTtl(user, conf);
        }

        boolean matches(ShadowUser user, String nextRouteSignature, boolean forceRebuild) {
            return !forceRebuild
                    && user != null
                    && ssPort == user.getSsPort()
                    && Strings.hashEquals(ssPwd, user.getSsPwd())
                    && Strings.hashEquals(routePlan.signature, nextRouteSignature);
        }
    }

    static final class ShadowRestart {
        final ShadowUser user;
        final ShadowServerRef oldRef;
        final ShadowRoutePlan routePlan;

        ShadowRestart(ShadowUser user, ShadowServerRef oldRef, ShadowRoutePlan routePlan) {
            this.user = user;
            this.oldRef = oldRef;
            this.routePlan = routePlan;
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
        final Map<String, RandomList<UpstreamSupport>> userSocksServers;
        final Map<String, RandomList<UpstreamSupport>> udp2rawUserSocksServers;
        final List<RssClientConf.SocksServer> configuredSocksServers;
        final List<RssClientConf.SocksServer> configuredUdp2rawSocksServers;
        volatile ScheduledFuture<?> healthTask;
        volatile long closeCheckMillis;
        volatile long closeDeadlineMillis;
        volatile boolean closing;
        volatile boolean closed;

        UpstreamSnapshot(RandomList<UpstreamSupport> socksServers,
                         RandomList<UpstreamSupport> udp2rawSocksServers,
                         RandomList<DnsServer.ResolveInterceptor> dnsInterceptors) {
            this(socksServers, udp2rawSocksServers, dnsInterceptors,
                    Collections.<String, RandomList<UpstreamSupport>>emptyMap(),
                    Collections.<String, RandomList<UpstreamSupport>>emptyMap(), null, null);
        }

        UpstreamSnapshot(RandomList<UpstreamSupport> socksServers,
                         RandomList<UpstreamSupport> udp2rawSocksServers,
                         RandomList<DnsServer.ResolveInterceptor> dnsInterceptors,
                         List<AuthenticEndpoint> configuredSocksServers,
                         List<AuthenticEndpoint> configuredUdp2rawSocksServers) {
            this(socksServers, udp2rawSocksServers, dnsInterceptors,
                    Collections.<String, RandomList<UpstreamSupport>>emptyMap(),
                    Collections.<String, RandomList<UpstreamSupport>>emptyMap(),
                    wrapConfiguredSocksEndpoints(configuredSocksServers),
                    wrapConfiguredSocksEndpoints(configuredUdp2rawSocksServers));
        }

        UpstreamSnapshot(RandomList<UpstreamSupport> socksServers,
                         RandomList<UpstreamSupport> udp2rawSocksServers,
                         RandomList<DnsServer.ResolveInterceptor> dnsInterceptors,
                         Map<String, RandomList<UpstreamSupport>> userSocksServers,
                         Map<String, RandomList<UpstreamSupport>> udp2rawUserSocksServers,
                         List<RssClientConf.SocksServer> configuredSocksServers,
                         List<RssClientConf.SocksServer> configuredUdp2rawSocksServers) {
            this.socksServers = socksServers;
            this.udp2rawSocksServers = udp2rawSocksServers;
            this.dnsInterceptors = dnsInterceptors;
            this.userSocksServers = copyUserSocksServers(userSocksServers);
            this.udp2rawUserSocksServers = copyUserSocksServers(udp2rawUserSocksServers);
            this.configuredSocksServers = copyConfiguredSocksServers(configuredSocksServers);
            this.configuredUdp2rawSocksServers = copyConfiguredSocksServers(configuredUdp2rawSocksServers);
        }

        private static Map<String, RandomList<UpstreamSupport>> copyUserSocksServers(Map<String, RandomList<UpstreamSupport>> userSocksServers) {
            if (userSocksServers == null || userSocksServers.isEmpty()) {
                return Collections.emptyMap();
            }
            return Collections.unmodifiableMap(new LinkedHashMap<String, RandomList<UpstreamSupport>>(userSocksServers));
        }

        private static List<RssClientConf.SocksServer> copyConfiguredSocksServers(List<RssClientConf.SocksServer> servers) {
            if (servers == null || servers.isEmpty()) {
                return Collections.emptyList();
            }
            return Collections.unmodifiableList(new ArrayList<RssClientConf.SocksServer>(servers));
        }

        private static List<RssClientConf.SocksServer> wrapConfiguredSocksEndpoints(List<AuthenticEndpoint> endpoints) {
            if (endpoints == null || endpoints.isEmpty()) {
                return Collections.emptyList();
            }
            List<RssClientConf.SocksServer> servers = new ArrayList<RssClientConf.SocksServer>(endpoints.size());
            for (AuthenticEndpoint endpoint : endpoints) {
                servers.add(new RssClientConf.SocksServer(null, RssClient.weightOf(endpoint), endpoint));
            }
            return servers;
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
