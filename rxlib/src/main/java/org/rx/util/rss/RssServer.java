package org.rx.util.rss;

import java.util.Arrays;
import io.netty.handler.codec.http.HttpMethod;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Reflects;
import org.rx.core.RxConfig;
import org.rx.core.Sys;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.NetworkFlowControl;
import org.rx.net.NetworkTrafficConfig;
import org.rx.net.TransportFlags;
import org.rx.net.http.HttpClient;
import org.rx.net.http.HttpServer;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcServerConfig;
import org.rx.net.socks.Authenticator;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.socks.SocksUser;
import org.rx.net.transport.TcpServerConfig;

import java.util.Map;

import static org.rx.core.Extends.eq;

@Slf4j
public final class RssServer {
    static HttpServer httpServer;

    private RssServer() {}

    public static void launch(Map<String, String> options, int port) {
        // The RSS client owns the long-lived answer cache. Coalesce concurrent egress lookups.
        RxConfig.NetConfig net = RxConfig.INSTANCE.getNet();
        net.getDns().setCacheResolverAnswers(false);
        net.setShareTcpAcceptLoop(true);
        if (net.getReactorThreadAmount() <= 0) {
            net.setReactorThreadAmount(1);
        }
        NetworkTrafficConfig limits = net.getGlobalTraffic();
        if (limits.getUdpProcessPendingBytes() == 0) {
            limits.setUdpProcessPendingBytes(4 * 1024 * 1024);
        }
        if (limits.getUdpProcessPendingPackets() == 0) {
            limits.setUdpProcessPendingPackets(4096);
        }
        NetworkFlowControl.refresh();
        if (options.containsKey("udp2rawPort")) {
            throw new IllegalArgumentException("Replace udp2rawPort with fixedUdpPort and configure paired RSS UDP mappings");
        }
        Integer rpcPort = Reflects.convertQuietly(options.get("rpcPort"), Integer.class);
        boolean debugFlag = "1".equals(options.get("debug"));
        AuthenticEndpoint shadowUser = Reflects.convertQuietly(options.get("shadowUser"), AuthenticEndpoint.class);
        if (shadowUser == null) {
            log.info("Invalid shadowUser arg");
            return;
        }
        if (rpcPort != null && (rpcPort <= 0 || rpcPort > 65535)) {
            log.info("Invalid rpcPort arg");
            return;
        }
        SocksUser ssUser = new SocksUser(shadowUser.getUsername());
        ssUser.setPassword(shadowUser.getPassword());

        SocksConfig outConf = new SocksConfig(port);
        configureOutboundConfig(outConf, debugFlag);
        outConf.setFixedUdpPort(Integer.parseInt(options.getOrDefault("fixedUdpPort", "0")));
        if (options.get("udpMtu") != null) {
            int mtu = Integer.parseInt(options.get("udpMtu").trim());
            if (!RssTransport.validUdpMtu(mtu)) {
                throw new IllegalArgumentException("udpMtu must be 0 or 256..65507");
            }
            outConf.setUdpMtu(mtu);
            outConf.setDailyUdpMtu(mtu);
        }
        int compressionLevel = Integer.parseInt(options.getOrDefault("tcpCompressionLevel", "3"));
        if (compressionLevel < 1 || compressionLevel > 9) throw new IllegalArgumentException("tcpCompressionLevel must be 1..9");
        outConf.setTcpCompressionLevel(compressionLevel);
        outConf.setAdaptiveTcp(!"0".equals(options.get("adaptiveTcp")));
        boolean udpHop = !"0".equals(options.get("udpHop")) && !"false".equalsIgnoreCase(options.get("udpHop"));
        outConf.setUdpHop(udpHop);
        outConf.setDailyPortRange(options.getOrDefault("udpHopPorts", "20000-20127"));
        if (udpHop && (outConf.getDailyPortLast() - outConf.getDailyPortFirst() < 3 || outConf.getDailyPortFirst() == 0
                || (outConf.getDailyPortLast() - outConf.getDailyPortFirst()) % 2 == 0)) {
            throw new IllegalArgumentException("udpHopPorts requires at least two pairs of dedicated UDP ports");
        }
        if (!udpHop && (outConf.getDailyPortLast() - outConf.getDailyPortFirst() < 1 || outConf.getDailyPortFirst() == 0)) {
            throw new IllegalArgumentException("udpHopPorts requires at least two dedicated UDP ports");
        }
        outConf.setRequiredTcpCipherPorts(parsePorts(options.get("tcpCipherPorts"), RssTransport.cipherPorts()));
        outConf.setRequiredUdpCipherPorts(parsePorts(options.get("udpCipherPorts"), RssTransport.udpCipherPorts()));
        outConf.setEgressCodec(org.rx.net.EgressCodec.parseServer(options.get("tcpEgressCodec")));
        if (outConf.getRequiredTcpCipherPorts().length != 0 && !org.rx.net.RssHopAes.configured()) {
            throw new IllegalArgumentException("RSS protected TCP ports require RSS_TCP_AES_KEY");
        }
        if (outConf.getRequiredUdpCipherPorts().length != 0 && !org.rx.net.rss.udp.RssDatagramHandler.hasCipherKey()) {
            throw new IllegalArgumentException("RSS protected UDP ports require RSS_UDP_AES_KEY");
        }
        Authenticator defAuth = (u, p) -> eq(u, ssUser.getUsername()) && eq(p, ssUser.getPassword()) ? ssUser : SocksUser.ANONYMOUS;
        configureHopRedirect(outConf);
        SocksProxyServer outSvr = new SocksProxyServer(outConf, defAuth);
        enforceServerDnsPolicy(outSvr);
        int actualRpcPort = rpcPort == null ? port + 1 : rpcPort;
        RpcServerConfig rpcConf = new RpcServerConfig(new TcpServerConfig(actualRpcPort));
        rpcConf.getTcpConfig().setTransportFlags(TransportFlags.GFW.flags(TransportFlags.CIPHER_BOTH).flags());
        rpcConf.getTcpConfig().setConnectTimeoutMillis(SocksRpcContract.FAKE_RECOVER_RPC_TIMEOUT_MILLIS);
        rpcConf.setEventComputeVersion(RpcServerConfig.EVENT_LATEST_COMPUTE);
        rpcConf.getEventBroadcastVersions().add(SocksRpcContract.RPC_EVENT_VERSION);
        WowEndpointRegistry wowEndpoints = WowEndpointRegistry.fromEnvironment();
        RssRpcApp app = new RssRpcApp(outSvr, wowEndpoints);
        java.util.Set<String> sensitiveMethods = new java.util.HashSet<>(WowEndpointRegistry.SENSITIVE_METHODS);
        sensitiveMethods.add("openUdpRelayGroup");
        rpcConf.setSensitiveMethods(sensitiveMethods);
        outSvr.setFakeEndpointResolver(app::recoverFakeEndpoint);
        Remoting.register(app, rpcConf);
        serverInit();
        httpServer.requestAsync("/api/wow/endpoint", wowEndpoints);
        app.await();
    }

