package org.rx.util.rss;

import com.alibaba.fastjson2.JSONFactory;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.reader.ObjectReader;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
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
public class RssClientConf {
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
                    server.setUdp2raw(Boolean.TRUE.equals(obj.getBoolean("udp2raw")));
                    server.setTcpClient(readSocksServerEndpoint(obj.get("tcpClient")));
                    server.setUdp2rawClient(readSocketAddress(obj.get("udp2rawClient")));
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
    // RSS Server RPC 固定端口；0 表示按同 host 首个 socksServer 端口 +1 推导。
    public int rpcPort;
    public int rpcRequestTimeoutMillis = 3000;
    public int upstreamHealthCheckSeconds = RssClient.DEFAULT_UPSTREAM_HEALTH_CHECK_SECONDS;
    public int upstreamHealthFailureThreshold = RssClient.DEFAULT_UPSTREAM_HEALTH_FAILURE_THRESHOLD;
    public boolean upstreamFailOpenWhenAllDown = true;
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

    private static InetSocketAddress readSocketAddress(Object endpoint) {
        if (endpoint == null) {
            return null;
        }
        if (endpoint instanceof InetSocketAddress) {
            return (InetSocketAddress) endpoint;
        }
        if (endpoint instanceof String) {
            return Sockets.parseEndpoint((String) endpoint);
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
        // 标记该上游作为 udp2raw 入口上游。
        public boolean udp2raw;
        // TCP 走 kcptun/hysteria 等客户端时仅覆盖 endpoint 地址；账号密码默认沿用当前 socksServer。
        public AuthenticEndpoint tcpClient;
        // UDP 固定 udp2raw 目标预留字段；当前 UDP 仍按 SOCKS5 UDP 路由处理。
        public InetSocketAddress udp2rawClient;

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
