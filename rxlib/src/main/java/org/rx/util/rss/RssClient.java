package org.rx.util.rss;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.netty.channel.local.LocalAddress;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.RandomList;
import org.rx.bean.Tuple;
import org.rx.codec.CodecUtil;
import org.rx.core.Linq;
import org.rx.core.Reflects;
import org.rx.core.Strings;
import org.rx.core.Sys;
import org.rx.core.Tasks;
import org.rx.core.YamlConfiguration;
import org.rx.exception.InvalidException;
import org.rx.io.DuplexStream;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.TransportFlags;
import org.rx.net.dns.DnsClient;
import org.rx.net.dns.DnsServer;
import org.rx.net.http.HttpClient;
import org.rx.net.http.HttpServer;
import org.rx.net.nameserver.NameserverConfig;
import org.rx.net.nameserver.NameserverImpl;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.socks.Authenticator;
import org.rx.net.socks.RrpConfig;
import org.rx.net.socks.RrpServer;
import org.rx.net.socks.ShadowsocksConfig;
import org.rx.net.socks.ShadowsocksServer;
import org.rx.net.socks.SocksConnectionTagRegistry;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.socks.encryption.CipherKind;
import org.rx.net.socks.upstream.SocksTcpUpstream;
import org.rx.net.socks.upstream.SocksUdpUpstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.GeoManager;
import org.rx.net.support.IpGeolocation;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;
import org.rx.net.transport.TcpClientConfig;
import org.rx.util.function.Action;
import org.rx.util.function.BiFunc;
import org.rx.util.function.QuadraFunc;
import org.rx.util.function.TripleAction;

import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ScheduledFuture;

import static org.rx.core.Extends.eachQuietly;
import static org.rx.core.Extends.tryClose;
import static org.rx.core.Sys.toJsonString;

