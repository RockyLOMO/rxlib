package org.rx.util.rss;

import io.netty.channel.local.LocalAddress;
import lombok.SneakyThrows;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.RandomList;
import org.rx.bean.Tuple;
import org.rx.core.Linq;
import org.rx.core.Reflects;
import org.rx.core.Sys;
import org.rx.core.Tasks;
import org.rx.core.YamlConfiguration;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.TransportFlags;
import org.rx.net.dns.DnsClient;
import org.rx.net.dns.DnsServer;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.socks.ShadowsocksConfig;
import org.rx.net.socks.ShadowsocksServer;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.socks.UdpCompressCodec;
import org.rx.net.socks.UdpCompressConfig;
import org.rx.net.socks.encryption.CipherKind;
import org.rx.net.socks.upstream.SocksTcpUpstream;
import org.rx.net.socks.upstream.SocksUdpUpstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.GeoManager;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;
import org.rx.net.transport.TcpClientConfig;
import org.rx.util.function.Action;
import org.rx.util.function.TripleAction;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.rx.core.Extends.eachQuietly;
import static org.rx.core.Extends.eq;
import static org.rx.core.Extends.tryClose;
import static org.rx.core.Sys.toJsonString;

public final class RssClient {
    private RssClient() {
    }

    @SneakyThrows
    public static void launch(Map<String, String> options, int port) {
        boolean enableUdp2raw = "1".equals(options.get("udp2raw"));
        int udp2rawPort = port + 10;
        RandomList<UpstreamSupport> socksServers = new RandomList<>();
        RandomList<UpstreamSupport> udp2rawSocksServers = new RandomList<>();
        RandomList<DnsServer.ResolveInterceptor> dnsInterceptors = new RandomList<>();
        GeoManager geoMgr = GeoManager.INSTANCE;

        List<Object> svrRefs = new ArrayList<>();
        YamlConfiguration watcher = new YamlConfiguration("conf.yml")
                .setWatchValidator(conf -> {
                    RSSConf changed = conf.readAs(RSSConf.class);
                    if (changed == null || CollectionUtils.isEmpty(changed.socksServers)) {
                        return false;
                    }
                    for (AuthenticEndpoint socksServer : changed.socksServers) {
                        if (socksServer == null || socksServer.getInetEndpoint() == null) {
                            continue;
                        }
                        int weight = Reflects.convertQuietly(socksServer.getParameters().get("w"), int.class, 0);
                        if (weight > 0) {
                            return true;
                        }
                    }
                    return false;
                }).enableWatch();
        watcher.onChanged.combine((s, e) -> {
            RSSConf changed = s.readAs(RSSConf.class);
            if (changed == null) {
                return;
            }
            if (changed.udp2rawSocksServers == null) {
                changed.udp2rawSocksServers = Collections.emptyList();
            }
            RssSupport.rssConf = changed;
            List<AuthenticEndpoint> svrs = RssSupport.rssConf.socksServers;
            org.slf4j.LoggerFactory.getLogger(RssClient.class).info("rssConf load socksServers: {}", toJsonString(svrs));
            List<AuthenticEndpoint> udp2rawSvrs = RssSupport.rssConf.udp2rawSocksServers;
            org.slf4j.LoggerFactory.getLogger(RssClient.class).info("rssConf load udp2rawSocksServers: {}", toJsonString(udp2rawSvrs));
            geoMgr.setGeoSiteDirectRules(RssSupport.rssConf.route.dstGeoSiteDirectRules);

            List<UpstreamSupport> oldSvrs = socksServers.aliveList();
            List<UpstreamSupport> oldUdp2rawSvrs = udp2rawSocksServers.aliveList();
            List<DnsServer.ResolveInterceptor> oldDnss = dnsInterceptors.aliveList();

            SocksRpcContract firstFacade = null;
            for (AuthenticEndpoint socksServer : svrs) {
                InetSocketAddress socksServerEp = socksServer.requireEndpoint();
                RpcClientConfig<SocksRpcContract> rpcConf = RpcClientConfig.poolMode(
                        Sockets.newEndpoint(socksServerEp, socksServerEp.getPort() + 1),
                        RssSupport.rssConf.rpcMinSize, RssSupport.rssConf.rpcMaxSize);
                TcpClientConfig tcpConfig = rpcConf.getTcpConfig();
                tcpConfig.setTransportFlags(TransportFlags.GFW.flags(TransportFlags.CIPHER_BOTH).flags());
                int weight = Reflects.convertQuietly(socksServer.getParameters().get("w"), int.class, 0);
                if (weight <= 0) {
                    continue;
                }
                SocksRpcContract facade = Remoting.createFacade(SocksRpcContract.class, rpcConf);
                if (firstFacade == null) {
                    firstFacade = facade;
                }
                UpstreamSupport us = new UpstreamSupport(socksServer, new SocksRpcContract() {
                    @Override
                    public void fakeEndpoint(BigInteger hash, String realEndpoint) {
                        facade.fakeEndpoint(hash, realEndpoint);
                    }

                    @Override
                    public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
                        boolean outProxy;
                        String ext;
                        RouteConf routeConf = RssSupport.rssConf.route;
                        if (routeConf.enable) {
                            if (routeConf.srcIpProxyRules != null && routeConf.srcIpProxyRules.contains(srcIp)) {
                                outProxy = true;
                                ext = "srcIp:proxy";
                            } else if (geoMgr.matchSiteDirect(host)) {
                                outProxy = false;
                                ext = "geosite:direct";
                            } else {
                                outProxy = true;
                                ext = "geosite:proxy";
                            }
                        } else {
                            outProxy = true;
                            ext = "routeDisabled";
                        }
                        if (RssSupport.rssConf.hasRouteFlag()) {
                            org.slf4j.LoggerFactory.getLogger(RssClient.class).info("route dns {}+{} {} <- {}",
                                    srcIp, host, outProxy ? "PROXY" : "DIRECT", ext);
                        }

                        return outProxy ? facade.resolveHost(srcIp, host) : DnsClient.inlandClient().resolveAll(host);
                    }

                    @Override
                    public void addWhiteList(InetAddress endpoint) {
                        facade.addWhiteList(endpoint);
                    }

                    @Override
                    public boolean resetUdpRelay(int relayPort) {
                        return facade.resetUdpRelay(relayPort);
                    }

                    @Override
                    public boolean claimUdpRelay(int relayPort, InetSocketAddress clientAddr) {
                        return facade.claimUdpRelay(relayPort, clientAddr);
                    }
                });
                socksServers.add(us, weight);
                dnsInterceptors.add(us.getFacade());
            }
            for (AuthenticEndpoint socksServer : udp2rawSvrs) {
                int weight = Reflects.convertQuietly(socksServer.getParameters().get("w"), int.class, 0);
                if (weight <= 0) {
                    continue;
                }
                UpstreamSupport us = new UpstreamSupport(socksServer, firstFacade);
                udp2rawSocksServers.add(us, weight);
            }

            socksServers.removeAll(oldSvrs);
            udp2rawSocksServers.removeAll(oldUdp2rawSvrs);
            dnsInterceptors.removeAll(oldDnss);
            for (UpstreamSupport support : oldSvrs) {
                org.rx.net.socks.Socks5UpstreamPoolManager.INSTANCE.closeEndpoint(support.getEndpoint());
                tryClose(support.getFacade());
            }
            for (UpstreamSupport support : oldUdp2rawSvrs) {
                org.rx.net.socks.Socks5UpstreamPoolManager.INSTANCE.closeEndpoint(support.getEndpoint());
                tryClose(support.getFacade());
            }

            boolean debugFlag = RssSupport.rssConf.hasDebugFlag();
            int connectTimeoutMillis = RssSupport.rssConf.connectTimeoutSeconds * 1000;
            org.slf4j.LoggerFactory.getLogger(RssClient.class).info("rssConf debug={}", debugFlag);
            for (Object svrRef : svrRefs) {
                if (svrRef instanceof ShadowsocksServer) {
                    ShadowsocksConfig config = ((ShadowsocksServer) svrRef).getConfig();
                    config.setDebug(debugFlag);
                    config.setConnectTimeoutMillis(connectTimeoutMillis);
                } else {
                    SocksConfig config = ((SocksProxyServer) svrRef).getConfig();
                    config.setDebug(debugFlag);
                    config.setConnectTimeoutMillis(connectTimeoutMillis);
                    if (config.isEnableUdp2raw()) {
                        config.setUdp2rawClient(RssSupport.rssConf.udp2rawClient);
                        config.setKcptunClient(RssSupport.rssConf.kcptunClient);
                    }
                }
            }
            org.slf4j.LoggerFactory.getLogger(RssClient.class).info("rssConf load ok");
        });
        watcher.raiseChange();

