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
import org.rx.net.dns.DnsResolveInterceptor;
import org.rx.net.dns.DnsServer;
import org.rx.net.nameserver.NameserverImpl;
import org.rx.net.socks.ShadowsocksConfig;
import org.rx.net.socks.ShadowsocksServer;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.SocksRpcContract;
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
    final RssAuthenticator authenticator;
    final RssRpcApp app;
    final Map<String, ShadowServerRef> shadowServers = new LinkedHashMap<>();
    final CopyOnWriteArrayList<UpstreamSnapshot> closingUpstreamSnapshots = new CopyOnWriteArrayList<>();
    interface ShadowBindHook {
        void beforeBind(ShadowUser user) throws Exception;
    }

    final AtomicBoolean drainStarted = new AtomicBoolean();
    volatile ShadowBindHook shadowServerBindHook;

    SwitchingRandomList<DnsResolveInterceptor> dnsInterceptors = new SwitchingRandomList<>();
    volatile UpstreamSnapshot upstreamSnapshot;
    volatile DnsServer dnsSvr;
    volatile NameserverImpl nameserverRef;

    volatile RssInServer inServer;
    volatile RssClientConf activeConf;

    RssRuntime(int port, RssClientConf conf) {
        this.port = port;
        UpstreamSnapshot snapshot = buildUpstreams(conf);
        dnsInterceptors.setDelegate(snapshot.dnsInterceptors);
        upstreamSnapshot = snapshot;
        startUpstreamHealthCheck(snapshot, conf);

        dnsSvr = createDnsServer(conf, dnsInterceptors);
        nameserverRef = new NameserverImpl(resolveNameserverConfig(conf), dnsSvr);
        nameserver = nameserverRef;
        InetSocketAddress shadowDnsEp = Sockets.newLoopbackEndpoint(conf.shadowDnsPort);
        Sockets.injectNameService(java.util.Collections.singletonList(shadowDnsEp));

        authenticator = new RssAuthenticator(conf.shadowUsers, conf.socksPwd.trim(), conf.memoryRetentionHours);
        inServer = createPrimaryInServer(conf);
        app = new RssRpcApp(inServer.server, null);

        activeConf = conf;
        rssConf = conf;
        org.rx.core.RxConfig.INSTANCE.getNet().setPublicIpEndpoints(conf.publicIpEndpoints);
        applyShadowServersPlan(buildShadowServers(conf, inServer, true));
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
        SwitchingRandomList<DnsResolveInterceptor> nextDnsInterceptors = null;
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
            rrpPlan = prepareRrpServerPlan(newConf);
            materializePortExclusiveRrpPlan(rrpPlan);
            shadowPlan = buildShadowServers(newConf, targetInServer, rebuildShadowServers);

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

            upstreamSnapshot = nextUpstream;
            startUpstreamHealthCheck(nextUpstream, newConf);
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
            InetAddress observed = null;
            try {
                observed = Sockets.parseIpAddress(Sockets.getPublicIp());
            } catch (Exception unavailable) {
                log.debug("No public IPv4 observation, registering authenticated RPC peer", unavailable);
            }
            InetAddress addr = observed;
            eachQuietly(upstreamSnapshot.dnsInterceptors, interceptor -> {
                if (interceptor instanceof SocksRpcContract) {
                    ((SocksRpcContract) interceptor).addWhiteList(addr, SocksRpcContract.rpcToken());
                }
            });
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
        configureInboundConfig(conf, inConf);
        log.info("rssConf socksBindPort={}, inListenAddress={}", conf.socksBindPort, inConf.getListenAddress());
        return createInSvr(conf, inConf, authenticator, this::firstRoute, () -> upstreamSnapshot);
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
                plan.oldDetached = true;
                closeInServerQuietly(plan.oldInServer);
            }
            plan.newInServer = createPrimaryInServer(conf, true);
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
        inServer = plan.newInServer;
        app.setServer(inServer.server);
        plan.newInServer = null;
        plan.oldInServer = null;
        plan.oldDetached = false;
        plan.committed = true;
        closeInServerQuietly(oldInServer);
    }

    private void rollbackInServersPlan(InServersPlan plan) {
        if (plan == null || plan.committed) {
            return;
        }
        closeInServerQuietly(plan.newInServer);
        plan.newInServer = null;
        if (!plan.oldDetached) {
            return;
        }
        try {
            RssInServer restoredInServer = createPrimaryInServer(activeConf, true);
            inServer = restoredInServer;
            app.setServer(restoredInServer.server);
        } catch (Throwable restoreError) {
            log.error("restore rss in server failed", restoreError);
        } finally {
            plan.oldInServer = null;
            plan.oldDetached = false;
        }
    }

    private void closeInServersPlanQuietly(InServersPlan plan) {
        if (plan == null || plan.committed) {
            return;
        }
        closeInServerQuietly(plan.newInServer);
        plan.newInServer = null;
    }

    private void firstRoute(SocksProxyServer server, SocksContext e) {
        InetSocketAddress dstEp = e.getFirstDestination();
        if (dstEp.getPort() == SocksRpcContract.DNS_PORT) {
            UpstreamSnapshot snapshot = upstreamSnapshot;
            RssClientConf conf = activeConf;
            e.setUpstream(new RssDnsInterceptor(dstEp, e.getSource(), e.getUser(), conf.defaultRouteMatcher,
                    dnsSvr, 60 * conf.dnsTtlMinutes,
                    resolveUserSocksServers(snapshot.socksServers, snapshot.userSocksServers, e.getUser()), snapshot.dnsAnswers));
            e.setHandled(true);
        }
    }

    private void applyLiveConfig(RssClientConf conf) {
        org.rx.core.RxConfig.INSTANCE.getNet().setPublicIpEndpoints(conf.publicIpEndpoints);
        applyInServerConfig(conf, inServer);
    }

    private void applyInServerConfig(RssClientConf conf, RssInServer server) {
        if (server == null) {
            return;
        }
        server.outConfRef.set(new RssClient.OutboundProfiles(conf, server.inConf));
        server.standardConfRef.set(RssClient.standardSocksConfig(conf));
    }

    private ShadowServersPlan buildShadowServers(RssClientConf conf, RssInServer targetInServer,
                                                 boolean forceRebuild) {
        ShadowServersPlan plan = new ShadowServersPlan();
        List<ShadowRestart> samePortRestarts = new ArrayList<>();
        try {
            SocketAddress inSvrAddress = targetInServer.inConf.getListenAddress();
            for (ShadowUser usr : conf.shadowUsers) {
                String username = usr.getUsername();
                RssRoutePlan routePlan = RssRoutePlan.viaLocal(resolveShadowEndpoint(inSvrAddress, usr.getUsername()));
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
            user.setSsCipher(oldRef.ssCipher);
            user.setRouteMatcher(oldRef.routeMatcher);
            UserRule route = new UserRule();
            route.setSrcSteeringTTL(oldRef.srcSteeringTTL);
            user.setRoute(route);
            ShadowServerRef restored = createShadowServer(activeConf, user, oldRef.routePlan, true);
            shadowServers.put(restored.username, restored);
            log.warn("restored shadow server after reload failure user={} port={} cipher={}",
                    oldRef.username, oldRef.ssPort, oldRef.ssCipher);
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

    private ShadowServerRef createShadowServer(RssClientConf conf, ShadowUser usr, RssRoutePlan routePlan) {
        return createShadowServer(conf, usr, routePlan, false);
    }

    private ShadowServerRef createShadowServer(RssClientConf conf, ShadowUser usr, RssRoutePlan routePlan, boolean restoring) {
        ShadowBindHook hook = shadowServerBindHook;
        if (!restoring && hook != null) {
            try {
                hook.beforeBind(usr);
            } catch (Throwable e) {
                throw InvalidException.sneaky(e);
            }
        }
        String cipher = usr.cipherName();
        ShadowsocksConfig ssConf = new ShadowsocksConfig(Sockets.newAnyEndpoint(usr.getSsPort()),
                cipher, usr.getSsPwd());
        configureShadowConfig(conf, ssConf);
        enableShadowIngressReusePort(ssConf);
        ShadowsocksServer ssSvr = new ShadowsocksServer(ssConf);
        SocksConfig toInConf = new SocksConfig();
        toInConf.setOptimalSettings(RssSupport.IN_OPS);
        ShadowServerRef ref = new ShadowServerRef(usr.getUsername(), usr.getSsPort(), usr.getSsPwd(), cipher,
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
                    log.debug("SS TCP route {} BLOCK <- {} {}",
                            dstEp, userRoute ? "user:route" : "defaultRoute",
                            org.rx.core.Sys.formatNanosElapsed(System.nanoTime() - userRuleBegin));
                }
                throw new InvalidException("rss route rule block user={} trans=SS-TCP dst={}",
                        ref.username, dstEp);
            }
            if (userRuleAction == RouteAction.DIRECT && dstEp.getPort() != SocksRpcContract.DNS_PORT) {
                if (routeLog) {
                    log.debug("SS TCP route {} DIRECT <- {} {}",
                            dstEp, userRoute ? "user:route" : "defaultRoute",
                            org.rx.core.Sys.formatNanosElapsed(System.nanoTime() - userRuleBegin));
                }
                e.setUpstream(new Upstream(dstEp));
                return;
            }
            if (userRuleAction == RouteAction.PROXY && routeLog) {
                log.debug("SS TCP route {} PROXY <- {} {}",
                        dstEp, userRoute ? "user:route" : "defaultRoute",
                        org.rx.core.Sys.formatNanosElapsed(System.nanoTime() - userRuleBegin));
            }
            UpstreamSupport svrSupport = routePlan.nextSupport(sourceAddress(e.getSource()), dstEp, ref.srcSteeringTTL);
            if (currentConf != null && currentConf.hasDebugFlag()) {
                log.debug("SS TCP route {} => {}[{}]", e.getSource(), svrSupport.getEndpoint(), dstEp);
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
                    log.debug("SS UDP route {} BLOCK <- {} {}",
                            dstEp, userRoute ? "user:route" : "defaultRoute",
                            org.rx.core.Sys.formatNanosElapsed(System.nanoTime() - userRuleBegin));
                }
                throw new InvalidException("rss route rule block user={} trans=SS-UDP dst={}",
                        ref.username, dstEp);
            }
            if (userRuleAction == RouteAction.DIRECT && dstEp.getPort() != SocksRpcContract.DNS_PORT) {
                if (routeLog) {
                    log.debug("SS UDP route {} DIRECT <- {} {}",
                            dstEp, userRoute ? "user:route" : "defaultRoute",
                            org.rx.core.Sys.formatNanosElapsed(System.nanoTime() - userRuleBegin));
                }
                e.setUpstream(new Upstream(dstEp));
                return;
            }
            if (userRuleAction == RouteAction.PROXY && routeLog) {
                log.debug("SS UDP route {} PROXY <- {} {}",
                        dstEp, userRoute ? "user:route" : "defaultRoute",
                        org.rx.core.Sys.formatNanosElapsed(System.nanoTime() - userRuleBegin));
            }
            UpstreamSupport svrSupport = routePlan.nextSupport(sourceAddress(e.getSource()), dstEp, ref.srcSteeringTTL);
            if (currentConf != null && currentConf.hasDebugFlag()) {
                log.debug("SS UDP route {} => {}[{}]", e.getSource(), svrSupport.getEndpoint(), dstEp);
            }
            e.setUpstream(createUdpRouteUpstream(dstEp, toInConf, svrSupport));
        });
        return ref;
    }

    static final class RssInServer {
        final SocksProxyServer server;
        final SocksConfig inConf;
        final AtomicReference<RssClient.OutboundProfiles> outConfRef;
        final AtomicReference<SocksConfig> standardConfRef;
        final AtomicBoolean sourceSteeringEnabled;

        RssInServer(SocksProxyServer server, SocksConfig inConf, AtomicReference<RssClient.OutboundProfiles> outConfRef,
                    AtomicReference<SocksConfig> standardConfRef, AtomicBoolean sourceSteeringEnabled) {
            this.server = server;
            this.inConf = inConf;
            this.outConfRef = outConfRef;
            this.standardConfRef = standardConfRef;
            this.sourceSteeringEnabled = sourceSteeringEnabled;
        }

        void disableSourceSteering() {
            sourceSteeringEnabled.set(false);
        }
    }

    static final class InServersPlan {
        final boolean portExclusive;
        RssInServer newInServer;
        RssInServer oldInServer;
        boolean oldDetached;
        boolean committed;

        InServersPlan(boolean portExclusive) {
            this.portExclusive = portExclusive;
        }
    }

    static final class RssRoutePlan {
        final String signature;
        final RandomList<UpstreamSupport> supports;

        private RssRoutePlan(String signature, RandomList<UpstreamSupport> supports) {
            this.signature = signature;
            this.supports = supports;
        }

        static RssRoutePlan viaLocal(AuthenticEndpoint endpoint) {
            RandomList<UpstreamSupport> supports = new RandomList<>();
            UpstreamSupport support = new UpstreamSupport(endpoint, null);
            support.setConfiguredWeight(1);
            supports.add(support, 1);
            return new RssRoutePlan(toJsonString(endpoint), supports);
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
        final String ssCipher;
        final RssRoutePlan routePlan;
        final ShadowsocksServer server;
        volatile UserRuleMatcher routeMatcher;
        volatile int srcSteeringTTL;

        ShadowServerRef(String username, int ssPort, String ssPwd, String ssCipher, RssRoutePlan routePlan,
                        ShadowsocksServer server, UserRuleMatcher routeMatcher, int srcSteeringTTL) {
            this.username = username;
            this.ssPort = ssPort;
            this.ssPwd = ssPwd;
            this.ssCipher = ssCipher;
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
                    && Strings.hashEquals(ssCipher, user.cipherName())
                    && Strings.hashEquals(routePlan.signature, nextRouteSignature);
        }
    }

    static final class ShadowRestart {
        final ShadowUser user;
        final ShadowServerRef oldRef;
        final RssRoutePlan routePlan;

        ShadowRestart(ShadowUser user, ShadowServerRef oldRef, RssRoutePlan routePlan) {
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
        final RssDnsInterceptor.Answers dnsAnswers = new RssDnsInterceptor.Answers();
        final RandomList<UpstreamSupport> socksServers;
        final RandomList<DnsResolveInterceptor> dnsInterceptors;
        final Map<String, RandomList<UpstreamSupport>> userSocksServers;
        final List<RssClientConf.SocksServer> configuredSocksServers;
        volatile ScheduledFuture<?> healthTask;
        volatile long closeCheckMillis;
        volatile long closeDeadlineMillis;
        volatile boolean closing;
        volatile boolean closed;

        UpstreamSnapshot(RandomList<UpstreamSupport> socksServers, RandomList<DnsResolveInterceptor> dnsInterceptors,
                         Map<String, RandomList<UpstreamSupport>> userSocksServers,
                         List<RssClientConf.SocksServer> configuredSocksServers) {
            this.socksServers = socksServers;
            this.dnsInterceptors = dnsInterceptors;
            this.userSocksServers = copyUserSocksServers(userSocksServers);
            this.configuredSocksServers = copyConfiguredSocksServers(configuredSocksServers);
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
