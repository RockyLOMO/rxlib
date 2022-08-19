package org.rx;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.RandomList;
import org.rx.bean.Tuple;
import org.rx.core.*;
import org.rx.exception.InvalidException;
import org.rx.net.*;
import org.rx.net.dns.DnsClient;
import org.rx.net.dns.DnsServer;
import org.rx.net.http.HttpClient;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.rpc.RpcServerConfig;
import org.rx.net.shadowsocks.ShadowsocksConfig;
import org.rx.net.shadowsocks.ShadowsocksServer;
import org.rx.net.shadowsocks.encryption.CipherKind;
import org.rx.net.socks.*;
import org.rx.net.socks.upstream.Socks5UdpUpstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.*;
import org.rx.net.socks.upstream.Socks5Upstream;
import org.rx.util.function.Action;
import org.rx.util.function.TripleAction;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.rx.core.App.*;
import static org.rx.core.Extends.*;
import static org.rx.core.Tasks.awaitQuietly;

@Slf4j
@RequiredArgsConstructor
public final class Main implements SocksSupport {
    @SneakyThrows
    public static void main(String[] args) {
        Map<String, String> options = App.mainOptions(args);
        Integer port = Reflects.tryConvert(options.get("port"), Integer.class);
        if (port == null) {
            log.info("Invalid port arg");
            return;
        }

        Main app;
        Integer connectTimeout = Reflects.tryConvert(options.get("connectTimeout"), Integer.class, 60000);
        String mode = options.get("shadowMode");

        boolean udp2raw = false;
//        Udp2rawHandler.DEFAULT.setGzipMinLength(Integer.MAX_VALUE);

        if (eq(mode, "1")) {
            AuthenticEndpoint shadowUser = Reflects.tryConvert(options.get("shadowUser"), AuthenticEndpoint.class);
            if (shadowUser == null) {
                log.info("Invalid shadowUser arg");
                return;
            }
            SocksUser ssUser = new SocksUser(shadowUser.getUsername());
            ssUser.setPassword(shadowUser.getPassword());
            ssUser.setMaxIpCount(-1);

            SocksConfig backConf = new SocksConfig(port);
            backConf.setTransportFlags(TransportFlags.FRONTEND_COMPRESS.flags());
            backConf.setMemoryMode(MemoryMode.MEDIUM);
            backConf.setConnectTimeoutMillis(connectTimeout);
            backConf.setEnableUdp2raw(udp2raw);
            SocksProxyServer backSvr = new SocksProxyServer(backConf, (u, p) -> eq(u, ssUser.getUsername()) && eq(p, ssUser.getPassword()) ? ssUser : SocksUser.ANONYMOUS);
            backSvr.setAesRouter(SocksProxyServer.DNS_AES_ROUTER);

            //server port + 1 = rpc
            RpcServerConfig rpcConf = new RpcServerConfig(port + 1);
            rpcConf.setTransportFlags(TransportFlags.FRONTEND_AES_COMBO.flags());
            Remoting.listen(app = new Main(backSvr), rpcConf);
        } else {
            String[] arg1 = Strings.split(options.get("shadowUsers"), ",");
            if (arg1.length == 0) {
                log.info("Invalid shadowUsers arg");
                return;
            }

            RandomList<UpstreamSupport> shadowServers = new RandomList<>();
            SocksConfig frontConf = new SocksConfig(port);
            YamlConfiguration watcher = new YamlConfiguration("conf.yml").enableWatch();
            watcher.onChanged.combine((s, e) -> {
                SSConf changed = s.readAs(SSConf.class);
                if (changed == null) {
                    return;
                }

                conf = changed;
                Linq<AuthenticEndpoint> svrs = Linq.from(conf.shadowServer).select(p -> Reflects.tryConvert(p, AuthenticEndpoint.class));
                if (!svrs.any() || svrs.any(Objects::isNull)) {
                    throw new InvalidException("Invalid shadowServer arg");
                }
                for (UpstreamSupport support : shadowServers) {
                    tryClose(support.getSupport());
                }
                shadowServers.clear();
                for (AuthenticEndpoint shadowServer : svrs) {
                    RpcClientConfig rpcConf = RpcClientConfig.poolMode(Sockets.newEndpoint(shadowServer.getEndpoint(), shadowServer.getEndpoint().getPort() + 1), 2, 6);
                    rpcConf.setTransportFlags(TransportFlags.BACKEND_AES_COMBO.flags());
                    String weight = shadowServer.getParameters().get("w");
                    if (Strings.isEmpty(weight)) {
                        continue;
                    }
                    shadowServers.add(new UpstreamSupport(shadowServer, Remoting.create(SocksSupport.class, rpcConf)), Integer.parseInt(weight));
                }
                log.info("reload svrs {}", toJsonString(svrs));

                if (conf.bypassHosts != null) {
                    frontConf.getBypassList().addAll(conf.bypassHosts);
                }
            });
            watcher.raiseChange();
            Linq<Tuple<ShadowsocksConfig, SocksUser>> shadowUsers = Linq.from(arg1).select(shadowUser -> {
                String[] sArgs = Strings.split(shadowUser, ":", 4);
                ShadowsocksConfig config = new ShadowsocksConfig(Sockets.anyEndpoint(Integer.parseInt(sArgs[0])),
                        CipherKind.AES_256_GCM.getCipherName(), sArgs[1]);
                config.setTcpTimeoutSeconds(conf.tcpTimeoutSeconds);
                config.setUdpTimeoutSeconds(conf.udpTimeoutSeconds);
                SocksUser user = new SocksUser(sArgs[2]);
                user.setPassword(conf.socksPwd);
                user.setMaxIpCount(Integer.parseInt(sArgs[3]));
                return Tuple.of(config, user);
            });

            Integer shadowDnsPort = Reflects.tryConvert(options.get("shadowDnsPort"), Integer.class, 53);
            DnsServer dnsSvr = new DnsServer(shadowDnsPort);
            dnsSvr.setTtl(60 * 60 * 10); //12 hour
            dnsSvr.setShadowServers(shadowServers);
            dnsSvr.addHostsFile("hosts.txt");
            InetSocketAddress shadowDnsEp = Sockets.localEndpoint(shadowDnsPort);
            Sockets.injectNameService(Collections.singletonList(shadowDnsEp));

            frontConf.setTransportFlags(TransportFlags.BACKEND_COMPRESS.flags());
            frontConf.setMemoryMode(MemoryMode.MEDIUM);
            frontConf.setConnectTimeoutMillis(connectTimeout);
            frontConf.setReadTimeoutSeconds(conf.tcpTimeoutSeconds);
            frontConf.setUdpReadTimeoutSeconds(conf.udpTimeoutSeconds);
            frontConf.setEnableUdp2raw(udp2raw);
            frontConf.setUdp2rawServers(Linq.from(shadowServers).select(p -> p.getEndpoint().getEndpoint()).toList());
            if (frontConf.isEnableUdp2raw() && conf.udp2rawEndpoint != null) {
                log.info("udp2rawEndpoint: {}", conf.udp2rawEndpoint);
                AuthenticEndpoint udp2rawSvrEp = AuthenticEndpoint.valueOf(conf.udp2rawEndpoint);
                frontConf.getUdp2rawServers().add(udp2rawSvrEp.getEndpoint());
            }
            SocksProxyServer frontSvr = new SocksProxyServer(frontConf, Authenticator.dbAuth(shadowUsers.select(p -> p.right).toList(), port + 1));
            Upstream shadowDnsUpstream = new Upstream(new UnresolvedEndpoint(shadowDnsEp));
            TripleAction<SocksProxyServer, SocksContext> firstRoute = (s, e) -> {
                UnresolvedEndpoint dstEp = e.getFirstDestination();
                //must first
                if (dstEp.getPort() == SocksSupport.DNS_PORT) {
                    e.setUpstream(shadowDnsUpstream);
                    return;
                }
                //bypass
                if (frontConf.isBypass(dstEp.getHost())) {
                    e.setUpstream(new Upstream(dstEp));
                }
            };
            frontSvr.onRoute.replace(firstRoute, (s, e) -> {
                if (e.getUpstream() != null) {
                    return;
                }
                e.setUpstream(new Socks5Upstream(e.getFirstDestination(), frontConf, () -> shadowServers.next(e.getSource(), conf.steeringTTL, true)));
            });
            frontSvr.onUdpRoute.replace(firstRoute, (s, e) -> {
                if (e.getUpstream() != null) {
                    return;
                }

                UnresolvedEndpoint dstEp = e.getFirstDestination();
//                if (conf.pcap2socks && e.getSource().getAddress().isLoopbackAddress()) {
//                    Cache<String, Boolean> cache = Cache.getInstance(Cache.MEMORY_CACHE);
//                    if (cache.get(hashKey("pcap", e.getSource().getPort()), k -> Sockets.socketInfos(SocketProtocol.UDP)
//                            .any(p -> p.getSource().getPort() == e.getSource().getPort()
//                                    && Strings.startsWith(p.getProcessName(), "pcap2socks")))) {
//                        log.info("pcap2socks forward");
//                        e.setUpstream(new Upstream(dstEp));
//                        return;
//                    }
//                }
//                if (frontConf.isEnableUdp2raw()) {
//                    if (udp2rawSvrEp != null) {
//                        e.setValue(new Upstream(dstEp, udp2rawSvrEp));
//                    } else {
//                        e.setValue(new Upstream(dstEp, shadowServers.next().getEndpoint()));
//                    }
//                    return;
//                }
                e.setUpstream(new Socks5UdpUpstream(dstEp, frontConf, () -> shadowServers.next(e.getSource(), conf.steeringTTL, true)));
            });
            frontSvr.setAesRouter(SocksProxyServer.DNS_AES_ROUTER);
            app = new Main(frontSvr);

            Action fn = () -> {
                InetAddress addr = InetAddress.getByName(IPSearcher.DEFAULT.current().getIp());
                eachQuietly(shadowServers, p -> p.getSupport().addWhiteList(addr));
            };
            fn.invoke();
            Tasks.schedulePeriod(fn, conf.autoWhiteListSeconds * 1000L);

            InetSocketAddress frontSvrEp = Sockets.localEndpoint(port);
            for (Tuple<ShadowsocksConfig, SocksUser> tuple : shadowUsers) {
                ShadowsocksConfig ssConfig = tuple.left;
                SocksUser user = tuple.right;
                AuthenticEndpoint srvEp = new AuthenticEndpoint(frontSvrEp, user.getUsername(), user.getPassword());

                ssConfig.setMemoryMode(MemoryMode.MEDIUM);
                ssConfig.setConnectTimeoutMillis(connectTimeout);
                SocksConfig directConf = new SocksConfig(port);
                frontConf.setMemoryMode(MemoryMode.MEDIUM);
                frontConf.setConnectTimeoutMillis(connectTimeout);
                ShadowsocksServer server = new ShadowsocksServer(ssConfig);
                TripleAction<ShadowsocksServer, SocksContext> ssFirstRoute = (s, e) -> {
                    UnresolvedEndpoint dstEp = e.getFirstDestination();
                    //must first
                    if (dstEp.getPort() == SocksSupport.DNS_PORT) {
                        e.setUpstream(shadowDnsUpstream);
                        return;
                    }
                    //bypass
                    if (ssConfig.isBypass(dstEp.getHost())) {
                        log.info("ss bypass: {}", dstEp);
                        e.setUpstream(new Upstream(dstEp));
                    }
                };
                server.onRoute.replace(ssFirstRoute, (s, e) -> {
                    if (e.getUpstream() != null) {
                        return;
                    }
                    //gateway
                    IPAddress ipAddress = awaitQuietly(() -> IPSearcher.DEFAULT.search(e.getFirstDestination().getHost()), SocksSupport.ASYNC_TIMEOUT / 2);
                    if (ipAddress != null && ipAddress.isChina()) {
                        e.setUpstream(new Upstream(e.getFirstDestination()));
                        return;
                    }
                    e.setUpstream(new Socks5Upstream(e.getFirstDestination(), directConf, () -> new UpstreamSupport(srvEp, null)));
                });
                server.onUdpRoute.replace(ssFirstRoute, (s, e) -> {
                    if (e.getUpstream() != null) {
                        return;
                    }
                    e.setUpstream(new Upstream(e.getFirstDestination(), srvEp));
                });
            }

            app.ddns();
        }

        log.info("Server started..");
        app.await();
    }