        DnsServer dnsSvr = new DnsServer(RssSupport.rssConf.shadowDnsPort);
        dnsSvr.setTtl(60 * RssSupport.rssConf.dnsTtlMinutes);
        dnsSvr.setNegativeTtl(DnsServer.DEFAULT_NEGATIVE_TTL);
        dnsSvr.setInterceptors(dnsInterceptors);
        dnsSvr.addHostsFile("hosts.txt");
        InetSocketAddress shadowDnsEp = Sockets.newLoopbackEndpoint(RssSupport.rssConf.shadowDnsPort);
        Sockets.injectNameService(Collections.singletonList(shadowDnsEp));

        Linq<Tuple<ShadowsocksConfig, ShadowUser>> shadowUsers = Linq.from(RssSupport.rssConf.shadowUsers).select(shadowUser -> {
            ShadowsocksConfig config = new ShadowsocksConfig(Sockets.newAnyEndpoint(shadowUser.getSsPort()),
                    CipherKind.AES_256_GCM.getCipherName(), shadowUser.getSsPwd());
            config.setUdpReadTimeoutSeconds(RssSupport.rssConf.udpTimeoutSeconds);
            return Tuple.of(config, shadowUser);
        });

        SocksConfig inConf = new SocksConfig(resolveClientInListenAddress(RssSupport.rssConf, port, "rss-in-"));
        inConf.setDebug(RssSupport.rssConf.hasDebugFlag());
        inConf.setTcpAsyncDnsMode(SocksConfig.TcpAsyncDnsMode.INLAND);
        inConf.setOptimalSettings(RssSupport.IN_OPS);
        inConf.setConnectTimeoutMillis(RssSupport.rssConf.connectTimeoutSeconds * 1000);
        inConf.setReadTimeoutSeconds(RssSupport.rssConf.tcpTimeoutSeconds);
        inConf.setUdpReadTimeoutSeconds(RssSupport.rssConf.udpTimeoutSeconds);
        inConf.setUdpRedundantMultiplier(2);
        RssSupport.applyUdpCompressionTrial(inConf);
        RssAuthenticator authenticator = new RssAuthenticator(shadowUsers.select(p -> p.right).toList(), RssSupport.rssConf.socksPwd.trim());
        Upstream shadowDnsUpstream = new Upstream(new UnresolvedEndpoint(shadowDnsEp));
        org.slf4j.LoggerFactory.getLogger(RssClient.class).info("rssConf socksBindPort={}, inListenAddress={}",
                RssSupport.rssConf.socksBindPort, inConf.getListenAddress());
        TripleAction<SocksProxyServer, SocksContext> firstRoute = (s, e) -> {
            UnresolvedEndpoint dstEp = e.getFirstDestination();
            if (dstEp.getPort() == SocksRpcContract.DNS_PORT) {
                e.setUpstream(shadowDnsUpstream);
                e.setHandled(true);
            }
        };
        SocksProxyServer inSvr = RssSupport.createInSvr(inConf, authenticator, firstRoute, socksServers, geoMgr);
        svrRefs.add(inSvr);
        RssRpcApp app = new RssRpcApp(inSvr);
        SocksProxyServer inUdp2rawSvr = null;
        SocketAddress inUdp2rawSvrAddress = null;
        if (enableUdp2raw) {
            SocksConfig inTunConf = Sys.deepClone(inConf);
            inTunConf.setDebug(RssSupport.rssConf.hasDebugFlag());
            inTunConf.setListenAddress(resolveClientInListenAddress(RssSupport.rssConf, udp2rawPort, "rss-in-tun-"));
            inTunConf.setKcptunClient(RssSupport.rssConf.kcptunClient);
            inTunConf.setUdpRedundantMultiplier(2);
            RssSupport.applyUdpCompressionTrial(inTunConf);
            inUdp2rawSvr = RssSupport.createInSvr(inTunConf, authenticator, firstRoute, udp2rawSocksServers, geoMgr);
            inUdp2rawSvrAddress = inTunConf.getListenAddress();
            svrRefs.add(inUdp2rawSvr);
        }

