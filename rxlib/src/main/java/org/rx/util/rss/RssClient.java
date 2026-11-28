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
import org.rx.core.Reflects;
import org.rx.core.RxConfig;
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
import org.rx.net.dns.DnsResolveInterceptor;
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
import org.rx.net.socks.upstream.SocksTcpUpstream;
import org.rx.net.socks.upstream.SocksUdpUpstream;
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
import java.util.concurrent.TimeUnit;
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
    static final long DEFAULT_PROCESS_DRAIN_MAX_WAIT_MILLIS = 180L * 1000L;
    static final long DEFAULT_PROCESS_DRAIN_TOKEN_TTL_MILLIS = 120L * 1000L;
    static final String PROCESS_DRAIN_MAX_WAIT_PROPERTY = "app.rss.drainMaxWaitMillis";
    static final String PROCESS_DRAIN_TOKEN_DIR_PROPERTY = "app.rss.drainTokenDir";
    static final String PROCESS_DRAIN_TOKEN_TTL_PROPERTY = "app.rss.drainTokenTtlMillis";
    static final String RRP_SERVER_ENABLED_PROPERTY = "app.rss.rrpServerEnabled";

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
    static final DdnsJob ddnsJob = DdnsJob.production();

    private RssClient() {}

    @SneakyThrows
    public static void launch(Map<String, String> options, int port) {
        YamlConfigSource<RssClientConf> source = new YamlConfigSource<RssClientConf>("rss", RssClientConf.class, "conf.yml")
                .setValidator(RssClient::validateForRuntime)
                .setChangeDetector((oldConfig, newConfig) -> !Strings.hashEquals(toJsonString(oldConfig), toJsonString(newConfig))
                        || !Strings.hashEquals(oldConfig == null ? null : oldConfig.wowEndpointWriterKey, newConfig.wowEndpointWriterKey)
                        || !java.util.Arrays.equals(oldConfig == null ? null : oldConfig.ddnsLocalCommand, newConfig.ddnsLocalCommand))
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
        WowEndpointPublisher wowPublisher;

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
                            // server 主动询问说明其映射已丢失或尚未注册，失效本地 ACK，后续请求重新上报。
                            SocksTcpUpstream.invalidateFakeEndpointRegistration(recovery.getFakeHost());
                            recovery.setRealEndpoint(SocksTcpUpstream.cachedFakeEndpoint(recovery.getFakeHost()));
                            e.setValue(recovery);
                        }, false);
            }
        }

        @Override
        public boolean fakeEndpoint(String fakeHost, String realEndpoint, String token) {
            return delegate.fakeEndpoint(fakeHost, realEndpoint, token);
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
                log.debug("route dns {}+{} {} <- {}", srcIp, host, action, ext);
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
        public byte[] queryDnsRecord(String domain, int type, boolean tcp, String token) {
            return delegate.queryDnsRecord(domain, type, tcp, token);
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
        public void close() {
            tryClose(wowPublisher);
            tryClose(delegate);
            tryClose(eventDelegate);
        }
    }

    static boolean validateForRuntime(RssClientConf conf) {
        if (!normalizeAndValidateRssConfig(conf)) return false;
        boolean rssPeer = conf.socksServers.stream().anyMatch(s -> s != null && weightOf(s) > 0
                && (s.type != RssClientConf.UpstreamType.SOCKS5 || s.rssEndpoint != null));
        boolean tcpProtection = conf.tcpCipherPorts.length != 0 || conf.shadowUsers.stream()
                .anyMatch(user -> user.transport != null && (user.transport.tcp() & 2) != 0);
        if (rssPeer && (tcpProtection && !org.rx.net.RssHopAes.configured()
                || conf.udpCipherPorts.length != 0 && !org.rx.net.rss.udp.RssDatagramHandler.hasCipherKey())) {
            log.warn("RSS protected port lists require the corresponding RSS_TCP_AES_KEY / RSS_UDP_AES_KEY");
            return false;
        }
        return true;
    }

    static boolean normalizeAndValidateRssConfig(RssClientConf conf) {
        if (conf == null) {
            return false;
        }
        if (conf.nameserver == null) {
            conf.nameserver = new NameserverConfig();
        }
        if (conf.nodeRegistry == null) {
            conf.nodeRegistry = new org.rx.net.registry.NodeRegistryConfig();
        }
        if (conf.nodeService == null) {
            conf.nodeService = new RssClientConf.NodeServiceConf();
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
        conf.ddnsLocalCommandTimeoutSeconds = Math.max(1, Math.min(60, conf.ddnsLocalCommandTimeoutSeconds));
        conf.shadowDnsPort = Math.max(1, conf.shadowDnsPort);
        conf.dnsTtlMinutes = Math.max(1, conf.dnsTtlMinutes);
        resolveNameserverConfig(conf);

        if (Strings.isEmpty(conf.socksPwd) || CollectionUtils.isEmpty(conf.shadowUsers) || CollectionUtils.isEmpty(conf.socksServers)) {
            return false;
        }
        if (!RssTransport.validPortLists(conf.tcpCipherPorts, conf.tcpCodecBypassPorts)) {
            log.warn("Invalid or overlapping RSS TCP port lists");
            return false;
        }
        if (!RssTransport.validPortLists(conf.udpCipherPorts, conf.udpCodecBypassPorts)) {
            log.warn("Invalid or overlapping RSS UDP port lists");
            return false;
        }
        if (conf.egress == null) {
            conf.egress = new RssClientConf.Egress();
        }
        try {
            if (conf.egress.udpCodec != null && !"none".equals(conf.egress.udpCodec)) {
                throw new IllegalArgumentException("RSS UDP egress codec must be none");
            }
            org.rx.net.EgressCodec.parse(conf.egress.tcpCodec);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid RSS egress configuration: {}", e.getMessage());
            return false;
        }
        if (!RssTransport.validUdpMtu(conf.udpMtu)) {
            log.warn("rssConf invalid udpMtu {}", conf.udpMtu);
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
        if (isRrpServerEnabled() && conf.rrpPort != null && conf.rrpPort <= 0) {
            return false;
        }
        if (!validateNodeRegistryAndService(conf)) {
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
            if (conflictsWithNodeServicePort(conf, user.getSsPort())) {
                log.warn("ShadowUser {} ssPort {} collides with NodeService port", user.getUsername(), user.getSsPort());
                return false;
            }
            if (user.transport != null && !user.transport.valid()) {
                log.warn("Invalid RSS transport for user {}: fec/tcp must match the profile, udp selects LZ4 (0/1), plain requires udp=0 and no hopping", user.getUsername());
                return false;
            }
            try {
                user.cipherName();
            } catch (IllegalArgumentException e) {
                log.warn("Invalid ssCipher for user {}", user.getUsername());
                return false;
            }
            if (user.transport != null && (user.transport.udpFeatures() & org.rx.net.rss.udp.RssDatagramHandler.AES) != 0
                    && !org.rx.net.rss.udp.RssDatagramHandler.hasCipherKey()) {
                log.warn("RSS UDP encryption for user {} requires RSS_UDP_AES_KEY", user.getUsername());
                return false;
            }
            if (!normalizeAndValidateUserSocksServers(user, conf.socksServers, conf.udpMtu)) {
                return false;
            }
            if (!normalizeAndValidateUserRoute(user)) {
                return false;
            }
            if (user.bandwidth != null && !user.bandwidth.isValid()) {
                log.warn("Invalid bandwidth config for user {}: {}", user.getUsername(), user.bandwidth);
                return false;
            }
        }
        if (shouldScheduleDdns(conf) && !CollectionUtils.isEmpty(conf.ddnsDomains)) {
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

    static boolean validateNodeRegistryAndService(RssClientConf conf) {
        org.rx.net.registry.NodeRegistryConfig reg = conf.nodeRegistry;
        RssClientConf.NodeServiceConf svc = conf.nodeService;
        if (reg != null && reg.isEnabled()) {
            if (Strings.isEmpty(reg.getDbPath()) || reg.getMaxOnlineNodes() <= 0
                    || reg.getMaxColdCacheBytes() <= 0 || reg.getMaxPendingPersistBytes() <= 0) {
                log.warn("Invalid NodeRegistry configuration: dbPath and positive caps are required");
                return false;
            }
        }
        if (svc == null || !svc.enabled) {
            return true;
        }
        if (reg == null || !reg.isEnabled()) {
            log.warn("nodeService.enabled requires nodeRegistry.enabled=true");
            return false;
        }
        if ((svc.rendezvousEnabled || svc.relayEnabled) && Strings.isEmpty(svc.authToken)) {
            log.warn("Public nodeService requires non-empty authToken (unauthenticated mode forbidden)");
            return false;
        }
        try {
            org.rx.net.EgressConfig egress = svc.egressConfig != null
                    ? svc.egressConfig : org.rx.net.EgressConfig.serverAutoAll();
            egress.validateServer();
        } catch (IllegalArgumentException e) {
            log.warn("Invalid nodeService egressConfig: {}", e.getMessage());
            return false;
        }
        Set<Integer> tcpPorts = new LinkedHashSet<>();
        tcpPorts.add(conf.shadowDnsPort);
        if (conf.nameserver != null && conf.nameserver.getRegisterPort() > 0) {
            if (!tcpPorts.add(conf.nameserver.getRegisterPort())) {
                log.warn("Port collision on nameserver.registerPort={}", conf.nameserver.getRegisterPort());
                return false;
            }
        }
        if (shouldEnableRrpServer(conf) && conf.rrpPort != null && conf.rrpPort > 0) {
            if (!tcpPorts.add(conf.rrpPort)) {
                log.warn("Port collision on rrpPort={}", conf.rrpPort);
                return false;
            }
        }
        if (svc.rendezvousEnabled) {
            if (svc.rendezvousPort < 0 || svc.rendezvousPort > 65535
                    || svc.udpObservePort < 0 || svc.udpObservePort > 65535) {
                log.warn("Invalid rendezvous ports rpc={} udp={}", svc.rendezvousPort, svc.udpObservePort);
                return false;
            }
            if (!svc.allowEphemeralPorts && (svc.rendezvousPort <= 0 || svc.udpObservePort <= 0)) {
                log.warn("nodeService.rendezvousEnabled requires explicit rendezvousPort > 0 and udpObservePort > 0");
                return false;
            }
            if (svc.rendezvousPort > 0 && !tcpPorts.add(svc.rendezvousPort)) {
                log.warn("Port collision on nodeService.rendezvousPort={}", svc.rendezvousPort);
                return false;
            }
            if (svc.udpObservePort > 0 && svc.udpObservePort == conf.shadowDnsPort) {
                log.warn("UDP port collision between nodeService.udpObservePort and shadowDnsPort={}", svc.udpObservePort);
                return false;
            }
        }
        if (svc.relayEnabled) {
            if (svc.relayPort < 0 || svc.relayPort > 65535) {
                log.warn("Invalid nodeService.relayPort={}", svc.relayPort);
                return false;
            }
            if (!svc.allowEphemeralPorts && svc.relayPort <= 0) {
                log.warn("nodeService.relayEnabled requires explicit relayPort > 0");
                return false;
            }
            if (svc.relayPort > 0 && !tcpPorts.add(svc.relayPort)) {
                log.warn("Port collision on nodeService.relayPort={}", svc.relayPort);
                return false;
            }
        }
        return true;
    }

    private static boolean conflictsWithNodeServicePort(RssClientConf conf, int ssPort) {
        if (conf == null || conf.nodeService == null || !conf.nodeService.enabled || ssPort <= 0) {
            return false;
        }
        RssClientConf.NodeServiceConf svc = conf.nodeService;
        return (svc.rendezvousEnabled && svc.rendezvousPort == ssPort)
                || (svc.relayEnabled && svc.relayPort == ssPort);
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

    private static boolean normalizeAndValidateSocksServerIds(RssClientConf conf) {
        Set<String> ids = new LinkedHashSet<>();
        for (RssClientConf.SocksServer spec : conf.socksServers) {
            if (spec == null || spec.type == null || spec.weight == null || spec.weight < 0
                    || spec.endpoint == null || !validEndpoint(spec.endpoint.getInetEndpoint())) {
                log.warn("Invalid RSS upstream type, endpoint or weight");
                return false;
            }
            spec.id = trimToNull(spec.id);
            if (spec.id == null || !ids.add(spec.id)) {
                log.warn("Missing or duplicate RSS upstream id {}", spec.id);
                return false;
            }
            boolean fixed = spec.type == RssClientConf.UpstreamType.UDPCLIENT;
            if (fixed != (spec.tcpClient != null) || fixed != (spec.udpClient != null)
                    || fixed != (spec.adaptiveUdpClient != null)
                    || fixed && (!validEndpoint(spec.tcpClient.getInetEndpoint()) || !validEndpoint(spec.udpClient)
                        || !validEndpoint(spec.adaptiveUdpClient) || spec.udpClient.equals(spec.adaptiveUdpClient))
                    || spec.rpcEndpoint != null && !validEndpoint(spec.rpcEndpoint)
                    || spec.type == RssClientConf.UpstreamType.SOCKS5 && spec.rssEndpoint == null && spec.rpcEndpoint != null
                    || spec.rssEndpoint != null && (spec.type != RssClientConf.UpstreamType.SOCKS5
                        || !validEndpoint(spec.rssEndpoint.getInetEndpoint()))) {
                log.warn("Invalid upstream {}: udpclient requires paired TCP and distinct ordinary/adaptive UDP mappings; nested SOCKS5 requires both a proxy and an RSS endpoint", spec.id);
                return false;
            }
            if (spec.type != RssClientConf.UpstreamType.SOCKS5 && spec.rpcEndpoint == null && conf.rpcPort == 0
                    && spec.endpoint.requireEndpoint().getPort() == 65535) {
                log.warn("Upstream {} requires an explicit rpcEndpoint", spec.id);
                return false;
            }
            if (spec.egress != null) {
                try {
                    if (spec.egress.udpCodec != null && !"none".equals(spec.egress.udpCodec)) {
                        throw new IllegalArgumentException("RSS UDP egress codec must be none");
                    }
                    org.rx.net.EgressCodec parsed = org.rx.net.EgressCodec.parse(spec.egress.tcpCodec);
                    if (spec.type == RssClientConf.UpstreamType.SOCKS5 && spec.rssEndpoint == null
                            && parsed != org.rx.net.EgressCodec.NONE) {
                        throw new IllegalArgumentException("Standard SOCKS5 upstream cannot enable RSS TCP egress codec");
                    }
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid egress config for upstream {}: {}", spec.id, e.getMessage());
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean validEndpoint(InetSocketAddress addr) {
        return addr != null && addr.getPort() > 0 && !addr.getHostString().trim().isEmpty();
    }

    private static boolean normalizeAndValidateUserSocksServers(ShadowUser user, List<RssClientConf.SocksServer> specs, Integer globalMtu) {
        Map<String, RssClientConf.SocksServer> index = indexSocksServers(specs);
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        boolean explicit = !CollectionUtils.isEmpty(user.getSocksServers());
        for (String id : explicit ? user.getSocksServers() : index.keySet()) {
            id = trimToNull(id);
            if (!index.containsKey(id) || !selected.add(id)) {
                log.warn("Unknown or duplicate upstream {} for user {}", id, user.getUsername());
                return false;
            }
        }
        RssTransport transport = user.transport == null ? RssTransport.DEFAULT : user.transport;
        boolean plain = "plain".equalsIgnoreCase(transport.profile);
        Integer mtu = transport.udpMtu == null ? globalMtu : transport.udpMtu;
        boolean enabled = false;
        for (String id : selected) {
            RssClientConf.SocksServer spec = index.get(id);
            if (weightOf(spec) == 0) {
                continue;
            }
            if (spec.type == RssClientConf.UpstreamType.UDPCLIENT && (mtu == null || mtu <= 0)) {
                log.warn("Fixed upstream {} requires an explicit net RSS frame udpMtu", id);
                return false;
            }
            if (spec.type == RssClientConf.UpstreamType.SOCKS5 && spec.rssEndpoint == null && !plain
                    || spec.type == RssClientConf.UpstreamType.UDPCLIENT && (plain || transport.udpHop())) {
                log.warn("Upstream {} cannot carry profile {} with udpHop={}", id, transport.profile, transport.udpHop());
                return false;
            }
            enabled = true;
        }
        if (explicit) {
            user.setSocksServers(new ArrayList<>(selected));
        }
        return enabled;
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
        RandomList<DnsResolveInterceptor> dnsInterceptors = new RandomList<>();
        List<SocksRpcContract> createdFacades = new ArrayList<>();
        Map<String, SocksRpcContract> facadeByRpcEndpoint = new LinkedHashMap<>();
        Map<String, UpstreamSupport> supportByServerId = new LinkedHashMap<>();
        try {
            org.rx.net.EgressCodec defaultEgress = conf.egress == null
                    ? org.rx.net.EgressCodec.NONE : org.rx.net.EgressCodec.parse(conf.egress.tcpCodec);
            for (RssClientConf.SocksServer spec : conf.socksServers) {
                int weight = weightOf(spec);
                if (weight == 0) {
                    continue;
                }
                boolean standard = spec.type == RssClientConf.UpstreamType.SOCKS5 && spec.rssEndpoint == null;
                AuthenticEndpoint outer = spec.rssEndpoint == null ? null : resolveCarrierEndpoint(spec.endpoint, conf);
                AuthenticEndpoint peer = spec.rssEndpoint == null ? spec.endpoint : resolveCarrierEndpoint(spec.rssEndpoint, conf);
                org.rx.net.EgressCodec routeOverride = standard || spec.egress == null
                        ? null : org.rx.net.EgressCodec.parse(spec.egress.tcpCodec);
                org.rx.net.EgressCodec routeEgress = standard ? org.rx.net.EgressCodec.NONE
                        : (routeOverride != null ? routeOverride : defaultEgress);
                SocksRpcContract facade = null;
                if (!standard) {
                    InetSocketAddress rpc = spec.rpcEndpoint != null ? spec.rpcEndpoint
                            : Sockets.newEndpoint(peer.requireEndpoint(), resolveRpcPort(conf, peer.requireEndpoint()));
                    facade = getOrCreateRpcFacade(conf, rpc, outer, spec.id, facadeByRpcEndpoint, createdFacades);
                }
                AuthenticEndpoint dial = spec.tcpClient == null ? peer : rewriteEndpoint(peer, spec.tcpClient);
                // A generation owns its facade, credentials, endpoint mapping and all pooled sessions.
                Map<String, String> parameters = new LinkedHashMap<>(dial.getParameters());
                parameters.put(UpstreamSupport.POOL_GENERATION, java.util.UUID.randomUUID().toString());
                dial = new AuthenticEndpoint(dial.getEndpoint(), dial.getUsername(), dial.getPassword(), parameters);
                UpstreamSupport support = outer != null || routeOverride != null
                        ? new BoundRoute(dial, facade, outer, routeOverride) : new UpstreamSupport(dial, facade);
                support.setRouteId(spec.id);
                support.setStandardSocks5(standard);
                support.setEgressCodec(routeEgress);
                support.setConfiguredWeight(weight);
                support.setUdpClient(spec.udpClient);
                support.setAdaptiveUdpClient(spec.adaptiveUdpClient);
                socksServers.add(support, weight);
                supportByServerId.put(spec.id, support);
                addDnsInterceptorWeight(dnsInterceptors, facade, weight);
            }
            return new RssRuntime.UpstreamSnapshot(socksServers, dnsInterceptors,
                    buildUserSocksServers(conf.shadowUsers, supportByServerId), conf.socksServers);
        } catch (Throwable e) {
            for (SocksRpcContract facade : createdFacades) {
                tryClose(facade);
            }
            throw InvalidException.sneaky(e);
        }
    }

    /** Pin the resolved transport peer for TCP, RPC and both UDP directions in this snapshot. */
    private static AuthenticEndpoint resolveCarrierEndpoint(AuthenticEndpoint endpoint, RssClientConf conf) throws Exception {
        InetSocketAddress addr = endpoint.requireEndpoint();
        if (addr.isUnresolved()) {
            addr = Sockets.resolveUdpEndpointAsync(addr, null).get(conf.connectTimeoutSeconds, TimeUnit.SECONDS);
        }
        InetAddress ip = InetAddress.getByAddress(addr.getAddress().getAddress());
        return new AuthenticEndpoint(new InetSocketAddress(ip, addr.getPort()), endpoint.getUsername(),
                endpoint.getPassword(), endpoint.getParameters());
    }

    static void addDnsInterceptorWeight(RandomList<DnsResolveInterceptor> dnsInterceptors,
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

    static int resolveRpcPort(RssClientConf conf, InetSocketAddress socksServerEp) {
        return conf != null && conf.rpcPort > 0 ? conf.rpcPort : socksServerEp.getPort() + 1;
    }

    private static SocksRpcContract getOrCreateRpcFacade(RssClientConf conf, InetSocketAddress rpcEndpoint, AuthenticEndpoint outer, String routeId,
            Map<String, SocksRpcContract> facadeByRpcEndpoint,
            List<SocksRpcContract> createdFacades) {
        String rpcKey = outer == null ? endpointKey(rpcEndpoint) : "socks5:" + routeId;
        SocksRpcContract facade = facadeByRpcEndpoint.get(rpcKey);
        if (facade != null) {
            return facade;
        }

        RpcClientConfig<SocksRpcContract> rpcConf = RpcClientConfig.poolMode(
                rpcEndpoint, conf.rpcMinSize, conf.rpcMaxSize);
        rpcConf.setRequestTimeoutMillis(resolveRpcRequestTimeoutMillis(conf));
        rpcConf.setSensitiveMethods(Collections.singleton("openUdpRelayGroup"));
        TcpClientConfig tcpConfig = rpcConf.getTcpConfig();
        configureRpcCarrier(rpcConf, outer);
        tcpConfig.setTransportFlags(TransportFlags.GFW.flags(TransportFlags.CIPHER_BOTH).flags());
        SocksRpcContract delegate = Remoting.createFacade(SocksRpcContract.class, rpcConf);

        RpcClientConfig<SocksRpcContract> eventRpcConf = RpcClientConfig.statefulMode(rpcEndpoint, SocksRpcContract.RPC_EVENT_VERSION);
        eventRpcConf.setRequestTimeoutMillis(resolveRpcRequestTimeoutMillis(conf));
        configureRpcCarrier(eventRpcConf, outer);
        eventRpcConf.getTcpConfig().setTransportFlags(TransportFlags.GFW.flags(TransportFlags.CIPHER_BOTH).flags());
        WowEndpointPublisher wowPublisher = outer == null ? WowEndpointPublisher.configure(conf, eventRpcConf) : null;
        SocksRpcContract eventDelegate = Remoting.createFacade(SocksRpcContract.class, eventRpcConf);
        facade = new ForwardingSocksRpcContract(delegate, eventDelegate);
        ((ForwardingSocksRpcContract) facade).wowPublisher = wowPublisher;
        facadeByRpcEndpoint.put(rpcKey, facade);
        createdFacades.add(facade);
        return facade;
    }

    private static void configureRpcCarrier(RpcClientConfig<?> config, AuthenticEndpoint outer) {
        if (outer == null) return;
        config.getTcpConfig().setSocksCarrierProxy(outer);
        config.getTransportConfig().setEnableAdaptiveUdp(false);
    }

    private static String rpcHostKey(InetSocketAddress endpoint) {
        return endpoint.getHostString().toLowerCase(Locale.ROOT);
    }

    private static String endpointKey(InetSocketAddress endpoint) {
        return rpcHostKey(endpoint) + ":" + endpoint.getPort();
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



    static DnsServer createDnsServer(RssClientConf conf, RandomList<DnsResolveInterceptor> dnsInterceptors) {
        DnsServer dnsServer = new DnsServer(conf.shadowDnsPort, org.rx.net.dns.DnsRecordExchange.directResolvers());
        applyDnsConfig(dnsServer, conf, dnsInterceptors);
        dnsServer.addHostsFile("hosts.txt");
        return dnsServer;
    }

    static void applyDnsConfig(DnsServer dnsServer, RssClientConf conf, RandomList<DnsResolveInterceptor> dnsInterceptors) {
        dnsServer.setTtl(60 * conf.dnsTtlMinutes);
        dnsServer.setNegativeTtl(DnsServer.DEFAULT_NEGATIVE_TTL);
        dnsServer.setInterceptorBreakerOpenMillis(RxConfig.INSTANCE.getNet().getDns().getInterceptorBreakerOpenMillis());
        dnsServer.setInterceptors(dnsInterceptors);
    }

    static boolean inServerRestartRequired(RssClientConf oldConf, RssClientConf newConf) {
        return oldConf == null
                || oldConf.socksBindPort != newConf.socksBindPort
                || oldConf.logFlags != newConf.logFlags
                || oldConf.connectTimeoutSeconds != newConf.connectTimeoutSeconds
                || oldConf.tcpTimeoutSeconds != newConf.tcpTimeoutSeconds
                || oldConf.udpTimeoutSeconds != newConf.udpTimeoutSeconds;
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
                || !Strings.hashEquals(oldNs.getNodeId(), newNs.getNodeId())
                || !Strings.hashEquals(oldNs.getReplicaAuthToken(), newNs.getReplicaAuthToken())
                || oldNs.getReplicaFullSyncPeriodMillis() != newNs.getReplicaFullSyncPeriodMillis()
                || !Strings.hashEquals(toJsonString(oldNs.getReplicaEndpoints()), toJsonString(newNs.getReplicaEndpoints()))
                || !Strings.hashEquals(toJsonString(oldNs.getAuthorizedReplicaPeers()), toJsonString(newNs.getAuthorizedReplicaPeers()));
    }

    static void configureInboundConfig(RssClientConf conf, SocksConfig config) {
        config.setDebug(conf.hasDebugFlag());
        config.setTcpAsyncDnsMode(SocksConfig.TcpAsyncDnsMode.DIRECT);
        config.setOptimalSettings(RssSupport.IN_OPS);
        config.setConnectTimeoutMillis(conf.connectTimeoutSeconds * 1000);
        config.setReadTimeoutSeconds(0);
        config.setWriteTimeoutSeconds(0);
        config.setTcpIdleTimeoutSeconds(conf.tcpTimeoutSeconds);
        config.setUdpReadTimeoutSeconds(conf.udpTimeoutSeconds);
    }

    static void configureOutboundConfig(RssClientConf conf, SocksConfig config) {
        config.setDebug(conf.hasDebugFlag());
        config.setConnectTimeoutMillis(conf.connectTimeoutSeconds * 1000);
        config.setReadTimeoutSeconds(0);
        config.setWriteTimeoutSeconds(0);
        config.setTcpIdleTimeoutSeconds(conf.tcpTimeoutSeconds);
        config.setUdpReadTimeoutSeconds(conf.udpTimeoutSeconds);

        int configuredUdpMtu = conf.udpMtu != null ? Math.max(0, conf.udpMtu) : 0;
        config.setUdpMtu(configuredUdpMtu);
        config.setDailyUdpMtu(conf.udpMtu != null ? Math.max(0, conf.udpMtu) : SocksConfig.DEFAULT_DAILY_UDP_MTU);
        config.setDailyUdpFeatures(0);
        config.setAdaptiveTcp(false);
        applyUdpLeasePool(conf, config);
    }

    static SocksConfig createOutboundConfig(RssClientConf conf, SocksConfig inConf) {
        SocksConfig outConf = Sys.deepClone(inConf);
        outConf.setTransportFlags(TransportFlags.NONE.flags());
        outConf.setTcpCompressionLevel(RssSupport.TCP_COMPRESSION_LEVEL);
        outConf.setEgressCodec(org.rx.net.EgressCodec.parse(conf.egress.tcpCodec));
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
        boolean enabled = shouldEnableRrpServer(conf);
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

    static boolean isRrpServerEnabled() {
        return Boolean.parseBoolean(System.getProperty(RRP_SERVER_ENABLED_PROPERTY, "false"));
    }

    static boolean shouldEnableRrpServer(RssClientConf conf) {
        return isRrpServerEnabled() && conf != null
                && !Strings.isEmpty(conf.rrpToken) && conf.rrpPort != null;
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
        tryClose(commitRrpServerPlanDeferredClose(plan));
    }

    static RrpServer commitRrpServerPlanDeferredClose(RssRuntime.RrpServerPlan plan) {
        if (plan == null || !plan.changed) {
            return null;
        }
        RrpServer oldServer = rrpServer;
        if (!plan.samePort) {
            plan.oldServer = oldServer;
            plan.oldToken = rrpToken;
            plan.oldPort = rrpPort;
        }
        if (!plan.enabled) {
            rrpServer = null;
            rrpToken = null;
            rrpPort = null;
            return oldServer;
        }
        if (plan.samePort) {
            oldServer = null;
        }
        rrpServer = plan.newServer;
        rrpToken = plan.token;
        rrpPort = plan.port;
        return oldServer;
    }

    static void rollbackCommittedRrpServerPlan(RssRuntime.RrpServerPlan plan, RrpServer deferredOldServer) {
        if (plan == null || !plan.changed) {
            return;
        }
        RrpServer committedNew = plan.newServer;
        plan.newServer = null;
        if (committedNew != null && committedNew == rrpServer) {
            rrpServer = null;
            rrpToken = null;
            rrpPort = null;
        }
        tryClose(committedNew);
        if (plan.oldDetached) {
            rollbackRrpServerPlan(plan);
            return;
        }
        rrpServer = deferredOldServer != null ? deferredOldServer : plan.oldServer;
        rrpToken = plan.oldToken;
        rrpPort = plan.oldPort;
        plan.oldServer = null;
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
        for (SocksRpcContract facade : facades) {
            tryClose(facade);
        }
    }

    private static void closeUpstreamEndpoint(UpstreamSupport support) {
        if (support == null) {
            return;
        }
        org.rx.net.socks.Socks5UpstreamPoolManager.INSTANCE.closeEndpoint(support.getEndpoint());
    }

    static void startUpstreamHealthCheck(RssRuntime.UpstreamSnapshot snapshot) {
        startUpstreamHealthCheck(snapshot, rssConf);
    }

    static void startUpstreamHealthCheck(RssRuntime.UpstreamSnapshot snapshot, RssClientConf conf) {
        if (snapshot == null) {
            return;
        }
        long periodMillis = upstreamHealthCheckPeriodMillis(conf);
        snapshot.healthTask = Tasks.schedulePeriod(() -> refreshUpstreamHealth(snapshot), periodMillis);
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
        if (healthy) {
            support.recordSuccess(UpstreamSupport.Capability.RPC);
        }
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
        if (snapshot != null && !snapshot.filteredSocksServers.isEmpty()) {
            for (RandomList<UpstreamSupport> filteredServers : snapshot.filteredSocksServers.values()) {
                setWeightIfPresent(filteredServers, support, weight);
            }
        }
        DiagnosticMetrics.record("rss.upstream.health", effectiveHealthy ? 1D : 0D, "endpoint=" + support.getEndpoint().getEndpoint());
        DiagnosticMetrics.record("rss.upstream.health.failures", failureCount, "endpoint=" + support.getEndpoint().getEndpoint());
        if (!healthy && effectiveHealthy) {
            log.warn("rss upstream {} health check failed {}/{}", support.getEndpoint().getEndpoint(), failureCount, failureThreshold);
        }
        if (previous != effectiveHealthy) {
            log.info("rss upstream {} health {}", support.getEndpoint().getEndpoint(), effectiveHealthy ? "UP" : "DOWN");
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
        return upstreamHealthCheckPeriodMillis(rssConf);
    }

    static long upstreamHealthCheckPeriodMillis(RssClientConf conf) {
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
        int count = snapshot.inFlightDnsQueryCount();
        Set<UpstreamSupport> supports = Collections.newSetFromMap(new IdentityHashMap<UpstreamSupport, Boolean>());
        supports.addAll(snapshot.socksServers.readOnlySnapshot());
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
            java.util.function.Supplier<RssRuntime.RuntimeGeneration> generationSupplier) {
        SocksProxyServer inSvr = new SocksProxyServer(inConf);
        if (authenticator instanceof RssAuthenticator) {
            RssAuthenticator rssAuthenticator = (RssAuthenticator) authenticator;
            inSvr.setConnectionTagResolver(rssAuthenticator::resolve);
        }
        AtomicReference<OutboundProfiles> outConfRef = new AtomicReference<>(new OutboundProfiles(conf, inConf));
        AtomicReference<SocksConfig> standardConfRef = new AtomicReference<>(standardSocksConfig(conf));
        AtomicBoolean sourceSteeringEnabled = new AtomicBoolean(true);
        inSvr.onTcpRoute.replace((s, e) -> {
            if (e.getUpstream() != null) {
                return;
            }
            InetSocketAddress dstEp = e.getFirstDestination();
            RssRuntime.RuntimeGeneration gen = generationSupplier.get();
            RssClientConf currentConf = gen != null ? gen.conf : rssConf;
            boolean routeLog = currentConf != null && currentConf.hasRouteFlag();
            long userRuleBegin = routeLog ? System.nanoTime() : 0L;
            boolean userRoute = e.getUser() instanceof ShadowUser && ((ShadowUser) e.getUser()).getRouteMatcher() != null;
            RouteAction userRuleAction = matchUserRoute(e.getUser(), dstEp.getHostString(), dstEp.getPort(), e.getSource(), currentConf);
            if (userRuleAction == RouteAction.BLOCK) {
                if (routeLog) {
                    log.debug("route dst TCP {} BLOCK <- {} {}",
                            dstEp.getHostString(), userRoute ? "user:route" : "defaultRoute",
                            Sys.formatNanosElapsed(System.nanoTime() - userRuleBegin));
                }
                String username = e.getUser() == null ? "anonymous" : e.getUser().getUsername();
                throw new InvalidException("rss route rule block user={} trans=TCP dst={}", username, dstEp);
            }
            firstRoute.invoke(s, e);
            if (e.getUpstream() != null) return;
            if (userRuleAction == RouteAction.DIRECT) {
                if (routeLog) {
                    log.debug("route dst TCP {} DIRECT <- {} {}",
                            dstEp.getHostString(), userRoute ? "user:route" : "defaultRoute",
                            Sys.formatNanosElapsed(System.nanoTime() - userRuleBegin));
                }
                e.setUpstream(new Upstream(dstEp));
                return;
            }
            if (routeLog) {
                log.debug("route dst TCP {} PROXY <- {} {}",
                        dstEp.getHostString(),
                        userRoute ? "user:route" : "defaultRoute",
                        Sys.formatNanosElapsed(System.nanoTime() - userRuleBegin));
            }
            OutboundProfiles profiles = gen != null ? gen.outboundProfiles : outConfRef.get();
            SocksConfig standardConf = gen != null ? gen.standardConf : standardConfRef.get();
            RssRuntime.UpstreamSnapshot routes = gen != null ? gen.upstreamSnapshot : null;
            SocksConfig currentOutConf = profiles.tcp(e.getUser(), dstEp.getPort());
            UpstreamSupport next = selectCompatibleUpstream(routes, currentConf, e, currentOutConf, true, sourceSteeringEnabled.get());
            e.setUpstream(new SocksTcpUpstream(dstEp, outboundForRoute(currentOutConf, standardConf, next, true), next));
        });
        inSvr.onUdpRoute.replace((s, e) -> {
            if (e.getUpstream() != null) {
                return;
            }
            InetSocketAddress dstEp = e.getFirstDestination();
            RssRuntime.RuntimeGeneration gen = generationSupplier.get();
            RssClientConf currentConf = gen != null ? gen.conf : rssConf;
            boolean routeLog = currentConf != null && currentConf.hasRouteFlag();
            long userRuleBegin = routeLog ? System.nanoTime() : 0L;
            boolean userRoute = e.getUser() instanceof ShadowUser && ((ShadowUser) e.getUser()).getRouteMatcher() != null;
            RouteAction userRuleAction = matchUserRoute(e.getUser(), dstEp.getHostString(), dstEp.getPort(), e.getSource(), currentConf);
            if (userRuleAction == RouteAction.BLOCK) {
                if (routeLog) {
                    log.debug("route dst UDP {} BLOCK <- {} {}",
                            dstEp.getHostString(), userRoute ? "user:route" : "defaultRoute",
                            Sys.formatNanosElapsed(System.nanoTime() - userRuleBegin));
                }
                String username = e.getUser() == null ? "anonymous" : e.getUser().getUsername();
                throw new InvalidException("rss route rule block user={} trans=UDP dst={}", username, dstEp);
            }
            firstRoute.invoke(s, e);
            if (e.getUpstream() != null) return;
            if (userRuleAction == RouteAction.DIRECT) {
                if (routeLog) {
                    log.debug("route dst UDP {} DIRECT <- {} {}",
                            dstEp.getHostString(), userRoute ? "user:route" : "defaultRoute",
                            Sys.formatNanosElapsed(System.nanoTime() - userRuleBegin));
                }
                e.setUpstream(new Upstream(dstEp));
                return;
            }
            if (routeLog) {
                log.debug("route dst UDP {} PROXY <- {} {}",
                        dstEp.getHostString(),
                        userRoute ? "user:route" : "defaultRoute",
                        Sys.formatNanosElapsed(System.nanoTime() - userRuleBegin));
            }
            OutboundProfiles profiles = gen != null ? gen.outboundProfiles : outConfRef.get();
            SocksConfig standardConf = gen != null ? gen.standardConf : standardConfRef.get();
            RssRuntime.UpstreamSnapshot routes = gen != null ? gen.upstreamSnapshot : null;
            SocksConfig profile = profiles.udp(e.getUser(), dstEp.getPort());
            UpstreamSupport next = selectCompatibleUpstream(routes, currentConf, e, profile, false, sourceSteeringEnabled.get());
            e.setUpstream(createUdpRouteUpstream(dstEp, outboundForRoute(profile, standardConf, next, false), next));
        });
        return new RssRuntime.RssInServer(inSvr, inConf, outConfRef, standardConfRef, sourceSteeringEnabled);
    }

    static RouteAction matchUserRoute(TrafficUser user, String host, int dstPort, InetSocketAddress srcEp, RssClientConf conf) {
        UserRuleMatcher matcher = user instanceof ShadowUser ? ((ShadowUser) user).getRouteMatcher() : null;
        if (matcher == null && conf != null) {
            matcher = conf.defaultRouteMatcher;
        }
        return matchRoute(matcher, host, dstPort, srcEp);
    }

    static UpstreamSupport selectCompatibleUpstream(RssRuntime.UpstreamSnapshot routes, RssClientConf conf,
            SocksContext e, SocksConfig profile, boolean tcp, boolean allowSourceSteering) {
        InetAddress srcHost = sourceAddress(e.getSource());
        InetSocketAddress dstEp = e.getFirstDestination();
        if (routes == null) {
            throw new InvalidException("No socks upstream snapshot available for {}", dstEp);
        }
        RandomList<UpstreamSupport> servers = resolveUserSocksServers(routes.socksServers, routes.userSocksServers, e.getUser());
        String userKey = e.getUser() == null || e.getUser().isAnonymous() ? "" : e.getUser().getUsername();
        RandomList<UpstreamSupport> compatible = compatibleUpstreams(routes, servers, userKey, profile, tcp);
        if (compatible.isEmpty()) {
            String username = e.getUser() == null ? "anonymous" : e.getUser().getUsername();
            throw new InvalidException("No compatible socks upstream user={} src={} dst={} tcp={}", username, srcHost, dstEp, tcp);
        }
        UpstreamSupport.Capability capability = tcp ? UpstreamSupport.Capability.TCP : UpstreamSupport.Capability.UDP;
        boolean preferAdaptiveUdp = tcp && profile.isAdaptiveTcp();
        UpstreamSupport next;
        try {
            next = nextUpstream(compatible, srcHost, dstEp, allowSourceSteering, sourceSteeringTtl(e.getUser(), conf),
                    capability, preferAdaptiveUdp);
        } catch (InvalidException ex) {
            String username = e.getUser() == null ? "anonymous" : e.getUser().getUsername();
            throw new InvalidException("No socks upstream user={} src={} dst={}", username, srcHost, dstEp, ex);
        }
        if (conf != null && conf.hasDebugFlag()) {
            log.debug("route upSvr src {} dst {} -> {}", srcHost, dstEp, next.getEndpoint().getEndpoint());
        }
        return next;
    }

    static boolean isCompatibleUpstream(SocksConfig profile, UpstreamSupport support, boolean tcp) {
        if (profile == null || support == null) {
            return false;
        }
        if (!support.isStandardSocks5()) {
            if (support.getUdpClient() != null
                    && ((!profile.isAdaptiveTcp() && tcp) || (!profile.isDailyUdp() && !tcp) || profile.isUdpHop())) {
                return false;
            }
            return true;
        }
        return !profile.isAdaptiveTcp()
                && !profile.isDailyUdp()
                && !profile.getTransportFlags().has(TransportFlags.RSS_PSK_AES);
    }

    static int compatibleCapabilityKey(SocksConfig profile, boolean tcp) {
        boolean requiresRss = profile.isAdaptiveTcp()
                || profile.isDailyUdp()
                || profile.getTransportFlags().has(TransportFlags.RSS_PSK_AES);
        boolean allowsFixedUdp = (tcp ? profile.isAdaptiveTcp() : profile.isDailyUdp()) && !profile.isUdpHop();
        return (tcp ? 1 : 0) | (requiresRss ? 2 : 0) | (allowsFixedUdp ? 4 : 0);
    }

    static RandomList<UpstreamSupport> compatibleUpstreams(RssRuntime.UpstreamSnapshot snapshot,
            RandomList<UpstreamSupport> servers, String userKey, SocksConfig profile, boolean tcp) {
        if (servers == null || servers.isEmpty() || snapshot == null || !snapshot.hasRestrictedUpstreams) {
            return servers;
        }
        String cacheKey = (userKey == null ? "" : userKey) + "#" + compatibleCapabilityKey(profile, tcp);
        return snapshot.filteredSocksServers.computeIfAbsent(cacheKey, k -> {
            List<UpstreamSupport> all = servers.readOnlySnapshot();
            boolean allMatch = true;
            for (UpstreamSupport support : all) {
                if (!isCompatibleUpstream(profile, support, tcp)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                return servers;
            }
            RandomList<UpstreamSupport> filtered = new RandomList<>();
            for (UpstreamSupport support : all) {
                if (isCompatibleUpstream(profile, support, tcp)) {
                    int weight = support.isHealthy() ? Math.max(0, support.getConfiguredWeight()) : 0;
                    filtered.add(support, weight);
                }
            }
            return filtered;
        });
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
        return nextUpstream(socksServers, srcHost, dstEp, allowSourceSteering, steeringTtl,
                UpstreamSupport.Capability.TCP, false);
    }

    static UpstreamSupport nextUpstream(RandomList<UpstreamSupport> socksServers, InetAddress srcHost,
            InetSocketAddress dstEp, boolean allowSourceSteering, int steeringTtl,
            UpstreamSupport.Capability capability, boolean preferAdaptiveUdp) {
        try {
            UpstreamSupport next = allowSourceSteering && srcHost != null && useSourceSteering(steeringTtl, dstEp)
                    ? socksServers.next(srcHost, steeringTtl, true)
                    : socksServers.next();
            if (isPreferredUpstream(next, capability, preferAdaptiveUdp)) {
                return next;
            }
            UpstreamSupport healthy = nextHealthyUpstreamForCapability(socksServers, capability, preferAdaptiveUdp);
            if (healthy != null) {
                return healthy;
            }
            if (preferAdaptiveUdp) {
                // Adaptive UDP degradation falls back to healthy TCP without taking TCP offline.
                UpstreamSupport tcpFallback = nextHealthyUpstreamForCapability(socksServers, capability, false);
                if (tcpFallback != null) {
                    return tcpFallback;
                }
            }
            throw new NoSuchElementException();
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

    private static boolean isPreferredUpstream(UpstreamSupport support, UpstreamSupport.Capability capability,
            boolean preferAdaptiveUdp) {
        if (support == null || !support.isCapabilityHealthy(capability)) {
            return false;
        }
        return !preferAdaptiveUdp || support.isCapabilityHealthy(UpstreamSupport.Capability.ADAPTIVE_UDP);
    }

    static UpstreamSupport nextHealthyUpstreamForCapability(RandomList<UpstreamSupport> socksServers,
            UpstreamSupport.Capability capability, boolean preferAdaptiveUdp) {
        if (socksServers == null || socksServers.isEmpty()) {
            return null;
        }
        List<UpstreamSupport> snapshot = socksServers.readOnlySnapshot();
        int totalWeight = 0;
        for (UpstreamSupport candidate : snapshot) {
            if (isPreferredUpstream(candidate, capability, preferAdaptiveUdp)) {
                int w = runtimeWeight(socksServers, candidate);
                if (w > 0) {
                    totalWeight += w;
                }
            }
        }
        if (totalWeight <= 0) {
            return null;
        }
        int pick = ThreadLocalRandom.current().nextInt(totalWeight);
        for (UpstreamSupport candidate : snapshot) {
            if (!isPreferredUpstream(candidate, capability, preferAdaptiveUdp)) {
                continue;
            }
            int w = runtimeWeight(socksServers, candidate);
            if (w <= 0) {
                continue;
            }
            pick -= w;
            if (pick < 0) {
                return candidate;
            }
        }
        return null;
    }

    private static int runtimeWeight(RandomList<UpstreamSupport> servers, UpstreamSupport candidate) {
        try {
            return Math.max(0, servers.getWeight(candidate));
        } catch (NoSuchElementException ignored) {
            return 0;
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
        DiagnosticMetrics.record("rss.upstream.fail_open.count", 1D, "endpoint=" + next.getEndpoint().getEndpoint());
        log.warn("rss upstream fail-open src {} -> {} healthFailures={}",
                srcHost, next.getEndpoint().getEndpoint(), next.getHealthFailureCount());
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

    static UpstreamSupport nextHealthyRpcUpstream(RandomList<UpstreamSupport> socksServers) {
        if (socksServers == null || socksServers.isEmpty()) {
            return null;
        }
        int size = socksServers.size();
        for (int i = 0; i < size; i++) {
            UpstreamSupport next = socksServers.next();
            if (next.getFacade() != null && next.isCapabilityHealthy(UpstreamSupport.Capability.RPC)) {
                return next;
            }
        }
        UpstreamSupport fallback = null;
        for (UpstreamSupport candidate : socksServers.readOnlySnapshot()) {
            if (candidate == null || candidate.getFacade() == null) {
                continue;
            }
            if (candidate.isCapabilityHealthy(UpstreamSupport.Capability.RPC)) {
                return candidate;
            }
            if (fallback == null && candidate.isHealthy()) {
                fallback = candidate;
            }
        }
        return fallback;
    }

    static Upstream createUdpRouteUpstream(InetSocketAddress dstEp, SocksConfig config, UpstreamSupport next) {
        if (next == null) {
            return new Upstream(dstEp);
        }
        return new SocksUdpUpstream(dstEp, config, next);
    }

    static SocksConfig standardSocksConfig(RssClientConf conf) {
        SocksConfig config = new SocksConfig();
        config.setOptimalSettings(RssSupport.OUT_OPS);
        configureOutboundConfig(conf, config);
        config.setTransportFlags(TransportFlags.NONE.flags());
        config.setEgressCodec(org.rx.net.EgressCodec.NONE);
        config.setUdpHop(false);
        config.setUdpPortHoppingEnabled(false);
        config.setUdpLeasePoolEnabled(false);
        config.setUdpRelayControlMode(org.rx.net.socks.UdpRelayControlMode.SOCKS5_COMPAT);
        return config;
    }

    static SocksConfig outboundForRoute(SocksConfig profile, SocksConfig standard, UpstreamSupport next, boolean tcp) {
        if (!next.isStandardSocks5()) {
            if (next.getUdpClient() != null && (!profile.isAdaptiveTcp() && tcp || !profile.isDailyUdp() && !tcp || profile.isUdpHop())) {
                throw new InvalidException("Fixed UDP mapping requires an enhanced no-hop profile");
            }
            return next instanceof BoundRoute ? ((BoundRoute) next).bind(profile, tcp) : profile;
        }
        if (profile.isAdaptiveTcp() || profile.isDailyUdp() || profile.getTransportFlags().has(TransportFlags.RSS_PSK_AES)) {
            throw new InvalidException("Standard SOCKS5 cannot carry protected or enhanced RSS traffic");
        }
        return standard;
    }

    private static final class BoundRoute extends UpstreamSupport {
        private final AuthenticEndpoint proxy;
        private final org.rx.net.EgressCodec egress;
        private final Map<SocksConfig, SocksConfig> configs = new java.util.IdentityHashMap<>();

        BoundRoute(AuthenticEndpoint peer, SocksRpcContract facade, AuthenticEndpoint proxy, org.rx.net.EgressCodec egress) {
            super(peer, facade);
            this.proxy = proxy;
            this.egress = egress;
        }

        SocksConfig bind(SocksConfig base, boolean tcp) {
            if (proxy == null && (!tcp || egress == null || egress == base.getEgressCodec())) {
                return base;
            }
            synchronized (this) {
                SocksConfig config = configs.get(base);
                if (config == null) {
                    config = Sys.deepClone(base);
                    if (egress != null) {
                        config.setEgressCodec(egress);
                    }
                    if (proxy != null) {
                        config.setSocksCarrierProxy(proxy);
                        config.setUdpLeasePoolEnabled(false);
                        config.setUdpRelayControlMode(org.rx.net.socks.UdpRelayControlMode.RSS_RPC);
                        config.setUdpRelayControlFallbackToSocks5(false);
                        int overhead = org.rx.net.socks.UdpManager.socks5HeaderTemplate(getEndpoint().requireEndpoint()).length();
                        int mtu = config.resolveDailyUdpMtu();
                        config.setSocksCarrierOuterMtu(mtu);
                        if (mtu != 0 && mtu - overhead < 256) {
                            throw new IllegalArgumentException("Path MTU too small for SOCKS5 UDP");
                        }
                        // Even an unset application limit is bounded by the largest outer UDP payload.
                        config.setDailyUdpMtu((mtu > 0 ? mtu : 65507) - overhead);
                    }
                    configs.put(base, config);
                }
                return config;
            }
        }
    }

    static final class OutboundProfiles {
        private final Map<String, Routes> users = new LinkedHashMap<>();
        private final Routes defaults;
        private final RssPortPolicy tcpPorts, udpPorts;

        OutboundProfiles(RssClientConf conf, SocksConfig inbound) {
            tcpPorts = new RssPortPolicy(conf.tcpCipherPorts, conf.tcpCodecBypassPorts);
            udpPorts = new RssPortPolicy(conf.udpCipherPorts, conf.udpCodecBypassPorts);
            SocksConfig base = createOutboundConfig(conf, inbound);
            Map<Long, SocksConfig> tcpCache = new LinkedHashMap<>();
            Map<Long, SocksConfig> udpCache = new LinkedHashMap<>();
            udpCache.put(udpCacheKey(0, true, base.getUdpMtu(), base.getDailyUdpMtu()), base);
            defaults = new Routes(RssTransport.DEFAULT, conf.udpMtu, base, tcpCache, udpCache);
            if (conf.shadowUsers != null) {
                for (ShadowUser u : conf.shadowUsers) {
                    if (u.transport != null) {
                        users.put(u.getUsername(), new Routes(u.transport, conf.udpMtu, base, tcpCache, udpCache));
                    }
                }
            }
        }

        private Routes routes(TrafficUser user) {
            return user instanceof ShadowUser ? users.getOrDefault(user.getUsername(), defaults) : defaults;
        }

        SocksConfig tcp(TrafficUser user, int port) {
            Routes r = routes(user);
            switch (tcpPorts.select(port)) {
                case PROTECTED: return r.protectedTcp;
                case BYPASS: return r.passthrough;
                default: return r.tcp;
            }
        }

        SocksConfig udp(TrafficUser user, int port) {
            Routes r = routes(user);
            switch (udpPorts.select(port)) {
                case PROTECTED: return r.protectedUdp;
                case BYPASS: return r.udpPassthrough;
                default: return r.udp;
            }
        }

        private static long udpCacheKey(int features, boolean hop, int udpMtu, int dailyUdpMtu) {
            return (features & 31L) | (hop ? 0L : 32L)
                    | ((udpMtu & 0xFFFFL) << 8)
                    | ((dailyUdpMtu & 0xFFFFL) << 24);
        }

        private static final class Routes {
            final SocksConfig tcp, protectedTcp, passthrough, udp, protectedUdp, udpPassthrough;

            Routes(RssTransport policy, Integer globalUdpMtu, SocksConfig base,
                   Map<Long, SocksConfig> tcpCache, Map<Long, SocksConfig> udpCache) {
                if (!policy.valid()) {
                    throw new IllegalArgumentException("Invalid RSS user transport");
                }
                boolean adaptive = (policy.fec() & 2) != 0;
                boolean hop = policy.udpHop();
                int mask = policy.tcp();
                Integer explicitMtu = policy.udpMtu != null ? policy.udpMtu : globalUdpMtu;
                int tcpUdpMtu = explicitMtu != null ? explicitMtu : base.getDailyUdpMtu();
                tcp = tcpConfig(base, tcpCache, mask, adaptive, policy, tcpUdpMtu);
                // Protected services may mix secrets and remote input. Compress here only on explicit PSK+zlib policy.
                int protectedMask = policy.tcp != null && mask == 3 ? 3 : 2;
                protectedTcp = tcpConfig(base, tcpCache, protectedMask, adaptive, policy, tcpUdpMtu);
                passthrough = tcpConfig(base, tcpCache, 0, adaptive, policy, tcpUdpMtu);
                int features = policy.udpFeatures();
                udp = udpConfig(base, udpCache, features, hop, explicitMtu);
                protectedUdp = udpConfig(base, udpCache, features | org.rx.net.rss.udp.RssDatagramHandler.AES, hop, explicitMtu);
                udpPassthrough = udpConfig(base, udpCache, features & ~(org.rx.net.rss.udp.RssDatagramHandler.LZ4 | org.rx.net.rss.udp.RssDatagramHandler.AES), hop, explicitMtu);
            }

            private static SocksConfig udpConfig(SocksConfig base, Map<Long, SocksConfig> udpCache,
                                                 int features, boolean hop, Integer explicitMtu) {
                int passthroughMtu = features == 0 ? (explicitMtu != null ? Math.max(0, explicitMtu) : 0) : 0;
                int tunnelMtu = features != 0 ? (explicitMtu != null ? Math.max(0, explicitMtu) : SocksConfig.DEFAULT_DAILY_UDP_MTU) : base.getDailyUdpMtu();
                long slot = udpCacheKey(features, hop, passthroughMtu, tunnelMtu);
                SocksConfig cached = udpCache.get(slot);
                if (cached == null) {
                    SocksConfig c = Sys.deepClone(base);
                    c.setDailyUdpFeatures(features);
                    c.setUdpHop(hop);
                    c.setUdpMtu(passthroughMtu);
                    c.setDailyUdpMtu(tunnelMtu);
                    udpCache.put(slot, cached = c);
                }
                return cached;
            }

            private static SocksConfig tcpConfig(SocksConfig base, Map<Long, SocksConfig> cache, int mask, boolean adaptive, RssTransport policy, int tcpUdpMtu) {
                boolean hop = policy.udpHop();
                int profile = adaptive ? policy.adaptiveProfile() : 0;
                int level = (mask & 1) == 0 ? 0 : policy.compressionLevel();
                org.rx.net.EgressCodec egress = policy.tcpEgressCodec(base.getEgressCodec());
                long key = mask | (adaptive ? 8 : 0) | (profile << 4) | (level << 6)
                        | (hop ? 0 : (1 << 10)) | ((long) tcpUdpMtu << 11) | ((long) egress.ordinal() << 28);
                SocksConfig cached = cache.get(key);
                if (cached == null) {
                    SocksConfig c = Sys.deepClone(base);
                    c.setAdaptiveTcp(adaptive);
                    c.setAdaptiveProfile(profile);
                    c.setDailyUdpMtu(tcpUdpMtu);
                    c.setUdpHop(hop);
                    c.setEgressCodec(egress);
                    if (level != 0) c.setTcpCompressionLevel(level);
                    TransportFlags protection = (mask & 2) != 0 ? TransportFlags.RSS_PSK_AES : TransportFlags.NONE;
                    c.setTransportFlags(protection.flags((mask & 1) != 0 ? TransportFlags.COMPRESS_BOTH : TransportFlags.NONE));
                    cache.put(key, cached = c);
                }
                return cached;
            }
        }
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

        HttpServer defaultHttp = HttpServer.getDefault();
        if (defaultHttp != null) {
            httpServer = defaultHttp.requestAsync(RssClientHttpHandler.SHADOW_USERS_PAGE_PATH,
                    new RssClientHttpHandler(trafficStore, authenticator.getMemoryRetentionHours(), authenticator::getShadowStore));
        }

        configureRrpServer(rssConf);

        configureDdnsSchedule(rssConf);
    }

    static boolean shouldScheduleDdns(RssClientConf conf) {
        return conf != null && conf.ddnsJobSeconds > 0 && (!CollectionUtils.isEmpty(conf.ddnsDomains)
                || conf.ddnsLocalCommand != null && conf.ddnsLocalCommand.length > 0);
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

        ddnsJob.run(conf);
    }

    static String observedWowPublicIp() {
        RssRuntime rt = runtime;
        RssRuntime.UpstreamSnapshot snapshot = rt == null ? null : rt.upstreamSnapshot;
        if (snapshot == null) return null;
        {
            RandomList<UpstreamSupport> group = snapshot.socksServers;
            if (group == null) return null;
            for (UpstreamSupport support : group) {
                SocksRpcContract rpc = support.getFacade();
                if (rpc instanceof ForwardingSocksRpcContract) {
                    WowEndpointPublisher publisher = ((ForwardingSocksRpcContract) rpc).wowPublisher;
                    if (publisher != null) {
                        String ip = publisher.observedPublicIp();
                        if (ip != null) return ip;
                    }
                }
            }
        }
        return null;
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

        String body = requestBody.toString();
        HttpClient.Request req = HttpClient.request(HttpMethod.POST, url)
                .header(HttpHeaderNames.ACCEPT, "application/json")
                .header(HttpHeaderNames.AUTHORIZATION, "Bearer " + apiKey)
                .header("X-Signature", dynadotSign(apiKey, url, body))
                .bytes(body.getBytes(StandardCharsets.UTF_8), "application/json");
        req.timeoutMillis(5000);
        try (HttpClient.Response response = RssSupport.DdnsHttp.CLIENT.execute(req)) {
            JSONObject result = response.bodyAsJson();
            if (response.code() < 200 || response.code() >= 300 || result == null || result.getIntValue("code") != 200)
                throw new IllegalStateException("DDNS provider did not confirm success");
            return result.toString();
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
        req.timeoutMillis(5000);
        try (HttpClient.Response response = RssSupport.DdnsHttp.CLIENT.execute(req)) {
            JSONObject result = response.bodyAsJson();
            if (response.code() < 200 || response.code() >= 300 || result == null || result.getIntValue("code") != 200)
                throw new IllegalStateException("DDNS provider did not confirm records");
            return result;
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

    static AuthenticEndpoint resolveShadowEndpoint(SocketAddress inSvrAddress, String authUserName) {
        AuthenticEndpoint target = new AuthenticEndpoint(inSvrAddress);
        target.getParameters().put(SocksConnectionTagRegistry.PARAM_NAME, authUserName);
        return target;
    }
}
