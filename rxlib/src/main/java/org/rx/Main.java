package org.rx;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.netty.util.concurrent.FastThreadLocal;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.RandomList;
import org.rx.bean.Tuple;
import org.rx.codec.CodecUtil;
import org.rx.core.*;
import org.rx.core.Arrays;
import org.rx.exception.InvalidException;
import org.rx.io.Files;
import org.rx.io.IOStream;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.OptimalSettings;
import org.rx.net.Sockets;
import org.rx.net.TransportFlags;
import org.rx.net.dns.DnsClient;
import org.rx.net.dns.DnsServer;
import org.rx.net.http.HttpClient;
import org.rx.net.http.HttpServer;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.rpc.RpcServerConfig;
import org.rx.net.shadowsocks.ShadowsocksConfig;
import org.rx.net.shadowsocks.ShadowsocksServer;
import org.rx.net.shadowsocks.encryption.CipherKind;
import org.rx.net.socks.*;
import org.rx.net.socks.upstream.SocksTcpUpstream;
import org.rx.net.socks.upstream.SocksUdpUpstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.*;
import org.rx.net.transport.TcpClientConfig;
import org.rx.net.transport.TcpServerConfig;
import org.rx.util.function.*;

import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.rx.bean.$.$;
import static org.rx.core.Extends.*;
import static org.rx.core.Sys.toJsonString;

@Slf4j
@RequiredArgsConstructor
public final class Main implements SocksSupport {
    @SneakyThrows
    public static void main(String[] args) {
//        serverInit();
//
//        String hfSvr = "AS(104,116,116,112,115,58,47,47,102,45,108,105,46,99,110,58,56,48,56,50)/hf";
//        String fu = "https://api.web.ecapi.cn/platform/dmOrder?page=1&pageSize=100&time_from=2024-11-20%2019%3A36%3A48&time_to=2024-11-23%2019%3A36%3A48&apkey=33b26a2d-9111-40ec-eff0-d1f7316cb689";
//        System.out.println(new HttpClient().get(HttpClient.buildUrl(hfSvr, Collections.singletonMap("fu", fu))).toString());
//        System.in.read();

        Map<String, String> options = Sys.mainOptions(args);
        Integer port = Reflects.convertQuietly(options.get("port"), Integer.class);
        if (port == null) {
            log.info("Invalid port arg");
            return;
        }

        Integer connectTimeout = Reflects.convertQuietly(options.get("connectTimeout"), Integer.class, 60000);
        String mode = options.get("shadowMode");
//        Udp2rawHandler.DEFAULT.setGzipMinLength(Integer.MAX_VALUE);
        if (eq(mode, "1")) {
            launchServer(options, port, connectTimeout);
            return;
        }
        launchClient(options, port, connectTimeout);
    }

    @Getter
    @Setter
    @ToString
    public static class RSSConf {
        public int logFlags;

        //socks
        public List<String> shadowUsers;
        public List<String> socksServers;
        public String socksPwd;
        public int tcpTimeoutSeconds = 60 * 2;
        public int udpTimeoutSeconds = 60 * 10;
        public int rpcMinSize = 2;
        public int rpcMaxSize = 6;
        public int rpcAutoWhiteListSeconds = 120;
        public int shadowDnsPort = 753;
        public int dnsTtlMinutes = 600;

        public List<String> udp2rawSocksServers;
        public String udp2rawClient;
        public String kcptunClient;

        //route
        public boolean enableRoute = true;
        public Set<String> routeDstGeoSiteDirectRules;
        public int routeSrcSteeringTTL;

        //ddns
        public int ddnsJobSeconds;
        public List<String> ddnsDomains;
        public String ddnsApiKey;
        public String ddnsApiProxy;

        public String pcapSourceIp;
        public boolean pcapUdpDirect;

        public boolean hasRouteFlag() {
            return (logFlags & 1) == 1;
        }

        public boolean hasDebugFlag() {
            return (logFlags & 2) == 2;
        }
    }

    public static final OptimalSettings OUT_OPS = new OptimalSettings((int) (640 * 0.8), 150, 60, 1000, OptimalSettings.Mode.LOW_LATENCY);
    public static final OptimalSettings IN_OPS = null;
    public static final OptimalSettings SS_IN_OPS = new OptimalSettings((int) (1024 * 0.8), 30, 200, 2000, OptimalSettings.Mode.BALANCED);
    static final FastThreadLocal<Upstream> frontBTcpUpstream = new FastThreadLocal<>(),
            frontBUdpUpstream = new FastThreadLocal<>();
    static final FastThreadLocal<SocksTcpUpstream> inTcpProxyUpstream = new FastThreadLocal<>(),
            ssTcpUpstream = new FastThreadLocal<>();
    static final FastThreadLocal<SocksUdpUpstream> inUdpProxyUpstream = new FastThreadLocal<>(),
            ssUdpUpstream = new FastThreadLocal<>();
    static RSSConf rssConf;

