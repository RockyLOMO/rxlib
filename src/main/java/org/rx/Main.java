package org.rx;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.RandomList;
import org.rx.bean.SUID;
import org.rx.bean.Tuple;
import org.rx.core.*;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.MemoryMode;
import org.rx.net.Sockets;
import org.rx.net.dns.DnsClient;
import org.rx.net.dns.DnsServer;
import org.rx.net.http.HttpClient;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.rpc.RpcServerConfig;
import org.rx.net.shadowsocks.ShadowsocksConfig;
import org.rx.net.shadowsocks.ShadowsocksServer;
import org.rx.net.shadowsocks.encryption.CipherKind;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.TransportFlags;
import org.rx.net.socks.upstream.DirectUpstream;
import org.rx.net.support.SocksSupport;
import org.rx.net.socks.upstream.Socks5Upstream;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.util.function.Action;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import static org.rx.core.App.eq;

@Slf4j
@RequiredArgsConstructor
public final class Main implements SocksSupport {
    @SneakyThrows
    public static void main(String[] args) {
        Map<String, String> options = App.argsOptions(args);
        Tuple<Boolean, Integer> port = Reflects.tryConvert(options.get("port"), Integer.class);
        if (port.right == null) {
            log.info("Invalid port arg");
            return;
        }

        Main app;
        Tuple<Boolean, Integer> connectTimeout = Reflects.tryConvert(options.get("connectTimeout"), Integer.class, 60000);
        String mode = options.get("shadowMode");
        if (eq(mode, "1")) {
            Tuple<Boolean, AuthenticEndpoint> shadowUser = Reflects.tryConvert(options.get("shadowUser"), AuthenticEndpoint.class);
            if (shadowUser.right == null) {
                System.out.println("Invalid shadowUser arg");
                return;
            }
            AuthenticEndpoint auth = shadowUser.right;

            SocksConfig backConf = new SocksConfig(port.right);
            backConf.setTransportFlags(TransportFlags.FRONTEND_COMPRESS.flags());
            backConf.setMemoryMode(MemoryMode.MEDIUM);
            backConf.setConnectTimeoutMillis(connectTimeout.right);
            SocksProxyServer backSvr = new SocksProxyServer(backConf, (u, p) -> eq(u, auth.getUsername()) && eq(p, auth.getPassword()), null);
            backSvr.setAesRouter(SocksProxyServer.DNS_AES_ROUTER);

            RpcServerConfig rpcConf = new RpcServerConfig(port.right + 1);
            rpcConf.setTransportFlags(TransportFlags.FRONTEND_AES_COMBO.flags());
            Remoting.listen(app = new Main(backSvr), rpcConf);
        } else {
            NQuery<Tuple<Boolean, AuthenticEndpoint>> q = NQuery.of(Strings.split(options.get("shadowServer"), ",")).select(p -> Reflects.tryConvert(p, AuthenticEndpoint.class));
            if (q.any(p -> p.right == null)) {
                log.info("Invalid shadowServer arg");
                return;
            }
            RandomList<AuthenticEndpoint> shadowServers = new RandomList<>();
            shadowServers.addAll(q.select(p -> p.right).toList());

            SocksConfig frontConf = new SocksConfig(port.right);
            frontConf.setTransportFlags(TransportFlags.BACKEND_COMPRESS.flags());
            frontConf.setMemoryMode(MemoryMode.MEDIUM);
            frontConf.setConnectTimeoutMillis(connectTimeout.right);
            SocksProxyServer frontSvr = new SocksProxyServer(frontConf, null, dstEp -> new Socks5Upstream(dstEp, frontConf, shadowServers));
            frontSvr.setAesRouter(SocksProxyServer.DNS_AES_ROUTER);

            app = new Main(frontSvr);
            RpcClientConfig rpcConf = RpcClientConfig.poolMode(Sockets.newEndpoint(shadowServer.right.getEndpoint(), shadowServer.right.getEndpoint().getPort() + 1), 2, 4);
            rpcConf.setTransportFlags(TransportFlags.BACKEND_AES_COMBO.flags());
            SocksSupport support = Remoting.create(SocksSupport.class, rpcConf);
            frontSvr.setSupport(support);

            Tuple<Boolean, Integer> shadowDnsPort = Reflects.tryConvert(options.get("shadowDnsPort"), Integer.class, 53);
            DnsServer frontDnsSvr = new DnsServer(shadowDnsPort.right);
            frontDnsSvr.setSupport(support);

            Action fn = () -> {
                InetAddress addr = InetAddress.getByName(HttpClient.getWanIp());
                if (!frontConf.getWhiteList().add(addr)) {
                    return;
                }
                support.addWhiteList(addr);
            };
            fn.invoke();
            Tasks.schedule(fn, 30 * 1000);

            ShadowsocksConfig ssConfig = new ShadowsocksConfig(Sockets.getAnyEndpoint(port.right + 1),
                    CipherKind.AES_128_GCM.getCipherName(), shadowServer.right.getPassword());
            ssConfig.setMemoryMode(MemoryMode.MEDIUM);
            ssConfig.setConnectTimeoutMillis(connectTimeout.right);
            SocksConfig directConf = new SocksConfig(port.right);
            frontConf.setMemoryMode(MemoryMode.MEDIUM);
            frontConf.setConnectTimeoutMillis(connectTimeout.right);
            UnresolvedEndpoint loopbackDns = new UnresolvedEndpoint("127.0.0.1", 53);
            ShadowsocksServer server = new ShadowsocksServer(ssConfig, dstEp -> {
                if (dstEp.equals(loopbackDns)) {
                    return new DirectUpstream(new UnresolvedEndpoint(dstEp.getHost(), shadowDnsPort.right));
                }
                return new Socks5Upstream(dstEp, directConf, new AuthenticEndpoint(String.format("127.0.0.1:%s", port.right)));
            });
        }

        log.info("Server started..");
        app.await();
    }

    final SocksProxyServer proxyServer;
    final DnsClient outlandClient = DnsClient.outlandClient();

    @Override
    public void fakeEndpoint(SUID hash, String endpoint) {
        SocksSupport.fakeDict().put(hash, UnresolvedEndpoint.valueOf(endpoint));
    }

    @Override
    public List<InetAddress> resolveHost(String host) {
        return outlandClient.resolveAll(host);
    }

    @Override
    public void addWhiteList(InetAddress endpoint) {
        proxyServer.getConfig().getWhiteList().add(endpoint);
    }

    @SneakyThrows
    synchronized void await() {
        wait();
    }
}