@Slf4j
public final class RssClient {
    static RSSConf rssConf;
    static RrpServer rrpServer;
    static HttpServer httpServer;
    static NameserverImpl nameserver;
    static RssUserTrafficStore trafficStore;
    static ScheduledFuture<?> ddnsTask;
    static int ddnsTaskPeriodSeconds;

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
        watcher.onChanged.add((s, e) -> {
            RSSConf changed = s.readAs(RSSConf.class);
            if (changed == null) {
                return;
            }
            if (changed.udp2rawSocksServers == null) {
                changed.udp2rawSocksServers = Collections.emptyList();
            }
            changed.trafficRetentionDays = Math.max(1, changed.trafficRetentionDays);
            changed.memoryRetentionHours = Math.max(1, changed.memoryRetentionHours);
            rssConf = changed;
            List<AuthenticEndpoint> svrs = rssConf.socksServers;
            log.info("rssConf load socksServers: {}", toJsonString(svrs));
            List<AuthenticEndpoint> udp2rawSvrs = rssConf.udp2rawSocksServers;
            log.info("rssConf load udp2rawSocksServers: {}", toJsonString(udp2rawSvrs));
            geoMgr.setGeoSiteDirectRules(rssConf.route.dstGeoSiteDirectRules);

            List<UpstreamSupport> oldSvrs = socksServers.aliveList();
            List<UpstreamSupport> oldUdp2rawSvrs = udp2rawSocksServers.aliveList();
            List<DnsServer.ResolveInterceptor> oldDnss = dnsInterceptors.aliveList();

            SocksRpcContract firstFacade = null;
            for (AuthenticEndpoint socksServer : svrs) {
                InetSocketAddress socksServerEp = socksServer.requireEndpoint();
                RpcClientConfig<SocksRpcContract> rpcConf = RpcClientConfig.poolMode(
                        Sockets.newEndpoint(socksServerEp, socksServerEp.getPort() + 1),
                        rssConf.rpcMinSize, rssConf.rpcMaxSize);
                rpcConf.setRequestTimeoutMillis(resolveRpcRequestTimeoutMillis(rssConf));
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
                        RSSConf.RouteConf routeConf = rssConf.route;
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
                        if (rssConf.hasRouteFlag()) {
                            log.info("route dns {}+{} {} <- {}", srcIp, host, outProxy ? "PROXY" : "DIRECT", ext);
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
                dnsInterceptors.add(us.getFacade(), weight);
            }
            for (AuthenticEndpoint socksServer : udp2rawSvrs) {
                int weight = Reflects.convertQuietly(socksServer.getParameters().get("w"), int.class, 0);
                if (weight <= 0) {
                    continue;
                }
                udp2rawSocksServers.add(new UpstreamSupport(socksServer, firstFacade), weight);
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

            boolean debugFlag = rssConf.hasDebugFlag();
            int connectTimeoutMillis = rssConf.connectTimeoutSeconds * 1000;
            log.info("rssConf debug={}", debugFlag);
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
                        config.setUdp2rawClient(rssConf.udp2rawClient);
                        config.setKcptunClient(rssConf.kcptunClient);
                    }
                }
            }
            configureDdnsSchedule(rssConf);
            log.info("rssConf load ok");
        });
        watcher.raiseChange();

        DnsServer dnsSvr = new DnsServer(rssConf.shadowDnsPort);
        dnsSvr.setTtl(60 * rssConf.dnsTtlMinutes);
        dnsSvr.setNegativeTtl(DnsServer.DEFAULT_NEGATIVE_TTL);
        dnsSvr.setInterceptors(dnsInterceptors);
        dnsSvr.addHostsFile("hosts.txt");
        nameserver = new NameserverImpl(resolveNameserverConfig(rssConf), dnsSvr);
        InetSocketAddress shadowDnsEp = Sockets.newLoopbackEndpoint(rssConf.shadowDnsPort);
        Sockets.injectNameService(Collections.singletonList(shadowDnsEp));

        Linq<Tuple<ShadowsocksConfig, ShadowUser>> shadowUsers = Linq.from(rssConf.shadowUsers).select(shadowUser -> {
            ShadowsocksConfig config = new ShadowsocksConfig(Sockets.newAnyEndpoint(shadowUser.getSsPort()),
                    CipherKind.AES_256_GCM.getCipherName(), shadowUser.getSsPwd());
            config.setUdpReadTimeoutSeconds(rssConf.udpTimeoutSeconds);
            enableShadowIngressReusePort(config);
            return Tuple.of(config, shadowUser);
        });

        SocksConfig inConf = new SocksConfig(resolveClientInListenAddress(rssConf, port, "rss-in-"));
        inConf.setDebug(rssConf.hasDebugFlag());
        inConf.setTcpAsyncDnsMode(SocksConfig.TcpAsyncDnsMode.INLAND);
        inConf.setOptimalSettings(RssSupport.IN_OPS);
        inConf.setConnectTimeoutMillis(rssConf.connectTimeoutSeconds * 1000);
        inConf.setReadTimeoutSeconds(rssConf.tcpTimeoutSeconds);
        inConf.setUdpReadTimeoutSeconds(rssConf.udpTimeoutSeconds);
        inConf.setUdpRedundantMultiplier(2);
        RssSupport.applyUdpCompressionTrial(inConf);
        RssAuthenticator authenticator = new RssAuthenticator(shadowUsers.select(p -> p.right).toList(),
                rssConf.socksPwd.trim(), rssConf.memoryRetentionHours);
        Upstream shadowDnsUpstream = new Upstream(new UnresolvedEndpoint(shadowDnsEp));
        log.info("rssConf socksBindPort={}, inListenAddress={}", rssConf.socksBindPort, inConf.getListenAddress());
        TripleAction<SocksProxyServer, SocksContext> firstRoute = (s, e) -> {
            UnresolvedEndpoint dstEp = e.getFirstDestination();
            if (dstEp.getPort() == SocksRpcContract.DNS_PORT) {
                e.setUpstream(shadowDnsUpstream);
                e.setHandled(true);
            }
        };
        SocksProxyServer inSvr = createInSvr(inConf, authenticator, firstRoute, socksServers, geoMgr);
        svrRefs.add(inSvr);
        RssRpcApp app = new RssRpcApp(inSvr);

        SocksProxyServer inUdp2rawSvr = null;
        SocketAddress inUdp2rawSvrAddress = null;
        if (enableUdp2raw) {
            SocksConfig inTunConf = Sys.deepClone(inConf);
            inTunConf.setDebug(rssConf.hasDebugFlag());
            inTunConf.setListenAddress(resolveClientInListenAddress(rssConf, udp2rawPort, "rss-in-tun-"));
            inTunConf.setKcptunClient(rssConf.kcptunClient);
            inTunConf.setUdpRedundantMultiplier(2);
            RssSupport.applyUdpCompressionTrial(inTunConf);
            inUdp2rawSvr = createInSvr(inTunConf, authenticator, firstRoute, udp2rawSocksServers, geoMgr);
            inUdp2rawSvrAddress = inTunConf.getListenAddress();
            svrRefs.add(inUdp2rawSvr);
        }

        Action fn = () -> {
            InetAddress addr = InetAddress.getByName(geoMgr.getPublicIp());
            eachQuietly(socksServers, p -> p.getFacade().addWhiteList(addr));
        };
        fn.invoke();
        Tasks.schedulePeriod(fn, rssConf.rpcAutoWhiteListSeconds * 1000L);

        SocketAddress inSvrAddress = inConf.getListenAddress();
        for (Tuple<ShadowsocksConfig, ShadowUser> tuple : shadowUsers) {
            ShadowsocksConfig conf = tuple.left;
            ShadowUser usr = tuple.right;
            String authUserName = usr.getUsername();
            String routeUserName = usr.getSocksUser();

            conf.setOptimalSettings(RssSupport.SS_IN_OPS);
            conf.setConnectTimeoutMillis(rssConf.connectTimeoutSeconds * 1000);
            conf.setReadTimeoutSeconds(0);
            conf.setWriteTimeoutSeconds(0);
            conf.setUdpReadTimeoutSeconds(0);
            conf.setUdpWriteTimeoutSeconds(0);
            ShadowsocksServer ssSvr = new ShadowsocksServer(conf);
            svrRefs.add(ssSvr);

            AuthenticEndpoint svrEp = resolveShadowEndpoint(inSvrAddress, inUdp2rawSvrAddress, rssConf.hysteriaClient, authUserName, routeUserName);
            SocksConfig toInConf = new SocksConfig();
            toInConf.setOptimalSettings(RssSupport.IN_OPS);
            UpstreamSupport svrSupport = new UpstreamSupport(svrEp, null);
            ssSvr.onTcpRoute.replace((s, e) -> {
                UnresolvedEndpoint dstEp = e.getFirstDestination();
                if (rssConf.hasDebugFlag()) {
                    log.info("SS TCP route {} => {}[{}]", e.getSource(), svrSupport.getEndpoint(), dstEp);
                }
                e.setUpstream(new SocksTcpUpstream(dstEp, toInConf, svrSupport));
            });
            ssSvr.onUdpRoute.replace((s, e) -> {
                UnresolvedEndpoint dstEp = e.getFirstDestination();
                if (rssConf.hasDebugFlag()) {
                    log.info("SS UDP route {} => {}[{}]", e.getSource(), svrSupport.getEndpoint(), dstEp);
                }
                e.setUpstream(new SocksUdpUpstream(dstEp, toInConf, svrSupport));
            });
        }

        clientInit(authenticator);
        log.info("Server started..");
        app.await();
    }

    public static SocketAddress resolveClientInListenAddress(RSSConf conf, int port, String localNamePrefix) {
        if (conf != null && conf.socksBindPort) {
            return Sockets.newLoopbackEndpoint(port);
        }
        return new LocalAddress(localNamePrefix + port);
    }

    static SocksProxyServer createInSvr(SocksConfig inConf, Authenticator authenticator,
                                        TripleAction<SocksProxyServer, SocksContext> firstRoute, RandomList<UpstreamSupport> socksServers,
                                        GeoManager geoMgr) {
        SocksProxyServer inSvr = new SocksProxyServer(inConf);
        if (authenticator instanceof RssAuthenticator) {
            RssAuthenticator rssAuthenticator = (RssAuthenticator) authenticator;
            inSvr.setConnectionTagResolver(rssAuthenticator::resolve);
        }
        boolean kcptun = inConf.getKcptunClient() != null;
        BiFunc<SocksContext, UpstreamSupport> routerFn = e -> {
            InetAddress srcHost = e.getSource().getAddress();
            UpstreamSupport next = nextUpstream(socksServers, srcHost);
            if (rssConf.hasDebugFlag()) {
                log.info("route upSvr src {} -> {}", srcHost, next.getEndpoint());
            }
            if (kcptun) {
                return routeUpstream(inConf, next);
            }
            return next;
        };
        SocksConfig outConf = Sys.deepClone(inConf);
        outConf.setTransportFlags(TransportFlags.GFW.flags(TransportFlags.COMPRESS_BOTH).flags());
        outConf.setTcpCompressionLevel(RssSupport.TCP_TRIAL_COMPRESSION_LEVEL);
        RssSupport.applyUdpCompressionTrial(outConf);
        applyUdpLeasePool(rssConf, outConf);
        if (!kcptun) {
            outConf.setOptimalSettings(RssSupport.OUT_OPS);
        }
        QuadraFunc<InetSocketAddress, UnresolvedEndpoint, String, Boolean> routeingFn = (srcEp, dstEp, transType) -> {
            String host = dstEp.getHost();
            boolean outProxy;
            long begin;
            String ext;
            RSSConf.RouteConf routeConf = rssConf.route;
            if (routeConf.enable) {
                begin = System.nanoTime();
                if (routeConf.srcIpProxyRules != null && routeConf.srcIpProxyRules.contains(srcEp.getAddress())) {
                    outProxy = true;
                    ext = "srcIp:proxy";
                } else if (!Sockets.isValidIp(host)) {
                    if (geoMgr.matchSiteDirect(host)) {
                        outProxy = false;
                        ext = "geosite:direct";
                    } else {
                        outProxy = true;
                        ext = "geosite:proxy";
                    }
                } else {
                    IpGeolocation geo = geoMgr.resolveIp(host);
                    String category = geo.getCategory();
                    outProxy = !Strings.equalsIgnoreCase(category, "cn") && !Strings.equalsIgnoreCase(category, "private");
                    ext = "geoip:" + category;
                }
            } else {
                outProxy = true;
                begin = 0L;
                ext = "routeDisabled";
            }
            if (rssConf.hasRouteFlag()) {
                log.info("route dst {} {} {} <- {} {}",
                        transType, host, outProxy ? "PROXY" : "DIRECT", ext,
                        Sys.formatNanosElapsed(System.nanoTime() - begin));
            }
            return outProxy;
        };
        inSvr.onTcpRoute.replace(firstRoute, (s, e) -> {
            if (e.getUpstream() != null) {
                return;
            }
            UnresolvedEndpoint dstEp = e.getFirstDestination();
            if (routeingFn.apply(e.getSource(), dstEp, "TCP")) {
                e.setUpstream(new SocksTcpUpstream(dstEp, outConf, routerFn.apply(e)));
            } else {
                e.setUpstream(new Upstream(dstEp));
            }
        });
        inSvr.onUdpRoute.replace(firstRoute, (s, e) -> {
            if (e.getUpstream() != null) {
                return;
            }
            UnresolvedEndpoint dstEp = e.getFirstDestination();
            if (routeingFn.apply(e.getSource(), dstEp, "UDP")) {
                e.setUpstream(new SocksUdpUpstream(dstEp, outConf, routerFn.apply(e)));
            } else {
                e.setUpstream(new Upstream(dstEp));
            }
        });
        inSvr.setCipherRouter(SocksProxyServer.DNS_CIPHER_ROUTER);
        return inSvr;
    }

    static UpstreamSupport nextUpstream(RandomList<UpstreamSupport> socksServers, InetAddress srcHost) {
        try {
            return socksServers.next(srcHost, rssConf.route.srcSteeringTTL, true);
        } catch (NoSuchElementException e) {
            throw new InvalidException("No available socks upstream for {}", srcHost);
        } catch (IllegalArgumentException e) {
            throw new InvalidException("No weighted socks upstream for {}", srcHost);
        }
    }

    static UpstreamSupport routeUpstream(SocksConfig inConf, UpstreamSupport next) {
        if (inConf == null || inConf.getKcptunClient() == null) {
            return next;
        }
        return new UpstreamSupport(inConf.getKcptunClient(), next.getFacade());
    }

    static void applyUdpLeasePool(RSSConf conf, SocksConfig config) {
        if (conf == null || config == null) {
            return;
        }
        config.setUdpLeasePoolEnabled(conf.udpLeasePoolEnabled);
        if (!conf.udpLeasePoolEnabled) {
            return;
        }

        int maxSize = Math.max(1, conf.udpLeasePoolMaxSize);
        int minSize = Math.max(0, Math.min(conf.udpLeasePoolMinSize, maxSize));
        config.setUdpLeasePoolMinSize(minSize);
        config.setUdpLeasePoolMaxSize(maxSize);
        config.setUdpLeasePoolMaxIdleMillis(Math.max(1000, conf.udpLeasePoolMaxIdleMillis));
        config.setUdpLeaseRpcBreakerThreshold(Math.max(1, conf.udpLeaseRpcBreakerThreshold));
        config.setUdpLeaseRpcBreakerOpenSeconds(Math.max(1, conf.udpLeaseRpcBreakerOpenSeconds));
    }

    static int resolveRpcRequestTimeoutMillis(RSSConf conf) {
        int configured = conf.rpcRequestTimeoutMillis;
        if (configured > 0) {
            return configured;
        }
        int connectTimeoutMillis = Math.max(1000, conf.connectTimeoutSeconds * 1000);
        return Math.min(connectTimeoutMillis, 3000);
    }

    static NameserverConfig resolveNameserverConfig(RSSConf conf) {
        NameserverConfig config = conf.nameserver;
        if (config == null) {
            config = new NameserverConfig();
            conf.nameserver = config;
        }
        config.setDnsPort(conf.shadowDnsPort);
        config.setDnsTtl(60 * conf.dnsTtlMinutes);
        return config;
    }

    static void clientInit(RssAuthenticator authenticator) {
        if (trafficStore == null || trafficStore.retentionDays() != rssConf.trafficRetentionDays) {
            if (trafficStore != null) {
                trafficStore.close();
            }
            trafficStore = new RssUserTrafficStore(null, rssConf.trafficRetentionDays);
            trafficStore.start();
            org.rx.net.socks.SocksUserTraffic.registerRecorder(trafficStore);
        }

        httpServer = HttpServer.getDefault().requestAsync(RssClientHttpHandler.SHADOW_USERS_PAGE_PATH,
                new RssClientHttpHandler(authenticator.getShadowStore(), trafficStore, authenticator.getMemoryRetentionHours()));

        if (!Strings.isEmpty(rssConf.rrpToken) && rssConf.rrpPort != null) {
            RrpConfig c = new RrpConfig();
            c.setToken(rssConf.rrpToken);
            c.setBindPort(rssConf.rrpPort);
            rrpServer = new RrpServer(c);
        }

        configureDdnsSchedule(rssConf);
    }

    static boolean shouldScheduleDdns(RSSConf conf) {
        return conf != null && conf.ddnsJobSeconds > 0 && !CollectionUtils.isEmpty(conf.ddnsDomains);
    }

    static synchronized boolean configureDdnsSchedule(RSSConf conf) {
        boolean enabled = shouldScheduleDdns(conf);
        int periodSeconds = enabled ? conf.ddnsJobSeconds : 0;
        if (ddnsTask != null && (!enabled || ddnsTaskPeriodSeconds != periodSeconds)) {
            ddnsTask.cancel(false);
            ddnsTask = null;
            ddnsTaskPeriodSeconds = 0;
        }
        if (!enabled || ddnsTask != null) {
            return false;
        }

        ddnsTaskPeriodSeconds = periodSeconds;
        ddnsTask = Tasks.schedulePeriod(RssClient::runDdnsJob, periodSeconds * 1000L);
        return true;
    }

    @SneakyThrows
    static void runDdnsJob() {
        RSSConf conf = rssConf;
        if (!shouldScheduleDdns(conf)) {
            return;
        }

        InetAddress wanIp = InetAddress.getByName(GeoManager.INSTANCE.getPublicIp());
        List<String> subDomains = Linq.from(conf.ddnsDomains)
                .where(sd -> !DnsClient.inlandClient().resolveAll(sd).contains(wanIp))
                .select(sd -> sd.substring(0, sd.indexOf("."))).toList();
        if (subDomains.isEmpty()) {
            return;
        }
        String oneSd = conf.ddnsDomains.get(0);
        String domain = oneSd.substring(oneSd.indexOf(".") + 1);
        String res = setDDns(conf.ddnsApiKey, domain, subDomains, wanIp.getHostAddress());
        log.info("ddns set {} + {} @ {} -> {}", domain, subDomains, wanIp.getHostAddress(), res);
    }

    static void enableShadowIngressReusePort(ShadowsocksConfig config) {
        if (config == null) {
            return;
        }

        config.setReusePortBindCount(2);
        int bindCount = Sockets.reusePortBindCount(config, config.getServerEndpoint());
        if (bindCount <= 1) {
            return;
        }

        log.info("shadow ingress enable SO_REUSEPORT endpoint={} bindCount={}",
                config.getServerEndpoint(), bindCount);
    }

    @SneakyThrows
    static String setDDns(String apiKey, String domain, List<String> subDomains, String ip) {
        JSONObject curDns = getDDns(apiKey, domain);
        log.info("ddns curDns {}", curDns);
        JSONArray mDomains = Sys.readJsonValue(curDns, "data.name_server_settings.main_domains");
        JSONArray sDomains = Sys.readJsonValue(curDns, "data.name_server_settings.sub_domains");

        String url = "https://api.dynadot.com/restful/v1/domains/" + domain + "/records";
        JSONObject requestBody = new JSONObject();
        requestBody.put("ttl", 300);

        if (mDomains == null) {
            mDomains = new JSONArray();
        }
        for (int i = 0; i < mDomains.size(); i++) {
            JSONObject md = mDomains.getJSONObject(i);
            md.put("record_value1", md.getString("value"));
        }
        requestBody.put("dns_main_list", mDomains);

        if (sDomains == null) {
            sDomains = new JSONArray();
        }
        for (int i = 0; i < sDomains.size(); i++) {
            JSONObject sd = sDomains.getJSONObject(i);
            String subHost = sd.getString("sub_host");
            int j;
            if ((j = subDomains.indexOf(subHost)) != -1 && "a".equals(sd.getString("record_type"))) {
                sd.put("record_value1", ip);
                subDomains.remove(j);
            } else {
                sd.put("record_value1", sd.getString("value"));
            }
        }
        for (String subDomain : subDomains) {
            JSONObject sd = new JSONObject();
            sd.put("sub_host", subDomain);
            sd.put("record_type", "a");
            sd.put("record_value1", ip);
            sDomains.add(sd);
        }
        requestBody.put("sub_list", sDomains);
        log.info("ddns update all {}", requestBody);

        URL u = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        try {
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("X-Signature", dynadotSign(apiKey, url, requestBody.toString()));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.toString().getBytes(StandardCharsets.UTF_8));
            }
            return DuplexStream.readString(conn.getInputStream(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    static JSONObject getDDns(String apiKey, String domain) {
        String url = "https://api.dynadot.com/restful/v1/domains/" + domain + "/records";
        HttpClient.Request req = HttpClient.request(HttpMethod.GET, url)
                .header(HttpHeaderNames.ACCEPT, "application/json")
                .header(HttpHeaderNames.AUTHORIZATION, "Bearer " + apiKey)
                .header("X-Signature", dynadotSign(apiKey, url, ""));
        try (HttpClient.Response response = RssSupport.MAIN_HTTP_CLIENT.execute(req)) {
            return response.bodyAsJson();
        }
    }

    static String dynadotSign(String apiKey, String url, String requestBody) {
        int startIndex = url.indexOf("/", url.indexOf("//") + 2);
        String fullPathAndQuery = startIndex != -1 ? url.substring(startIndex) : "/";
        String stringToSign = apiKey + "\n" + fullPathAndQuery + "\n\n" + requestBody;
        return CodecUtil.toHex(CodecUtil.hmacSHA256(apiKey, stringToSign));
    }

    static Map<String, Object> toShadowStorePayload(Map<String, ShadowUser> shadowStore) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        if (shadowStore == null) {
            return payload;
        }
        for (Map.Entry<String, ShadowUser> entry : shadowStore.entrySet()) {
            payload.put(entry.getKey(), toShadowUserPayload(entry.getValue()));
        }
        return payload;
    }

    static Map<String, Object> toShadowUserPayload(ShadowUser user) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        if (user == null) {
            return payload;
        }
        payload.put("ssPort", user.getSsPort());
        payload.put("username", user.getUsername());
        payload.put("socksUser", user.getSocksUser());
        payload.put("ipLimit", user.getIpLimit());
        payload.put("lastResetTime", user.getLastResetTime());
        payload.put("loginIps", user.snapshotLoginIps());
        payload.put("totalReadBytes", user.getTotalReadBytes());
        payload.put("totalWriteBytes", user.getTotalWriteBytes());
        payload.put("totalReadPackets", user.getTotalReadPackets());
        payload.put("totalWritePackets", user.getTotalWritePackets());
        payload.put("humanTotalReadBytes", user.getHumanTotalReadBytes());
        payload.put("humanTotalWriteBytes", user.getHumanTotalWriteBytes());
        return payload;
    }

    static AuthenticEndpoint resolveShadowEndpoint(SocketAddress inSvrAddress, SocketAddress inUdp2rawSvrAddress,
                                                   AuthenticEndpoint hysteriaClient, String authUserName, String routeUserName) {
        if (routeUserName != null && routeUserName.startsWith("hysteria")) {
            return hysteriaClient;
        }

        SocketAddress endpoint = inSvrAddress;
        if (routeUserName != null && routeUserName.startsWith("tun") && inUdp2rawSvrAddress != null) {
            endpoint = inUdp2rawSvrAddress;
        }
        AuthenticEndpoint target = new AuthenticEndpoint(endpoint);
        target.getParameters().put(SocksConnectionTagRegistry.PARAM_NAME, authUserName);
        return target;
    }
}