    @SneakyThrows
    static void launchClient(Map<String, String> options, Integer port, Integer connectTimeout) {
        //Udp2raw 将 UDP 转换为 FakeTCP、ICMP
        boolean enableUdp2raw = "1".equals(options.get("udp2raw"));
        int udp2rawPort = port + 10;
        RandomList<UpstreamSupport> socksServers = new RandomList<>();
        RandomList<UpstreamSupport> udp2rawSocksServers = new RandomList<>();
        RandomList<DnsServer.ResolveInterceptor> dnsInterceptors = new RandomList<>();
        GeoManager geoMgr = GeoManager.INSTANCE;

        List<Object> svrRefs = new ArrayList<>();
        YamlConfiguration watcher = new YamlConfiguration("conf.yml").enableWatch();
        watcher.onChanged.combine((s, e) -> {
            RSSConf changed = s.readAs(RSSConf.class);
            if (changed == null) {
                return;
            }

            rssConf = changed;
            Linq<AuthenticEndpoint> svrs = Linq.from(rssConf.socksServers).where(Objects::nonNull).select(p -> Reflects.convertQuietly(p, AuthenticEndpoint.class));
            if (!svrs.any()) {
                throw new InvalidException("Invalid shadowServer arg");
            }
            Linq<AuthenticEndpoint> udp2rawSvrs = Linq.from(rssConf.udp2rawSocksServers).where(Objects::nonNull).select(p -> Reflects.convertQuietly(p, AuthenticEndpoint.class));
            geoMgr.setGeoSiteDirectRules(rssConf.routeDstGeoSiteDirectRules);

            List<UpstreamSupport> oldSvrs = socksServers.aliveList();
            List<UpstreamSupport> oldUdp2rawSvrs = udp2rawSocksServers.aliveList();
            List<DnsServer.ResolveInterceptor> oldDnss = dnsInterceptors.aliveList();

            SocksSupport firstSupport = null;
            for (AuthenticEndpoint socksServer : svrs) {
                RpcClientConfig<SocksSupport> rpcConf = RpcClientConfig.poolMode(Sockets.newEndpoint(socksServer.getEndpoint(), socksServer.getEndpoint().getPort() + 1),
                        rssConf.rpcMinSize, rssConf.rpcMaxSize);
                TcpClientConfig tcpConfig = rpcConf.getTcpConfig();
                tcpConfig.setTransportFlags(TransportFlags.GFW.flags(TransportFlags.CIPHER_BOTH).flags());
//                tcpConfig.setTransportFlags(TransportFlags.CLIENT_HTTP_PSEUDO_BOTH.flags(TransportFlags.CLIENT_CIPHER_BOTH));
                int weight = Reflects.convertQuietly(socksServer.getParameters().get("w"), int.class, 0);
                if (weight <= 0) {
                    continue;
                }
                SocksSupport facade = Remoting.createFacade(SocksSupport.class, rpcConf);
                if (firstSupport == null) {
                    firstSupport = facade;
                }
                UpstreamSupport us = new UpstreamSupport(socksServer, new SocksSupport() {
                    @Override
                    public void fakeEndpoint(BigInteger hash, String realEndpoint) {
                        facade.fakeEndpoint(hash, realEndpoint);
                    }

                    @Override
                    public List<InetAddress> resolveHost(String host) {
                        if (geoMgr.matchSiteDirect(host)) {
                            return DnsClient.inlandClient().resolveAll(host);
                        }

                        return facade.resolveHost(host);
                    }

                    @Override
                    public void addWhiteList(InetAddress endpoint) {
                        facade.addWhiteList(endpoint);
                    }
                });
                socksServers.add(us, weight);
                dnsInterceptors.add(us.getSupport());
            }
            for (AuthenticEndpoint socksServer : udp2rawSvrs) {
                int weight = Reflects.convertQuietly(socksServer.getParameters().get("w"), int.class, 0);
                if (weight <= 0) {
                    continue;
                }
                UpstreamSupport us = new UpstreamSupport(socksServer, firstSupport);
                udp2rawSocksServers.add(us, weight);
            }

            socksServers.removeAll(oldSvrs);
            udp2rawSocksServers.removeAll(oldUdp2rawSvrs);
            dnsInterceptors.removeAll(oldDnss);
            for (UpstreamSupport support : oldSvrs) {
                tryClose(support.getSupport());
            }
            for (UpstreamSupport support : oldUdp2rawSvrs) {
                tryClose(support.getSupport());
            }
            log.info("reload svrs {}", toJsonString(svrs));

            boolean debugFlag = rssConf.hasDebugFlag();
            for (Object svrRef : svrRefs) {
                if (svrRef instanceof ShadowsocksServer) {
                    ((ShadowsocksServer) svrRef).getConfig().setDebug(debugFlag);
                } else {
                    ((SocksProxyServer) svrRef).getConfig().setDebug(debugFlag);
                }
            }
        });
        watcher.raiseChange();

        DnsServer dnsSvr = new DnsServer(rssConf.shadowDnsPort);
        dnsSvr.setTtl(60 * rssConf.dnsTtlMinutes);
        dnsSvr.setInterceptors(dnsInterceptors);
        dnsSvr.addHostsFile("hosts.txt");
        InetSocketAddress shadowDnsEp = Sockets.newLoopbackEndpoint(rssConf.shadowDnsPort);
        Sockets.injectNameService(Collections.singletonList(shadowDnsEp));

        Linq<Tuple<ShadowsocksConfig, SocksUser>> shadowUsers = Linq.from(rssConf.shadowUsers).select(shadowUser -> {
            String[] sArgs = Strings.split(shadowUser, ":", 4);
            ShadowsocksConfig config = new ShadowsocksConfig(Sockets.newAnyEndpoint(Integer.parseInt(sArgs[0])),
                    CipherKind.AES_256_GCM.getCipherName(), sArgs[1]);
            config.setUdpTimeoutSeconds(rssConf.udpTimeoutSeconds);
            SocksUser user = new SocksUser(sArgs[2]);
            user.setPassword(rssConf.socksPwd);
            user.setMaxIpCount(Integer.parseInt(sArgs[3]));
            return Tuple.of(config, user);
        });

        SocksConfig inConf = new SocksConfig(port);
//        inConf.setTransportFlags(null);
        inConf.setOptimalSettings(IN_OPS);
        inConf.setConnectTimeoutMillis(connectTimeout);
        inConf.setReadTimeoutSeconds(rssConf.tcpTimeoutSeconds);
        inConf.setUdpReadTimeoutSeconds(rssConf.udpTimeoutSeconds);
        DefaultSocksAuthenticator authenticator = new DefaultSocksAuthenticator(shadowUsers.select(p -> p.right).toList());
        Upstream shadowDnsUpstream = new Upstream(new UnresolvedEndpoint(shadowDnsEp));
        TripleAction<SocksProxyServer, SocksContext> firstRoute = (s, e) -> {
            UnresolvedEndpoint dstEp = e.getFirstDestination();
            //must first
            if (dstEp.getPort() == SocksSupport.DNS_PORT) {
                e.setUpstream(shadowDnsUpstream);
                e.setHandled(true);
//                return;
            }
        };
        SocksProxyServer inSvr = createInSvr(inConf, authenticator, firstRoute, socksServers, geoMgr);
        svrRefs.add(inSvr);
        Main app = new Main(inSvr);
        if (enableUdp2raw) {
            SocksConfig inUdp2rawConf = Sys.deepClone(inConf);
            inUdp2rawConf.setListenPort(udp2rawPort);
            inUdp2rawConf.setEnableUdp2raw(enableUdp2raw);
            inUdp2rawConf.setUdp2rawClient(Sockets.parseEndpoint(rssConf.udp2rawClient));
            inUdp2rawConf.setKcptunClient(rssConf.kcptunClient);
            SocksProxyServer inUdp2rawSvr = createInSvr(inUdp2rawConf, authenticator, firstRoute, udp2rawSocksServers, geoMgr);
            svrRefs.add(inUdp2rawSvr);
        }

        Action fn = () -> {
            InetAddress addr = InetAddress.getByName(geoMgr.getPublicIp());
            eachQuietly(socksServers, p -> p.getSupport().addWhiteList(addr));
        };
        fn.invoke();
        Tasks.schedulePeriod(fn, rssConf.rpcAutoWhiteListSeconds * 1000L);

        InetSocketAddress inSvrEp = Sockets.newLoopbackEndpoint(port);
        InetSocketAddress inUdp2rawSvrEp = Sockets.newLoopbackEndpoint(udp2rawPort);
        for (Tuple<ShadowsocksConfig, SocksUser> tuple : shadowUsers) {
            ShadowsocksConfig conf = tuple.left;
            SocksUser usr = tuple.right;
            InetSocketAddress upSvrEp = usr.getUsername().startsWith("tun") ? inUdp2rawSvrEp : inSvrEp;
            AuthenticEndpoint svrEp = new AuthenticEndpoint(upSvrEp, usr.getUsername(), usr.getPassword());

            conf.setOptimalSettings(SS_IN_OPS);
            conf.setConnectTimeoutMillis(connectTimeout);
            ShadowsocksServer ssSvr = new ShadowsocksServer(conf);
            svrRefs.add(ssSvr);
            SocksConfig toInConf = new SocksConfig(svrEp.getEndpoint().getPort());
//            toInConf.setTransportFlags(null);
            toInConf.setOptimalSettings(IN_OPS);
            UpstreamSupport svrSupport = new UpstreamSupport(svrEp, null);
            ssSvr.onRoute.replace((s, e) -> {
                UnresolvedEndpoint dstEp = e.getFirstDestination();
                SocksTcpUpstream upstream = ssTcpUpstream.get();
                if (upstream == null) {
                    upstream = new SocksTcpUpstream(toInConf, dstEp, svrSupport);
                } else {
                    upstream.reuse(toInConf, dstEp, svrSupport);
                }
                e.setUpstream(upstream);
            });
            ssSvr.onUdpRoute.replace((s, e) -> {
                UnresolvedEndpoint dstEp = e.getFirstDestination();
                SocksUdpUpstream upstream = ssUdpUpstream.get();
                if (upstream == null) {
                    upstream = new SocksUdpUpstream(toInConf, dstEp, svrSupport);
                } else {
                    upstream.reuse(toInConf, dstEp, svrSupport);
                }
                e.setUpstream(upstream);
            });
        }

        clientInit(authenticator);
        log.info("Server started..");
        app.await();
    }

