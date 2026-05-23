package org.rx.util.rss;

import io.netty.handler.codec.http.HttpMethod;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Reflects;
import org.rx.core.Sys;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.TransportFlags;
import org.rx.net.http.HttpClient;
import org.rx.net.http.HttpServer;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcServerConfig;
import org.rx.net.socks.Authenticator;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.SocksUser;
import org.rx.net.udp.UdpPortHoppingMode;
import org.rx.net.udp.UdpRedundantMode;
import org.rx.net.transport.TcpServerConfig;

import java.util.Map;

import static org.rx.core.Extends.eq;

@Slf4j
public final class RssServer {
    static final int UDP2RAW_MTU = RssClient.UDP2RAW_MTU;
    static HttpServer httpServer;

    private RssServer() {}

    public static void launch(Map<String, String> options, int port) {
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
        Authenticator defAuth = (u, p) -> eq(u, ssUser.getUsername()) && eq(p, ssUser.getPassword()) ? ssUser : SocksUser.ANONYMOUS;
        SocksProxyServer outSvr = new SocksProxyServer(outConf, defAuth);
        outSvr.setCipherRouter(SocksProxyServer.DNS_CIPHER_ROUTER);
        SocksProxyServer outUdp2rawSvr = null;
        if (udp2rawPort != null && udp2rawPort > 0) {
            SocksConfig outTunConf = Sys.deepClone(outConf);
            configureUdp2rawOutboundConfig(outTunConf, debugFlag, udp2rawPort);
            outUdp2rawSvr = new SocksProxyServer(outTunConf, defAuth);
            outUdp2rawSvr.setCipherRouter(SocksProxyServer.DNS_CIPHER_ROUTER);
        }

        int actualRpcPort = rpcPort == null ? port + 1 : rpcPort;
        RpcServerConfig rpcConf = new RpcServerConfig(new TcpServerConfig(actualRpcPort));
        rpcConf.getTcpConfig().setTransportFlags(TransportFlags.GFW.flags(TransportFlags.CIPHER_BOTH).flags());
        RssRpcApp app = new RssRpcApp(outSvr, outUdp2rawSvr);
        Remoting.register(app, rpcConf);
        serverInit();
        app.await();
    }

    static void configureOutboundConfig(SocksConfig config, boolean debugFlag) {
        config.setDebug(debugFlag);
        config.setWhiteListEnabled(true);
        config.setTcpAsyncDnsMode(SocksConfig.TcpAsyncDnsMode.REMOTE);
        config.setTransportFlags(TransportFlags.GFW.flags(TransportFlags.COMPRESS_BOTH).flags());
        config.setTcpCompressionLevel(RssSupport.TCP_TRIAL_COMPRESSION_LEVEL);
        config.setOptimalSettings(RssSupport.OUT_OPS);

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
        RssSupport.applyUdpCompressionTrial(config);
    }

    static void configureUdp2rawOutboundConfig(SocksConfig config, boolean debugFlag, int udp2rawPort) {
        configureOutboundConfig(config, debugFlag);
        config.setListenAddress(Sockets.newAnyEndpoint(udp2rawPort));
        // RSS Server 的独立 udp2raw 端口必须开启隧道服务能力，客户端 RPC open 才能成功。
        config.setEnableUdp2raw(true);
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
