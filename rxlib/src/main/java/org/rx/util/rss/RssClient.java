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
import org.rx.net.rpc.RemotingEventArgs;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.socks.Authenticator;
import org.rx.net.socks.FakeEndpointRecovery;
import org.rx.net.socks.RrpConfig;
import org.rx.net.socks.RrpServer;
import org.rx.net.socks.ShadowsocksConfig;
import org.rx.net.socks.SocksConnectionTagRegistry;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.SocksRpcCapabilities;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.socks.TrafficUser;
import org.rx.net.socks.UdpRelayGroupOpenRequest;
import org.rx.net.socks.UdpRelayGroupOpenResult;
import org.rx.net.socks.UdpRelayGroupUpdateResult;
import org.rx.net.socks.Udp2rawOpenRequest;
import org.rx.net.socks.Udp2rawOpenResult;
import org.rx.net.udp.UdpPortHoppingMode;
import org.rx.net.udp.UdpRedundantMode;
import org.rx.net.socks.upstream.SocksTcpUpstream;
import org.rx.net.socks.upstream.SocksUdpUpstream;
import org.rx.net.socks.upstream.Udp2rawUpstream;
import org.rx.net.socks.upstream.UdpClientUpstream;
import org.rx.net.socks.upstream.Upstream;
import java.net.InetSocketAddress;
import org.rx.net.support.UpstreamSupport;
import org.rx.net.support.V2RayGeoManager;
import org.rx.net.transport.TcpClientConfig;
import org.rx.util.function.BiFunc;
import org.rx.util.function.TripleAction;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.rx.core.Extends.eachQuietly;
import static org.rx.core.Extends.tryClose;
import static org.rx.core.Sys.toJsonString;

@Slf4j
public final class RssClient {
    static final long RSS_RELOAD_DEBOUNCE_MILLIS = 100L;
    static final long UPSTREAM_CLOSE_DELAY_MILLIS = 30_000L;
    static final long UPSTREAM_CLOSE_CHECK_MILLIS = 1_000L;
    static final long UPSTREAM_CLOSE_MAX_WAIT_MILLIS = 60L * 1000L;
    static final int DEFAULT_UPSTREAM_HEALTH_CHECK_SECONDS = 5;
    static final int DEFAULT_UPSTREAM_HEALTH_FAILURE_THRESHOLD = 3;
    static final int UDP2RAW_MTU = 1300;
    static final long DEFAULT_PROCESS_DRAIN_MAX_WAIT_MILLIS = 180L * 1000L;
    static final long DEFAULT_PROCESS_DRAIN_TOKEN_TTL_MILLIS = 120L * 1000L;
    static final String PROCESS_DRAIN_MAX_WAIT_PROPERTY = "app.rss.drainMaxWaitMillis";
    static final String PROCESS_DRAIN_TOKEN_DIR_PROPERTY = "app.rss.drainTokenDir";
    static final String PROCESS_DRAIN_TOKEN_TTL_PROPERTY = "app.rss.drainTokenTtlMillis";

    static volatile RssClientConf rssConf;
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

    private RssClient() {}

    @SneakyThrows
    public static void launch(Map<String, String> options, int port) {
        YamlConfigSource<RssClientConf> source = new YamlConfigSource<RssClientConf>("rss", RssClientConf.class, "conf.yml")
                .setValidator(RssClient::normalizeAndValidateRssConfig)
                .setChangeDetector((oldConfig, newConfig) -> !Strings.hashEquals(toJsonString(oldConfig), toJsonString(newConfig)))
                .setDebounceMillis(RSS_RELOAD_DEBOUNCE_MILLIS);
        RssRuntime rt = null;
        try {
            source.start();
            rt = new RssRuntime(port, source.current());
            runtime = rt;
            RssRuntime current = rt;
            source.onChanged.add((s, e) -> current.reload(e.getOldConfig(), e.getNewConfig()));
            RssProcessControl.register();
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

    static long processDrainMaxWaitMillis() {
        String configured = System.getProperty(PROCESS_DRAIN_MAX_WAIT_PROPERTY);
        if (Strings.isBlank(configured)) {
            return DEFAULT_PROCESS_DRAIN_MAX_WAIT_MILLIS;
        }
        try {
            long value = Long.parseLong(configured);
            return value < 0L ? DEFAULT_PROCESS_DRAIN_MAX_WAIT_MILLIS : value;
        } catch (NumberFormatException e) {
            log.warn("invalid rss drain max wait millis {}={}, use default {}",
                    PROCESS_DRAIN_MAX_WAIT_PROPERTY, configured, DEFAULT_PROCESS_DRAIN_MAX_WAIT_MILLIS);
            return DEFAULT_PROCESS_DRAIN_MAX_WAIT_MILLIS;
        }
    }

    static void drainAndExit(String reason) {
        if (!RssProcessControl.acceptDrainSignal(reason)) {
            return;
        }

        RssRuntime rt = runtime;
        if (rt == null) {
            log.warn("rss drain signal {} without runtime, exit now", reason);
            System.exit(0);
            return;
        }
        rt.beginDrainAndExit(reason, processDrainMaxWaitMillis());
    }

    static final class RssProcessControl {
        static final AtomicBoolean REGISTERED = new AtomicBoolean();

        private RssProcessControl() {}

        static void register() {
            if (!REGISTERED.compareAndSet(false, true)) {
                return;
            }
            registerSignal("USR1");
            registerSignal("TERM");
        }

        static void registerSignal(final String signalName) {
            try {
                Class<?> signalClass = Class.forName("sun.misc.Signal");
                Class<?> handlerClass = Class.forName("sun.misc.SignalHandler");
                final Object signal = signalClass.getConstructor(String.class).newInstance(signalName);
                Object handler = Proxy.newProxyInstance(RssClient.class.getClassLoader(),
                        new Class<?>[] {handlerClass}, new InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, Method method, Object[] args) {
                                if ("handle".equals(method.getName())) {
                                    drainAndExit(String.valueOf(signal));
                                }
                                return null;
                            }
                        });
                signalClass.getMethod("handle", signalClass, handlerClass).invoke(null, signal, handler);
                log.info("rss process control registered signal {}", signalName);
            } catch (Throwable e) {
                log.warn("rss process control signal {} unavailable", signalName, e);
            }
        }

        static boolean acceptDrainSignal(String reason) {
            if (isJvmReservedSignal(reason)) {
                log.warn("rss drain signal {} ignored, reserved for JVM/JFR", reason);
                return false;
            }
            if (!isTokenDrainSignal(reason)) {
                return true;
            }

            File tokenFile = drainTokenFile();
            if (!tokenFile.isFile()) {
                log.warn("rss drain signal {} ignored, token missing file={}", reason, tokenFile.getAbsolutePath());
                return false;
            }

            long now = System.currentTimeMillis();
            long ageMillis = now - tokenFile.lastModified();
            long ttlMillis = processDrainTokenTtlMillis();
            if (ageMillis < 0L || ageMillis > ttlMillis) {
                if (!tokenFile.delete()) {
                    log.warn("rss drain token delete failed file={}", tokenFile.getAbsolutePath());
                }
                log.warn("rss drain signal {} ignored, token expired file={} ageMillis={} ttlMillis={}",
                        reason, tokenFile.getAbsolutePath(), ageMillis, ttlMillis);
                return false;
            }

            if (!tokenFile.delete()) {
                log.warn("rss drain token delete failed file={}", tokenFile.getAbsolutePath());
            }
            log.info("rss drain signal {} accepted token={}", reason, tokenFile.getAbsolutePath());
            return true;
        }

        static boolean isTokenDrainSignal(String reason) {
            return "SIGUSR1".equals(reason) || "USR1".equals(reason);
        }

        static boolean isJvmReservedSignal(String reason) {
            return "SIGUSR2".equals(reason) || "USR2".equals(reason);
        }

        static File drainTokenFile() {
            String dir = System.getProperty(PROCESS_DRAIN_TOKEN_DIR_PROPERTY);
            if (Strings.isBlank(dir)) {
                dir = ".drain";
            }
            return new File(dir, "rss-drain-" + currentProcessId() + ".token");
        }

        static String currentProcessId() {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            int i = name.indexOf('@');
            return i > 0 ? name.substring(0, i) : name;
        }

        static long processDrainTokenTtlMillis() {
            String configured = System.getProperty(PROCESS_DRAIN_TOKEN_TTL_PROPERTY);
            if (Strings.isBlank(configured)) {
                return DEFAULT_PROCESS_DRAIN_TOKEN_TTL_MILLIS;
            }
            try {
                long value = Long.parseLong(configured);
                return value < 0L ? DEFAULT_PROCESS_DRAIN_TOKEN_TTL_MILLIS : value;
            } catch (NumberFormatException e) {
                log.warn("invalid rss drain token ttl millis {}={}, use default {}",
                        PROCESS_DRAIN_TOKEN_TTL_PROPERTY, configured, DEFAULT_PROCESS_DRAIN_TOKEN_TTL_MILLIS);
                return DEFAULT_PROCESS_DRAIN_TOKEN_TTL_MILLIS;
            }
        }
    }

