package org.rx.util.rss;

import com.alibaba.fastjson2.JSONFactory;
import com.alibaba.fastjson2.annotation.JSONField;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.reader.ObjectReader;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.EgressConfig;
import org.rx.net.Sockets;
import org.rx.net.nameserver.NameserverConfig;
import org.rx.net.registry.NodeRegistryConfig;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

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
                    for (String field : obj.keySet()) {
                        switch (field) {
                            case "id": case "weight": case "type": case "endpoint": case "rssEndpoint":
                            case "rpcEndpoint": case "tcpClient": case "udpClient": case "adaptiveUdpClient":
                            case "egress":
                                break;
                            default:
                                throw new IllegalArgumentException("Unknown RSS upstream field: " + field);
                        }
                    }
                    SocksServer server = new SocksServer();
                    String type = obj.getString("type");
                    if (type != null) {
                        server.type = UpstreamType.valueOf(type.trim().toUpperCase(java.util.Locale.ROOT));
                    }
                    server.rssEndpoint = readSocksServerEndpoint(obj.get("rssEndpoint"));
                    server.rpcEndpoint = readSocketAddress(obj.get("rpcEndpoint"));
                    server.setId(obj.getString("id"));
                    server.setWeight(obj.getInteger("weight"));
                    Object endpoint = obj.get("endpoint");
                    server.setEndpoint(readSocksServerEndpoint(endpoint));
                    server.setTcpClient(readSocksServerEndpoint(obj.get("tcpClient")));
                    server.setUdpClient(readSocketAddress(obj.get("udpClient")));
                    server.setAdaptiveUdpClient(readSocketAddress(obj.get("adaptiveUdpClient")));
                    server.setEgress(readEgress(obj.get("egress")));
                    return server;
                });
    }

    public int logFlags;
    public Egress egress = new Egress();
    /** Default RSS private TCP handshake mode: lightweight (default, 0 added RTT) | core (explicit full RXTC). */
    public String handshakeMode;
    /** Unified private security mode: fast (default, AES-128-GCM + HMAC-SHA256) | none. */
    public String securityMode;

    @lombok.EqualsAndHashCode
    @ToString
    public static class Egress implements Serializable {
        private static final long serialVersionUID = 1L;
        /** Null means inherit from parent scope (global -> user -> upstream); explicit "none" overrides to NONE. */
        public String tcpCodec;
        public String udpCodec;
        public String httpBodyEncoding;
        public Boolean httpRandomHeaders;
        public Boolean httpAllowGetMethod;
        public String httpHost;
        public String httpPath;
        public Integer maxHeaderBytes;
        public Integer maxBodyBytes;
        public Integer maxInflateBytes;
        public Integer detectTimeoutMillis;
        public List<String> allowedCodecs;

        public EgressConfig toClientEgressConfig() {
            return toClientEgressConfig(null);
        }

        public EgressConfig toClientEgressConfig(EgressConfig fallback) {
            if (udpCodec != null && !"none".equalsIgnoreCase(udpCodec.trim())) {
                throw new IllegalArgumentException("RSS UDP egress codec must be none");
            }
            if (allowedCodecs != null && !allowedCodecs.isEmpty()) {
                throw new IllegalArgumentException("allowedCodecs is only supported on server egress config");
            }
            EgressConfig cfg = fallback != null ? fallback.copy() : new EgressConfig();
            if (tcpCodec != null) {
                cfg.setTcpCodec(org.rx.net.EgressCodec.parse(tcpCodec));
            } else if (fallback == null) {
                cfg.setTcpCodec(org.rx.net.EgressCodec.NONE);
            }
            if (httpBodyEncoding != null) {
                cfg.setHttpBodyEncoding(org.rx.net.EgressCodec.HttpBodyEncoding.parse(httpBodyEncoding));
            }
            if (httpRandomHeaders != null) {
                cfg.setHttpRandomHeaders(httpRandomHeaders.booleanValue());
            }
            if (httpAllowGetMethod != null) {
                cfg.setHttpAllowGetMethod(httpAllowGetMethod.booleanValue());
            }
            if (httpHost != null) {
                cfg.setHttpHost(httpHost);
            }
            if (httpPath != null) {
                cfg.setHttpPath(httpPath);
            }
            if (maxHeaderBytes != null) {
                cfg.setMaxHeaderBytes(maxHeaderBytes.intValue());
            }
            if (maxBodyBytes != null) {
                cfg.setMaxBodyBytes(maxBodyBytes.intValue());
            }
            if (maxInflateBytes != null) {
                cfg.setMaxInflateBytes(maxInflateBytes.intValue());
            }
            if (detectTimeoutMillis != null) {
                cfg.setDetectTimeoutMillis(detectTimeoutMillis.intValue());
            }
            cfg.validateClient();
            return cfg;
        }
    }

    @Getter
    @Setter
    @ToString(exclude = {"authToken"})
    public static class NodeServiceConf implements Serializable {
        private static final long serialVersionUID = 1L;
        public boolean enabled;
        public boolean rendezvousEnabled;
        public boolean relayEnabled;
        public int rendezvousPort;
        public int udpObservePort;
        public int relayPort;
        @JSONField(serialize = false)
        public String authToken;
        public EgressConfig egressConfig = EgressConfig.serverAutoAll();
        /** Test-only toggle permitting port 0 ephemeral bindings; production requires explicit ports > 0. */
        public boolean allowEphemeralPorts;
    }

    // socks
    public List<ShadowUser> shadowUsers;
    public int[] tcpCipherPorts = RssTransport.cipherPorts();
    @JSONField(alternateNames = {"tcpAlreadyEncryptedPorts"})
    public int[] tcpCodecBypassPorts = RssTransport.alreadyEncryptedPorts();
    public int[] udpCipherPorts = RssTransport.udpCipherPorts();
    @JSONField(alternateNames = {"udpAlreadyEncryptedPorts"})
    public int[] udpCodecBypassPorts = {443, 853};
    public UserRule defaultRoute;
    @JSONField(serialize = false, deserialize = false)
    @ToString.Exclude
    transient UserRuleMatcher defaultRouteMatcher;
    public List<SocksServer> socksServers;
    // false=LocalAddress 进程内转发，true=127.0.0.1:port 真实监听
    public boolean socksBindPort;
    public String socksPwd;
    public int connectTimeoutSeconds = 10;
    // Close TCP relays only when neither direction has made progress.
    public int tcpTimeoutSeconds = 60 * 2;
    public int udpTimeoutSeconds = 60 * 10;
    // 全局可选 UDP payload MTU 上限（字节，不含 IP/UDP 头）；null 表示 plain=0（透传）、daily/weak/game=1300。
    public Integer udpMtu;
    public int rpcMinSize = 1;
    public int rpcMaxSize = 6;
    // RSS Server RPC 固定端口；0 表示按同 host 首个 socksServer 端口 +1 推导。
    public int rpcPort;
    public int rpcRequestTimeoutMillis = 3000;
    public int upstreamHealthCheckSeconds = RssClient.DEFAULT_UPSTREAM_HEALTH_CHECK_SECONDS;
    public int upstreamHealthFailureThreshold = RssClient.DEFAULT_UPSTREAM_HEALTH_FAILURE_THRESHOLD;
    public boolean upstreamFailOpenWhenAllDown = true;
    public int rpcAutoWhiteListSeconds = 120;
    // Optional single game-host identity. The read-only HTTPS query key is never sent here.
    public String wowEndpointClientId;
    @com.alibaba.fastjson2.annotation.JSONField(serialize = false)
    @ToString.Exclude
    public String wowEndpointWriterKey;
    public String wowEndpointReadyFile;
    public String wowEndpointRpcEndpoint;
    public String[] publicIpEndpoints;
    public boolean udpLeasePoolEnabled = true;
    public int udpLeasePoolMinSize = 2;
    public int udpLeasePoolMaxSize = 32;
    public int udpLeasePoolMaxIdleMillis = 300_000;
    public int udpLeaseRpcBreakerThreshold = 3;
    public int udpLeaseRpcBreakerOpenSeconds = 30;
    public int shadowDnsPort = 753;
    public int dnsTtlMinutes = 600;
    public NameserverConfig nameserver = new NameserverConfig();
    /** Opt-in shared NodeRegistry control-plane directory (default disabled: zero H2/timers when off). */
    public NodeRegistryConfig nodeRegistry = new NodeRegistryConfig();
    /** Opt-in public node coordination & on-demand RRP RAW Relay service (default disabled). */
    public NodeServiceConf nodeService = new NodeServiceConf();
    public int trafficRetentionDays = 60;
    public int memoryRetentionHours = RssAuthenticator.DEFAULT_MEMORY_RETENTION_HOURS;

    // rrp
    public String rrpToken;
    public Integer rrpPort;

    // ddns
    public int ddnsJobSeconds;
    public List<String> ddnsDomains;
    public String ddnsApiKey;
    public String ddnsApiProxy;
    @JSONField(serialize = false)
    @ToString.Exclude
    public String[] ddnsLocalCommand;
    public int ddnsLocalCommandTimeoutSeconds = 10;

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
        throw new IllegalArgumentException("Invalid upstream endpoint");
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
        throw new IllegalArgumentException("Invalid upstream socket address");
    }

    @SuppressWarnings("unchecked")
    private static Egress readEgress(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Egress) {
            return (Egress) raw;
        }
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException("Invalid RSS egress configuration");
        }
        JSONObject obj = raw instanceof JSONObject ? (JSONObject) raw : new JSONObject((Map<String, Object>) raw);
        for (String field : obj.keySet()) {
            switch (field) {
                case "tcpCodec":
                case "udpCodec":
                case "httpBodyEncoding":
                case "httpRandomHeaders":
                case "httpAllowGetMethod":
                case "httpHost":
                case "httpPath":
                case "maxHeaderBytes":
                case "maxBodyBytes":
                case "maxInflateBytes":
                case "detectTimeoutMillis":
                case "allowedCodecs":
                    break;
                default:
                    throw new IllegalArgumentException("Unknown RSS egress field: " + field);
            }
        }
        Egress egress = new Egress();
        String tcpCodec = obj.getString("tcpCodec");
        if (tcpCodec != null) {
            egress.tcpCodec = tcpCodec;
        }
        String udpCodec = obj.getString("udpCodec");
        if (udpCodec != null) {
            egress.udpCodec = udpCodec;
        }
        egress.httpBodyEncoding = obj.getString("httpBodyEncoding");
        egress.httpRandomHeaders = obj.getBoolean("httpRandomHeaders");
        egress.httpAllowGetMethod = obj.getBoolean("httpAllowGetMethod");
        egress.httpHost = obj.getString("httpHost");
        egress.httpPath = obj.getString("httpPath");
        egress.maxHeaderBytes = obj.getInteger("maxHeaderBytes");
        egress.maxBodyBytes = obj.getInteger("maxBodyBytes");
        egress.maxInflateBytes = obj.getInteger("maxInflateBytes");
        egress.detectTimeoutMillis = obj.getInteger("detectTimeoutMillis");
        if (obj.containsKey("allowedCodecs")) {
            egress.allowedCodecs = obj.getList("allowedCodecs", String.class);
        }
        return egress;
    }

    public enum UpstreamType {
        RSS, SOCKS5, UDPCLIENT
    }

    @Getter
    @Setter
    @ToString(onlyExplicitlyIncluded = true)
    public static class SocksServer implements Serializable {
        private static final long serialVersionUID = 6049607274827395471L;

        @ToString.Include
        public String id;
        @ToString.Include
        public UpstreamType type = UpstreamType.RSS;
        public AuthenticEndpoint rssEndpoint;
        public InetSocketAddress rpcEndpoint;
        public Integer weight;
        public AuthenticEndpoint endpoint;
        public AuthenticEndpoint tcpClient;
        // 固定外部 UDP client 的普通业务 UDP 数据面映射目标。
        public InetSocketAddress udpClient;
        // 固定外部 UDP client 的 TCP adaptive UDP (udpHop=false) 数据面映射目标。
        public InetSocketAddress adaptiveUdpClient;
        // 可选单路由物理 TCP 出口包装策略（默认继承顶层 egress，标准第三方 SOCKS5 仅允许 none）。
        public Egress egress;

        public SocksServer() {
        }

        public SocksServer(String id, int weight, AuthenticEndpoint endpoint) {
            this.id = id;
            this.weight = weight;
            this.endpoint = endpoint;
        }
    }
}