        Action fn = () -> {
            InetAddress addr = InetAddress.getByName(geoMgr.getPublicIp());
            eachQuietly(socksServers, p -> p.getFacade().addWhiteList(addr));
        };
        fn.invoke();
        Tasks.schedulePeriod(fn, RssSupport.rssConf.rpcAutoWhiteListSeconds * 1000L);

        SocketAddress inSvrAddress = inConf.getListenAddress();
        for (Tuple<ShadowsocksConfig, ShadowUser> tuple : shadowUsers) {
            ShadowsocksConfig conf = tuple.left;
            ShadowUser usr = tuple.right;
            // SS 用户名用于入口认证；socksUser 决定落到哪个内部转发账号。
            String authUserName = usr.getUsername();
            String routeUserName = usr.getSocksUser();

            conf.setOptimalSettings(RssSupport.SS_IN_OPS);
            conf.setConnectTimeoutMillis(RssSupport.rssConf.connectTimeoutSeconds * 1000);
            conf.setReadTimeoutSeconds(0);
            conf.setWriteTimeoutSeconds(0);
            conf.setUdpReadTimeoutSeconds(0);
            conf.setUdpWriteTimeoutSeconds(0);
            ShadowsocksServer ssSvr = new ShadowsocksServer(conf);
            svrRefs.add(ssSvr);

            AuthenticEndpoint svrEp;
            if (routeUserName != null && routeUserName.startsWith("hysteria")) {
                svrEp = RssSupport.rssConf.hysteriaClient;
            } else if (routeUserName != null && routeUserName.startsWith("tun")) {
                svrEp = new AuthenticEndpoint(inUdp2rawSvrAddress, authUserName, authenticator.getSocksPassword());
            } else {
                svrEp = new AuthenticEndpoint(inSvrAddress, authUserName, authenticator.getSocksPassword());
            }

            SocksConfig toInConf = new SocksConfig();
            toInConf.setOptimalSettings(RssSupport.IN_OPS);
            UpstreamSupport svrSupport = new UpstreamSupport(svrEp, null);
            ssSvr.onTcpRoute.replace((s, e) -> {
                UnresolvedEndpoint dstEp = e.getFirstDestination();
                if (RssSupport.rssConf.hasDebugFlag()) {
                    org.slf4j.LoggerFactory.getLogger(RssClient.class).info("SS TCP route {} => {}[{}]", e.getSource(), svrSupport.getEndpoint(), dstEp);
                }
                e.setUpstream(new SocksTcpUpstream(dstEp, toInConf, svrSupport));
            });
            ssSvr.onUdpRoute.replace((s, e) -> {
                UnresolvedEndpoint dstEp = e.getFirstDestination();
                if (RssSupport.rssConf.hasDebugFlag()) {
                    org.slf4j.LoggerFactory.getLogger(RssClient.class).info("SS UDP route {} => {}[{}]", e.getSource(), svrSupport.getEndpoint(), dstEp);
                }
                e.setUpstream(new SocksUdpUpstream(dstEp, toInConf, svrSupport));
            });
        }

        RssSupport.clientInit(authenticator);
        org.slf4j.LoggerFactory.getLogger(RssClient.class).info("Server started..");
        app.await();
    }

    public static SocketAddress resolveClientInListenAddress(RSSConf conf, int port, String localNamePrefix) {
        return RssSupport.resolveClientInListenAddress(conf, port, localNamePrefix);
    }
}