    static final class ForwardingSocksRpcContract implements SocksRpcContract {
        final SocksRpcContract delegate;
        final SocksRpcContract eventDelegate;

        ForwardingSocksRpcContract(SocksRpcContract delegate) {
            this(delegate, null);
        }

        ForwardingSocksRpcContract(SocksRpcContract delegate, SocksRpcContract eventDelegate) {
            this.delegate = delegate;
            this.eventDelegate = eventDelegate;
            if (eventDelegate != null) {
                eventDelegate.<RemotingEventArgs<FakeEndpointRecovery>>attachEvent(
                        SocksRpcContract.EVENT_FAKE_ENDPOINT_RECOVERY, (s, e) -> {
                            FakeEndpointRecovery recovery = e.getValue();
                            if (recovery == null) {
                                return;
                            }
                            recovery.setRealEndpoint(SocksTcpUpstream.cachedFakeEndpoint(recovery.getHash()));
                            e.setValue(recovery);
                        }, false);
            }
        }

        @Override
        public boolean fakeEndpoint(long hash, String realEndpoint, String token) {
            return delegate.fakeEndpoint(hash, realEndpoint, token);
        }

        @Override
        public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
            RouteAction action = matchRoute(null, host);
            String ext;
            if (action == RouteAction.BLOCK) {
                ext = "defaultRoute";
            } else if (action == RouteAction.DIRECT) {
                ext = "defaultRoute";
            } else {
                ext = "defaultRoute";
            }
            RssClientConf conf = rssConf;
            if (conf != null && conf.hasRouteFlag()) {
                log.info("route dns {}+{} {} <- {}", srcIp, host, action, ext);
            }
            if (action == RouteAction.BLOCK) {
                return Collections.emptyList();
            }
            List<InetAddress> result = action == RouteAction.PROXY
                    ? delegate.resolveHost(srcIp, host)
                    : DnsClient.directClient().resolveAll(host);
            // RSS Client 禁止把未命中的域名透传给本地系统 DNS；空结果由 DNS Server 转 NXDOMAIN。
            return result == null ? Collections.<InetAddress>emptyList() : result;
        }

        @Override
        public void addWhiteList(InetAddress endpoint, String token) {
            delegate.addWhiteList(endpoint, token);
        }

        @Override
        public boolean resetUdpRelay(int relayPort, String token) {
            return delegate.resetUdpRelay(relayPort, token);
        }

        @Override
        public boolean claimUdpRelay(int relayPort, InetSocketAddress clientAddr, String token) {
            return delegate.claimUdpRelay(relayPort, clientAddr, token);
        }

        @Override
        public SocksRpcCapabilities capabilities(String token) {
            return delegate.capabilities(token);
        }

        @Override
        public UdpRelayGroupOpenResult openUdpRelayGroup(UdpRelayGroupOpenRequest request, String token) {
            return delegate.openUdpRelayGroup(request, token);
        }

        @Override
        public UdpRelayGroupUpdateResult addUdpRelays(String groupId, int count, String token) {
            return delegate.addUdpRelays(groupId, count, token);
        }

        @Override
        public boolean removeUdpRelay(String groupId, int relayPort, String token) {
            return delegate.removeUdpRelay(groupId, relayPort, token);
        }

        @Override
        public boolean heartbeatUdpRelayGroup(String groupId, String token) {
            return delegate.heartbeatUdpRelayGroup(groupId, token);
        }

        @Override
        public boolean closeUdpRelayGroup(String groupId, String token) {
            return delegate.closeUdpRelayGroup(groupId, token);
        }

        @Override
        public Udp2rawOpenResult openUdp2rawTunnel(Udp2rawOpenRequest request, String token) {
            return delegate.openUdp2rawTunnel(request, token);
        }

        @Override
        public boolean heartbeatUdp2rawTunnel(String tunnelId, String token) {
            return delegate.heartbeatUdp2rawTunnel(tunnelId, token);
        }

        @Override
        public boolean closeUdp2rawTunnel(String tunnelId, String token) {
            return delegate.closeUdp2rawTunnel(tunnelId, token);
        }

