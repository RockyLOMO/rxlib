package org.rx.util.rss;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.net.AuthenticEndpoint;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;

@Getter
@Setter
@ToString
public class RSSConf {
    public int logFlags;

    // socks
    public List<ShadowUser> shadowUsers;
    public List<AuthenticEndpoint> socksServers;
    // false=LocalAddress 进程内转发，true=127.0.0.1:port 真实监听
    public boolean socksBindPort;
    public String socksPwd;
    public int connectTimeoutSeconds = 10;
    public int tcpTimeoutSeconds = 60 * 2;
    public int udpTimeoutSeconds = 60 * 10;
    public int rpcMinSize = 2;
    public int rpcMaxSize = 6;
    public int rpcAutoWhiteListSeconds = 120;
    public int shadowDnsPort = 753;
    public int dnsTtlMinutes = 600;
    public int trafficRetentionDays = 60;
    public int memoryRetentionHours = 24;

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