    static SocksProxyServer createInSvr(SocksConfig inConf, DefaultSocksAuthenticator authenticator,
                                        TripleAction<SocksProxyServer, SocksContext> firstRoute, RandomList<UpstreamSupport> socksServers,
                                        GeoManager geoMgr) {
        SocksProxyServer inSvr = new SocksProxyServer(inConf, authenticator);
        boolean kcptun = inConf.getKcptunClient() != null;
        UpstreamSupport kcpUpstream = kcptun ? new UpstreamSupport(AuthenticEndpoint.valueOf(inConf.getKcptunClient()), null)
                : null;
        BiFunc<SocksContext, UpstreamSupport> routerFn = e -> {
//            String destHost = e.getFirstDestination().getHost();
            InetAddress srcHost = e.getSource().getAddress();
            UpstreamSupport next = socksServers.next(srcHost, rssConf.routeSrcSteeringTTL, true);
            if (kcptun) {
                kcpUpstream.setSupport(next.getSupport());
                return kcpUpstream;
            }
            return next;
        };
        SocksConfig outConf = Sys.deepClone(inConf);
        outConf.setTransportFlags(TransportFlags.GFW.flags(TransportFlags.COMPRESS_BOTH).flags());
        if (!kcptun) {
            outConf.setOptimalSettings(OUT_OPS);
        }
        TripleFunc<UnresolvedEndpoint, String, Boolean> routeingFn = (dstEp, transType) -> {
            String host = dstEp.getHost();
            boolean outProxy;
            long begin;
            String ext;
            if (rssConf.enableRoute) {
                begin = System.nanoTime();
                if (!Sockets.isValidIp(host)) {
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
                    if (Strings.equalsIgnoreCase(category, "cn") || Strings.equalsIgnoreCase(category, "private")) {
                        outProxy = false;
                    } else {
                        outProxy = true;
                    }
                    ext = "geoip:" + category;
                }
            } else {
                outProxy = true;
                begin = 0;
                ext = "routeDisabled";
            }
            if (rssConf.hasRouteFlag()) {
                log.info("route dst {} {} {} <- {} {}", transType, host, outProxy ? "PROXY" : "DIRECT", ext,
                        Sys.formatNanosElapsed(System.nanoTime() - begin));
            }
            return outProxy;
        };
        inSvr.onRoute.replace(firstRoute, (s, e) -> {
            if (e.getUpstream() != null) {
                return;
            }
            UnresolvedEndpoint dstEp = e.getFirstDestination();
            if (routeingFn.apply(dstEp, "TCP")) {
                SocksTcpUpstream upstream = inTcpProxyUpstream.get();
                if (upstream == null) {
                    upstream = new SocksTcpUpstream(outConf, dstEp, routerFn.apply(e));
                } else {
                    upstream.reuse(outConf, dstEp, routerFn.apply(e));
                }
                e.setUpstream(upstream);
            } else {
                Upstream upstream = frontBTcpUpstream.get();
                if (upstream == null) {
                    upstream = new Upstream(dstEp);
                } else {
                    upstream.reuse(null, dstEp);
                }
                e.setUpstream(upstream);
            }
        });
        inSvr.onUdpRoute.replace(firstRoute, (s, e) -> {
            if (e.getUpstream() != null) {
                return;
            }
//            if (rssConf.pcapSourceIp != null
//                    && InetAddress.getByName(rssConf.pcapSourceIp).equals(e.getSource().getAddress())) {
//                log.info("pcap pack {}", e.getSource());
//                if (rssConf.pcapUdpDirect) {
//                    e.setUpstream(new Upstream(dstEp));
//                    return;
//                }
//            }
//          if (conf.pcap2socks && e.getSource().getAddress().isLoopbackAddress()) {
//              Cache<String, Boolean> cache = Cache.getInstance(Cache.MEMORY_CACHE);
//              if (cache.get(hashKey("pcap", e.getSource().getPort()), k -> Sockets.socketInfos(SocketProtocol.UDP)
//                      .any(p -> p.getSource().getPort() == e.getSource().getPort()
//                              && Strings.startsWith(p.getProcessName(), "pcap2socks")))) {
//                  log.info("pcap2socks forward");
//                  e.setUpstream(new Upstream(dstEp));
//                  return;
//              }
//          }
            UnresolvedEndpoint dstEp = e.getFirstDestination();
            if (routeingFn.apply(dstEp, "UDP")) {
                SocksUdpUpstream upstream = inUdpProxyUpstream.get();
                if (upstream == null) {
                    upstream = new SocksUdpUpstream(outConf, dstEp, routerFn.apply(e));
                } else {
                    upstream.reuse(outConf, dstEp, routerFn.apply(e));
                }
                e.setUpstream(upstream);
            } else {
                Upstream upstream = frontBUdpUpstream.get();
                if (upstream == null) {
                    upstream = new Upstream(dstEp);
                } else {
                    upstream.reuse(null, dstEp);
                }
                e.setUpstream(upstream);
            }
        });
        inSvr.setCipherRouter(SocksProxyServer.DNS_CIPHER_ROUTER);
        return inSvr;
    }

