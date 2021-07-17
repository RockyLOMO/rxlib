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
import org.rx.net.support.UpstreamSupport;
import org.rx.util.function.Action;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.rx.core.App.eq;

@Slf4j
@RequiredArgsConstructor
public final class Main implements SocksSupport {
    @SneakyThrows
    public static void main(String[] args) {
        Map<String, String> options = App.argsOptions(args);
        Integer port = Reflects.tryConvert(options.get("port"), Integer.class);
        if (port == null) {
            log.info("Invalid port arg");
            return;
        }

        Main app;
        Integer connectTimeout = Reflects.tryConvert(options.get("connectTimeout"), Integer.class, 60000);
        String mode = options.get("shadowMode");
        if (eq(mode, "1")) {
            AuthenticEndpoint shadowUser = Reflects.tryConvert(options.get("shadowUser"), AuthenticEndpoint.class);
            if (shadowUser == null) {
                System.out.println("Invalid shadowUser arg");
                return;
            }

            SocksConfig backConf = new SocksConfig(port);
            backConf.setTransportFlags(TransportFlags.FRONTEND_COMPRESS.flags());
            backConf.setMemoryMode(MemoryMode.MEDIUM);
            backConf.setConnectTimeoutMillis(connectTimeout);
            SocksProxyServer backSvr = new SocksProxyServer(backConf, (u, p) -> eq(u, shadowUser.getUsername()) && eq(p, shadowUser.getPassword()), null);
            backSvr.setAesRouter(SocksProxyServer.DNS_AES_ROUTER);

            RpcServerConfig rpcConf = new RpcServerConfig(port + 1);
            rpcConf.setTransportFlags(TransportFlags.FRONTEND_AES_COMBO.flags());
            Remoting.listen(app = new Main(backSvr), rpcConf);
        } else {
            NQuery<AuthenticEndpoint> q = NQuery.of(Strings.split(options.get("shadowServer"), ",")).select(p -> Reflects.tryConvert(p, AuthenticEndpoint.class));
            if (!q.any() || q.any(Objects::isNull)) {
                log.info("Invalid shadowServer arg");
                return;
            }

            RandomList<UpstreamSupport> shadowServers = new RandomList<>(q.select(shadowServer -> {
                RpcClientConfig rpcConf = RpcClientConfig.poolMode(Sockets.newEndpoint(shadowServer.getEndpoint(), shadowServer.getEndpoint().getPort() + 1), 2, 6);
                rpcConf.setTransportFlags(TransportFlags.BACKEND_AES_COMBO.flags());
                return new UpstreamSupport(shadowServer, Remoting.create(SocksSupport.class, rpcConf));
            }).toList());

            SocksConfig frontConf = new SocksConfig(port);
            frontConf.setTransportFlags(TransportFlags.BACKEND_COMPRESS.flags());
            frontConf.setMemoryMode(MemoryMode.MEDIUM);
            frontConf.setConnectTimeoutMillis(connectTimeout);
            SocksProxyServer frontSvr = new SocksProxyServer(frontConf, null, dstEp -> new Socks5Upstream(dstEp, frontConf, shadowServers));
            frontSvr.setAesRouter(SocksProxyServer.DNS_AES_ROUTER);
            app = new Main(frontSvr);

            Integer shadowDnsPort = Reflects.tryConvert(options.get("shadowDnsPort"), Integer.class, 53);
            DnsServer frontDnsSvr = new DnsServer(shadowDnsPort);
            frontDnsSvr.setTtl(60 * 60 * 12); //12 hour
            frontDnsSvr.setSupport(shadowServers);
            Sockets.injectNameService(Sockets.localEndpoint(shadowDnsPort));

            Action fn = () -> {
                InetAddress addr = InetAddress.getByName(HttpClient.getWanIp());
                if (!frontConf.getWhiteList().add(addr)) {
                    return;
                }
                for (UpstreamSupport shadowServer : shadowServers) {
                    shadowServer.getSupport().addWhiteList(addr);
                }
            };
            fn.invoke();
            Tasks.schedule(fn, 120 * 1000);

            ShadowsocksConfig ssConfig = new ShadowsocksConfig(Sockets.anyEndpoint(port + 1),
                    CipherKind.AES_128_GCM.getCipherName(), shadowServers.next().getEndpoint().getPassword());
            ssConfig.setMemoryMode(MemoryMode.MEDIUM);
            ssConfig.setConnectTimeoutMillis(connectTimeout);
            SocksConfig directConf = new SocksConfig(port);
            frontConf.setMemoryMode(MemoryMode.MEDIUM);
            frontConf.setConnectTimeoutMillis(connectTimeout);
            ShadowsocksServer server = new ShadowsocksServer(ssConfig, dstEp -> {
                //must first
                if (dstEp.getPort() == SocksSupport.DNS_PORT) {
                    return new DirectUpstream(new UnresolvedEndpoint(dstEp.getHost(), shadowDnsPort));
                }
                if (ssConfig.isBypass(dstEp.getHost())) {
                    return new DirectUpstream(dstEp);
                }

                return new Socks5Upstream(dstEp, directConf, new AuthenticEndpoint(String.format("127.0.0.1:%s", port)));
            });
        }

        log.info("Server started..");
        app.await();
    }

    final SocksProxyServer proxyServer;
    final DnsClient outlandClient = DnsClient.outlandClient();

    @Override
    public void fakeEndpoint(SUID hash, String endpoint) {
        SocksSupport.fakeDict().putIfAbsent(hash, UnresolvedEndpoint.valueOf(endpoint));
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