    private static void configureHopRedirect(SocksConfig config) {
        if (!config.isUdpHop() || !System.getProperty("os.name", "").startsWith("Linux")) {
            return;
        }
        try {
            if (RssUdpFirewall.start(config.getDailyPortFirst(), config.getDailyPortLast())) {
                config.setRedirectUdpHops(true);
                return;
            }
        } catch (Exception e) {
            log.warn("Cannot install RSS UDP hop rules", e);
        }
        config.setDailyUdpFeatures(0);
        config.setAdaptiveTcp(false);
        log.warn("RSS UDP hop extensions unavailable: check nft, CAP_NET_ADMIN and dedicated port range");
    }

    static void enforceServerDnsPolicy(SocksProxyServer server) {
        SocksConfig config = server.getConfig();
        server.onTcpRoute.replace((s, e) -> {
            if (e.getFirstDestination().getPort() == SocksRpcContract.DNS_PORT
                    && !config.requiresTcpCipher(SocksRpcContract.DNS_PORT)) {
                throw new org.rx.exception.InvalidException("RSS server rejects raw TCP port 53; use authenticated DNS RPC");
            }
            SocksProxyServer.DIRECT_ROUTER.invoke(s, e);
        });
        server.onUdpRoute.replace((s, e) -> {
            if (e.getFirstDestination().getPort() == SocksRpcContract.DNS_PORT
                    && !config.requiresUdpCipher(SocksRpcContract.DNS_PORT)) {
                throw new org.rx.exception.InvalidException("RSS server rejects raw UDP port 53; use authenticated DNS RPC");
            }
            SocksProxyServer.DIRECT_ROUTER.invoke(s, e);
        });
    }

    private static int[] parsePorts(String list, int[] defaults) {
        if (list == null) return defaults;
        return list.trim().isEmpty() ? new int[0] : Arrays.stream(list.split(",", -1))
                .mapToInt(p -> Integer.parseInt(p.trim())).toArray();
    }

    static void configureOutboundConfig(SocksConfig config, boolean debugFlag) {
        config.setDebug(debugFlag);
        config.setReadTimeoutSeconds(0);
        config.setWriteTimeoutSeconds(0);
        config.setTcpIdleTimeoutSeconds(SocksConfig.DEF_READ_TIMEOUT_SECONDS);
        config.setWhiteListEnabled(true);
        config.setTcpAsyncDnsMode(SocksConfig.TcpAsyncDnsMode.REMOTE);
        config.setTransportFlags(TransportFlags.GFW.flags(TransportFlags.COMPRESS_BOTH).flags());
        config.setTcpCompressionLevel(RssSupport.TCP_COMPRESSION_LEVEL);
        config.setOptimalSettings(RssSupport.OUT_OPS);

        config.setUdpMtu(0);
        config.setDailyUdpMtu(SocksConfig.DEFAULT_DAILY_UDP_MTU);
        config.setDailyUdpFeatures(31);
    }

    static void serverInit() {
        httpServer = HttpServer.getDefault().requestMapping("/getPublicIp", (request, response) -> response.jsonBody(request.getRemoteEndpoint().getHostString()))
                .requestAsync("/hf", (request, response) -> {
                    String url = request.getQueryString().getFirst("fu");
                    Integer tm = Reflects.convertQuietly(request.getQueryString().getFirst("tm"), Integer.class);
                    HttpClient.Request req = HttpClient.request(HttpMethod.GET, url);
                    if (tm != null) {
                        req.timeoutMillis(tm);
                    }
                    try (HttpClient.Response remote = RssSupport.MAIN_HTTP_CLIENT.execute(req)) {
                        response.jsonBody(remote.bodyAsJson());
                    }
                });
    }
}
