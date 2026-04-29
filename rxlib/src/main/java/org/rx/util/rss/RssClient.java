package org.rx.util.rss;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.netty.channel.local.LocalAddress;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.RandomList;
import org.rx.codec.CodecUtil;
import org.rx.core.Linq;
import org.rx.core.Reflects;
import org.rx.core.Strings;
import org.rx.core.Sys;
import org.rx.core.Tasks;
import org.rx.core.config.YamlConfigSource;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.exception.InvalidException;
import org.rx.io.DuplexStream;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.TransportFlags;
import org.rx.net.dns.DnsClient;
import org.rx.net.dns.DnsServer;
import org.rx.net.http.HttpClient;
import org.rx.net.http.HttpServer;
import org.rx.net.nameserver.NameserverConfig;
import org.rx.net.nameserver.NameserverImpl;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.socks.Authenticator;
import org.rx.net.socks.RrpConfig;
import org.rx.net.socks.RrpServer;
import org.rx.net.socks.ShadowsocksConfig;
import org.rx.net.socks.SocksConnectionTagRegistry;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.socks.upstream.SocksTcpUpstream;
import org.rx.net.socks.upstream.SocksUdpUpstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.GeoManager;
import org.rx.net.support.IpGeolocation;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;
import org.rx.net.transport.TcpClientConfig;
import org.rx.util.function.BiFunc;
import org.rx.util.function.QuadraFunc;
import org.rx.util.function.TripleAction;

import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.rx.core.Extends.eachQuietly;
import static org.rx.core.Extends.tryClose;
import static org.rx.core.Sys.toJsonString;

@Slf4j
public final class RssClient {
    static final long RSS_RELOAD_DEBOUNCE_MILLIS = 100L;
    static final long UPSTREAM_CLOSE_DELAY_MILLIS = 30_000L;
    static final long UPSTREAM_CLOSE_CHECK_MILLIS = 1_000L;
    static final long UPSTREAM_CLOSE_MAX_WAIT_MILLIS = 5L * 60L * 1000L;
    static final long UPSTREAM_HEALTH_CHECK_PERIOD_MILLIS = 5_000L;

    static volatile RSSConf rssConf;
    static volatile RssRuntime runtime;
    static RrpServer rrpServer;
    static String rrpToken;
    static Integer rrpPort;
    static HttpServer httpServer;
    static NameserverImpl nameserver;
    static RssUserTrafficStore trafficStore;
    static ScheduledFuture<?> ddnsTask;
    static int ddnsTaskPeriodSeconds;
    static ScheduledFuture<?> autoWhiteListTask;
    static int autoWhiteListTaskPeriodSeconds;

    private RssClient() {
    }

    @SneakyThrows
    public static void launch(Map<String, String> options, int port) {
        YamlConfigSource<RSSConf> source = new YamlConfigSource<RSSConf>("rss", RSSConf.class, "conf.yml")
                .setValidator(RssClient::normalizeAndValidateRssConfig)
                .setChangeDetector((oldConfig, newConfig) -> !Strings.hashEquals(toJsonString(oldConfig), toJsonString(newConfig)))
                .setDebounceMillis(RSS_RELOAD_DEBOUNCE_MILLIS);
        RssRuntime rt = null;
        try {
            source.start();
            rt = new RssRuntime(options, port, source.current());
            runtime = rt;
            RssRuntime current = rt;
            source.onChanged.add((s, e) -> current.reload(e.getOldConfig(), e.getNewConfig()));
        } catch (Throwable e) {
            tryClose(source);
            if (rt != null) {
                tryClose(rt);
            }
            throw InvalidException.sneaky(e);
        }

        log.info("Server started..");
        rt.await();
    }

    static final class ForwardingSocksRpcContract implements SocksRpcContract {
        final SocksRpcContract delegate;
        final GeoManager geoMgr;

        ForwardingSocksRpcContract(SocksRpcContract delegate, GeoManager geoMgr) {
            this.delegate = delegate;
            this.geoMgr = geoMgr;
        }

        @Override
        public void fakeEndpoint(BigInteger hash, String realEndpoint) {
            delegate.fakeEndpoint(hash, realEndpoint);
        }

        @Override
        public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
            boolean outProxy;
            String ext;
            RSSConf conf = rssConf;
            RSSConf.RouteConf routeConf = conf.route;
            if (routeConf.enable) {
                if (routeConf.srcIpProxyRules != null && routeConf.srcIpProxyRules.contains(srcIp)) {
                    outProxy = true;
                    ext = "srcIp:proxy";
                } else if (geoMgr.matchSiteDirect(host)) {
                    outProxy = false;
                    ext = "geosite:direct";
                } else {
                    outProxy = true;
                    ext = "geosite:proxy";
                }
            } else {
                outProxy = true;
                ext = "routeDisabled";
            }
            if (conf.hasRouteFlag()) {
                log.info("route dns {}+{} {} <- {}", srcIp, host, outProxy ? "PROXY" : "DIRECT", ext);
            }
            return outProxy ? delegate.resolveHost(srcIp, host) : DnsClient.inlandClient().resolveAll(host);
        }

        @Override
        public void addWhiteList(InetAddress endpoint) {
            delegate.addWhiteList(endpoint);
        }

        @Override
        public boolean resetUdpRelay(int relayPort) {
            return delegate.resetUdpRelay(relayPort);
        }

        @Override
        public boolean claimUdpRelay(int relayPort, InetSocketAddress clientAddr) {
            return delegate.claimUdpRelay(relayPort, clientAddr);
        }