    @Data
    public static class SSConf {
        public List<String> shadowServer;
        public String socksPwd;
        public int tcpTimeoutSeconds = 60 * 2;
        public int udpTimeoutSeconds = 60 * 10;
        public int autoWhiteListSeconds;
        public List<String> bypassHosts;
        public int steeringTTL;
        public int ddnsSeconds;
        public List<String> ddnsDomains;
        public String godaddyKey;

        public boolean pcap2socks;
        public String udp2rawEndpoint;
    }

    static SSConf conf;
    final SocksProxyServer proxyServer;

    void ddns() {
        Tasks.schedulePeriod(() -> {
            if (conf == null) {
                log.warn("conf is null");
            }

            InetAddress wanIp = InetAddress.getByName(HttpClient.getWanIp());
            for (String ddns : conf.ddnsDomains) {
                List<InetAddress> currentIps = DnsClient.inlandClient().resolveAll(ddns);
                if (currentIps.contains(wanIp)) {
                    continue;
                }
                int i = ddns.indexOf(".");
                String domain = ddns.substring(i + 1), name = ddns.substring(0, i);
                log.info("ddns-{}.{}: {}->{}", name, domain, currentIps, wanIp);
                HttpClient.godaddyDns(conf.getGodaddyKey(), domain, name, wanIp.getHostAddress());
            }
        }, conf.ddnsSeconds * 1000L);
    }

    @Override
    public void fakeEndpoint(long hash, String endpoint) {
        SocksSupport.fakeDict().putIfAbsent(hash, UnresolvedEndpoint.valueOf(endpoint));
    }

    @Override
    public List<InetAddress> resolveHost(String host) {
        return DnsClient.outlandClient().resolveAll(host);
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
