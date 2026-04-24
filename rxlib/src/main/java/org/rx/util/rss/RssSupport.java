package org.rx.util.rss;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.netty.channel.local.LocalAddress;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.DateTime;
import org.rx.bean.RandomList;
import org.rx.bean.Tuple;
import org.rx.codec.CodecUtil;
import org.rx.core.Linq;
import org.rx.core.Reflects;
import org.rx.core.Strings;
import org.rx.core.Sys;
import org.rx.core.Tasks;
import org.rx.diagnostic.DiagnosticMonitor;
import org.rx.exception.InvalidException;
import org.rx.io.DuplexStream;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.OptimalSettings;
import org.rx.net.Sockets;
import org.rx.net.TransportFlags;
import org.rx.net.dns.DnsClient;
import org.rx.net.dns.DnsServer;
import org.rx.net.http.HttpClient;
import org.rx.net.http.HttpServer;
import org.rx.net.socks.Authenticator;
import org.rx.net.socks.AuthResult;
import org.rx.net.socks.RrpConfig;
import org.rx.net.socks.RrpServer;
import org.rx.net.socks.SocksConnectionTagRegistry;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.socks.SocksUdpRelayHandler;
import org.rx.net.socks.SocksUser;
import org.rx.net.socks.ShadowsocksConfig;
import org.rx.net.socks.UdpCompressCodec;
import org.rx.net.socks.UdpCompressConfig;
import org.rx.net.socks.UdpRelayAttributes;
import org.rx.net.socks.upstream.SocksTcpUpstream;
import org.rx.net.socks.upstream.SocksUdpUpstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.GeoManager;
import org.rx.net.support.IpGeolocation;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.rx.core.Extends.eachQuietly;
import static org.rx.core.Extends.eq;
import static org.rx.core.Extends.ifNull;
import static org.rx.core.Extends.tryClose;
import static org.rx.core.Sys.toJsonString;

@Slf4j
public final class RssSupport {
    public static final OptimalSettings OUT_OPS = new OptimalSettings((int) (640 * 0.8), 150, 60, 1000, OptimalSettings.Mode.LOW_LATENCY);
    public static final OptimalSettings IN_OPS = null;
    public static final OptimalSettings SS_IN_OPS = new OptimalSettings((int) (1024 * 0.8), 30, 200, 2000, OptimalSettings.Mode.BALANCED);
    public static final int TCP_TRIAL_COMPRESSION_LEVEL = 5;
    static RSSConf rssConf;
    static RrpServer rrpServer;
    static HttpServer httpServer;
    static RssUserTrafficStore trafficStore;

    /** Dynadot、/hf 等复用，避免每次 new HttpClient */
    static final HttpClient MAIN_HTTP_CLIENT = new HttpClient();

    private RssSupport() {
    }

    static void bootstrapRuntime() throws ClassNotFoundException {
        Class.forName(Sys.class.getName());
        DiagnosticMonitor.startDefault();
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
        UpstreamSupport kcpUpstream = kcptun ? new UpstreamSupport(inConf.getKcptunClient(), null) : null;
        BiFunc<SocksContext, UpstreamSupport> routerFn = e -> {
            InetAddress srcHost = e.getSource().getAddress();
            UpstreamSupport next = nextUpstream(socksServers, srcHost);
            if (rssConf.hasDebugFlag()) {
                log.info("route upSvr src {} -> {}", srcHost, next.getEndpoint());
            }
            if (kcptun) {
                kcpUpstream.setFacade(next.getFacade());
                return kcpUpstream;
            }
            return next;
        };
        SocksConfig outConf = Sys.deepClone(inConf);
        outConf.setTransportFlags(TransportFlags.GFW.flags(TransportFlags.COMPRESS_BOTH).flags());
        outConf.setTcpCompressionLevel(TCP_TRIAL_COMPRESSION_LEVEL);
        applyUdpCompressionTrial(outConf);
        if (!kcptun) {
            outConf.setOptimalSettings(OUT_OPS);
        }
        QuadraFunc<InetSocketAddress, UnresolvedEndpoint, String, Boolean> routeingFn = (srcEp, dstEp, transType) -> {
            String host = dstEp.getHost();
            boolean outProxy;
            long begin;
            String ext;
            RouteConf routeConf = rssConf.route;
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

    static void clientInit(RssAuthenticator authenticator) {
        if (trafficStore == null) {
            trafficStore = new RssUserTrafficStore(null);
            trafficStore.start();
            org.rx.net.socks.SocksUserTraffic.registerRecorder(trafficStore);
        }

        httpServer = HttpServer.getDefault().requestMapping("/usrInfo", (request, response) ->
                response.jsonBody(toShadowStorePayload(authenticator.getShadowStore())));

        if (!Strings.isEmpty(rssConf.rrpToken) && rssConf.rrpPort != null) {
            RrpConfig c = new RrpConfig();
            c.setToken(rssConf.rrpToken);
            c.setBindPort(rssConf.rrpPort);
            rrpServer = new RrpServer(c);
        }

        Tasks.schedulePeriod(() -> {
            if (rssConf == null || CollectionUtils.isEmpty(rssConf.ddnsDomains)) {
                return;
            }

            InetAddress wanIp = InetAddress.getByName(GeoManager.INSTANCE.getPublicIp());
            List<String> subDomains = Linq.from(rssConf.ddnsDomains)
                    .where(sd -> !DnsClient.inlandClient().resolveAll(sd).contains(wanIp))
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
                    try (HttpClient.Response remote = MAIN_HTTP_CLIENT.execute(req)) {
                        response.jsonBody(remote.bodyAsJson());
                    }
                });
    }

    static void applyUdpCompressionTrial(SocksConfig config) {
        config.setUdpCompressEnabled(true);
        config.setUdpCompressCodec(UdpCompressCodec.LZ4_FAST);
        config.setUdpCompressCompressionLevel(UdpCompressConfig.DEFAULT_COMPRESSION_LEVEL);
        config.setUdpCompressMinPayloadBytes(96);
        config.setUdpCompressMinSavingsBytes(24);
        config.setUdpCompressMinSavingsRatio(0.12D);
        config.setUdpCompressAdaptiveBypass(true);
        config.setUdpCompressAdaptiveBypassWindowSeconds(30);
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
        try (HttpClient.Response response = MAIN_HTTP_CLIENT.execute(req)) {
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
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
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
}