        @Override
        public void close() {
            tryClose(delegate);
            tryClose(eventDelegate);
        }
    }

    static boolean normalizeAndValidateRssConfig(RssClientConf conf) {
        if (conf == null) {
            return false;
        }
        if (conf.nameserver == null) {
            conf.nameserver = new NameserverConfig();
        }
        conf.trafficRetentionDays = Math.max(1, conf.trafficRetentionDays);
        conf.memoryRetentionHours = conf.memoryRetentionHours <= 0
                ? RssAuthenticator.DEFAULT_MEMORY_RETENTION_HOURS
                : conf.memoryRetentionHours;
        conf.connectTimeoutSeconds = Math.max(1, conf.connectTimeoutSeconds);
        conf.tcpTimeoutSeconds = Math.max(0, conf.tcpTimeoutSeconds);
        conf.udpTimeoutSeconds = Math.max(0, conf.udpTimeoutSeconds);
        conf.rpcMinSize = Math.max(1, conf.rpcMinSize);
        conf.rpcMaxSize = Math.max(conf.rpcMinSize, conf.rpcMaxSize);
        if (conf.rpcPort < 0 || conf.rpcPort > 65535) {
            log.warn("rssConf rpcPort {} invalid", conf.rpcPort);
            return false;
        }
        conf.rpcRequestTimeoutMillis = Math.max(0, conf.rpcRequestTimeoutMillis);
        conf.rpcAutoWhiteListSeconds = Math.max(0, conf.rpcAutoWhiteListSeconds);
        conf.shadowDnsPort = Math.max(1, conf.shadowDnsPort);
        conf.dnsTtlMinutes = Math.max(1, conf.dnsTtlMinutes);
        resolveNameserverConfig(conf);

        if (Strings.isEmpty(conf.socksPwd) || CollectionUtils.isEmpty(conf.shadowUsers) || CollectionUtils.isEmpty(conf.socksServers)) {
            return false;
        }
        if (!hasWeightedSocksServer(conf.socksServers)) {
            log.warn("rssConf socksServers has no enabled server");
            return false;
        }
        if (!normalizeAndValidateSocksServerIds(conf)) {
            return false;
        }
        if (!normalizeAndValidateDefaultRoute(conf)) {
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
            if (!normalizeAndValidateUserSocksServers(user, conf.socksServers)) {
                return false;
            }
            if (!normalizeAndValidateUserRoute(user)) {
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

    private static boolean hasWeightedSocksServer(List<RssClientConf.SocksServer> socksServers) {
        for (RssClientConf.SocksServer socksServer : socksServers) {
            AuthenticEndpoint endpoint = socksServer == null ? null : socksServer.getEndpoint();
            if (endpoint != null && endpoint.getInetEndpoint() != null && weightOf(socksServer) > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasWeightedSocksServer(List<RssClientConf.SocksServer> socksServers, ServerRouteMode mode) {
        for (RssClientConf.SocksServer socksServer : socksServers) {
            AuthenticEndpoint endpoint = socksServer == null ? null : socksServer.getEndpoint();
            if (endpoint != null && endpoint.getInetEndpoint() != null
                    && routeMode(socksServer) == mode && weightOf(socksServer) > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean normalizeAndValidateSocksServerIds(RssClientConf conf) {
        Set<String> serverIds = new LinkedHashSet<>();
        for (RssClientConf.SocksServer socksServer : conf.socksServers) {
            AuthenticEndpoint endpoint = socksServer == null ? null : socksServer.getEndpoint();
            if (endpoint == null) {
                log.warn("rssConf socksServer {} endpoint is empty", socksServer);
                return false;
            }
            if (weightOf(socksServer) > 0 && endpoint.getInetEndpoint() == null) {
                log.warn("rssConf socksServer {} enabled but endpoint is not InetSocketAddress", socksServer);
                return false;
            }
            AuthenticEndpoint tcpClient = socksServer.getTcpClient();
            if (tcpClient != null && tcpClient.getInetEndpoint() == null) {
                log.warn("rssConf socksServer {} tcpClient is not InetSocketAddress", socksServer);
                return false;
            }
            InetSocketAddress udpClient = socksServer.getUdpClient();
            if (udpClient != null && udpClient.getPort() <= 0) {
                log.warn("rssConf socksServer {} udpClient port invalid", socksServer);
                return false;
            }
            if (socksServer.isUdp2raw() && weightOf(socksServer) > 0) {
                if (tcpClient == null) {
                    log.warn("rssConf socksServer {} udp2raw requires tcpClient", socksServer);
                    return false;
                }
            }
            String id = trimToNull(socksServer.getId());
            if (id == null) {
                log.warn("rssConf socksServer {} id is empty", socksServer);
                return false;
            }
            socksServer.setId(id);
            if (id != null && !serverIds.add(id)) {
                log.warn("rssConf duplicate socksServer id {}", id);
                return false;
            }
        }
        return true;
    }

    private static boolean normalizeAndValidateUserSocksServers(ShadowUser user, List<RssClientConf.SocksServer> socksServers) {
        if (CollectionUtils.isEmpty(user.getSocksServers())) {
            boolean hasDefaultSocksServer = hasWeightedSocksServer(socksServers, ServerRouteMode.SOCKS);
            if (!hasDefaultSocksServer) {
                log.warn("rssConf shadowUser {} uses default socksServers but no normal socks server is enabled",
                        user.getUsername());
            }
            return hasDefaultSocksServer;
        }
        Map<String, RssClientConf.SocksServer> serverById = indexSocksServers(socksServers);

        boolean hasWeighted = false;
        boolean hasSocksMode = false;
        boolean hasUdp2rawMode = false;
        boolean hasTcpClientMode = false;
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String serverId : user.getSocksServers()) {
            serverId = trimToNull(serverId);
            if (serverId == null) {
                log.warn("rssConf shadowUser {} socksServers contains empty id", user.getUsername());
                return false;
            }
            RssClientConf.SocksServer socksServer = serverById.get(serverId);
            if (socksServer == null) {
                log.warn("rssConf shadowUser {} socksServer id {} not found", user.getUsername(), serverId);
                return false;
            }
            if (!normalized.add(serverId)) {
                log.warn("rssConf shadowUser {} duplicate socksServer id {}", user.getUsername(), serverId);
                return false;
            }
            if (weightOf(socksServer) > 0) {
                ServerRouteMode serverMode = routeMode(socksServer);
                switch (serverMode) {
                    case SOCKS:
                        hasSocksMode = true;
                        break;
                    case UDP2RAW:
                        hasUdp2rawMode = true;
                        break;
                    case TCP_CLIENT:
                        hasTcpClientMode = true;
                        break;
                    default:
                        break;
                }
                hasWeighted = true;
            }
        }
        if (!hasWeighted) {
            log.warn("rssConf shadowUser {} socksServers {} has no enabled server",
                    user.getUsername(), user.getSocksServers());
            return false;
        }
        if (hasTcpClientMode && (hasSocksMode || hasUdp2rawMode)) {
            log.warn("rssConf shadowUser {} socksServers {} mixes tcpClient with other route modes",
                    user.getUsername(), user.getSocksServers());
            return false;
        }
        user.setSocksServers(new ArrayList<String>(normalized));
        return true;
    }

    private static boolean normalizeAndValidateDefaultRoute(RssClientConf conf) {
        try {
            if (conf.defaultRoute == null) {
                conf.defaultRoute = UserRuleMatcher.defaultRoute();
            } else {
                if (conf.defaultRoute.getEnabled() == null) {
                    conf.defaultRoute.setEnabled(Boolean.TRUE);
                }
                conf.defaultRoute.setSrcSteeringTTL(Math.max(0, conf.defaultRoute.getSrcSteeringTTL()));
                if (CollectionUtils.isEmpty(conf.defaultRoute.getRules())) {
                    conf.defaultRoute.setRules(UserRuleMatcher.defaultRoute().getRules());
                }
            }
            conf.defaultRouteMatcher = UserRuleMatcher.compileDefaultRoute(conf.defaultRoute, V2RayGeoManager.INSTANCE);
            return true;
        } catch (RuntimeException e) {
            log.warn("rssConf defaultRoute invalid", e);
            return false;
        }
    }

    private static boolean normalizeAndValidateUserRoute(ShadowUser user) {
        try {
            user.setRouteMatcher(UserRuleMatcher.compile(
                    user.getRoute(), V2RayGeoManager.INSTANCE, user.getUsername()));
            return true;
        } catch (RuntimeException e) {
            log.warn("rssConf shadowUser {} route invalid", user.getUsername(), e);
            return false;
        }
    }

    enum ServerRouteMode {
        SOCKS, UDP2RAW, TCP_CLIENT, MIXED
    }

    static ServerRouteMode routeMode(RssClientConf.SocksServer socksServer) {
        if (socksServer != null && socksServer.isUdp2raw()) {
            return ServerRouteMode.UDP2RAW;
        }
        return socksServer != null && (socksServer.getTcpClient() != null || socksServer.getUdpClient() != null)
                ? ServerRouteMode.TCP_CLIENT
                : ServerRouteMode.SOCKS;
    }

    private static Map<String, RssClientConf.SocksServer> indexSocksServers(List<RssClientConf.SocksServer> socksServers) {
        if (CollectionUtils.isEmpty(socksServers)) {
            return Collections.emptyMap();
        }
        LinkedHashMap<String, RssClientConf.SocksServer> serverById = new LinkedHashMap<>();
        for (RssClientConf.SocksServer socksServer : socksServers) {
            String id = socksServer == null ? null : socksServer.getId();
            if (id != null) {
                serverById.put(id, socksServer);
            }
        }
        return serverById;
    }

    static ServerRouteMode userRouteMode(ShadowUser user, List<RssClientConf.SocksServer> socksServers) {
        if (user == null || CollectionUtils.isEmpty(user.getSocksServers())) {
            return ServerRouteMode.SOCKS;
        }
        Map<String, RssClientConf.SocksServer> serverById = indexSocksServers(socksServers);
        ServerRouteMode routeMode = null;
        for (String serverId : user.getSocksServers()) {
            RssClientConf.SocksServer socksServer = serverById.get(serverId);
            if (socksServer != null && weightOf(socksServer) > 0) {
                ServerRouteMode serverMode = routeMode(socksServer);
                if (routeMode == null) {
                    routeMode = serverMode;
                } else if (routeMode != serverMode) {
                    return ServerRouteMode.MIXED;
                }
            }
        }
        return routeMode == null ? ServerRouteMode.SOCKS : routeMode;
    }

    static boolean hasUdp2rawSocksServer(RssClientConf conf) {
        return conf != null && hasWeightedSocksServer(conf.socksServers, ServerRouteMode.UDP2RAW);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        value = value.trim();
        return Strings.isEmpty(value) ? null : value;
    }

    static int weightOf(AuthenticEndpoint endpoint) {
        if (endpoint == null) {
            return 0;
        }
        return Reflects.convertQuietly(endpoint.getParameters().get("w"), int.class, 0);
    }

    static int weightOf(RssClientConf.SocksServer socksServer) {
        if (socksServer == null) {
            return 0;
        }
        Integer weight = socksServer.getWeight();
        return weight != null ? Math.max(0, weight) : 0;
    }

    static RssRuntime.UpstreamSnapshot buildUpstreams(RssClientConf conf) {
        RandomList<UpstreamSupport> socksServers = new RandomList<>();
        RandomList<UpstreamSupport> udp2rawSocksServers = new RandomList<>();
        RandomList<DnsServer.ResolveInterceptor> dnsInterceptors = new RandomList<>();
        List<SocksRpcContract> createdFacades = new ArrayList<>();
        Map<String, SocksRpcContract> facadeByRpcEndpoint = new LinkedHashMap<>();
        Map<String, InetSocketAddress> rpcEndpointByServerHost = buildRpcEndpointsByServerHost(conf);
        Map<String, UpstreamSupport> supportByServerId = new LinkedHashMap<>();
        Map<String, UpstreamSupport> udp2rawSupportByServerId = new LinkedHashMap<>();

        try {
            for (RssClientConf.SocksServer configuredServer : conf.socksServers) {
                AuthenticEndpoint socksServer = configuredServer.getEndpoint();
                int weight = weightOf(configuredServer);
                if (weight <= 0) {
                    continue;
                }
                if (routeMode(configuredServer) != ServerRouteMode.SOCKS) {
                    continue;
                }
                InetSocketAddress socksServerEp = socksServer.requireEndpoint();
                SocksRpcContract facade = getOrCreateRpcFacade(conf, socksServerEp,
                        rpcEndpointByServerHost, facadeByRpcEndpoint, createdFacades);
                UpstreamSupport support = new UpstreamSupport(socksServer, facade);
                support.setConfiguredWeight(weight);
                support.setTcpClient(configuredServer.getTcpClient());
                support.setUdpClient(configuredServer.getUdpClient());
                socksServers.add(support, weight);
                addDnsInterceptorWeight(dnsInterceptors, facade, weight);
                if (!Strings.isEmpty(configuredServer.getId())) {
                    supportByServerId.put(configuredServer.getId(), support);
                }
            }
            for (RssClientConf.SocksServer configuredServer : conf.socksServers) {
                if (routeMode(configuredServer) != ServerRouteMode.UDP2RAW) {
                    continue;
                }
                AuthenticEndpoint socksServer = configuredServer.getEndpoint();
                int weight = weightOf(configuredServer);
                if (weight <= 0) {
                    continue;
                }
                InetSocketAddress socksServerEp = socksServer.requireEndpoint();
                SocksRpcContract facade = getOrCreateRpcFacade(conf, socksServerEp,
                        rpcEndpointByServerHost, facadeByRpcEndpoint, createdFacades);
                UpstreamSupport support = new UpstreamSupport(socksServer, facade);
                support.setConfiguredWeight(weight);
                support.setTcpClient(configuredServer.getTcpClient());
                support.setUdpClient(configuredServer.getUdpClient());
                support.setUdp2raw(true);
                udp2rawSocksServers.add(support, weight);
                if (!Strings.isEmpty(configuredServer.getId())) {
                    udp2rawSupportByServerId.put(configuredServer.getId(), support);
                }
            }
            log.info("rssConf load socksServers: {}", toJsonString(conf.socksServers));
            List<RssClientConf.SocksServer> configuredSocksServers = collectConfiguredSocksServers(conf, ServerRouteMode.SOCKS);
            List<RssClientConf.SocksServer> configuredUdp2rawSocksServers = collectConfiguredSocksServers(conf, ServerRouteMode.UDP2RAW);
            log.info("rssConf load udp2rawSocksServers: {}", toJsonString(configuredUdp2rawSocksServers));
            return new RssRuntime.UpstreamSnapshot(socksServers, udp2rawSocksServers, dnsInterceptors,
                    buildUserSocksServers(conf.shadowUsers, supportByServerId),
                    buildUserSocksServers(conf.shadowUsers, udp2rawSupportByServerId),
                    configuredSocksServers, configuredUdp2rawSocksServers);
        } catch (Throwable e) {
            for (SocksRpcContract facade : createdFacades) {
                tryClose(facade);
            }
            throw InvalidException.sneaky(e);
        }
    }

    static void addDnsInterceptorWeight(RandomList<DnsServer.ResolveInterceptor> dnsInterceptors,
            SocksRpcContract facade, int weight) {
        if (dnsInterceptors == null || facade == null || weight <= 0) {
            return;
        }
        int nextWeight = weight;
        if (dnsInterceptors.contains(facade)) {
            nextWeight += dnsInterceptors.getWeight(facade);
        }
        dnsInterceptors.add(facade, nextWeight);
    }

    static Map<String, InetSocketAddress> buildRpcEndpointsByServerHost(RssClientConf conf) {
        LinkedHashMap<String, InetSocketAddress> endpoints = new LinkedHashMap<>();
        addRpcEndpointsByMode(conf, endpoints, ServerRouteMode.SOCKS);
        addRpcEndpointsByMode(conf, endpoints, null);
        return endpoints;
    }

    private static void addRpcEndpointsByMode(RssClientConf conf, Map<String, InetSocketAddress> endpoints,
            ServerRouteMode preferredMode) {
        if (conf == null || CollectionUtils.isEmpty(conf.socksServers)) {
            return;
        }
        for (RssClientConf.SocksServer server : conf.socksServers) {
            if (server == null || weightOf(server) <= 0 || server.getEndpoint() == null) {
                continue;
            }
            if (preferredMode != null && routeMode(server) != preferredMode) {
                continue;
            }
            InetSocketAddress endpoint = server.getEndpoint().getInetEndpoint();
            if (endpoint == null) {
                continue;
            }
            String hostKey = rpcHostKey(endpoint);
            if (!endpoints.containsKey(hostKey)) {
                endpoints.put(hostKey, Sockets.newEndpoint(endpoint, resolveRpcPort(conf, endpoint)));
            }
        }
    }

    static InetSocketAddress resolveRpcEndpoint(RssClientConf conf, InetSocketAddress socksServerEp,
            Map<String, InetSocketAddress> rpcEndpointByServerHost) {
        InetSocketAddress endpoint = rpcEndpointByServerHost == null ? null : rpcEndpointByServerHost.get(rpcHostKey(socksServerEp));
        return endpoint != null ? endpoint : Sockets.newEndpoint(socksServerEp, resolveRpcPort(conf, socksServerEp));
    }

    static int resolveRpcPort(RssClientConf conf, InetSocketAddress socksServerEp) {
        return conf != null && conf.rpcPort > 0 ? conf.rpcPort : socksServerEp.getPort() + 1;
    }

    private static SocksRpcContract getOrCreateRpcFacade(RssClientConf conf, InetSocketAddress socksServerEp,
            Map<String, InetSocketAddress> rpcEndpointByServerHost,
            Map<String, SocksRpcContract> facadeByRpcEndpoint,
            List<SocksRpcContract> createdFacades) {
        InetSocketAddress rpcEndpoint = resolveRpcEndpoint(conf, socksServerEp, rpcEndpointByServerHost);
        String rpcKey = endpointKey(rpcEndpoint);
        SocksRpcContract facade = facadeByRpcEndpoint.get(rpcKey);
        if (facade != null) {
            return facade;
        }

        RpcClientConfig<SocksRpcContract> rpcConf = RpcClientConfig.poolMode(
                rpcEndpoint, conf.rpcMinSize, conf.rpcMaxSize);
        rpcConf.setRequestTimeoutMillis(resolveRpcRequestTimeoutMillis(conf));
        TcpClientConfig tcpConfig = rpcConf.getTcpConfig();
        tcpConfig.setTransportFlags(TransportFlags.GFW.flags(TransportFlags.CIPHER_BOTH).flags());
        SocksRpcContract delegate = Remoting.createFacade(SocksRpcContract.class, rpcConf);

        RpcClientConfig<SocksRpcContract> eventRpcConf = RpcClientConfig.statefulMode(rpcEndpoint, SocksRpcContract.RPC_EVENT_VERSION);
        eventRpcConf.setRequestTimeoutMillis(resolveRpcRequestTimeoutMillis(conf));
        eventRpcConf.getTcpConfig().setTransportFlags(TransportFlags.GFW.flags(TransportFlags.CIPHER_BOTH).flags());
        SocksRpcContract eventDelegate = Remoting.createFacade(SocksRpcContract.class, eventRpcConf);
        facade = new ForwardingSocksRpcContract(delegate, eventDelegate);
        facadeByRpcEndpoint.put(rpcKey, facade);
        createdFacades.add(facade);
        return facade;
    }

    private static String rpcHostKey(InetSocketAddress endpoint) {
        return endpoint.getHostString().toLowerCase(Locale.ROOT);
    }

    private static String endpointKey(InetSocketAddress endpoint) {
        return rpcHostKey(endpoint) + ":" + endpoint.getPort();
    }

    private static List<RssClientConf.SocksServer> collectConfiguredSocksServers(RssClientConf conf, ServerRouteMode routeMode) {
        List<RssClientConf.SocksServer> servers = new ArrayList<>();
        if (conf != null && !CollectionUtils.isEmpty(conf.socksServers)) {
            for (RssClientConf.SocksServer socksServer : conf.socksServers) {
                if (socksServer != null && routeMode(socksServer) == routeMode && socksServer.getEndpoint() != null) {
                    servers.add(socksServer);
                }
            }
        }
        return servers.isEmpty() ? Collections.<RssClientConf.SocksServer>emptyList() : servers;
    }

    static Map<String, RandomList<UpstreamSupport>> buildUserSocksServers(List<ShadowUser> shadowUsers,
            Map<String, UpstreamSupport> supportByServerId) {
        if (CollectionUtils.isEmpty(shadowUsers) || supportByServerId == null || supportByServerId.isEmpty()) {
            return Collections.emptyMap();
        }
        LinkedHashMap<String, RandomList<UpstreamSupport>> userServers = new LinkedHashMap<>();
        for (ShadowUser user : shadowUsers) {
            if (user == null || CollectionUtils.isEmpty(user.getSocksServers())) {
                continue;
            }
            RandomList<UpstreamSupport> servers = new RandomList<>();
            for (String serverId : user.getSocksServers()) {
                UpstreamSupport support = supportByServerId.get(serverId);
                if (support != null) {
                    servers.add(support, support.getConfiguredWeight());
                }
            }
            if (!servers.isEmpty()) {
                userServers.put(user.getUsername(), servers);
            }
        }
        return userServers.isEmpty() ? Collections.<String, RandomList<UpstreamSupport>>emptyMap()
                : Collections.unmodifiableMap(userServers);
    }

    static RandomList<UpstreamSupport> buildUserTcpClientServers(ShadowUser user, List<RssClientConf.SocksServer> socksServers) {
        RandomList<UpstreamSupport> servers = new RandomList<>();
        if (user == null || CollectionUtils.isEmpty(user.getSocksServers()) || CollectionUtils.isEmpty(socksServers)) {
            return servers;
        }
        Map<String, RssClientConf.SocksServer> serverById = indexSocksServers(socksServers);
        for (String serverId : user.getSocksServers()) {
            RssClientConf.SocksServer configuredServer = serverById.get(serverId);
            if (configuredServer == null || routeMode(configuredServer) != ServerRouteMode.TCP_CLIENT) {
                continue;
            }
            int weight = weightOf(configuredServer);
            if (weight <= 0) {
                continue;
            }
            AuthenticEndpoint endpoint = configuredServer.getTcpClient() == null
                    ? configuredServer.getEndpoint()
                    : rewriteEndpoint(configuredServer.getEndpoint(), configuredServer.getTcpClient());
            UpstreamSupport support = new UpstreamSupport(endpoint, null);
            support.setConfiguredWeight(weight);
            support.setTcpClient(configuredServer.getTcpClient());
            support.setUdpClient(configuredServer.getUdpClient());
            servers.add(support, weight);
        }
        return servers;
    }

    static DnsServer createDnsServer(RssClientConf conf, RandomList<DnsServer.ResolveInterceptor> dnsInterceptors) {
        DnsServer dnsServer = new DnsServer(conf.shadowDnsPort);
        applyDnsConfig(dnsServer, conf, dnsInterceptors);
        dnsServer.addHostsFile("hosts.txt");
        return dnsServer;
    }

    static void applyDnsConfig(DnsServer dnsServer, RssClientConf conf, RandomList<DnsServer.ResolveInterceptor> dnsInterceptors) {
        dnsServer.setTtl(60 * conf.dnsTtlMinutes);
        dnsServer.setNegativeTtl(DnsServer.DEFAULT_NEGATIVE_TTL);
        dnsServer.setInterceptors(dnsInterceptors);
    }

    static boolean inServerRestartRequired(RssClientConf oldConf, RssClientConf newConf) {
        return oldConf == null
                || oldConf.socksBindPort != newConf.socksBindPort
                || oldConf.logFlags != newConf.logFlags
                || oldConf.connectTimeoutSeconds != newConf.connectTimeoutSeconds
                || oldConf.tcpTimeoutSeconds != newConf.tcpTimeoutSeconds
                || oldConf.udpTimeoutSeconds != newConf.udpTimeoutSeconds
                || hasUdp2rawSocksServer(oldConf) != hasUdp2rawSocksServer(newConf);
    }

    static boolean shadowServerRestartRequired(RssClientConf oldConf, RssClientConf newConf) {
        return oldConf == null
                || oldConf.logFlags != newConf.logFlags
                || oldConf.connectTimeoutSeconds != newConf.connectTimeoutSeconds;
    }

    static boolean dnsRestartRequired(RssClientConf oldConf, RssClientConf newConf) {
        return oldConf == null || oldConf.shadowDnsPort != newConf.shadowDnsPort;
    }

    static boolean nameserverRestartRequired(RssClientConf oldConf, RssClientConf newConf) {
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

    static void configureInboundConfig(RssClientConf conf, SocksConfig config, boolean udp2raw) {
        config.setDebug(conf.hasDebugFlag());
        // RSS udp2raw 入口必须启用服务端隧道能力，RPC open 才不会返回 unsupported。
        config.setEnableUdp2raw(udp2raw);
        config.setTcpAsyncDnsMode(SocksConfig.TcpAsyncDnsMode.DIRECT);
        config.setOptimalSettings(RssSupport.IN_OPS);
        // config.setUdpMtu(UDP2RAW_MTU);
        config.setConnectTimeoutMillis(conf.connectTimeoutSeconds * 1000);
        config.setReadTimeoutSeconds(conf.tcpTimeoutSeconds);
        config.setUdpReadTimeoutSeconds(conf.udpTimeoutSeconds);
    }

    static void configureOutboundConfig(RssClientConf conf, SocksConfig config) {
        config.setDebug(conf.hasDebugFlag());
        config.setConnectTimeoutMillis(conf.connectTimeoutSeconds * 1000);
        config.setReadTimeoutSeconds(conf.tcpTimeoutSeconds);
        config.setUdpReadTimeoutSeconds(conf.udpTimeoutSeconds);

        config.setUdpMtu(UDP2RAW_MTU);
        config.setUdpRedundantMultiplier(2);
        config.setSocksUdpRedundantMode(UdpRedundantMode.BIDIRECTIONAL);
        config.setUdpRedundantAdaptive(true);
        config.setUdpRedundantMinMultiplier(1);
        config.setUdpRedundantMaxMultiplier(3);
        config.setUdpRedundantLossThresholdHigh(0.20);
        config.setUdpRedundantLossThresholdLow(0.05);
        config.setUdpRedundantStablePeriods(3);

        config.setUdpPortHoppingEnabled(true);
        config.setUdpPortHoppingAdaptive(true);
        config.setUdpPortHoppingMinHopCount(1);
        config.setUdpPortHoppingMaxHopCount(2);
        config.setUdpPortHoppingMinActiveHops(1);
        config.setUdpPortHoppingMode(UdpPortHoppingMode.ROUND_ROBIN);
        applyUdpLeasePool(conf, config);
        RssSupport.applyUdpCompressionTrial(config);
    }

    static SocksConfig createOutboundConfig(RssClientConf conf, SocksConfig inConf) {
        SocksConfig outConf = Sys.deepClone(inConf);
        outConf.setTransportFlags(TransportFlags.GFW.flags(TransportFlags.COMPRESS_BOTH).flags());
        outConf.setTcpCompressionLevel(RssSupport.TCP_TRIAL_COMPRESSION_LEVEL);
        outConf.setOptimalSettings(RssSupport.OUT_OPS);
        configureOutboundConfig(conf, outConf);
        return outConf;
    }

    static void configureShadowConfig(RssClientConf conf, ShadowsocksConfig config) {
        config.setOptimalSettings(RssSupport.SS_IN_OPS);
        config.setDebug(conf.hasDebugFlag());
        config.setConnectTimeoutMillis(conf.connectTimeoutSeconds * 1000);
        config.setReadTimeoutSeconds(0);
        config.setWriteTimeoutSeconds(0);
        config.setUdpReadTimeoutSeconds(0);
        config.setUdpWriteTimeoutSeconds(0);
    }

    static synchronized boolean configureAutoWhiteListSchedule(RssClientConf conf, RssRuntime rt) {
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

    static synchronized void configureRrpServer(RssClientConf conf) {
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

    static RssRuntime.RrpServerPlan prepareRrpServerPlan(RssClientConf conf) {
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
        long now = System.currentTimeMillis();
        long delayMillis = Math.min(UPSTREAM_CLOSE_DELAY_MILLIS, UPSTREAM_CLOSE_MAX_WAIT_MILLIS);
        long deadline = now + UPSTREAM_CLOSE_MAX_WAIT_MILLIS;
        if (snapshot.closeDeadlineMillis <= 0L || snapshot.closeDeadlineMillis > deadline) {
            snapshot.closeDeadlineMillis = deadline;
        }
        snapshot.closeCheckMillis = now + delayMillis;
        cancelUpstreamHealthCheck(snapshot);
        Tasks.setTimeout(() -> closeUpstreamsWhenIdle(snapshot), delayMillis);
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
            snapshot.closeCheckMillis = System.currentTimeMillis() + UPSTREAM_CLOSE_CHECK_MILLIS;
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
            closeUpstreamEndpoint(support);
            if (support.getFacade() != null) {
                facades.add(support.getFacade());
            }
        }
        for (UpstreamSupport support : snapshot.udp2rawSocksServers.readOnlySnapshot()) {
            closeUpstreamEndpoint(support);
            if (support.getFacade() != null) {
                facades.add(support.getFacade());
            }
        }
        for (SocksRpcContract facade : facades) {
            tryClose(facade);
        }
    }

    private static void closeUpstreamEndpoint(UpstreamSupport support) {
        if (support == null) {
            return;
        }
        org.rx.net.socks.Socks5UpstreamPoolManager.INSTANCE.closeEndpoint(support.getEndpoint());
        if (support.getTcpClient() != null) {
            org.rx.net.socks.Socks5UpstreamPoolManager.INSTANCE.closeEndpoint(
                    rewriteEndpoint(support.getEndpoint(), support.getTcpClient()));
        }
    }

    static void startUpstreamHealthCheck(RssRuntime.UpstreamSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        long periodMillis = upstreamHealthCheckPeriodMillis();
        snapshot.healthTask = Tasks.schedulePeriod(() -> refreshUpstreamHealth(snapshot),
                0L, periodMillis);
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
        return Remoting.isHealthy(facade);
    }

    static void updateUpstreamHealth(RssRuntime.UpstreamSnapshot snapshot, RandomList<UpstreamSupport> servers,
            UpstreamSupport support, boolean healthy, boolean updateDns) {
        if (support == null || servers == null) {
            return;
        }
        boolean previous = support.isHealthy();
        int failureThreshold = upstreamHealthFailureThreshold();
        boolean effectiveHealthy;
        int failureCount;
        if (healthy) {
            support.setHealthFailureCount(0);
            failureCount = 0;
            effectiveHealthy = true;
        } else {
            failureCount = support.getHealthFailureCount() + 1;
            support.setHealthFailureCount(failureCount);
            effectiveHealthy = previous && failureCount < failureThreshold;
        }

        support.setHealthy(effectiveHealthy);
        int weight = effectiveHealthy ? Math.max(0, support.getConfiguredWeight()) : 0;
        setWeightIfPresent(servers, support, weight);
        if (updateDns && snapshot != null && support.getFacade() != null) {
            setWeightIfPresent(snapshot.dnsInterceptors, support.getFacade(),
                    healthyDnsInterceptorWeight(snapshot, support.getFacade()));
        }
        if (snapshot != null && snapshot.userSocksServers != null) {
            for (RandomList<UpstreamSupport> userServers : snapshot.userSocksServers.values()) {
                setWeightIfPresent(userServers, support, weight);
            }
        }
        if (snapshot != null && snapshot.udp2rawUserSocksServers != null) {
            for (RandomList<UpstreamSupport> userServers : snapshot.udp2rawUserSocksServers.values()) {
                setWeightIfPresent(userServers, support, weight);
            }
        }
        DiagnosticMetrics.record("rss.upstream.health", effectiveHealthy ? 1D : 0D, "endpoint=" + support.getEndpoint());
        DiagnosticMetrics.record("rss.upstream.health.failures", failureCount, "endpoint=" + support.getEndpoint());
        if (!healthy && effectiveHealthy) {
            log.warn("rss upstream {} health check failed {}/{}", support.getEndpoint(), failureCount, failureThreshold);
        }
        if (previous != effectiveHealthy) {
            log.info("rss upstream {} health {}", support.getEndpoint(), effectiveHealthy ? "UP" : "DOWN");
        }
    }

    static int healthyDnsInterceptorWeight(RssRuntime.UpstreamSnapshot snapshot, SocksRpcContract facade) {
        if (snapshot == null || facade == null || snapshot.socksServers == null) {
            return 0;
        }
        int weight = 0;
        for (UpstreamSupport support : snapshot.socksServers.readOnlySnapshot()) {
            if (support != null && support.getFacade() == facade && support.isHealthy()) {
                weight += Math.max(0, support.getConfiguredWeight());
            }
        }
        return weight;
    }

    static int upstreamHealthFailureThreshold() {
        RssClientConf conf = rssConf;
        return conf == null ? DEFAULT_UPSTREAM_HEALTH_FAILURE_THRESHOLD
                : Math.max(1, conf.upstreamHealthFailureThreshold);
    }

    static long upstreamHealthCheckPeriodMillis() {
        RssClientConf conf = rssConf;
        int seconds = conf == null ? DEFAULT_UPSTREAM_HEALTH_CHECK_SECONDS
                : Math.max(1, conf.upstreamHealthCheckSeconds);
        return seconds * 1000L;
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
            // 热加载旧实例可能仍有 UDP relay 残留事件，关闭前先停源地址粘滞缓存。
            server.disableSourceSteering();
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

    public static SocketAddress resolveClientInListenAddress(RssClientConf conf, int port, String localNamePrefix) {
        if (conf != null && conf.socksBindPort) {
            return Sockets.newLoopbackEndpoint(port);
        }
        return new LocalAddress(localNamePrefix + port);
    }

    static RouteAction matchRoute(UserRuleMatcher matcher, String host) {
        return matchRoute(matcher, host, -1, null);
    }

    static RouteAction matchRoute(UserRuleMatcher matcher, String host, int dstPort, InetSocketAddress srcEp) {
        if (matcher == null) {
            RssClientConf conf = rssConf;
            matcher = conf == null ? null : conf.defaultRouteMatcher;
        }
        return matcher == null ? RouteAction.PROXY : matcher.match(host, dstPort, srcEp);
    }

    static RouteAction matchUserRoute(TrafficUser user, String host) {
        return matchUserRoute(user, host, -1, null);
    }

    static RouteAction matchUserRoute(TrafficUser user, String host, int dstPort, InetSocketAddress srcEp) {
        UserRuleMatcher matcher = user instanceof ShadowUser ? ((ShadowUser) user).getRouteMatcher() : null;
        return matchRoute(matcher, host, dstPort, srcEp);
    }

    static RssRuntime.RssInServer createInSvr(RssClientConf conf, SocksConfig inConf, Authenticator authenticator,
            TripleAction<SocksProxyServer, SocksContext> firstRoute,
            AtomicReference<RandomList<UpstreamSupport>> socksServersRef,
            AtomicReference<Map<String, RandomList<UpstreamSupport>>> userSocksServersRef) {
        SocksProxyServer inSvr = new SocksProxyServer(inConf);
        if (authenticator instanceof RssAuthenticator) {
            RssAuthenticator rssAuthenticator = (RssAuthenticator) authenticator;
            inSvr.setConnectionTagResolver(rssAuthenticator::resolve);
        }
        SocksConfig outConf = createOutboundConfig(conf, inConf);
        AtomicReference<SocksConfig> outConfRef = new AtomicReference<>(outConf);
        AtomicBoolean sourceSteeringEnabled = new AtomicBoolean(true);
        BiFunc<SocksContext, UpstreamSupport> selectUpstreamFn = e -> {
            InetAddress srcHost = sourceAddress(e.getSource());
            InetSocketAddress dstEp = e.getFirstDestination();
            RandomList<UpstreamSupport> servers = resolveUserSocksServers(socksServersRef.get(),
                    userSocksServersRef == null ? null : userSocksServersRef.get(), e.getUser());
            UpstreamSupport next;
            try {
                next = nextUpstream(servers, srcHost, dstEp, sourceSteeringEnabled.get(), sourceSteeringTtl(e.getUser()));
            } catch (InvalidException ex) {
                String username = e.getUser() == null ? "anonymous" : e.getUser().getUsername();
                throw new InvalidException("No socks upstream user={} src={} dst={}", username, srcHost, dstEp, ex);
            }
            if (rssConf.hasDebugFlag()) {
                log.info("route upSvr src {} dst {} -> {}", srcHost, dstEp, next.getEndpoint());
            }
            return next;
        };
        inSvr.onTcpRoute.replace(firstRoute, (s, e) -> {
            if (e.getUpstream() != null) {
                return;
            }
            InetSocketAddress dstEp = e.getFirstDestination();
            SocksConfig currentOutConf = outConfRef.get();
            RssClientConf currentConf = rssConf;
            boolean routeLog = currentConf != null && currentConf.hasRouteFlag();
            long userRuleBegin = routeLog ? System.nanoTime() : 0L;
            boolean userRoute = e.getUser() instanceof ShadowUser && ((ShadowUser) e.getUser()).getRouteMatcher() != null;
            RouteAction userRuleAction = matchUserRoute(e.getUser(), dstEp.getHostString(), dstEp.getPort(), e.getSource());
            if (userRuleAction == RouteAction.BLOCK) {
                if (routeLog) {
                    log.info("route dst TCP {} BLOCK <- {} {}",
                            dstEp.getHostString(), userRoute ? "user:route" : "defaultRoute",
                            Sys.formatNanosElapsed(System.nanoTime() - userRuleBegin));
                }
                String username = e.getUser() == null ? "anonymous" : e.getUser().getUsername();
                throw new InvalidException("rss route rule block user={} trans=TCP dst={}", username, dstEp);
            }
            if (userRuleAction == RouteAction.DIRECT) {
                if (routeLog) {
                    log.info("route dst TCP {} DIRECT <- {} {}",
                            dstEp.getHostString(), userRoute ? "user:route" : "defaultRoute",
                            Sys.formatNanosElapsed(System.nanoTime() - userRuleBegin));
                }
                e.setUpstream(new Upstream(dstEp));
                return;
            }
            if (routeLog) {
                log.info("route dst TCP {} PROXY <- {} {}",
                        dstEp.getHostString(),
                        userRoute ? "user:route" : "defaultRoute",
                        Sys.formatNanosElapsed(System.nanoTime() - userRuleBegin));
            }
            e.setUpstream(new SocksTcpUpstream(dstEp, currentOutConf,
                    routeUpstream(currentOutConf, selectUpstreamFn.apply(e))));
        });
        inSvr.onUdpRoute.replace(firstRoute, (s, e) -> {
            if (e.getUpstream() != null) {
                return;
            }
            InetSocketAddress dstEp = e.getFirstDestination();
            SocksConfig currentOutConf = outConfRef.get();
            RssClientConf currentConf = rssConf;
            boolean routeLog = currentConf != null && currentConf.hasRouteFlag();
            long userRuleBegin = routeLog ? System.nanoTime() : 0L;
            boolean userRoute = e.getUser() instanceof ShadowUser && ((ShadowUser) e.getUser()).getRouteMatcher() != null;
            RouteAction userRuleAction = matchUserRoute(e.getUser(), dstEp.getHostString(), dstEp.getPort(), e.getSource());
            if (userRuleAction == RouteAction.BLOCK) {
                if (routeLog) {
                    log.info("route dst UDP {} BLOCK <- {} {}",
                            dstEp.getHostString(), userRoute ? "user:route" : "defaultRoute",
                            Sys.formatNanosElapsed(System.nanoTime() - userRuleBegin));
                }
                String username = e.getUser() == null ? "anonymous" : e.getUser().getUsername();
                throw new InvalidException("rss route rule block user={} trans=UDP dst={}", username, dstEp);
            }
            if (userRuleAction == RouteAction.DIRECT) {
                if (routeLog) {
                    log.info("route dst UDP {} DIRECT <- {} {}",
                            dstEp.getHostString(), userRoute ? "user:route" : "defaultRoute",
                            Sys.formatNanosElapsed(System.nanoTime() - userRuleBegin));
                }
                e.setUpstream(new Upstream(dstEp));
                return;
            }
            if (routeLog) {
                log.info("route dst UDP {} PROXY <- {} {}",
                        dstEp.getHostString(),
                        userRoute ? "user:route" : "defaultRoute",
                        Sys.formatNanosElapsed(System.nanoTime() - userRuleBegin));
            }
            e.setUpstream(createUdpRouteUpstream(dstEp, currentOutConf, selectUpstreamFn.apply(e)));
        });
        inSvr.setCipherRouter(SocksProxyServer.DNS_CIPHER_ROUTER);
        return new RssRuntime.RssInServer(inSvr, inConf, outConfRef, sourceSteeringEnabled);
    }

    static RandomList<UpstreamSupport> resolveUserSocksServers(RandomList<UpstreamSupport> defaultServers,
            Map<String, RandomList<UpstreamSupport>> userServers,
            TrafficUser user) {
        if (userServers == null || userServers.isEmpty() || user == null || user.isAnonymous()) {
            return defaultServers;
        }
        RandomList<UpstreamSupport> servers = userServers.get(user.getUsername());
        return servers == null ? defaultServers : servers;
    }

    static UpstreamSupport nextUpstream(RandomList<UpstreamSupport> socksServers, InetAddress srcHost) {
        return nextUpstream(socksServers, srcHost, null);
    }

    static UpstreamSupport nextUpstream(RandomList<UpstreamSupport> socksServers, InetAddress srcHost, InetSocketAddress dstEp) {
        return nextUpstream(socksServers, srcHost, dstEp, true);
    }

    static UpstreamSupport nextUpstream(RandomList<UpstreamSupport> socksServers, InetAddress srcHost,
            InetSocketAddress dstEp, boolean allowSourceSteering) {
        return nextUpstream(socksServers, srcHost, dstEp, allowSourceSteering, 0);
    }

    static UpstreamSupport nextUpstream(RandomList<UpstreamSupport> socksServers, InetAddress srcHost,
            InetSocketAddress dstEp, boolean allowSourceSteering, int steeringTtl) {
        try {
            UpstreamSupport next = allowSourceSteering && srcHost != null && useSourceSteering(steeringTtl, dstEp)
                    ? socksServers.next(srcHost, steeringTtl, true)
                    : socksServers.next();
            if (next.isHealthy()) {
                return next;
            }
            return nextHealthyUpstream(socksServers);
        } catch (NoSuchElementException e) {
            UpstreamSupport next = tryNextFailOpen(socksServers, srcHost);
            if (next != null) {
                return next;
            }
            throw new InvalidException("No available socks upstream for {}", srcHost);
        } catch (IllegalArgumentException e) {
            UpstreamSupport next = tryNextFailOpen(socksServers, srcHost);
            if (next != null) {
                return next;
            }
            throw new InvalidException("No weighted socks upstream for {}", srcHost);
        }
    }

    static int sourceSteeringTtl(TrafficUser user) {
        return sourceSteeringTtl(user, rssConf);
    }

    static int sourceSteeringTtl(TrafficUser user, RssClientConf conf) {
        if (user instanceof ShadowUser) {
            UserRule route = ((ShadowUser) user).getRoute();
            if (route != null && !Boolean.FALSE.equals(route.getEnabled())) {
                return Math.max(0, route.getSrcSteeringTTL());
            }
        }
        return defaultRouteSteeringTtl(conf);
    }

    static int defaultRouteSteeringTtl() {
        return defaultRouteSteeringTtl(rssConf);
    }

    static int defaultRouteSteeringTtl(RssClientConf conf) {
        UserRule route = conf == null ? null : conf.defaultRoute;
        return route == null || Boolean.FALSE.equals(route.getEnabled()) ? 0 : Math.max(0, route.getSrcSteeringTTL());
    }

    static InetAddress sourceAddress(InetSocketAddress source) {
        return source == null ? null : source.getAddress();
    }

    static UpstreamSupport tryNextFailOpen(RandomList<UpstreamSupport> socksServers, InetAddress srcHost) {
        if (!upstreamFailOpenWhenAllDown()) {
            return null;
        }
        UpstreamSupport next = nextConfiguredUpstream(socksServers);
        if (next == null) {
            return null;
        }
        DiagnosticMetrics.record("rss.upstream.fail_open.count", 1D, "endpoint=" + next.getEndpoint());
        log.warn("rss upstream fail-open src {} -> {} healthFailures={}",
                srcHost, next.getEndpoint(), next.getHealthFailureCount());
        return next;
    }

    static boolean upstreamFailOpenWhenAllDown() {
        RssClientConf conf = rssConf;
        return conf == null || conf.upstreamFailOpenWhenAllDown;
    }

    static UpstreamSupport nextConfiguredUpstream(RandomList<UpstreamSupport> socksServers) {
        if (socksServers == null) {
            return null;
        }
        List<UpstreamSupport> snapshot = socksServers.readOnlySnapshot();
        int totalWeight = 0;
        for (UpstreamSupport support : snapshot) {
            if (support != null && support.getConfiguredWeight() > 0) {
                totalWeight += support.getConfiguredWeight();
            }
        }
        if (totalWeight <= 0) {
            return null;
        }
        int value = ThreadLocalRandom.current().nextInt(totalWeight);
        for (UpstreamSupport support : snapshot) {
            if (support == null || support.getConfiguredWeight() <= 0) {
                continue;
            }
            value -= support.getConfiguredWeight();
            if (value < 0) {
                return support;
            }
        }
        return null;
    }

    static boolean useSourceSteering(int steeringTtl, InetSocketAddress dstEp) {
        return steeringTtl > 0 && (dstEp == null || !isCommonStatelessPort(dstEp.getPort()));
    }

    static boolean isCommonStatelessPort(int port) {
        switch (port) {
            case 53: // DNS
            case 80: // HTTP
            case 123: // NTP
            case 443: // HTTPS / HTTP3 / QUIC
            case 853: // DNS over TLS
            case 3478: // STUN / TURN
            case 5349: // STUNS / TURNS
            case 8000:
            case 8008:
            case 8080:
            case 8081:
            case 8088:
            case 8443:
            case 8888:
            case 19302: // Google STUN
                return true;
            default:
                return false;
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

    static Upstream createUdpRouteUpstream(InetSocketAddress dstEp, SocksConfig config, UpstreamSupport next) {
        if (next == null) {
            return new Upstream(dstEp);
        }
        if (next.isUdp2raw()) {
            return new Udp2rawUpstream(dstEp, udp2rawRouteConfig(config), next);
        }
        InetSocketAddress udpClient = next.getUdpClient();
        if (udpClient != null) {
            return new UdpClientUpstream(dstEp, config, udpClient);
        }
        return new SocksUdpUpstream(dstEp, config, routeUpstream(config, next));
    }

    private static SocksConfig udp2rawRouteConfig(SocksConfig config) {
        if (config == null) {
            config = new SocksConfig();
        }
        if (config.getUdpMtu() > 0) {
            return config;
        }
        SocksConfig routed = Sys.deepClone(config);
        routed.setUdpMtu(UDP2RAW_MTU);
        return routed;
    }

    static UpstreamSupport routeUpstream(SocksConfig inConf, UpstreamSupport next) {
        if (next == null) {
            return next;
        }
        AuthenticEndpoint tcpClient = next.getTcpClient();
        if (tcpClient == null) {
            return next;
        }
        UpstreamSupport routed = new UpstreamSupport(rewriteEndpoint(next.getEndpoint(), tcpClient), next.getFacade());
        routed.setConnectionTracker(next);
        return routed;
    }

    static AuthenticEndpoint rewriteEndpoint(AuthenticEndpoint source, AuthenticEndpoint endpointOverride) {
        if (source == null || endpointOverride == null) {
            return endpointOverride;
        }
        String username = Strings.isEmpty(endpointOverride.getUsername()) ? source.getUsername() : endpointOverride.getUsername();
        String password = Strings.isEmpty(endpointOverride.getPassword()) ? source.getPassword() : endpointOverride.getPassword();
        Map<String, String> parameters = new LinkedHashMap<String, String>();
        parameters.putAll(source.getParameters());
        parameters.putAll(endpointOverride.getParameters());
        return new AuthenticEndpoint(endpointOverride.getEndpoint(), username, password, parameters);
    }

    static void applyUdpLeasePool(RssClientConf conf, SocksConfig config) {
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

    static int resolveRpcRequestTimeoutMillis(RssClientConf conf) {
        int configured = conf.rpcRequestTimeoutMillis;
        if (configured > 0) {
            return configured;
        }
        int connectTimeoutMillis = Math.max(1000, conf.connectTimeoutSeconds * 1000);
        return Math.min(connectTimeoutMillis, 3000);
    }

    static NameserverConfig resolveNameserverConfig(RssClientConf conf) {
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

    static boolean shouldScheduleDdns(RssClientConf conf) {
        return conf != null && conf.ddnsJobSeconds > 0 && !CollectionUtils.isEmpty(conf.ddnsDomains);
    }

    static synchronized boolean configureDdnsSchedule(RssClientConf conf) {
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
        RssClientConf conf = rssConf;
        if (!shouldScheduleDdns(conf)) {
            return;
        }

        InetAddress wanIp = Sockets.parseIpAddress(Sockets.getPublicIp());
        List<String> subDomains = Linq.from(conf.ddnsDomains)
                .where(sd -> !DnsClient.directClient().resolveAll(sd).contains(wanIp))
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

        String body = requestBody.toString();
        HttpClient.Request req = HttpClient.request(HttpMethod.POST, url)
                .header(HttpHeaderNames.ACCEPT, "application/json")
                .header(HttpHeaderNames.AUTHORIZATION, "Bearer " + apiKey)
                .header("X-Signature", dynadotSign(apiKey, url, body))
                .bytes(body.getBytes(StandardCharsets.UTF_8), "application/json");
        try (HttpClient.Response response = RssSupport.MAIN_HTTP_CLIENT.execute(req)) {
            return response.bodyAsString();
        }

//        URL u = new URL(url);
//        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
//        try {
//            conn.setDoOutput(true);
//            conn.setRequestMethod("POST");
//            conn.setRequestProperty("Content-Type", "application/json");
//            conn.setRequestProperty("Accept", "application/json");
//            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
//            conn.setRequestProperty("X-Signature", dynadotSign(apiKey, url, requestBody.toString()));
//            try (OutputStream os = conn.getOutputStream()) {
//                os.write(requestBody.toString().getBytes(StandardCharsets.UTF_8));
//            }
//            return DuplexStream.readString(conn.getInputStream(), StandardCharsets.UTF_8);
//        } finally {
//            conn.disconnect();
//        }
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
        payload.put("socksServers", user.getSocksServers());
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
            String authUserName, boolean udp2raw) {
        SocketAddress endpoint = inSvrAddress;
        if (udp2raw && inUdp2rawSvrAddress != null) {
            endpoint = inUdp2rawSvrAddress;
        }
        AuthenticEndpoint target = new AuthenticEndpoint(endpoint);
        target.getParameters().put(SocksConnectionTagRegistry.PARAM_NAME, authUserName);
        return target;
    }
}
