package org.rx.util.rss;

import org.rx.core.Reflects;
import org.rx.core.Sys;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.TransportFlags;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcServerConfig;
import org.rx.net.socks.Authenticator;
import org.rx.net.socks.RrpConfig;
import org.rx.net.socks.RrpServer;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.SocksUser;
import org.rx.net.socks.encryption.CipherKind;
import org.rx.net.transport.TcpServerConfig;

import java.util.Map;

import static org.rx.core.Extends.eq;

public final class RssServer {
    private RssServer() {
    }

    public static void launch(Map<String, String> options, int port) {
        boolean enableUdp2raw = "1".equals(options.get("udp2raw"));
        int udp2rawPort = port + 10;
        boolean debugFlag = "1".equals(options.get("debug"));
        AuthenticEndpoint shadowUser = Reflects.convertQuietly(options.get("shadowUser"), AuthenticEndpoint.class);
        if (shadowUser == null) {
            org.slf4j.LoggerFactory.getLogger(RssServer.class).info("Invalid shadowUser arg");
            return;
        }
        SocksUser ssUser = new SocksUser(shadowUser.getUsername());
        ssUser.setPassword(shadowUser.getPassword());

        SocksConfig outConf = new SocksConfig(port);
        outConf.setDebug(debugFlag);
        outConf.setWhiteListEnabled(true);
        outConf.setTcpAsyncDnsMode(SocksConfig.TcpAsyncDnsMode.OUTLAND);
        outConf.setTransportFlags(TransportFlags.GFW.flags(TransportFlags.COMPRESS_BOTH).flags());
        outConf.setTcpCompressionLevel(RssSupport.TCP_TRIAL_COMPRESSION_LEVEL);
        outConf.setOptimalSettings(RssSupport.OUT_OPS);
        outConf.setUdpRedundantMultiplier(2);
        RssSupport.applyUdpCompressionTrial(outConf);
        Authenticator defAuth = (u, p) -> eq(u, ssUser.getUsername()) && eq(p, ssUser.getPassword()) ? ssUser : SocksUser.ANONYMOUS;
        SocksProxyServer outSvr = new SocksProxyServer(outConf, defAuth);
        outSvr.setCipherRouter(SocksProxyServer.DNS_CIPHER_ROUTER);
        if (enableUdp2raw) {
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
        RssSupport.serverInit();
        app.await();
    }
}