        @Override
        public void close() {
            tryClose(delegate);
        }
    }

    static boolean normalizeAndValidateRssConfig(RSSConf conf) {
        if (conf == null) {
            return false;
        }
        if (conf.route == null) {
            conf.route = new RSSConf.RouteConf();
        }
        if (conf.nameserver == null) {
            conf.nameserver = new NameserverConfig();
        }
        if (conf.udp2rawSocksServers == null) {
            conf.udp2rawSocksServers = Collections.emptyList();
        }
        conf.trafficRetentionDays = Math.max(1, conf.trafficRetentionDays);
        conf.memoryRetentionHours = conf.memoryRetentionHours <= 0
                ? RssAuthenticator.DEFAULT_MEMORY_RETENTION_HOURS : conf.memoryRetentionHours;
        conf.connectTimeoutSeconds = Math.max(1, conf.connectTimeoutSeconds);
        conf.tcpTimeoutSeconds = Math.max(0, conf.tcpTimeoutSeconds);
        conf.udpTimeoutSeconds = Math.max(0, conf.udpTimeoutSeconds);
        conf.rpcMinSize = Math.max(1, conf.rpcMinSize);
        conf.rpcMaxSize = Math.max(conf.rpcMinSize, conf.rpcMaxSize);
        conf.rpcRequestTimeoutMillis = Math.max(0, conf.rpcRequestTimeoutMillis);
        conf.rpcAutoWhiteListSeconds = Math.max(0, conf.rpcAutoWhiteListSeconds);
        conf.shadowDnsPort = Math.max(1, conf.shadowDnsPort);
        conf.dnsTtlMinutes = Math.max(1, conf.dnsTtlMinutes);
        resolveNameserverConfig(conf);

        if (Strings.isEmpty(conf.socksPwd) || CollectionUtils.isEmpty(conf.shadowUsers) || CollectionUtils.isEmpty(conf.socksServers)) {
            return false;
        }
        if (!hasWeightedSocksServer(conf.socksServers)) {
            return false;
        }
        if (conf.rrpPort != null && conf.rrpPort <= 0) {
            return false;
        }
        Set<String> usernames = new LinkedHashSet<>();
        Set<Integer> shadowPorts = new LinkedHashSet<>();
        for (ShadowUser user : conf.shadowUsers) {
            if (user == null || user.getSsPort() <= 0 || Strings.isEmpty(user.getSsPwd())
                    || Strings.isEmpty(user.getSocksUser()) || Strings.isEmpty(user.getUsername())) {
                return false;
            }
            if (!usernames.add(user.getUsername()) || !shadowPorts.add(user.getSsPort())) {
                return false;
            }
        }
        if (shouldScheduleDdns(conf)) {
            if (Strings.isEmpty(conf.ddnsApiKey)) {
                return false;
            }
            for (String domain : conf.ddnsDomains) {
                int dotIndex = domain == null ? -1 : domain.indexOf('.');
                if (dotIndex <= 0 || dotIndex == domain.length() - 1) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasWeightedSocksServer(List<AuthenticEndpoint> socksServers) {
        for (AuthenticEndpoint socksServer : socksServers) {
            if (socksServer != null && socksServer.getInetEndpoint() != null && weightOf(socksServer) > 0) {
                return true;
            }
        }
        return false;
    }

    static int weightOf(AuthenticEndpoint endpoint) {
        if (endpoint == null) {
            return 0;
        }
        return Reflects.convertQuietly(endpoint.getParameters().get("w"), int.class, 0);
    }

    static RssRuntime.UpstreamSnapshot buildUpstreams(RSSConf conf, GeoManager geoMgr) {
        RandomList<UpstreamSupport> socksServers = new RandomList<>();
        RandomList<UpstreamSupport> udp2rawSocksServers = new RandomList<>();
        RandomList<DnsServer.ResolveInterceptor> dnsInterceptors = new RandomList<>();
        List<SocksRpcContract> createdFacades = new ArrayList<>();

        try {
            SocksRpcContract firstFacade = null;
            for (AuthenticEndpoint socksServer : conf.socksServers) {
                int weight = weightOf(socksServer);
                if (weight <= 0) {
                    continue;
                }
                InetSocketAddress socksServerEp = socksServer.requireEndpoint();
                RpcClientConfig<SocksRpcContract> rpcConf = RpcClientConfig.poolMode(
                        Sockets.newEndpoint(socksServerEp, socksServerEp.getPort() + 1),
                        conf.rpcMinSize, conf.rpcMaxSize);
                rpcConf.setRequestTimeoutMillis(resolveRpcRequestTimeoutMillis(conf));
                TcpClientConfig tcpConfig = rpcConf.getTcpConfig();
                tcpConfig.setTransportFlags(TransportFlags.GFW.flags(TransportFlags.CIPHER_BOTH).flags());
                SocksRpcContract facade = new ForwardingSocksRpcContract(Remoting.createFacade(SocksRpcContract.class, rpcConf), geoMgr);
                createdFacades.add(facade);
                if (firstFacade == null) {
                    firstFacade = facade;
                }
                UpstreamSupport support = new UpstreamSupport(socksServer, facade);
                support.setConfiguredWeight(weight);
                socksServers.add(support, weight);
                dnsInterceptors.add(facade, weight);
            }
            for (AuthenticEndpoint socksServer : conf.udp2rawSocksServers) {
                int weight = weightOf(socksServer);
                if (weight <= 0) {
                    continue;
                }
                UpstreamSupport support = new UpstreamSupport(socksServer, firstFacade);
                support.setConfiguredWeight(weight);
                udp2rawSocksServers.add(support, weight);
            }
            log.info("rssConf load socksServers: {}", toJsonString(conf.socksServers));
            log.info("rssConf load udp2rawSocksServers: {}", toJsonString(conf.udp2rawSocksServers));
            return new RssRuntime.UpstreamSnapshot(socksServers, udp2rawSocksServers, dnsInterceptors);
        } catch (Throwable e) {
            for (SocksRpcContract facade : createdFacades) {
                tryClose(facade);
            }
            throw InvalidException.sneaky(e);
        }
    }

    static DnsServer createDnsServer(RSSConf conf, RandomList<DnsServer.ResolveInterceptor> dnsInterceptors) {
        DnsServer dnsServer = new DnsServer(conf.shadowDnsPort);
        applyDnsConfig(dnsServer, conf, dnsInterceptors);
        dnsServer.addHostsFile("hosts.txt");
        return dnsServer;
    }

    static void applyDnsConfig(DnsServer dnsServer, RSSConf conf, RandomList<DnsServer.ResolveInterceptor> dnsInterceptors) {
        dnsServer.setTtl(60 * conf.dnsTtlMinutes);
        dnsServer.setNegativeTtl(DnsServer.DEFAULT_NEGATIVE_TTL);
        dnsServer.setInterceptors(dnsInterceptors);
    }

    static boolean inServerRestartRequired(RSSConf oldConf, RSSConf newConf) {
        return oldConf == null
                || oldConf.socksBindPort != newConf.socksBindPort
                || oldConf.logFlags != newConf.logFlags
                || oldConf.connectTimeoutSeconds != newConf.connectTimeoutSeconds
                || oldConf.tcpTimeoutSeconds != newConf.tcpTimeoutSeconds
                || oldConf.udpTimeoutSeconds != newConf.udpTimeoutSeconds
                || !Strings.hashEquals(toJsonString(oldConf.udp2rawClient), toJsonString(newConf.udp2rawClient))
                || !Strings.hashEquals(toJsonString(oldConf.kcptunClient), toJsonString(newConf.kcptunClient));
    }

    static boolean shadowServerRestartRequired(RSSConf oldConf, RSSConf newConf) {
        return oldConf == null
                || oldConf.logFlags != newConf.logFlags
                || oldConf.connectTimeoutSeconds != newConf.connectTimeoutSeconds;
    }

    static boolean dnsRestartRequired(RSSConf oldConf, RSSConf newConf) {
        return oldConf == null || oldConf.shadowDnsPort != newConf.shadowDnsPort;
    }

    static boolean nameserverRestartRequired(RSSConf oldConf, RSSConf newConf) {
        if (oldConf == null) {
            return true;
        }
        NameserverConfig oldNs = oldConf.nameserver;
        NameserverConfig newNs = newConf.nameserver;
        return oldNs.getRegisterPort() != newNs.getRegisterPort()
                || oldNs.getSyncPort() != newNs.getSyncPort()
                || !Strings.hashEquals(toJsonString(oldNs.getReplicaEndpoints()), toJsonString(newNs.getReplicaEndpoints()))
                || !Strings.hashEquals(toJsonString(oldNs.getUdpCodecAllowPrefixes()), toJsonString(newNs.getUdpCodecAllowPrefixes()));
    }

    static void configureInboundConfig(RSSConf conf, SocksConfig config, boolean udp2raw) {
        config.setDebug(conf.hasDebugFlag());
        config.setTcpAsyncDnsMode(SocksConfig.TcpAsyncDnsMode.INLAND);
        config.setOptimalSettings(RssSupport.IN_OPS);
        config.setConnectTimeoutMillis(conf.connectTimeoutSeconds * 1000);
        config.setReadTimeoutSeconds(conf.tcpTimeoutSeconds);
        config.setUdpReadTimeoutSeconds(conf.udpTimeoutSeconds);
        config.setUdpRedundantMultiplier(2);
        if (udp2raw) {
            config.setUdp2rawClient(conf.udp2rawClient);
            config.setKcptunClient(conf.kcptunClient);
        }
        RssSupport.applyUdpCompressionTrial(config);
    }

    static void configureOutboundConfig(RSSConf conf, SocksConfig config) {
        config.setDebug(conf.hasDebugFlag());
        config.setConnectTimeoutMillis(conf.connectTimeoutSeconds * 1000);
        config.setReadTimeoutSeconds(conf.tcpTimeoutSeconds);
        config.setUdpReadTimeoutSeconds(conf.udpTimeoutSeconds);
        applyUdpLeasePool(conf, config);
        RssSupport.applyUdpCompressionTrial(config);
    }

    static SocksConfig createOutboundConfig(RSSConf conf, SocksConfig inConf) {
        SocksConfig outConf = Sys.deepClone(inConf);
        outConf.setTransportFlags(TransportFlags.GFW.flags(TransportFlags.COMPRESS_BOTH).flags());
        outConf.setTcpCompressionLevel(RssSupport.TCP_TRIAL_COMPRESSION_LEVEL);
        outConf.setOptimalSettings(RssSupport.OUT_OPS);
        configureOutboundConfig(conf, outConf);
        return outConf;
    }

    static void configureShadowConfig(RSSConf conf, ShadowsocksConfig config) {
        config.setOptimalSettings(RssSupport.SS_IN_OPS);
        config.setDebug(conf.hasDebugFlag());
        config.setConnectTimeoutMillis(conf.connectTimeoutSeconds * 1000);
        config.setReadTimeoutSeconds(0);
        config.setWriteTimeoutSeconds(0);
        config.setUdpReadTimeoutSeconds(0);
        config.setUdpWriteTimeoutSeconds(0);
    }

    static synchronized boolean configureAutoWhiteListSchedule(RSSConf conf, RssRuntime rt) {
        boolean enabled = conf != null && rt != null && conf.rpcAutoWhiteListSeconds > 0;
        int periodSeconds = enabled ? conf.rpcAutoWhiteListSeconds : 0;
        if (autoWhiteListTask != null && (!enabled || autoWhiteListTaskPeriodSeconds != periodSeconds)) {
            autoWhiteListTask.cancel(false);
            autoWhiteListTask = null;
            autoWhiteListTaskPeriodSeconds = 0;
        }
        if (!enabled || autoWhiteListTask != null) {
            return false;
        }
        autoWhiteListTaskPeriodSeconds = periodSeconds;
        autoWhiteListTask = Tasks.schedulePeriod(rt::addPublicIpWhiteList, periodSeconds * 1000L);
        return true;
    }

    static synchronized void configureRrpServer(RSSConf conf) {
        RssRuntime.RrpServerPlan plan = prepareRrpServerPlan(conf);
        try {
            materializePortExclusiveRrpPlan(plan);
            commitRrpServerPlan(plan);
        } catch (Throwable e) {
            rollbackRrpServerPlan(plan);
            closeRrpServerPlanQuietly(plan);
            throw InvalidException.sneaky(e);
        }
    }

    static RssRuntime.RrpServerPlan prepareRrpServerPlan(RSSConf conf) {
        boolean enabled = conf != null && !Strings.isEmpty(conf.rrpToken) && conf.rrpPort != null;
        String nextToken = enabled ? conf.rrpToken : null;
        Integer nextPort = enabled ? conf.rrpPort : null;
        boolean changed = !(Strings.hashEquals(rrpToken, nextToken)
                && (rrpPort == nextPort || (rrpPort != null && rrpPort.equals(nextPort))));
        boolean samePort = enabled && rrpPort != null && rrpPort.equals(nextPort);
        RssRuntime.RrpServerPlan plan = new RssRuntime.RrpServerPlan(changed, enabled, samePort, nextToken, nextPort);
        if (changed && enabled && !samePort) {
            plan.newServer = createRrpServer(nextToken, nextPort);
        }
        return plan;
    }

    static void materializePortExclusiveRrpPlan(RssRuntime.RrpServerPlan plan) {
        if (plan == null || !plan.changed || !plan.enabled || !plan.samePort || plan.newServer != null) {
            return;
        }
        plan.oldServer = rrpServer;
        plan.oldToken = rrpToken;
        plan.oldPort = rrpPort;
        rrpServer = null;
        rrpToken = null;
        rrpPort = null;
        plan.oldDetached = true;
        tryClose(plan.oldServer);
        try {
            plan.newServer = createRrpServer(plan.token, plan.port);
        } catch (Throwable e) {
            rollbackRrpServerPlan(plan);
            throw InvalidException.sneaky(e);
        }
    }

    static void commitRrpServerPlan(RssRuntime.RrpServerPlan plan) {
        if (plan == null || !plan.changed) {
            return;
        }
        RrpServer oldServer = rrpServer;
        if (!plan.enabled) {
            rrpServer = null;
            rrpToken = null;
            rrpPort = null;
            tryClose(oldServer);
            return;
        }
        if (plan.samePort) {
            oldServer = null;
        }
        rrpServer = plan.newServer;
        rrpToken = plan.token;
        rrpPort = plan.port;
        plan.newServer = null;
        plan.oldServer = null;
        plan.oldDetached = false;
        tryClose(oldServer);
    }

    static RrpServer createRrpServer(String token, Integer port) {
        RrpConfig c = new RrpConfig();
        c.setToken(token);
        c.setBindPort(port);
        return new RrpServer(c);
    }

    static void rollbackRrpServerPlan(RssRuntime.RrpServerPlan plan) {
        if (plan == null || !plan.oldDetached) {
            return;
        }
        closeRrpServerPlanQuietly(plan);
        try {
            if (!Strings.isEmpty(plan.oldToken) && plan.oldPort != null) {
                rrpServer = createRrpServer(plan.oldToken, plan.oldPort);
                rrpToken = plan.oldToken;
                rrpPort = plan.oldPort;
            }
        } catch (Throwable restoreError) {
            log.error("rrp rollback failed port={}", plan.oldPort, restoreError);
        } finally {
            plan.oldServer = null;
            plan.oldDetached = false;
        }
    }

    static void closeRrpServerPlanQuietly(RssRuntime.RrpServerPlan plan) {
        if (plan != null) {
            tryClose(plan.newServer);
            plan.newServer = null;
        }
    }

    static void closeUpstreamsLater(RssRuntime.UpstreamSnapshot snapshot) {
        if (snapshot == null || snapshot.closed) {
            return;
        }
        snapshot.closing = true;
        long deadline = System.currentTimeMillis() + UPSTREAM_CLOSE_DELAY_MILLIS + UPSTREAM_CLOSE_MAX_WAIT_MILLIS;
        if (snapshot.closeDeadlineMillis <= 0L || snapshot.closeDeadlineMillis > deadline) {
            snapshot.closeDeadlineMillis = deadline;
        }
        cancelUpstreamHealthCheck(snapshot);
        Tasks.setTimeout(() -> closeUpstreamsWhenIdle(snapshot), UPSTREAM_CLOSE_DELAY_MILLIS);
    }

    static void closeUpstreamsWhenIdle(RssRuntime.UpstreamSnapshot snapshot) {
        if (snapshot == null || snapshot.closed) {
            return;
        }
        int active = activeConnectionCount(snapshot);
        if (active > 0) {
            long deadline = snapshot.closeDeadlineMillis;
            if (deadline > 0L && System.currentTimeMillis() >= deadline) {
                log.warn("rssConf old upstream force close activeConnections={} after maxWait={}ms",
                        active, UPSTREAM_CLOSE_MAX_WAIT_MILLIS);
                closeUpstreamsQuietly(snapshot);
                return;
            }
            log.info("rssConf old upstream wait activeConnections={}", active);
            Tasks.setTimeout(() -> closeUpstreamsWhenIdle(snapshot), UPSTREAM_CLOSE_CHECK_MILLIS);
            return;
        }
        closeUpstreamsQuietly(snapshot);
    }

    static void closeUpstreamsQuietly(RssRuntime.UpstreamSnapshot snapshot) {
        if (snapshot == null || snapshot.closed) {
            return;
        }
        snapshot.closed = true;
        snapshot.closing = true;
        cancelUpstreamHealthCheck(snapshot);
        Set<SocksRpcContract> facades = Collections.newSetFromMap(new IdentityHashMap<SocksRpcContract, Boolean>());
        for (UpstreamSupport support : snapshot.socksServers.readOnlySnapshot()) {
            org.rx.net.socks.Socks5UpstreamPoolManager.INSTANCE.closeEndpoint(support.getEndpoint());
            if (support.getFacade() != null) {
                facades.add(support.getFacade());
            }
        }
        for (UpstreamSupport support : snapshot.udp2rawSocksServers.readOnlySnapshot()) {
            org.rx.net.socks.Socks5UpstreamPoolManager.INSTANCE.closeEndpoint(support.getEndpoint());
            if (support.getFacade() != null) {
                facades.add(support.getFacade());
            }
        }
        for (SocksRpcContract facade : facades) {
            tryClose(facade);
        }
    }

    static void startUpstreamHealthCheck(RssRuntime.UpstreamSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        snapshot.healthTask = Tasks.schedulePeriod(() -> refreshUpstreamHealth(snapshot),
                0L, UPSTREAM_HEALTH_CHECK_PERIOD_MILLIS);
    }

    static void cancelUpstreamHealthCheck(RssRuntime.UpstreamSnapshot snapshot) {
        if (snapshot == null || snapshot.healthTask == null) {
            return;
        }
        snapshot.healthTask.cancel(false);
        snapshot.healthTask = null;
    }

    static void refreshUpstreamHealth(RssRuntime.UpstreamSnapshot snapshot) {
        if (snapshot == null || snapshot.closing) {
            return;
        }
        refreshUpstreamHealth(snapshot, snapshot.socksServers, true);
        refreshUpstreamHealth(snapshot, snapshot.udp2rawSocksServers, false);
    }

    static void refreshUpstreamHealth(RssRuntime.UpstreamSnapshot snapshot, RandomList<UpstreamSupport> servers, boolean updateDns) {
        if (servers == null) {
            return;
        }
        for (UpstreamSupport support : servers.readOnlySnapshot()) {
            boolean healthy = pingUpstream(support);
            updateUpstreamHealth(snapshot, servers, support, healthy, updateDns);
        }
    }

    static boolean pingUpstream(UpstreamSupport support) {
        if (support == null || support.getFacade() == null) {
            return true;
        }
        SocksRpcContract facade = support.getFacade();
        if (facade instanceof ForwardingSocksRpcContract) {
            facade = ((ForwardingSocksRpcContract) facade).delegate;
        }
        return Remoting.ping(facade);
    }

    static void updateUpstreamHealth(RssRuntime.UpstreamSnapshot snapshot, RandomList<UpstreamSupport> servers,
                                     UpstreamSupport support, boolean healthy, boolean updateDns) {
        if (support == null || servers == null) {
            return;
        }
        boolean previous = support.isHealthy();
        support.setHealthy(healthy);
        int weight = healthy ? Math.max(0, support.getConfiguredWeight()) : 0;
        setWeightIfPresent(servers, support, weight);
        if (updateDns && snapshot != null && support.getFacade() != null) {
            setWeightIfPresent(snapshot.dnsInterceptors, support.getFacade(), weight);
        }
        DiagnosticMetrics.record("rss.upstream.health", healthy ? 1D : 0D, "endpoint=" + support.getEndpoint());
        if (previous != healthy) {
            log.info("rss upstream {} health {}", support.getEndpoint(), healthy ? "UP" : "DOWN");
        }
    }

    static <T> void setWeightIfPresent(RandomList<T> list, T element, int weight) {
        if (list == null || element == null || !list.contains(element)) {
            return;
        }
        try {
            if (list.getWeight(element) != weight) {
                list.setWeight(element, weight);
            }
        } catch (NoSuchElementException ignored) {
        }
    }

    static int activeConnectionCount(RssRuntime.UpstreamSnapshot snapshot) {
        if (snapshot == null) {
            return 0;
        }
        int count = 0;
        Set<UpstreamSupport> supports = Collections.newSetFromMap(new IdentityHashMap<UpstreamSupport, Boolean>());
        supports.addAll(snapshot.socksServers.readOnlySnapshot());
        supports.addAll(snapshot.udp2rawSocksServers.readOnlySnapshot());
        for (UpstreamSupport support : supports) {
            count += support.activeConnectionCount();
        }
        return count;
    }

    static void closeInServerQuietly(RssRuntime.RssInServer server) {
        if (server != null) {
            tryClose(server.server);
        }
    }

    static void closeShadowServerQuietly(RssRuntime.ShadowServerRef ref) {
        if (ref != null) {
            tryClose(ref.server);
        }
    }

    static void closeNameserverQuietly(NameserverImpl server) {
        if (server != null) {
            tryClose(server);
        }
    }

    static void closeDnsServerQuietly(DnsServer server) {
        if (server != null) {
            tryClose(server);
        }
    }

    public static SocketAddress resolveClientInListenAddress(RSSConf conf, int port, String localNamePrefix) {
        if (conf != null && conf.socksBindPort) {
            return Sockets.newLoopbackEndpoint(port);
        }
        return new LocalAddress(localNamePrefix + port);
    }

    static RssRuntime.RssInServer createInSvr(RSSConf conf, SocksConfig inConf, Authenticator authenticator,
                                              TripleAction<SocksProxyServer, SocksContext> firstRoute,
                                              AtomicReference<RandomList<UpstreamSupport>> socksServersRef,
                                              GeoManager geoMgr) {
        SocksProxyServer inSvr = new SocksProxyServer(inConf);
        if (authenticator instanceof RssAuthenticator) {
            RssAuthenticator rssAuthenticator = (RssAuthenticator) authenticator;
            inSvr.setConnectionTagResolver(rssAuthenticator::resolve);
        }
        SocksConfig outConf = createOutboundConfig(conf, inConf);
        AtomicReference<SocksConfig> outConfRef = new AtomicReference<>(outConf);
        BiFunc<SocksContext, UpstreamSupport> routerFn = e -> {
            InetAddress srcHost = e.getSource().getAddress();
            UpstreamSupport next = nextUpstream(socksServersRef.get(), srcHost);
            if (rssConf.hasDebugFlag()) {
                log.info("route upSvr src {} -> {}", srcHost, next.getEndpoint());
            }
            SocksConfig currentOutConf = outConfRef.get();
            if (currentOutConf.getKcptunClient() != null) {
                return routeUpstream(currentOutConf, next);
            }
            return next;
        };
        QuadraFunc<InetSocketAddress, UnresolvedEndpoint, String, Boolean> routeingFn = (srcEp, dstEp, transType) -> {
            String host = dstEp.getHost();
            boolean outProxy;
            long begin;
            String ext;
            RSSConf.RouteConf routeConf = rssConf.route;
            if (routeConf.enable) {
                begin = System.nanoTime();
                if (routeConf.srcIpProxyRules != null && routeConf.srcIpProxyRules.contains(srcEp.getAddress())) {
                    outProxy = true;
                    ext = "srcIp:proxy";
                } else if (!Sockets.isValidIp(host)) {
                    if (geoMgr.matchSiteDirect(host)) {
                        outProxy = false;
                        ext = "geosite:direct";
                    } else {
                        outProxy = true;
                        ext = "geosite:proxy";
                    }
                } else {
                    IpGeolocation geo = geoMgr.resolveIp(host);
                    String category = geo.getCategory();
                    outProxy = !Strings.equalsIgnoreCase(category, "cn") && !Strings.equalsIgnoreCase(category, "private");
                    ext = "geoip:" + category;
                }
            } else {
                outProxy = true;
                begin = 0L;
                ext = "routeDisabled";
            }
            if (rssConf.hasRouteFlag()) {
                log.info("route dst {} {} {} <- {} {}",
                        transType, host, outProxy ? "PROXY" : "DIRECT", ext,
                        Sys.formatNanosElapsed(System.nanoTime() - begin));
            }
            return outProxy;
        };
        inSvr.onTcpRoute.replace(firstRoute, (s, e) -> {
            if (e.getUpstream() != null) {
                return;
            }
            UnresolvedEndpoint dstEp = e.getFirstDestination();
            SocksConfig currentOutConf = outConfRef.get();
            if (routeingFn.apply(e.getSource(), dstEp, "TCP")) {
                e.setUpstream(new SocksTcpUpstream(dstEp, currentOutConf, routerFn.apply(e)));
            } else {
                e.setUpstream(new Upstream(dstEp));
            }
        });
        inSvr.onUdpRoute.replace(firstRoute, (s, e) -> {
            if (e.getUpstream() != null) {
                return;
            }
            UnresolvedEndpoint dstEp = e.getFirstDestination();
            SocksConfig currentOutConf = outConfRef.get();
            if (routeingFn.apply(e.getSource(), dstEp, "UDP")) {
                e.setUpstream(new SocksUdpUpstream(dstEp, currentOutConf, routerFn.apply(e)));
            } else {
                e.setUpstream(new Upstream(dstEp));
            }
        });
        inSvr.setCipherRouter(SocksProxyServer.DNS_CIPHER_ROUTER);
        return new RssRuntime.RssInServer(inSvr, inConf, outConfRef);
    }

    static UpstreamSupport nextUpstream(RandomList<UpstreamSupport> socksServers, InetAddress srcHost) {
        try {
            UpstreamSupport next = socksServers.next(srcHost, rssConf.route.srcSteeringTTL, true);
            if (next.isHealthy()) {
                return next;
            }
            return nextHealthyUpstream(socksServers);
        } catch (NoSuchElementException e) {
            throw new InvalidException("No available socks upstream for {}", srcHost);
        } catch (IllegalArgumentException e) {
            throw new InvalidException("No weighted socks upstream for {}", srcHost);
        }
    }

    static UpstreamSupport nextHealthyUpstream(RandomList<UpstreamSupport> socksServers) {
        int size = socksServers.size();
        for (int i = 0; i < size; i++) {
            UpstreamSupport next = socksServers.next();
            if (next.isHealthy()) {
                return next;
            }
        }
        throw new NoSuchElementException();
    }

    static UpstreamSupport routeUpstream(SocksConfig inConf, UpstreamSupport next) {
        if (inConf == null || inConf.getKcptunClient() == null) {
            return next;
        }
        UpstreamSupport routed = new UpstreamSupport(inConf.getKcptunClient(), next.getFacade());
        routed.setConnectionTracker(next);
        return routed;
    }

    static void applyUdpLeasePool(RSSConf conf, SocksConfig config) {
        if (conf == null || config == null) {
            return;
        }
        config.setUdpLeasePoolEnabled(conf.udpLeasePoolEnabled);
        if (!conf.udpLeasePoolEnabled) {
            return;
        }

        int maxSize = Math.max(1, conf.udpLeasePoolMaxSize);
        int minSize = Math.max(0, Math.min(conf.udpLeasePoolMinSize, maxSize));
        config.setUdpLeasePoolMinSize(minSize);
        config.setUdpLeasePoolMaxSize(maxSize);
        config.setUdpLeasePoolMaxIdleMillis(Math.max(1000, conf.udpLeasePoolMaxIdleMillis));
        config.setUdpLeaseRpcBreakerThreshold(Math.max(1, conf.udpLeaseRpcBreakerThreshold));
        config.setUdpLeaseRpcBreakerOpenSeconds(Math.max(1, conf.udpLeaseRpcBreakerOpenSeconds));
    }

    static int resolveRpcRequestTimeoutMillis(RSSConf conf) {
        int configured = conf.rpcRequestTimeoutMillis;
        if (configured > 0) {
            return configured;
        }
        int connectTimeoutMillis = Math.max(1000, conf.connectTimeoutSeconds * 1000);
        return Math.min(connectTimeoutMillis, 3000);
    }

    static NameserverConfig resolveNameserverConfig(RSSConf conf) {
        NameserverConfig config = conf.nameserver;
        if (config == null) {
            config = new NameserverConfig();
            conf.nameserver = config;
        }
        config.setDnsPort(conf.shadowDnsPort);
        config.setDnsTtl(60 * conf.dnsTtlMinutes);
        return config;
    }

    static void clientInit(RssAuthenticator authenticator) {
        if (trafficStore == null || trafficStore.retentionDays() != rssConf.trafficRetentionDays) {
            if (trafficStore != null) {
                trafficStore.close();
            }
            trafficStore = new RssUserTrafficStore(null, rssConf.trafficRetentionDays);
            trafficStore.start();
            org.rx.net.socks.SocksUserTraffic.registerRecorder(trafficStore);
        }

        httpServer = HttpServer.getDefault().requestAsync(RssClientHttpHandler.SHADOW_USERS_PAGE_PATH,
                new RssClientHttpHandler(authenticator.getShadowStore(), trafficStore, authenticator.getMemoryRetentionHours()));

        configureRrpServer(rssConf);

        configureDdnsSchedule(rssConf);
    }

    static boolean shouldScheduleDdns(RSSConf conf) {
        return conf != null && conf.ddnsJobSeconds > 0 && !CollectionUtils.isEmpty(conf.ddnsDomains);
    }

    static synchronized boolean configureDdnsSchedule(RSSConf conf) {
        boolean enabled = shouldScheduleDdns(conf);
        int periodSeconds = enabled ? conf.ddnsJobSeconds : 0;
        if (ddnsTask != null && (!enabled || ddnsTaskPeriodSeconds != periodSeconds)) {
            ddnsTask.cancel(false);
            ddnsTask = null;
            ddnsTaskPeriodSeconds = 0;
        }
        if (!enabled || ddnsTask != null) {
            return false;
        }

        ddnsTaskPeriodSeconds = periodSeconds;
        ddnsTask = Tasks.schedulePeriod(RssClient::runDdnsJob, periodSeconds * 1000L);
        return true;
    }

    @SneakyThrows
    static void runDdnsJob() {
        RSSConf conf = rssConf;
        if (!shouldScheduleDdns(conf)) {
            return;
        }

        InetAddress wanIp = Sockets.parseIpAddress(GeoManager.INSTANCE.getPublicIp());
        List<String> subDomains = Linq.from(conf.ddnsDomains)
                .where(sd -> !DnsClient.inlandClient().resolveAll(sd).contains(wanIp))
                .select(sd -> sd.substring(0, sd.indexOf("."))).toList();
        if (subDomains.isEmpty()) {
            return;
        }
        String oneSd = conf.ddnsDomains.get(0);
        String domain = oneSd.substring(oneSd.indexOf(".") + 1);
        String res = setDDns(conf.ddnsApiKey, domain, subDomains, wanIp.getHostAddress());
        log.info("ddns set {} + {} @ {} -> {}", domain, subDomains, wanIp.getHostAddress(), res);
    }

    static void enableShadowIngressReusePort(ShadowsocksConfig config) {
        if (config == null) {
            return;
        }

        config.setReusePortBindCount(2);
        int bindCount = Sockets.reusePortBindCount(config, config.getServerEndpoint());
        if (bindCount <= 1) {
            return;
        }

        log.info("shadow ingress enable SO_REUSEPORT endpoint={} bindCount={}",
                config.getServerEndpoint(), bindCount);
    }

    @SneakyThrows
    static String setDDns(String apiKey, String domain, List<String> subDomains, String ip) {
        JSONObject curDns = getDDns(apiKey, domain);
        log.info("ddns curDns {}", curDns);
        JSONArray mDomains = Sys.readJsonValue(curDns, "data.name_server_settings.main_domains");
        JSONArray sDomains = Sys.readJsonValue(curDns, "data.name_server_settings.sub_domains");

        String url = "https://api.dynadot.com/restful/v1/domains/" + domain + "/records";
        JSONObject requestBody = new JSONObject();
        requestBody.put("ttl", 300);

        if (mDomains == null) {
            mDomains = new JSONArray();
        }
        for (int i = 0; i < mDomains.size(); i++) {
            JSONObject md = mDomains.getJSONObject(i);
            md.put("record_value1", md.getString("value"));
        }
        requestBody.put("dns_main_list", mDomains);

        if (sDomains == null) {
            sDomains = new JSONArray();
        }
        for (int i = 0; i < sDomains.size(); i++) {
            JSONObject sd = sDomains.getJSONObject(i);
            String subHost = sd.getString("sub_host");
            int j;
            if ((j = subDomains.indexOf(subHost)) != -1 && "a".equals(sd.getString("record_type"))) {
                sd.put("record_value1", ip);
                subDomains.remove(j);
            } else {
                sd.put("record_value1", sd.getString("value"));
            }
        }
        for (String subDomain : subDomains) {
            JSONObject sd = new JSONObject();
            sd.put("sub_host", subDomain);
            sd.put("record_type", "a");
            sd.put("record_value1", ip);
            sDomains.add(sd);
        }
        requestBody.put("sub_list", sDomains);
        log.info("ddns update all {}", requestBody);

        URL u = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        try {
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("X-Signature", dynadotSign(apiKey, url, requestBody.toString()));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.toString().getBytes(StandardCharsets.UTF_8));
            }
            return DuplexStream.readString(conn.getInputStream(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    static JSONObject getDDns(String apiKey, String domain) {
        String url = "https://api.dynadot.com/restful/v1/domains/" + domain + "/records";
        HttpClient.Request req = HttpClient.request(HttpMethod.GET, url)
                .header(HttpHeaderNames.ACCEPT, "application/json")
                .header(HttpHeaderNames.AUTHORIZATION, "Bearer " + apiKey)
                .header("X-Signature", dynadotSign(apiKey, url, ""));
        try (HttpClient.Response response = RssSupport.MAIN_HTTP_CLIENT.execute(req)) {
            return response.bodyAsJson();
        }
    }

    static String dynadotSign(String apiKey, String url, String requestBody) {
        int startIndex = url.indexOf("/", url.indexOf("//") + 2);
        String fullPathAndQuery = startIndex != -1 ? url.substring(startIndex) : "/";
        String stringToSign = apiKey + "\n" + fullPathAndQuery + "\n\n" + requestBody;
        return CodecUtil.toHex(CodecUtil.hmacSHA256(apiKey, stringToSign));
    }

    static Map<String, Object> toShadowStorePayload(Map<String, ShadowUser> shadowStore) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        if (shadowStore == null) {
            return payload;
        }
        for (Map.Entry<String, ShadowUser> entry : shadowStore.entrySet()) {
            payload.put(entry.getKey(), toShadowUserPayload(entry.getValue()));
        }
        return payload;
    }

    static Map<String, Object> toShadowUserPayload(ShadowUser user) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        if (user == null) {
            return payload;
        }
        payload.put("ssPort", user.getSsPort());
        payload.put("username", user.getUsername());
        payload.put("socksUser", user.getSocksUser());
        payload.put("ipLimit", user.getIpLimit());
        payload.put("lastResetTime", user.getLastResetTime());
        payload.put("loginIps", user.snapshotLoginIps());
        payload.put("totalReadBytes", user.getTotalReadBytes());
        payload.put("totalWriteBytes", user.getTotalWriteBytes());
        payload.put("totalReadPackets", user.getTotalReadPackets());
        payload.put("totalWritePackets", user.getTotalWritePackets());
        payload.put("humanTotalReadBytes", user.getHumanTotalReadBytes());
        payload.put("humanTotalWriteBytes", user.getHumanTotalWriteBytes());
        return payload;
    }

    static AuthenticEndpoint resolveShadowEndpoint(SocketAddress inSvrAddress, SocketAddress inUdp2rawSvrAddress,
                                                   AuthenticEndpoint hysteriaClient, String authUserName, String routeUserName) {
        if (routeUserName != null && routeUserName.startsWith("hysteria")) {
            return hysteriaClient;
        }

        SocketAddress endpoint = inSvrAddress;
        if (routeUserName != null && routeUserName.startsWith("tun") && inUdp2rawSvrAddress != null) {
            endpoint = inUdp2rawSvrAddress;
        }
        AuthenticEndpoint target = new AuthenticEndpoint(endpoint);
        target.getParameters().put(SocksConnectionTagRegistry.PARAM_NAME, authUserName);
        return target;
    }
}
