package org.rx.util.rss;

import io.netty.channel.local.LocalAddress;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.RandomList;
import org.rx.core.Strings;
import org.rx.core.Tasks;
import org.rx.exception.InvalidException;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.BandwidthLimitConfig;
import org.rx.net.BandwidthLimiter;
import org.rx.net.Sockets;
import org.rx.net.dns.DnsResolveInterceptor;
import org.rx.net.dns.DnsServer;
import org.rx.net.nameserver.NameserverImpl;
import org.rx.net.registry.NodeRegistry;
import org.rx.net.rendezvous.RendezvousConfig;
import org.rx.net.rendezvous.RendezvousService;
import org.rx.net.socks.RrpConfig;
import org.rx.net.socks.RrpServer;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    final Map<String, BandwidthLimiter> userBandwidthLimiters = new ConcurrentHashMap<>();
    final CopyOnWriteArrayList<UpstreamSnapshot> closingUpstreamSnapshots = new CopyOnWriteArrayList<>();
    interface ShadowBindHook {
        void beforeBind(ShadowUser user) throws Exception;
    }

    final AtomicBoolean drainStarted = new AtomicBoolean();
    volatile ShadowBindHook shadowServerBindHook;
    volatile Runnable reloadPostPublishHook;

    SwitchingRandomList<DnsResolveInterceptor> dnsInterceptors = new SwitchingRandomList<>();
    volatile RuntimeGeneration generation;
    volatile UpstreamSnapshot upstreamSnapshot;
    volatile DnsServer dnsSvr;
    volatile NameserverImpl nameserverRef;
    volatile NodeRegistry nodeRegistryRef;
    volatile RendezvousService rendezvousRef;
    volatile RrpServer nodeRelayRef;

    volatile RssInServer inServer;
    volatile RssClientConf activeConf;

    RssRuntime(int port, RssClientConf conf) {
        this.port = port;
        try {
            UpstreamSnapshot snapshot = buildUpstreams(conf);
            dnsInterceptors.setDelegate(snapshot.dnsInterceptors);
            upstreamSnapshot = snapshot;

            // Startup order (§3.1): NodeRegistry -> DNS -> Nameserver(shared NodeRegistry) -> optional Rendezvous/Relay -> RSS
            nodeRegistryRef = NodeRegistry.openIfEnabled(conf.nodeRegistry);
            dnsSvr = createDnsServer(conf, dnsInterceptors);
            nameserverRef = new NameserverImpl(resolveNameserverConfig(conf), dnsSvr, nodeRegistryRef);
            nameserver = nameserverRef;
            InetSocketAddress shadowDnsEp = Sockets.newLoopbackEndpoint(conf.shadowDnsPort);
            Sockets.injectNameService(java.util.Collections.singletonList(shadowDnsEp));

            if (conf.nodeService != null && conf.nodeService.enabled) {
                if (nodeRegistryRef == null) {
                    throw new IllegalStateException("nodeService.enabled requires nodeRegistry.enabled=true");
                }
                if (conf.nodeService.rendezvousEnabled) {
                    rendezvousRef = createRendezvousService(conf.nodeService, nodeRegistryRef);
                }
                if (conf.nodeService.relayEnabled) {
                    nodeRelayRef = createNodeRelayServer(conf.nodeService, nodeRegistryRef);
                }
            }

            authenticator = new RssAuthenticator(conf.shadowUsers, conf.socksPwd.trim(), conf.memoryRetentionHours);
            inServer = createPrimaryInServer(conf);
            RuntimeGeneration initialGeneration = new RuntimeGeneration(conf, snapshot, dnsSvr,
                    inServer.outConfRef.get(), inServer.standardConfRef.get());
            generation = initialGeneration;
            app = new RssRpcApp(inServer.server, null);

            activeConf = conf;
            rssConf = conf;
            org.rx.core.RxConfig.INSTANCE.getNet().setPublicIpEndpoints(conf.publicIpEndpoints);
            applyShadowServersPlan(buildShadowServers(conf, inServer, true));
            startUpstreamHealthCheck(snapshot, conf);
            clientInit(authenticator);
            addPublicIpWhiteList();
            configureAutoWhiteListSchedule(conf, this);
        } catch (Throwable e) {
            closeNodeServicesReverseOrder();
            closeInServerQuietly(inServer);
            closeNameserverQuietly(nameserverRef);
            closeDnsServerQuietly(dnsSvr);
            closeNodeRegistryQuietly(nodeRegistryRef);
            nodeRegistryRef = null;
            closeUpstreamsQuietly(upstreamSnapshot);
            throw InvalidException.sneaky(e);
        }
    }

    RuntimeGeneration currentGeneration() {
        return generation;
    }

    synchronized void reload(RssClientConf oldConf, RssClientConf newConf) {
        RssClientConf currentConf = activeConf;
        log.info("rssConf reload start version old={} new={}", currentConf == null ? null : currentConf.hashCode(), newConf.hashCode());
        RuntimeGeneration prevGeneration = generation;
        UpstreamSnapshot prevUpstream = prevGeneration != null ? prevGeneration.upstreamSnapshot : upstreamSnapshot;
        DnsServer prevDnsServer = dnsSvr;
        SwitchingRandomList<DnsResolveInterceptor> prevDnsInterceptors = dnsInterceptors;
        NameserverImpl prevNameserver = nameserverRef;
        NodeRegistry prevNodeRegistry = nodeRegistryRef;
        RendezvousService prevRendezvous = rendezvousRef;
        RrpServer prevNodeRelay = nodeRelayRef;
        RssInServer prevInServer = inServer;
        RssAuthenticator.AuthSettings prevAuthSettings = authenticator.currentSettings();
        String[] prevPublicIpEndpoints = org.rx.core.RxConfig.INSTANCE.getNet().getPublicIpEndpoints();

        UpstreamSnapshot nextUpstream = null;
        InServersPlan inServersPlan = null;
        DnsServer nextDnsServer = null;
        SwitchingRandomList<DnsResolveInterceptor> nextDnsInterceptors = null;
        NameserverImpl nextNameserver = null;
        NodeServicePlan nodeServicePlan = null;
        RrpServerPlan rrpPlan = null;
        ShadowServersPlan shadowPlan = null;
        boolean restartInServers = inServerRestartRequired(currentConf, newConf);
        boolean restartDns = dnsRestartRequired(currentConf, newConf);
        boolean nodeRegistryWillToggle = (prevNodeRegistry != null) != (newConf.nodeRegistry != null && newConf.nodeRegistry.isEnabled());
        boolean restartNameserver = restartDns || nodeRegistryWillToggle || nameserverRestartRequired(currentConf, newConf);
        boolean rebuildShadowServers = restartInServers || shadowServerRestartRequired(currentConf, newConf);

        boolean authCommitted = false;
        boolean nameserverPublished = false;
        boolean nameserverSamePortDetached = false;
        boolean nodeServicePublished = false;
        boolean dnsPublished = false;
        boolean dnsDelegateSwitched = false;
        boolean inServerSwitched = false;
        boolean rrpCommitted = false;
        boolean generationPublished = false;
        org.rx.net.socks.RrpServer obsoleteRrpServer = null;
        RssInServer obsoleteInServer = null;

        try {
            // Prepare all fallible resources and immutable generation before publishing any live state.
            nodeServicePlan = prepareNodeServicePlan(currentConf, newConf);
            RssAuthenticator.AuthSettings nextAuthSettings = authenticator.prepareReload(
                    newConf.shadowUsers, newConf.socksPwd.trim(), newConf.memoryRetentionHours);
            nextUpstream = buildUpstreams(newConf);
            if (restartInServers) {
                inServersPlan = prepareInServersPlan(newConf);
            }
            RssInServer targetInServer = restartInServers ? inServersPlan.newInServer : inServer;
            RssClient.OutboundProfiles nextOutboundProfiles = new RssClient.OutboundProfiles(newConf, targetInServer.inConf);
            SocksConfig nextStandardConf = RssClient.standardSocksConfig(newConf);

            if (restartDns) {
                nextDnsInterceptors = new SwitchingRandomList<>();
                nextDnsInterceptors.setDelegate(nextUpstream.dnsInterceptors);
                nextDnsServer = createDnsServer(newConf, nextDnsInterceptors);
            }
            DnsServer targetDnsServer = restartDns ? nextDnsServer : dnsSvr;
            if (restartNameserver) {
                boolean sameNsPort = prevNameserver != null && currentConf != null
                        && currentConf.nameserver != null && newConf.nameserver != null
                        && currentConf.nameserver.getRegisterPort() == newConf.nameserver.getRegisterPort();
                if (sameNsPort) {
                    closeNameserverQuietly(prevNameserver);
                    nameserverSamePortDetached = true;
                }
                nextNameserver = new NameserverImpl(resolveNameserverConfig(newConf), targetDnsServer, nodeServicePlan.targetRegistry);
            }

            rrpPlan = prepareRrpServerPlan(newConf);
            materializePortExclusiveRrpPlan(rrpPlan);
            shadowPlan = buildShadowServers(newConf, targetInServer, rebuildShadowServers);

            RuntimeGeneration nextGeneration = new RuntimeGeneration(newConf, nextUpstream, targetDnsServer,
                    nextOutboundProfiles, nextStandardConf);

            // Commit phase: publish atomically and defer closing obsolete resources until every step succeeds.
            authenticator.applySettings(nextAuthSettings);
            authCommitted = true;

            if (restartNameserver) {
                nameserverRef = nextNameserver;
                nameserver = nextNameserver;
                nameserverPublished = true;
            }
            nodeRegistryRef = nodeServicePlan.targetRegistry;
            rendezvousRef = nodeServicePlan.targetRendezvous;
            nodeRelayRef = nodeServicePlan.targetNodeRelay;
            nodeServicePublished = true;
            if (restartDns) {
                dnsSvr = nextDnsServer;
                dnsInterceptors = nextDnsInterceptors;
                InetSocketAddress shadowDnsEp = Sockets.newLoopbackEndpoint(newConf.shadowDnsPort);
                Sockets.injectNameService(java.util.Collections.singletonList(shadowDnsEp));
                dnsPublished = true;
            } else {
                dnsInterceptors.setDelegate(nextUpstream.dnsInterceptors);
                applyDnsConfig(dnsSvr, newConf, dnsInterceptors);
                dnsDelegateSwitched = true;
            }

            if (restartInServers) {
                obsoleteInServer = inServersPlan.portExclusive ? null : inServer;
                inServer = inServersPlan.newInServer;
                app.setServer(inServer.server);
                inServerSwitched = true;
            }

            generation = nextGeneration;
            upstreamSnapshot = nextUpstream;
            activeConf = newConf;
            rssConf = newConf;
            generationPublished = true;

            applyLiveConfig(newConf, nextOutboundProfiles, nextStandardConf);
            configureDdnsSchedule(newConf);
            configureAutoWhiteListSchedule(newConf, this);
            obsoleteRrpServer = commitRrpServerPlanDeferredClose(rrpPlan);
            rrpCommitted = true;

            Runnable hook = reloadPostPublishHook;
            if (hook != null) {
                hook.run();
            }

            List<ShadowServerRef> obsoleteShadowServers = commitShadowServersPlanDeferredClose(shadowPlan);
            if (inServersPlan != null) {
                inServersPlan.newInServer = null;
                inServersPlan.oldInServer = null;
                inServersPlan.oldDetached = false;
                inServersPlan.committed = true;
            }
            if (nodeServicePlan != null) {
                nodeServicePlan.committed = true;
            }
            nextNameserver = null;
            nextDnsServer = null;
            nextDnsInterceptors = null;
            nextUpstream = null;
            shadowPlan = null;
            rrpPlan = null;

            startUpstreamHealthCheck(nextGeneration.upstreamSnapshot, newConf);
            addPublicIpWhiteList();

            // Close obsolete resources in reverse order only after the new generation is completely committed.
            closeNodeServiceObsoleteReverseOrder(nodeServicePlan);
            closeInServerQuietly(obsoleteInServer);
            for (ShadowServerRef obsoleteRef : obsoleteShadowServers) {
                closeShadowServerQuietly(obsoleteRef);
            }
            tryClose(obsoleteRrpServer);
            closeUpstreamsLater(prevUpstream);
            trackClosingUpstream(prevUpstream);
            if (restartNameserver && !nameserverSamePortDetached) {
                closeNameserverQuietly(prevNameserver);
            }
            if (restartDns) {
                closeDnsServerQuietly(prevDnsServer);
            }
            if (nodeServicePlan != null && nodeServicePlan.obsoleteRegistry != null) {
                closeNodeRegistryQuietly(nodeServicePlan.obsoleteRegistry);
            }
            log.info("rssConf reload ok");
        } catch (Throwable e) {
            if (generationPublished) {
                generation = prevGeneration;
                upstreamSnapshot = prevUpstream;
                activeConf = currentConf;
                rssConf = currentConf;
            }
            org.rx.core.RxConfig.INSTANCE.getNet().setPublicIpEndpoints(prevPublicIpEndpoints);
            if (authCommitted) {
                authenticator.applySettings(prevAuthSettings);
            }
            if (nameserverPublished) {
                closeNameserverQuietly(nextNameserver);
                nextNameserver = null;
                if (nameserverSamePortDetached && currentConf != null) {
                    try {
                        prevNameserver = new NameserverImpl(resolveNameserverConfig(currentConf), prevDnsServer, prevNodeRegistry);
                    } catch (Throwable restoreErr) {
                        log.warn("rssConf reload rollback failed to restore nameserver", restoreErr);
                    }
                }
                nameserverRef = prevNameserver;
                nameserver = prevNameserver;
            } else if (nameserverSamePortDetached && currentConf != null) {
                closeNameserverQuietly(nextNameserver);
                nextNameserver = null;
                try {
                    prevNameserver = new NameserverImpl(resolveNameserverConfig(currentConf), prevDnsServer, prevNodeRegistry);
                    nameserverRef = prevNameserver;
                    nameserver = prevNameserver;
                } catch (Throwable restoreErr) {
                    log.warn("rssConf reload rollback failed to restore nameserver", restoreErr);
                }
            }
            if (nodeServicePublished) {
                nodeRegistryRef = prevNodeRegistry;
                rendezvousRef = prevRendezvous;
                nodeRelayRef = prevNodeRelay;
            }
            rollbackNodeServicePlan(nodeServicePlan, currentConf);
            if (dnsPublished) {
                dnsSvr = prevDnsServer;
                dnsInterceptors = prevDnsInterceptors;
                if (currentConf != null) {
                    Sockets.injectNameService(java.util.Collections.singletonList(
                            Sockets.newLoopbackEndpoint(currentConf.shadowDnsPort)));
                }
            } else if (dnsDelegateSwitched && prevUpstream != null && prevDnsInterceptors != null) {
                prevDnsInterceptors.setDelegate(prevUpstream.dnsInterceptors);
                if (prevDnsServer != null && currentConf != null) {
                    applyDnsConfig(prevDnsServer, currentConf, prevDnsInterceptors);
                }
            }
            if (inServerSwitched) {
                inServer = prevInServer;
                if (prevInServer != null) {
                    app.setServer(prevInServer.server);
                }
            }
            if (prevGeneration != null && currentConf != null) {
                applyLiveConfig(currentConf, prevGeneration.outboundProfiles, prevGeneration.standardConf);
            }
            if (rrpCommitted) {
                rollbackCommittedRrpServerPlan(rrpPlan, obsoleteRrpServer);
            } else {
                rollbackRrpServerPlan(rrpPlan);
                closeRrpServerPlanQuietly(rrpPlan);
            }
            configureDdnsSchedule(currentConf);
            configureAutoWhiteListSchedule(currentConf, this);
            rollbackInServersPlan(inServersPlan);
            closeInServersPlanQuietly(inServersPlan);
            closeUpstreamsQuietly(nextUpstream);
            closeNameserverQuietly(nextNameserver);
            closeDnsServerQuietly(nextDnsServer);
            closeShadowServersPlanQuietly(shadowPlan);
            log.error("rssConf reload failed, keep previous runtime", e);
            throw InvalidException.sneaky(e);
        }
    }

    void addPublicIpWhiteList() {
        try {
            RuntimeGeneration gen = generation;
            UpstreamSnapshot snapshot = gen != null ? gen.upstreamSnapshot : upstreamSnapshot;
            if (snapshot == null) {
                return;
            }
            InetAddress observed = null;
            try {
                observed = Sockets.parseIpAddress(Sockets.getPublicIp());
            } catch (Exception unavailable) {
                log.debug("No public IPv4 observation, registering authenticated RPC peer", unavailable);
            }
            InetAddress addr = observed;
            eachQuietly(snapshot.dnsInterceptors, interceptor -> {
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
        // Reverse-order shutdown (§3.1): stop relay/rendezvous -> RSS servers -> Nameserver/DNS -> flush & close NodeRegistry
        closeNodeServicesReverseOrder();
        for (ShadowServerRef ref : shadowServers.values()) {
            closeShadowServerQuietly(ref);
        }
        shadowServers.clear();
        userBandwidthLimiters.clear();
        closeInServerQuietly(inServer);
        closeNameserverQuietly(nameserverRef);
        closeDnsServerQuietly(dnsSvr);
        closeNodeRegistryQuietly(nodeRegistryRef);
        nodeRegistryRef = null;
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
        closeNodeServicesReverseOrder();
        closeInServerQuietly(inServer);
        for (ShadowServerRef ref : shadowServers.values()) {
            closeShadowServerQuietly(ref);
        }
        closeNameserverQuietly(nameserverRef);
        closeDnsServerQuietly(dnsSvr);
        closeNodeRegistryQuietly(nodeRegistryRef);
        nodeRegistryRef = null;
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
        return createInSvr(conf, inConf, authenticator, this::firstRoute, this::currentGeneration);
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
            e.setUpstream(new RssDnsInterceptor(dstEp, e.getSource(), e.getUser(), this));
            e.setHandled(true);
        }
    }

    private void applyLiveConfig(RssClientConf conf, RssClient.OutboundProfiles outboundProfiles, SocksConfig standardConf) {
        org.rx.core.RxConfig.INSTANCE.getNet().setPublicIpEndpoints(conf.publicIpEndpoints);
        applyInServerConfig(outboundProfiles, standardConf, inServer);
    }

    private void applyInServerConfig(RssClient.OutboundProfiles outboundProfiles, SocksConfig standardConf, RssInServer server) {
        if (server == null) {
            return;
        }
        server.outConfRef.set(outboundProfiles);
        server.standardConfRef.set(standardConf);
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
                BandwidthLimitConfig nextBw = usr.getBandwidth() == null ? null : usr.getBandwidth().copy();
                if (oldRef != null && oldRef.matches(usr, routePlan.signature, forceRebuild)) {
                    plan.matcherUpdates.add(new ShadowMatcherUpdate(oldRef, usr, conf));
                    plan.bandwidthUpdates.add(new ShadowBandwidthUpdate(oldRef, username, nextBw, null));
                    plan.next.put(username, oldRef);
                    continue;
                }

                if (oldRef != null && oldRef.ssPort == usr.getSsPort()) {
                    samePortRestarts.add(new ShadowRestart(usr, oldRef, routePlan));
                    continue;
                }
                BandwidthLimiter prepared = prepareShadowBandwidthLimiter(username, nextBw);
                ShadowServerRef ref = createShadowServer(conf, usr, routePlan, false, prepared);
                plan.created.add(ref);
                plan.bandwidthUpdates.add(new ShadowBandwidthUpdate(ref, username, nextBw, prepared));
                plan.next.put(username, ref);
            }
            for (ShadowRestart restart : samePortRestarts) {
                // 同端口密码变化无法双实例并行绑定，只能接受短暂不可用窗口。
                closeShadowServerQuietly(restart.oldRef);
                plan.stopped.add(restart);
                String username = restart.user.getUsername();
                BandwidthLimitConfig nextBw = restart.user.getBandwidth() == null ? null : restart.user.getBandwidth().copy();
                BandwidthLimiter prepared = prepareShadowBandwidthLimiter(username, nextBw);
                ShadowServerRef ref = createShadowServer(conf, restart.user, restart.routePlan, false, prepared);
                plan.created.add(ref);
                plan.bandwidthUpdates.add(new ShadowBandwidthUpdate(ref, username, nextBw, prepared));
                plan.next.put(username, ref);
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
        List<ShadowServerRef> obsolete = commitShadowServersPlanDeferredClose(plan);
        for (ShadowServerRef oldRef : obsolete) {
            closeShadowServerQuietly(oldRef);
        }
    }

    private List<ShadowServerRef> commitShadowServersPlanDeferredClose(ShadowServersPlan plan) {
        if (plan == null) {
            return Collections.emptyList();
        }
        for (ShadowMatcherUpdate update : plan.matcherUpdates) {
            update.ref.updateRouteMatcher(update.user, update.conf);
        }
        for (ShadowBandwidthUpdate update : plan.bandwidthUpdates) {
            boolean enabled = update.nextConfig != null && update.nextConfig.isEnabled();
            BandwidthLimiter limiter = userBandwidthLimiters.get(update.username);
            if (limiter == null) {
                if (enabled) {
                    limiter = update.preparedLimiter != null
                            ? update.preparedLimiter
                            : new BandwidthLimiter(update.username, update.nextConfig);
                    userBandwidthLimiters.put(update.username, limiter);
                }
            } else {
                limiter.configure(enabled ? update.nextConfig : BandwidthLimitConfig.unlimited());
            }
            update.ref.bandwidth = update.nextConfig;
            update.ref.server.getConfig().setBandwidthLimiter(enabled ? limiter : null);
        }
        List<ShadowServerRef> obsolete = new ArrayList<>();
        for (Map.Entry<String, ShadowServerRef> entry : shadowServers.entrySet()) {
            ShadowServerRef oldRef = entry.getValue();
            ShadowServerRef nextRef = plan.next.get(entry.getKey());
            if (oldRef != nextRef) {
                obsolete.add(oldRef);
            }
            if (nextRef == null) {
                userBandwidthLimiters.remove(entry.getKey());
            }
        }
        shadowServers.clear();
        shadowServers.putAll(plan.next);
        plan.committed = true;
        return obsolete;
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
            user.setBandwidth(oldRef.bandwidth == null ? null : oldRef.bandwidth.copy());
            UserRule route = new UserRule();
            route.setSrcSteeringTTL(oldRef.srcSteeringTTL);
            user.setRoute(route);
            BandwidthLimiter existing = userBandwidthLimiters.get(oldRef.username);
            ShadowServerRef restored = createShadowServer(activeConf, user, oldRef.routePlan, true, existing);
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

    private BandwidthLimiter prepareShadowBandwidthLimiter(String username, BandwidthLimitConfig config) {
        BandwidthLimiter existing = userBandwidthLimiters.get(username);
        if (existing != null) {
            return existing;
        }
        if (config != null && config.isEnabled()) {
            return new BandwidthLimiter(username, config);
        }
        return null;
    }

    private ShadowServerRef createShadowServer(RssClientConf conf, ShadowUser usr, RssRoutePlan routePlan) {
        return createShadowServer(conf, usr, routePlan, false, null);
    }

    private ShadowServerRef createShadowServer(RssClientConf conf, ShadowUser usr, RssRoutePlan routePlan,
                                               boolean restoring, BandwidthLimiter bandwidthLimiter) {
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
        if (usr.getBandwidth() != null && usr.getBandwidth().isEnabled() && bandwidthLimiter != null) {
            ssConf.setBandwidthLimiter(bandwidthLimiter);
        }
        enableShadowIngressReusePort(ssConf);
        ShadowsocksServer ssSvr = new ShadowsocksServer(ssConf);
        SocksConfig toInConf = new SocksConfig();
        toInConf.setOptimalSettings(RssSupport.IN_OPS);
        ShadowServerRef ref = new ShadowServerRef(usr.getUsername(), usr.getSsPort(), usr.getSsPwd(), cipher,
                routePlan, ssSvr, usr.getRouteMatcher(), sourceSteeringTtl(usr, conf),
                usr.getBandwidth() == null ? null : usr.getBandwidth().copy());
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
        volatile BandwidthLimitConfig bandwidth;

        ShadowServerRef(String username, int ssPort, String ssPwd, String ssCipher, RssRoutePlan routePlan,
                        ShadowsocksServer server, UserRuleMatcher routeMatcher, int srcSteeringTTL,
                        BandwidthLimitConfig bandwidth) {
            this.username = username;
            this.ssPort = ssPort;
            this.ssPwd = ssPwd;
            this.ssCipher = ssCipher;
            this.routePlan = routePlan;
            this.server = server;
            this.routeMatcher = routeMatcher;
            this.srcSteeringTTL = Math.max(0, srcSteeringTTL);
            this.bandwidth = bandwidth;
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

    static final class ShadowMatcherUpdate {
        final ShadowServerRef ref;
        final ShadowUser user;
        final RssClientConf conf;

        ShadowMatcherUpdate(ShadowServerRef ref, ShadowUser user, RssClientConf conf) {
            this.ref = ref;
            this.user = user;
            this.conf = conf;
        }
    }

    static final class ShadowBandwidthUpdate {
        final ShadowServerRef ref;
        final String username;
        final BandwidthLimitConfig nextConfig;
        final BandwidthLimiter preparedLimiter;

        ShadowBandwidthUpdate(ShadowServerRef ref, String username, BandwidthLimitConfig nextConfig,
                              BandwidthLimiter preparedLimiter) {
            this.ref = ref;
            this.username = username;
            this.nextConfig = nextConfig;
            this.preparedLimiter = preparedLimiter;
        }
    }

    static final class ShadowServersPlan {
        final Map<String, ShadowServerRef> next = new LinkedHashMap<>();
        final List<ShadowServerRef> created = new ArrayList<>();
        final List<ShadowRestart> stopped = new ArrayList<>();
        final List<ShadowMatcherUpdate> matcherUpdates = new ArrayList<>();
        final List<ShadowBandwidthUpdate> bandwidthUpdates = new ArrayList<>();
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

    static final class RuntimeGeneration {
        final RssClientConf conf;
        final UpstreamSnapshot upstreamSnapshot;
        final DnsServer dnsServer;
        final RssClient.OutboundProfiles outboundProfiles;
        final SocksConfig standardConf;

        RuntimeGeneration(RssClientConf conf, UpstreamSnapshot upstreamSnapshot, DnsServer dnsServer,
                          RssClient.OutboundProfiles outboundProfiles, SocksConfig standardConf) {
            this.conf = conf;
            this.upstreamSnapshot = upstreamSnapshot;
            this.dnsServer = dnsServer;
            this.outboundProfiles = outboundProfiles;
            this.standardConf = standardConf;
        }
    }

    static final class UpstreamSnapshot {
        final RssDnsInterceptor.Answers dnsAnswers = new RssDnsInterceptor.Answers();
        final RandomList<UpstreamSupport> socksServers;
        final RandomList<DnsResolveInterceptor> dnsInterceptors;
        final Map<String, RandomList<UpstreamSupport>> userSocksServers;
        final List<RssClientConf.SocksServer> configuredSocksServers;
        final boolean hasRestrictedUpstreams;
        final ConcurrentHashMap<String, RandomList<UpstreamSupport>> filteredSocksServers = new ConcurrentHashMap<>();
        final AtomicInteger inFlightDnsQueries = new AtomicInteger();
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
            this.hasRestrictedUpstreams = detectRestrictedUpstreams(socksServers);
        }

        private static boolean detectRestrictedUpstreams(RandomList<UpstreamSupport> servers) {
            if (servers == null) {
                return false;
            }
            for (UpstreamSupport support : servers.readOnlySnapshot()) {
                if (support != null && (support.isStandardSocks5() || support.getUdpClient() != null)) {
                    return true;
                }
            }
            return false;
        }

        boolean tryRetainDnsQuery() {
            if (closed) {
                return false;
            }
            inFlightDnsQueries.incrementAndGet();
            if (closed) {
                inFlightDnsQueries.decrementAndGet();
                return false;
            }
            return true;
        }

        void releaseDnsQuery() {
            inFlightDnsQueries.updateAndGet(v -> v > 0 ? v - 1 : 0);
        }

        int inFlightDnsQueryCount() {
            return inFlightDnsQueries.get();
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

    static final class NodeServicePlan {
        NodeRegistry targetRegistry;
        NodeRegistry newlyCreatedRegistry;
        NodeRegistry obsoleteRegistry;
        RendezvousService targetRendezvous;
        RendezvousService newlyCreatedRendezvous;
        RendezvousService obsoleteRendezvous;
        boolean oldRendezvousDetached;
        RrpServer targetNodeRelay;
        RrpServer newlyCreatedNodeRelay;
        RrpServer obsoleteNodeRelay;
        boolean oldRelayDetached;
        boolean committed;
    }

    private NodeServicePlan prepareNodeServicePlan(RssClientConf currentConf, RssClientConf newConf) {
        NodeServicePlan plan = new NodeServicePlan();
        boolean curRegEnabled = nodeRegistryRef != null;
        boolean newRegEnabled = newConf.nodeRegistry != null && newConf.nodeRegistry.isEnabled();

        // Single-NodeRegistry invariant (§3.1): reuse existing registry; reject unsafe live dbPath change.
        if (curRegEnabled && newRegEnabled) {
            String curPath = currentConf != null && currentConf.nodeRegistry != null ? currentConf.nodeRegistry.getDbPath() : null;
            String newPath = newConf.nodeRegistry.getDbPath();
            if (!Strings.hashEquals(curPath, newPath)) {
                throw new IllegalStateException("Changing nodeRegistry.dbPath requires restart: old=" + curPath + " new=" + newPath);
            }
            plan.targetRegistry = nodeRegistryRef;
        } else if (!curRegEnabled && newRegEnabled) {
            plan.newlyCreatedRegistry = NodeRegistry.openIfEnabled(newConf.nodeRegistry);
            plan.targetRegistry = plan.newlyCreatedRegistry;
        } else if (curRegEnabled) {
            plan.obsoleteRegistry = nodeRegistryRef;
            plan.targetRegistry = null;
        }

        RssClientConf.NodeServiceConf curSvc = currentConf == null ? null : currentConf.nodeService;
        RssClientConf.NodeServiceConf newSvc = newConf.nodeService;
        boolean newSvcOn = newSvc != null && newSvc.enabled;
        boolean newRvOn = newSvcOn && newSvc.rendezvousEnabled;
        boolean newRelayOn = newSvcOn && newSvc.relayEnabled;
        if (newSvcOn && plan.targetRegistry == null) {
            closeNodeServicePlanNewResources(plan);
            throw new IllegalStateException("nodeService.enabled requires nodeRegistry.enabled=true");
        }

        try {
            boolean rvChanged = (rendezvousRef != null) != newRvOn
                    || (newRvOn && !sameRendezvousConfig(curSvc, newSvc))
                    || (newRvOn && plan.targetRegistry != nodeRegistryRef);
            if (!rvChanged) {
                plan.targetRendezvous = rendezvousRef;
            } else if (newRvOn) {
                boolean samePort = rendezvousRef != null && curSvc != null
                        && ((curSvc.rendezvousPort > 0 && curSvc.rendezvousPort == newSvc.rendezvousPort)
                        || (curSvc.udpObservePort > 0 && curSvc.udpObservePort == newSvc.udpObservePort));
                if (samePort) {
                    plan.oldRendezvousDetached = true;
                    tryClose(rendezvousRef);
                    rendezvousRef = null;
                } else {
                    plan.obsoleteRendezvous = rendezvousRef;
                }
                plan.newlyCreatedRendezvous = createRendezvousService(newSvc, plan.targetRegistry);
                plan.targetRendezvous = plan.newlyCreatedRendezvous;
            } else {
                plan.obsoleteRendezvous = rendezvousRef;
                plan.targetRendezvous = null;
            }

            boolean relayChanged = (nodeRelayRef != null) != newRelayOn
                    || (newRelayOn && !sameNodeRelayConfig(curSvc, newSvc))
                    || (newRelayOn && plan.targetRegistry != nodeRegistryRef);
            if (!relayChanged) {
                plan.targetNodeRelay = nodeRelayRef;
            } else if (newRelayOn) {
                boolean samePort = nodeRelayRef != null && curSvc != null
                        && curSvc.relayPort > 0 && curSvc.relayPort == newSvc.relayPort;
                if (samePort) {
                    plan.oldRelayDetached = true;
                    tryClose(nodeRelayRef);
                    nodeRelayRef = null;
                } else {
                    plan.obsoleteNodeRelay = nodeRelayRef;
                }
                plan.newlyCreatedNodeRelay = createNodeRelayServer(newSvc, plan.targetRegistry);
                plan.targetNodeRelay = plan.newlyCreatedNodeRelay;
            } else {
                plan.obsoleteNodeRelay = nodeRelayRef;
                plan.targetNodeRelay = null;
            }
            return plan;
        } catch (Throwable e) {
            rollbackNodeServicePlan(plan, currentConf);
            throw InvalidException.sneaky(e);
        }
    }

    private void rollbackNodeServicePlan(NodeServicePlan plan, RssClientConf currentConf) {
        if (plan == null || plan.committed) {
            return;
        }
        closeNodeServicePlanNewResources(plan);
        if (currentConf != null && currentConf.nodeService != null && currentConf.nodeService.enabled && nodeRegistryRef != null) {
            if (plan.oldRendezvousDetached && rendezvousRef == null && currentConf.nodeService.rendezvousEnabled) {
                try {
                    rendezvousRef = createRendezvousService(currentConf.nodeService, nodeRegistryRef);
                } catch (Throwable restoreErr) {
                    log.error("restore rendezvous service failed after reload rollback", restoreErr);
                }
            }
            if (plan.oldRelayDetached && nodeRelayRef == null && currentConf.nodeService.relayEnabled) {
                try {
                    nodeRelayRef = createNodeRelayServer(currentConf.nodeService, nodeRegistryRef);
                } catch (Throwable restoreErr) {
                    log.error("restore node relay server failed after reload rollback", restoreErr);
                }
            }
        }
    }

    private static void closeNodeServicePlanNewResources(NodeServicePlan plan) {
        if (plan == null) {
            return;
        }
        tryClose(plan.newlyCreatedNodeRelay);
        plan.newlyCreatedNodeRelay = null;
        tryClose(plan.newlyCreatedRendezvous);
        plan.newlyCreatedRendezvous = null;
        closeNodeRegistryQuietly(plan.newlyCreatedRegistry);
        plan.newlyCreatedRegistry = null;
    }

    private static void closeNodeServiceObsoleteReverseOrder(NodeServicePlan plan) {
        if (plan == null) {
            return;
        }
        tryClose(plan.obsoleteNodeRelay);
        plan.obsoleteNodeRelay = null;
        tryClose(plan.obsoleteRendezvous);
        plan.obsoleteRendezvous = null;
    }

    private void closeNodeServicesReverseOrder() {
        tryClose(nodeRelayRef);
        nodeRelayRef = null;
        tryClose(rendezvousRef);
        rendezvousRef = null;
    }

    private static void closeNodeRegistryQuietly(NodeRegistry reg) {
        if (reg == null) {
            return;
        }
        try {
            reg.close();
        } catch (Throwable e) {
            log.warn("close NodeRegistry failed", e);
        }
    }

    private static RendezvousService createRendezvousService(RssClientConf.NodeServiceConf svc, NodeRegistry registry) {
        RendezvousConfig cfg = new RendezvousConfig();
        cfg.setRpcPort(svc.rendezvousPort);
        cfg.setUdpObservePort(svc.udpObservePort);
        cfg.setAuthSecret(svc.authToken);
        cfg.setEgressConfig(svc.egressConfig != null ? svc.egressConfig : org.rx.net.EgressConfig.serverAutoAll());
        cfg.setRelayAvailable(svc.relayEnabled);
        return new RendezvousService(cfg, registry);
    }

    private static RrpServer createNodeRelayServer(RssClientConf.NodeServiceConf svc, NodeRegistry registry) {
        RrpConfig cfg = new RrpConfig();
        cfg.setToken(svc.authToken);
        cfg.setBindPort(svc.relayPort);
        cfg.setEnableRawRelay(true);
        cfg.setEgressConfig(svc.egressConfig != null ? svc.egressConfig : org.rx.net.EgressConfig.serverAutoAll());
        return new RrpServer(cfg, registry);
    }

    private static boolean sameRendezvousConfig(RssClientConf.NodeServiceConf a, RssClientConf.NodeServiceConf b) {
        if (a == null || b == null) {
            return false;
        }
        return a.rendezvousPort == b.rendezvousPort
                && a.udpObservePort == b.udpObservePort
                && a.relayEnabled == b.relayEnabled
                && Strings.hashEquals(a.authToken, b.authToken)
                && java.util.Objects.equals(a.egressConfig, b.egressConfig);
    }

    private static boolean sameNodeRelayConfig(RssClientConf.NodeServiceConf a, RssClientConf.NodeServiceConf b) {
        if (a == null || b == null) {
            return false;
        }
        return a.relayPort == b.relayPort
                && Strings.hashEquals(a.authToken, b.authToken)
                && java.util.Objects.equals(a.egressConfig, b.egressConfig);
    }
}
