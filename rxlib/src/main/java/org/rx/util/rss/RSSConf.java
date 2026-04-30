package org.rx.util.rss;

import com.alibaba.fastjson2.JSONFactory;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.reader.ObjectReader;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.nameserver.NameserverConfig;

import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Getter
@Setter
@ToString
public class RSSConf {
    static {
        JSONFactory.getDefaultObjectReaderProvider().register(SocksServer.class,
                (ObjectReader<SocksServer>) (jsonReader, fieldType, fieldName, features) -> {
                    JSONObject obj = jsonReader.read(JSONObject.class);
                    if (obj == null) {
                        return null;
                    }
                    SocksServer server = new SocksServer();
                    server.setId(obj.getString("id"));
                    server.setWeight(obj.getInteger("weight"));
                    Object endpoint = obj.get("endpoint");
                    server.setEndpoint(readSocksServerEndpoint(endpoint));
                    return server;
                });
    }

    public int logFlags;

    // socks
    public List<ShadowUser> shadowUsers;
    public List<SocksServer> socksServers;
    // false=LocalAddress 进程内转发，true=127.0.0.1:port 真实监听
    public boolean socksBindPort;
    public String socksPwd;
    public int connectTimeoutSeconds = 10;
    public int tcpTimeoutSeconds = 60 * 2;
    public int udpTimeoutSeconds = 60 * 10;
    public int rpcMinSize = 2;
    public int rpcMaxSize = 6;
    public int rpcRequestTimeoutMillis = 3000;
    public int rpcAutoWhiteListSeconds = 120;
    public boolean udpLeasePoolEnabled = true;
    public int udpLeasePoolMinSize = 2;
    public int udpLeasePoolMaxSize = 32;
    public int udpLeasePoolMaxIdleMillis = 300_000;
    public int udpLeaseRpcBreakerThreshold = 3;
    public int udpLeaseRpcBreakerOpenSeconds = 30;
    public int shadowDnsPort = 753;
    public int dnsTtlMinutes = 600;
    public NameserverConfig nameserver = new NameserverConfig();
    public int trafficRetentionDays = 60;
    public int memoryRetentionHours = RssAuthenticator.DEFAULT_MEMORY_RETENTION_HOURS;

    // rrp
    public String rrpToken;
    public Integer rrpPort;

    public List<AuthenticEndpoint> udp2rawSocksServers;
    public InetSocketAddress udp2rawClient;
    // 传递后tcp走kcptun
    public AuthenticEndpoint kcptunClient;
    public AuthenticEndpoint hysteriaClient;

    // route
    public RouteConf route = new RouteConf();

    // ddns
    public int ddnsJobSeconds;
    public List<String> ddnsDomains;
    public String ddnsApiKey;
    public String ddnsApiProxy;

    public boolean hasRouteFlag() {
        return (logFlags & 1) == 1;
    }

    public boolean hasDebugFlag() {
        return (logFlags & 2) == 2;
    }

    @SuppressWarnings("unchecked")
    private static AuthenticEndpoint readSocksServerEndpoint(Object endpoint) {
        if (endpoint == null) {
            return null;
        }
        if (endpoint instanceof AuthenticEndpoint) {
            return (AuthenticEndpoint) endpoint;
        }
        if (endpoint instanceof String) {
            return AuthenticEndpoint.valueOf((String) endpoint);
        }
        if (endpoint instanceof Map) {
            return new JSONObject((Map<String, Object>) endpoint).to(AuthenticEndpoint.class);
        }
        return null;
    }

    @Getter
    @Setter
    @ToString
    public static class SocksServer implements Serializable {
        private static final long serialVersionUID = 6049607274827395471L;

        public String id;
        public Integer weight;
        public AuthenticEndpoint endpoint;

        public SocksServer() {
        }

        public SocksServer(String id, int weight, AuthenticEndpoint endpoint) {
            this.id = id;
            this.weight = weight;
            this.endpoint = endpoint;
        }
    }

    @Getter
    @Setter
    @ToString
    public static class RouteConf {
        public boolean enable;
        public Set<String> dstGeoSiteDirectRules;
        public Set<InetAddress> srcIpProxyRules;
        public int srcSteeringTTL;
    }
}
