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
import org.rx.net.transport.TcpServerConfig;

import java.util.Map;

import static org.rx.core.Extends.eq;

@Slf4j
public final class RssServer {
    static HttpServer httpServer;

    private RssServer() {
    }

    public static void launch(Map<String, String> options, int port) {
        Integer udp2rawPort = Reflects.convertQuietly(options.get("udp2rawPort"), Integer.class);
        boolean debugFlag = "1".equals(options.get("debug"));
        AuthenticEndpoint shadowUser = Reflects.convertQuietly(options.get("shadowUser"), AuthenticEndpoint.class);
        if (shadowUser == null) {
            log.info("Invalid shadowUser arg");
            return;
        }
        SocksUser ssUser = new SocksUser(shadowUser.getUsername());
        ssUser.setPassword(shadowUser.getPassword());

        SocksConfig outConf = new SocksConfig(port);
        outConf.setDebug(debugFlag);
        outConf.setWhiteListEnabled(true);
        outConf.setTcpAsyncDnsMode(SocksConfig.TcpAsyncDnsMode.REMOTE);
        outConf.setTransportFlags(TransportFlags.GFW.flags(TransportFlags.COMPRESS_BOTH).flags());
        outConf.setTcpCompressionLevel(RssSupport.TCP_TRIAL_COMPRESSION_LEVEL);
        outConf.setOptimalSettings(RssSupport.OUT_OPS);
        outConf.setUdpRedundantMultiplier(2);
        RssSupport.applyUdpCompressionTrial(outConf);
        Authenticator defAuth = (u, p) -> eq(u, ssUser.getUsername()) && eq(p, ssUser.getPassword()) ? ssUser : SocksUser.ANONYMOUS;
        SocksProxyServer outSvr = new SocksProxyServer(outConf, defAuth);
        outSvr.setCipherRouter(SocksProxyServer.DNS_CIPHER_ROUTER);
        if (udp2rawPort != null && udp2rawPort > 0) {
            SocksConfig outTunConf = Sys.deepClone(outConf);
            outTunConf.setDebug(debugFlag);
            outTunConf.setListenAddress(Sockets.newAnyEndpoint(udp2rawPort));
            outTunConf.setUdpRedundantMultiplier(2);
            RssSupport.applyUdpCompressionTrial(outTunConf);
            SocksProxyServer outUdp2rawSvr = new SocksProxyServer(outTunConf, defAuth);
            outUdp2rawSvr.setCipherRouter(SocksProxyServer.DNS_CIPHER_ROUTER);
        }

        RpcServerConfig rpcConf = new RpcServerConfig(new TcpServerConfig(port + 1));
        rpcConf.getTcpConfig().setTransportFlags(TransportFlags.GFW.flags(TransportFlags.CIPHER_BOTH).flags());
        RssRpcApp app = new RssRpcApp(outSvr);
        Remoting.register(app, rpcConf);
        serverInit();
        app.await();
    }

    static void serverInit() {
        httpServer = HttpServer.getDefault().requestMapping("/getPublicIp", (request, response) ->
                response.jsonBody(request.getRemoteEndpoint().getHostString()))
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