    static void clientInit(DefaultSocksAuthenticator authenticator) {
        Tasks.schedulePeriod(() -> {
            Files.writeLines("usr-info.txt", Collections.singletonList(toJsonString(authenticator)));

            if (rssConf == null) {
                log.warn("conf is null");
            }

            InetAddress wanIp = InetAddress.getByName(GeoManager.INSTANCE.getPublicIp());
            List<String> subDomains = Linq.from(rssConf.ddnsDomains).where(sd -> !DnsClient.inlandClient().resolveAll(sd).contains(wanIp))
                    .select(sd -> sd.substring(0, sd.indexOf("."))).toList();
            if (subDomains.isEmpty()) {
                return;
            }
            String oneSd = rssConf.ddnsDomains.get(0);
            String domain = oneSd.substring(oneSd.indexOf(".") + 1);
            String res = setDDns(rssConf.ddnsApiKey, domain, subDomains, wanIp.getHostAddress());
            log.info("ddns set {} + {} @ {} -> {}", domain, subDomains, wanIp.getHostAddress(), res);
        }, rssConf.ddnsJobSeconds * 1000L);
    }

    @SneakyThrows
    static String setDDns(String apiKey, String domain, List<String> subDomains, String ip) {
//        AuthenticProxy p = conf.godaddyProxy != null
//                ? new AuthenticProxy(Proxy.Type.SOCKS, Sockets.parseEndpoint(conf.godaddyProxy))
//                : null;
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
            if ((j = subDomains.indexOf(subHost)) != -1
                    && "a".equals(sd.getString("record_type"))) {
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
//        HttpClient client = new HttpClient();
//        client.requestHeaders().set("Content-Type", "application/json");
//        client.requestHeaders().set("Accept", "application/json");
//        client.requestHeaders().set("Authorization", "Bearer " + apiKey);
//        client.requestHeaders().set("X-Signature", dynadotSign(apiKey, url, requestBody.toString()));
//        return client.postJson(url, requestBody).toString();

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
            return IOStream.readString(conn.getInputStream(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    static JSONObject getDDns(String apiKey, String domain) {
        String url = "https://api.dynadot.com/restful/v1/domains/" + domain + "/records";
        HttpClient client = new HttpClient();
        client.requestHeaders().set("Accept", "application/json");
        client.requestHeaders().set("Authorization", "Bearer " + apiKey);
        client.requestHeaders().set("X-Signature", dynadotSign(apiKey, url, ""));
        return client.get(url).toJson();
    }

    static String dynadotSign(String apiKey, String url, String requestBody) {
        int startIndex = url.indexOf("/", url.indexOf("//") + 2);
        String fullPathAndQuery = startIndex != -1 ? url.substring(startIndex) : "/";
        String stringToSign = apiKey + "\n" + fullPathAndQuery + "\n\n" + requestBody;
        return CodecUtil.toHex(CodecUtil.hmacSHA256(apiKey, stringToSign));
    }

    static HttpServer httpServer;

    static void launchServer(Map<String, String> options, Integer port, Integer connectTimeout) {
        boolean enableUdp2raw = "1".equals(options.get("udp2raw"));
        AuthenticEndpoint shadowUser = Reflects.convertQuietly(options.get("shadowUser"), AuthenticEndpoint.class);
        if (shadowUser == null) {
            log.info("Invalid shadowUser arg");
            return;
        }
        SocksUser ssUser = new SocksUser(shadowUser.getUsername());
        ssUser.setPassword(shadowUser.getPassword());
        ssUser.setMaxIpCount(-1);

        SocksConfig outConf = new SocksConfig(port);
        outConf.setTransportFlags(TransportFlags.GFW.flags(TransportFlags.COMPRESS_BOTH).flags());
        outConf.setOptimalSettings(OUT_OPS);
        outConf.setConnectTimeoutMillis(connectTimeout);
        Authenticator defAuth = (u, p) -> eq(u, ssUser.getUsername()) && eq(p, ssUser.getPassword()) ? ssUser : SocksUser.ANONYMOUS;
        SocksProxyServer outSvr = new SocksProxyServer(outConf, defAuth);
        outSvr.setCipherRouter(SocksProxyServer.DNS_CIPHER_ROUTER);
        if (enableUdp2raw) {
            SocksConfig outUdp2rawConf = Sys.deepClone(outConf);
            outUdp2rawConf.setListenPort(port + 10);
            outUdp2rawConf.setEnableUdp2raw(enableUdp2raw);
            SocksProxyServer outUdp2rawSvr = new SocksProxyServer(outUdp2rawConf, defAuth);
            outUdp2rawSvr.setCipherRouter(SocksProxyServer.DNS_CIPHER_ROUTER);
        }

        //server port + 1 = rpc
        RpcServerConfig rpcConf = new RpcServerConfig(new TcpServerConfig(port + 1));
        rpcConf.getTcpConfig().setTransportFlags(TransportFlags.GFW.flags(TransportFlags.CIPHER_BOTH).flags());
//        rpcConf.getTcpConfig().setTransportFlags(TransportFlags.SERVER_HTTP_PSEUDO_BOTH.flags(TransportFlags.SERVER_CIPHER_BOTH));
        Main app = new Main(outSvr);
        Remoting.register(app, rpcConf);
        serverInit();
        app.await();
    }

    static void serverInit() {
        httpServer = new HttpServer(8082, true)
                .requestMapping("/hf", (request, response) -> {
                    String url = request.getQueryString().getFirst("fu");
                    Integer tm = Reflects.convertQuietly(request.getQueryString().getFirst("tm"), Integer.class);
                    HttpClient client = new HttpClient();
                    if (tm != null) {
                        client.withTimeoutMillis(tm);
                    }
                    response.jsonBody(client.get(url).toJson());
                })
                .requestMapping("/getPublicIp", (request, response) -> {
                    response.jsonBody(request.getRemoteEndpoint().getHostString());
                })
//                .requestMapping("/geo", (request, response) -> {
//                    response.jsonBody(IPSearcher.DEFAULT.resolve(request.getQueryString().getFirst("host")));
//                })
        ;
    }

    final SocksProxyServer proxyServer;

    @Override
    public void fakeEndpoint(BigInteger hash, String endpoint) {
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
