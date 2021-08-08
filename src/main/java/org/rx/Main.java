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
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.rpc.RpcServerConfig;
import org.rx.net.shadowsocks.ShadowsocksConfig;
import org.rx.net.shadowsocks.ShadowsocksServer;
import org.rx.net.shadowsocks.encryption.CipherKind;
import org.rx.net.socks.*;
import org.rx.net.TransportFlags;
import org.rx.net.socks.upstream.UdpProxyUpstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.*;
import org.rx.net.socks.upstream.Socks5Upstream;
import org.rx.util.function.Action;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.rx.core.App.eq;
import static org.rx.core.Tasks.awaitQuietly;

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
            SocksUser ssUser = new SocksUser(shadowUser.getUsername());
            ssUser.setPassword(shadowUser.getPassword());
            ssUser.setMaxIpCount(-1);

            SocksConfig backConf = new SocksConfig(port);
            backConf.setTransportFlags(TransportFlags.FRONTEND_COMPRESS.flags());
            backConf.setMemoryMode(MemoryMode.MEDIUM);
            backConf.setConnectTimeoutMillis(connectTimeout);
            SocksProxyServer backSvr = new SocksProxyServer(backConf, (u, p) -> eq(u, ssUser.getUsername()) && eq(p, ssUser.getPassword()) ? ssUser : SocksUser.ANONYMOUS, null);
            backSvr.setAesRouter(SocksProxyServer.DNS_AES_ROUTER);

            //server port + 1 = rpc
            RpcServerConfig rpcConf = new RpcServerConfig(port + 1);
            rpcConf.setTransportFlags(TransportFlags.FRONTEND_AES_COMBO.flags());
            Remoting.listen(app = new Main(backSvr), rpcConf);
        } else {
            NQuery<AuthenticEndpoint> arg0 = NQuery.of(Strings.split(options.get("shadowServer"), ",")).select(p -> Reflects.tryConvert(p, AuthenticEndpoint.class));
            if (!arg0.any() || arg0.any(Objects::isNull)) {
                log.info("Invalid shadowServer arg");
                return;
            }
            String[] arg1 = Strings.split(options.get("shadowUsers"), ",");
            if (arg1.length == 0) {
                log.info("Invalid shadowUsers arg");
                return;
            }

            RandomList<UpstreamSupport> shadowServers = new RandomList<>(arg0.select(shadowServer -> {
                RpcClientConfig rpcConf = RpcClientConfig.poolMode(Sockets.newEndpoint(shadowServer.getEndpoint(), shadowServer.getEndpoint().getPort() + 1), 2, 6);
                rpcConf.setTransportFlags(TransportFlags.BACKEND_AES_COMBO.flags());
                return new UpstreamSupport(shadowServer, Remoting.create(SocksSupport.class, rpcConf));
            }).toList());
            NQuery<Tuple<ShadowsocksConfig, SocksUser>> shadowUsers = NQuery.of(arg1).select(shadowUser -> {
                String[] sArgs = Strings.split(shadowUser, ":", 4);
                ShadowsocksConfig config = new ShadowsocksConfig(Sockets.anyEndpoint(Integer.parseInt(sArgs[0])),
                        CipherKind.AES_128_GCM.getCipherName(), sArgs[1]);
                SocksUser user = new SocksUser(sArgs[2]);
                user.setPassword("202002");
                user.setMaxIpCount(Integer.parseInt(sArgs[3]));
                return Tuple.of(config, user);
            });

            SocksConfig frontConf = new SocksConfig(port);
            frontConf.setTransportFlags(TransportFlags.BACKEND_COMPRESS.flags());
            frontConf.setMemoryMode(MemoryMode.MEDIUM);
            frontConf.setConnectTimeoutMillis(connectTimeout);
            SocksProxyServer frontSvr = new SocksProxyServer(frontConf,
                    Authenticator.dbAuth(shadowUsers.select(p -> p.right).toList(), port + 1),
                    dstEp -> new Socks5Upstream(dstEp, frontConf, shadowServers),
                    dstEp -> new UdpProxyUpstream(dstEp, shadowServers));
            frontSvr.setAesRouter(SocksProxyServer.DNS_AES_ROUTER);
            app = new Main(frontSvr);

            Integer shadowDnsPort = Reflects.tryConvert(options.get("shadowDnsPort"), Integer.class, 53);
            DnsServer dnsSvr = new DnsServer(shadowDnsPort);
            dnsSvr.setTtl(60 * 60 * 12); //12 hour
            dnsSvr.setSupport(shadowServers);
            dnsSvr.addHostsFile("hosts.txt");
            Sockets.injectNameService(Sockets.localEndpoint(shadowDnsPort));

            Action fn = () -> {
                InetAddress addr = InetAddress.getByName(IPSearcher.DEFAULT.current().getIp());
                for (UpstreamSupport shadowServer : shadowServers) {
                    shadowServer.getSupport().addWhiteList(addr);
                }
            };
            fn.invoke();
            Tasks.schedule(fn, 60 * 4 * 1000);

            for (Tuple<ShadowsocksConfig, SocksUser> tuple : shadowUsers) {
                ShadowsocksConfig ssConfig = tuple.left;
                ssConfig.setMemoryMode(MemoryMode.MEDIUM);
                ssConfig.setConnectTimeoutMillis(connectTimeout);
                SocksConfig directConf = new SocksConfig(port);
                frontConf.setMemoryMode(MemoryMode.MEDIUM);
                frontConf.setConnectTimeoutMillis(connectTimeout);
                ShadowsocksServer server = new ShadowsocksServer(ssConfig, dstEp -> {
                    //must first
                    if (dstEp.getPort() == SocksSupport.DNS_PORT) {
                        return new Upstream(new UnresolvedEndpoint(dstEp.getHost(), shadowDnsPort));
                    }
                    //bypass
                    if (ssConfig.isBypass(dstEp.getHost())) {
                        return new Upstream(dstEp);
                    }
                    //gateway
                    IPAddress ipAddress = awaitQuietly(() -> IPSearcher.DEFAULT.search(dstEp.getHost()), SocksSupport.ASYNC_TIMEOUT / 2);
                    if (ipAddress != null && ipAddress.isChina()) {
                        return new Upstream(dstEp);
                    }
                    SocksUser user = tuple.right;
                    return new Socks5Upstream(dstEp, directConf,
                            new AuthenticEndpoint(Sockets.localEndpoint(port), user.getUsername(), user.getPassword()));
                });
            }
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
