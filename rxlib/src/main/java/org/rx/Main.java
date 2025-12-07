package org.rx;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.$;
import org.rx.bean.RandomList;
import org.rx.bean.Tuple;
import org.rx.codec.CodecUtil;
import org.rx.core.*;
import org.rx.exception.InvalidException;
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
import org.rx.net.socks.upstream.Socks5TcpUpstream;
import org.rx.net.socks.upstream.Socks5UdpUpstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.*;
import org.rx.net.transport.TcpClientConfig;
import org.rx.net.transport.TcpServerConfig;
import org.rx.util.function.Action;
import org.rx.util.function.BiFunc;
import org.rx.util.function.Func;
import org.rx.util.function.TripleAction;

import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.rx.bean.$.$;
import static org.rx.core.Extends.*;
import static org.rx.core.Sys.toJsonString;
import static org.rx.core.Tasks.awaitQuietly;

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
        public List<String> shadowServer;
        public String socksPwd;
        public int tcpTimeoutSeconds = 60 * 2;
        public int udpTimeoutSeconds = 60 * 10;
        public int rpcMinSize = 2;
        public int rpcMaxSize = 6;
        public int autoWhiteListSeconds = 120;
        public List<String> bypassHosts;
        public int steeringTTL;
        public List<String> gfwList;
        public List<String> directList;
        public boolean autoGfw;
        public int waitIpInfoMillis = 1000;
        public int ddnsJobSeconds;
        public List<String> ddnsDomains;
        public String ddnsApiKey;
        public String ddnsApiProxy;

        public String pcapSourceIp;
        public boolean pcapUdpDirect;
        public String udp2rawEndpoint;
    }

    static boolean udp2raw = false;
    static RSSConf conf;
    public static final OptimalSettings B = new OptimalSettings((int) (640 * 0.8), 150, 60, 1000, OptimalSettings.Mode.LOW_LATENCY);
    public static final OptimalSettings AF = new OptimalSettings((int) (1024 * 0.8), 30, 200, 2000, OptimalSettings.Mode.BALANCED);
    public static final OptimalSettings AB = null;

    @SneakyThrows
    static void launchClient(Map<String, String> options, Integer port, Integer connectTimeout) {
        String[] arg1 = Strings.split(options.get("shadowUsers"), ",");
        if (arg1.length == 0) {
            log.info("Invalid shadowUsers arg");
            return;
        }

        RandomList<UpstreamSupport> shadowServers = new RandomList<>();
        $<UpstreamSupport> defSS = $();
        RandomList<DnsServer.ResolveInterceptor> dnsInterceptors = new RandomList<>();
        SocksConfig frontBConf = new SocksConfig(port);
        YamlConfiguration watcher = new YamlConfiguration("conf.yml").enableWatch();
        watcher.onChanged.combine((s, e) -> {
            RSSConf changed = s.readAs(RSSConf.class);
            if (changed == null) {
                return;
            }

            conf = changed;
            Linq<AuthenticEndpoint> svrs = Linq.from(conf.shadowServer).select(p -> Reflects.convertQuietly(p, AuthenticEndpoint.class));
            if (!svrs.any() || svrs.any(Objects::isNull)) {
                throw new InvalidException("Invalid shadowServer arg");
            }
            for (UpstreamSupport support : shadowServers) {
                tryClose(support.getSupport());
            }
            shadowServers.clear();
            dnsInterceptors.clear();
            int defW = 0;
            for (AuthenticEndpoint shadowServer : svrs) {
                RpcClientConfig<SocksSupport> rpcConf = RpcClientConfig.poolMode(Sockets.newEndpoint(shadowServer.getEndpoint(), shadowServer.getEndpoint().getPort() + 1),
                        conf.rpcMinSize, conf.rpcMaxSize);
                TcpClientConfig tcpConfig = rpcConf.getTcpConfig();
                tcpConfig.setTransportFlags(TransportFlags.GFW.flags(TransportFlags.CLIENT_CIPHER_BOTH).flags());
//                tcpConfig.setTransportFlags(TransportFlags.CLIENT_HTTP_PSEUDO_BOTH.flags(TransportFlags.CLIENT_CIPHER_BOTH));
                int weight = Reflects.convertQuietly(shadowServer.getParameters().get("w"), int.class, 0);
                if (weight <= 0) {
                    continue;
                }
                SocksSupport facade = Remoting.createFacade(SocksSupport.class, rpcConf);
                UpstreamSupport upstream = new UpstreamSupport(shadowServer, new SocksSupport() {
                    @Override
                    public void fakeEndpoint(BigInteger hash, String realEndpoint) {
                        facade.fakeEndpoint(hash, realEndpoint);
                    }

                    @Override
                    public List<InetAddress> resolveHost(String host) {
                        if (Sockets.isBypass(conf.directList, host)) {
                            return DnsClient.inlandClient().resolveAll(host);
                        }
                        return facade.resolveHost(host);
                    }

                    @Override
                    public void addWhiteList(InetAddress endpoint) {
                        facade.addWhiteList(endpoint);
                    }
                });
                shadowServers.add(upstream, weight);
                if (defW < weight) {
                    defSS.v = upstream;
                    defW = weight;
                }
            }
            dnsInterceptors.addAll(Linq.from(shadowServers).<DnsServer.ResolveInterceptor>select(UpstreamSupport::getSupport).toList());
            log.info("reload svrs {}", toJsonString(svrs));

            if (conf.bypassHosts != null) {
                frontBConf.getBypassHosts().addAll(conf.bypassHosts);
            }
        });
        watcher.raiseChange();
        Linq<Tuple<ShadowsocksConfig, SocksUser>> shadowUsers = Linq.from(arg1).select(shadowUser -> {
            String[] sArgs = Strings.split(shadowUser, ":", 4);
            ShadowsocksConfig config = new ShadowsocksConfig(Sockets.newAnyEndpoint(Integer.parseInt(sArgs[0])),
                    CipherKind.AES_256_GCM.getCipherName(), sArgs[1]);
            config.setTcpTimeoutSeconds(conf.tcpTimeoutSeconds);
            config.setUdpTimeoutSeconds(conf.udpTimeoutSeconds);
            SocksUser user = new SocksUser(sArgs[2]);
            user.setPassword(conf.socksPwd);
            user.setMaxIpCount(Integer.parseInt(sArgs[3]));
            return Tuple.of(config, user);
        });

        Integer shadowDnsPort = Reflects.convertQuietly(options.get("shadowDnsPort"), Integer.class, 53);
        DnsServer dnsSvr = new DnsServer(shadowDnsPort);
        dnsSvr.setTtl(60 * 60 * 10); //12 hour
        dnsSvr.setInterceptors(dnsInterceptors);
        dnsSvr.addHostsFile("hosts.txt");
        InetSocketAddress shadowDnsEp = Sockets.newLoopbackEndpoint(shadowDnsPort);
        Sockets.injectNameService(Collections.singletonList(shadowDnsEp));

        frontBConf.setTransportFlags(null);
        frontBConf.setOptimalSettings(AB);
        frontBConf.setConnectTimeoutMillis(connectTimeout);
        frontBConf.setReadTimeoutSeconds(conf.tcpTimeoutSeconds);
        frontBConf.setUdpReadTimeoutSeconds(conf.udpTimeoutSeconds);
        frontBConf.setEnableUdp2raw(udp2raw);
        frontBConf.setUdp2rawServers(Linq.from(shadowServers).select(p -> p.getEndpoint().getEndpoint()).toList());
        if (frontBConf.isEnableUdp2raw() && conf.udp2rawEndpoint != null) {
            log.info("udp2rawEndpoint: {}", conf.udp2rawEndpoint);
            AuthenticEndpoint udp2rawSvrEp = AuthenticEndpoint.valueOf(conf.udp2rawEndpoint);
            frontBConf.getUdp2rawServers().add(udp2rawSvrEp.getEndpoint());
        }
        DefaultSocksAuthenticator authenticator = new DefaultSocksAuthenticator(shadowUsers.select(p -> p.right).toList());
        SocksProxyServer frontBSvr = new SocksProxyServer(frontBConf, authenticator);
        Upstream shadowDnsUpstream = new Upstream(new UnresolvedEndpoint(shadowDnsEp));
        TripleAction<SocksProxyServer, SocksContext> firstRoute = (s, e) -> {
            UnresolvedEndpoint dstEp = e.getFirstDestination();
            //must first
            if (dstEp.getPort() == SocksSupport.DNS_PORT) {
                e.setUpstream(shadowDnsUpstream);
                e.setHandled(true);
                return;
            }
            //bypass
            if (Sockets.isBypass(frontBConf.getBypassHosts(), dstEp.getHost())) {
                e.setUpstream(new Upstream(dstEp));
                e.setHandled(true);
            }
        };
        int[] httpPorts = {80, 443};
        BiFunc<SocksContext, Func<UpstreamSupport>> routerFn = e -> {
            if (Arrays.contains(httpPorts, e.getFirstDestination().getPort())) {
                return shadowServers::next;
            }
//            String destHost = e.getFirstDestination().getHost();
            InetAddress srcHost = e.getSource().getAddress();
            return () -> shadowServers.next(srcHost, conf.steeringTTL, true);
        };
        SocksConfig backConf = Sys.deepClone(frontBConf);
        backConf.setTransportFlags(TransportFlags.GFW.flags(TransportFlags.COMPRESS_BOTH).flags());
        backConf.setOptimalSettings(B);
        frontBSvr.onRoute.replace(firstRoute, (s, e) -> {
            e.setUpstream(new Socks5TcpUpstream(backConf, e.getFirstDestination(), routerFn.apply(e)));
        });
        frontBSvr.onUdpRoute.replace(firstRoute, (s, e) -> {
            UnresolvedEndpoint dstEp = e.getFirstDestination();
            if (conf.pcapSourceIp != null
                    && InetAddress.getByName(conf.pcapSourceIp).equals(e.getSource().getAddress())) {
                log.info("pcap pack {}", e.getSource());
                if (conf.pcapUdpDirect) {
                    e.setUpstream(new Upstream(dstEp));
                    return;
                }
            }
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
//          if (frontConf.isEnableUdp2raw()) {
//              if (udp2rawSvrEp != null) {
//                  e.setValue(new Upstream(dstEp, udp2rawSvrEp));
//              } else {
//                  e.setValue(new Upstream(dstEp, shadowServers.next().getEndpoint()));
//              }
//              return;
//          }
            e.setUpstream(new Socks5UdpUpstream(backConf, dstEp, routerFn.apply(e)));
        });
        frontBSvr.setCipherRouter(SocksProxyServer.DNS_CIPHER_ROUTER);
        Main app = new Main(frontBSvr);

        Action fn = () -> {
            InetAddress addr = InetAddress.getByName(IPSearcher.DEFAULT.resolvePublicIp().getIp());
            eachQuietly(shadowServers, p -> p.getSupport().addWhiteList(addr));
        };
        fn.invoke();
        Tasks.schedulePeriod(fn, conf.autoWhiteListSeconds * 1000L);

        InetSocketAddress frontSvrEp = Sockets.newLoopbackEndpoint(port);
        for (Tuple<ShadowsocksConfig, SocksUser> tuple : shadowUsers) {
            ShadowsocksConfig conf = tuple.left;
            SocksUser usr = tuple.right;
            AuthenticEndpoint srvEp = new AuthenticEndpoint(frontSvrEp, usr.getUsername(), usr.getPassword());

            conf.setOptimalSettings(AF);
            conf.setConnectTimeoutMillis(connectTimeout);
            ShadowsocksServer svr = new ShadowsocksServer(conf);
            TripleAction<ShadowsocksServer, SocksContext> ssFirstRoute = (s, e) -> {
                UnresolvedEndpoint dstEp = e.getFirstDestination();
                //must first
                if (dstEp.getPort() == SocksSupport.DNS_PORT) {
                    e.setUpstream(shadowDnsUpstream);
                    e.setHandled(true);
                    return;
                }
                //bypass
                if (Sockets.isBypass(conf.getBypassHosts(), dstEp.getHost())) {
//                    log.info("ss bypass: {}", dstEp);
                    e.setUpstream(new Upstream(dstEp));
                    e.setHandled(true);
                }
            };
            SocksConfig toAFConf = new SocksConfig(port);
            toAFConf.setOptimalSettings(AB);
//            toAFConf.setTransportFlags(conf.getTransportFlags());
            svr.onRoute.replace(ssFirstRoute, (s, e) -> {
                //gateway
                boolean gfw;
                String host = e.getFirstDestination().getHost();
                if (Sockets.isBypass(Main.conf.gfwList, host)) {
//                    log.info("ss gfw: {}", host);
                    gfw = true;
                } else if (Sockets.isBypass(Main.conf.directList, host)) {
                    gfw = false;
                } else if (Main.conf.autoGfw) {
                    IPAddress ipAddress = awaitQuietly(() -> IPSearcher.DEFAULT.resolve(host), Main.conf.waitIpInfoMillis);
                    gfw = ipAddress == null || !ipAddress.isChina();
                } else {
                    gfw = true;
                }
                if (!gfw) {
                    e.setUpstream(new Upstream(e.getFirstDestination()));
                    return;
                }

                e.setUpstream(new Socks5TcpUpstream(toAFConf, e.getFirstDestination(), () -> new UpstreamSupport(srvEp, null)));
            });
            svr.onUdpRoute.replace(ssFirstRoute, (s, e) -> {
                e.setUpstream(new Upstream(toAFConf, e.getFirstDestination(), srvEp));
            });
        }

        clientInit(authenticator);
        log.info("Server started..");
        app.await();
    }

    static void clientInit(DefaultSocksAuthenticator authenticator) {
        Tasks.schedulePeriod(() -> {
            log.info(authenticator.toString());

            if (conf == null) {
                log.warn("conf is null");
            }

            InetAddress wanIp = InetAddress.getByName(IPSearcher.DEFAULT.getPublicIp());
            List<String> subDomains = Linq.from(conf.ddnsDomains).where(sd -> !DnsClient.inlandClient().resolveAll(sd).contains(wanIp))
                    .select(sd -> sd.substring(0, sd.indexOf("."))).toList();
            if (subDomains.isEmpty()) {
                return;
            }
            String oneSd = conf.ddnsDomains.get(0);
            String domain = oneSd.substring(oneSd.indexOf(".") + 1);
            String res = setDDns(conf.ddnsApiKey, domain, subDomains, wanIp.getHostAddress());
            log.info("ddns set {} + {} @ {} -> {}", domain, subDomains, wanIp.getHostAddress(), res);
        }, conf.ddnsJobSeconds * 1000L);
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
        AuthenticEndpoint shadowUser = Reflects.convertQuietly(options.get("shadowUser"), AuthenticEndpoint.class);
        if (shadowUser == null) {
            log.info("Invalid shadowUser arg");
            return;
        }
        SocksUser ssUser = new SocksUser(shadowUser.getUsername());
        ssUser.setPassword(shadowUser.getPassword());
        ssUser.setMaxIpCount(-1);

        SocksConfig backConf = new SocksConfig(port);
        backConf.setTransportFlags(TransportFlags.GFW.flags(TransportFlags.COMPRESS_BOTH).flags());
        backConf.setOptimalSettings(B);
        backConf.setConnectTimeoutMillis(connectTimeout);
        backConf.setEnableUdp2raw(udp2raw);
        SocksProxyServer backSvr = new SocksProxyServer(backConf, (u, p) -> eq(u, ssUser.getUsername()) && eq(p, ssUser.getPassword()) ? ssUser : SocksUser.ANONYMOUS);
        backSvr.setCipherRouter(SocksProxyServer.DNS_CIPHER_ROUTER);

        //server port + 1 = rpc
        RpcServerConfig rpcConf = new RpcServerConfig(new TcpServerConfig(port + 1));
        rpcConf.getTcpConfig().setTransportFlags(TransportFlags.GFW.flags(TransportFlags.SERVER_CIPHER_BOTH).flags());
//        rpcConf.getTcpConfig().setTransportFlags(TransportFlags.SERVER_HTTP_PSEUDO_BOTH.flags(TransportFlags.SERVER_CIPHER_BOTH));
        Main app = new Main(backSvr);
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
                .requestMapping("/geo", (request, response) -> {
                    response.jsonBody(IPSearcher.DEFAULT.resolve(request.getQueryString().getFirst("host")));
                });
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
