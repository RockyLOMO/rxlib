package org.rx.util.rss;

import java.util.Arrays;
import org.rx.net.RssHopTls;
import io.netty.handler.codec.http.HttpMethod;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Reflects;
import org.rx.core.RxConfig;
import org.rx.core.Sys;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.NetworkFlowControl;
import org.rx.net.NetworkTrafficConfig;
import org.rx.net.Sockets;
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
import java.util.Collections;

import static org.rx.core.Extends.eq;

@Slf4j
public final class RssServer {
    static final int UDP2RAW_MTU = RssClient.UDP2RAW_MTU;
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
        Integer udp2rawPort = Reflects.convertQuietly(options.get("udp2rawPort"), Integer.class);
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
        outConf.setRssTlsContext(RssHopTls.serverFromEnvironment());
        if (outConf.getRequiredTcpCipherPorts().length != 0 && !org.rx.net.RssHopAes.configured() && outConf.getRssTlsContext() == null) {
            log.warn("RSS protected TCP ports require RSS_TCP_AES_KEY or an explicitly configured TLS transport");
        }
        Authenticator defAuth = (u, p) -> eq(u, ssUser.getUsername()) && eq(p, ssUser.getPassword()) ? ssUser : SocksUser.ANONYMOUS;
        configureHopRedirect(outConf);
        SocksProxyServer outSvr = new SocksProxyServer(outConf, defAuth);
        SocksProxyServer outUdp2rawSvr = null;
        if (udp2rawPort != null && udp2rawPort > 0) {
            SocksConfig outTunConf = Sys.deepClone(outConf);
            outTunConf.setRssTlsContext(outConf.getRssTlsContext());
            configureUdp2rawOutboundConfig(outTunConf, debugFlag, udp2rawPort);
            outUdp2rawSvr = new SocksProxyServer(outTunConf, defAuth);
        }

        int actualRpcPort = rpcPort == null ? port + 1 : rpcPort;
        RpcServerConfig rpcConf = new RpcServerConfig(new TcpServerConfig(actualRpcPort));
        rpcConf.getTcpConfig().setTransportFlags(TransportFlags.GFW.flags(TransportFlags.CIPHER_BOTH).flags());
        rpcConf.getTcpConfig().setConnectTimeoutMillis(SocksRpcContract.FAKE_RECOVER_RPC_TIMEOUT_MILLIS);
        rpcConf.setEventComputeVersion(RpcServerConfig.EVENT_LATEST_COMPUTE);
        rpcConf.getEventBroadcastVersions().add(SocksRpcContract.RPC_EVENT_VERSION);
        WowEndpointRegistry wowEndpoints = WowEndpointRegistry.fromEnvironment();
        RssRpcApp app = new RssRpcApp(outSvr, outUdp2rawSvr, wowEndpoints);
        java.util.Set<String> sensitiveMethods = new java.util.HashSet<>(WowEndpointRegistry.SENSITIVE_METHODS);
        sensitiveMethods.add("openUdpRelayGroup");
        rpcConf.setSensitiveMethods(sensitiveMethods);
        outSvr.setFakeEndpointResolver(app::recoverFakeEndpoint);
        if (outUdp2rawSvr != null) {
            outUdp2rawSvr.setFakeEndpointResolver(app::recoverFakeEndpoint);
        }
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
        config.setDailyUdpMtu(UDP2RAW_MTU);
        config.setDailyUdpFeatures(31);
    }

    static void configureUdp2rawOutboundConfig(SocksConfig config, boolean debugFlag, int udp2rawPort) {
        configureOutboundConfig(config, debugFlag);
        config.setUdpMtu(UDP2RAW_MTU);
        config.setListenAddress(Sockets.newAnyEndpoint(udp2rawPort));
        // RSS Server 的独立 udp2raw 端口必须开启隧道服务能力，客户端 RPC open 才能成功。
        config.setEnableUdp2raw(true);
        RssSupport.configureUdp2raw(config);
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
