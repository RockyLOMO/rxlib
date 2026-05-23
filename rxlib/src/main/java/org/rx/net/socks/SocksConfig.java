package org.rx.net.socks;

import lombok.AccessLevel;
import io.netty.channel.local.LocalAddress;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.core.cache.H2StoreCache;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;
import org.rx.net.udp.UdpPortHoppingConfig;
import org.rx.net.udp.UdpPortHoppingMode;
import org.rx.net.udp.UdpRedundantMode;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Set;

@Getter
@Setter
@ToString
public class SocksConfig extends SocketConfig {
    public enum TcpAsyncDnsMode {
        SYSTEM, DIRECT, REMOTE
    }

    public static final int DEF_READ_TIMEOUT_SECONDS = 60 * 4;
    public static final int DEF_UDP_READ_TIMEOUT_SECONDS = 60 * 20;
    private static final String WHITE_LIST_KEY_PREFIX = "socksWhiteList";

    private static final long serialVersionUID = 3526543718065617052L;

    // 监听配置（必填）
    private SocketAddress listenAddress;

    // 基础 I/O 与 TCP 解析策略
    private int trafficShapingInterval = 10000;
    private int readTimeoutSeconds = DEF_READ_TIMEOUT_SECONDS;
    private int writeTimeoutSeconds;
    private int udpReadTimeoutSeconds = DEF_UDP_READ_TIMEOUT_SECONDS;
    private int udpWriteTimeoutSeconds;
    /**
     * TCP 出站建连时的 DNS 解析策略。
     * SYSTEM: 不指定 Netty 异步 DNS，退回 Bootstrap 默认解析；
     * DIRECT/REMOTE: 使用 Netty 异步 DNS，并分别走直连/远程 DNS Client。
     */
    private TcpAsyncDnsMode tcpAsyncDnsMode = TcpAsyncDnsMode.DIRECT;

    // 访问控制
    /**
     * 是否启用「非公网仅白名单」访问控制。默认 false（非公网一律放行）；为 true 时仅私网或 {@link #getWhiteList()} 内地址通过 {@link #isAllowed(InetAddress)}。
     * 为 false 时 {@link #allowWhiteList} 不再写入。
     */
    private boolean whiteListEnabled = false;
    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    private transient volatile Set<InetAddress> whiteList;

    // UDP relay 控制面
    /**
     * UDP relay 控制面选择。AUTO 下优先尝试 rxlib RPC group，失败按 fallback 配置回退标准 SOCKS5。
     */
    private UdpRelayControlMode udpRelayControlMode = UdpRelayControlMode.AUTO;
    private boolean udpRelayControlFallbackToSocks5 = true;
    private int udpRelayControlMaxRelaysPerGroup = 4;
    private long udpRelayGroupIdleMillis = 300_000L;
    private long udpRelayGroupHeartbeatMillis = 30_000L;
    private int udpRelayControlFailureThreshold = 5;
    private long udpRelayControlBreakerOpenMillis = 60_000L;

    // UDP 端口跳跃与 SOCKS/udp2raw 专属方向控制
    /**
     * UDP SOCKS5 upstream 端口跳跃配置。
     * 默认关闭；开启后同一逻辑 upstream 可持有多个远端 UDP relay 端口。
     */
    private UdpPortHoppingConfig udpPortHopping;
    /**
     * SOCKS UDP RDNT 方向控制。只决定登记 request/response 哪个方向的 peer。
     */
    private UdpRedundantMode socksUdpRedundantMode = UdpRedundantMode.REQUEST_ONLY;

    // UDP2RAW 隧道
    private boolean enableUdp2raw;
    private InetSocketAddress udp2rawClient;
    private InetSocketAddress udp2rawListenAddress;
    private int udp2rawSessionIdleSeconds = 300;
    private int udp2rawMaxSessions = 65536;
    private Udp2rawAuthMode udp2rawAuthMode = Udp2rawAuthMode.FIRST_PACKET_MAC;
    /**
     * udp2raw payload RDNT 方向控制。默认双向，保持旧配置兼容。
     */
    private UdpRedundantMode udp2rawRedundantMode = UdpRedundantMode.BIDIRECTIONAL;
    private boolean udp2rawRequireRpc = true;
    private int udp2rawBadAuthThreshold = 8;
    private int udp2rawBadAuthFuseSeconds = 30;
    private int udp2rawPeerRateLimitPerSecond;
    private int udp2rawPeerRateLimitBurst;

    // 上游连接池
    private boolean tcpWarmPoolEnabled;
    private int tcpWarmPoolMinSize = 2;
    private int tcpWarmPoolMaxIdleMillis = 60_000;
    private int tcpWarmPoolRefillIntervalMillis = 1_000;
    private boolean udpLeasePoolEnabled;
    private int udpLeasePoolMinSize = 2;
    private int udpLeasePoolMaxSize = 32;
    private int udpLeasePoolMaxIdleMillis = 300_000;
    private int udpLeaseRpcBreakerThreshold = 3;
    private int udpLeaseRpcBreakerOpenSeconds = 30;

    public SocksConfig() {}

    public SocksConfig(int listenPort) {
        this(Sockets.newAnyEndpoint(listenPort));
    }

    public SocksConfig(SocketAddress listenAddress) {
        this.listenAddress = listenAddress;
    }

    public void setListenAddress(SocketAddress listenAddress) {
        this.listenAddress = listenAddress;
    }

    public InetSocketAddress getInetListenAddress() {
        return listenAddress instanceof InetSocketAddress ? (InetSocketAddress) listenAddress : null;
    }

    public int getListenPort() {
        InetSocketAddress address = getInetListenAddress();
        return address != null ? address.getPort() : 0;
    }

    public void setListenPort(int listenPort) {
        setListenAddress(Sockets.newAnyEndpoint(listenPort));
    }

    public LocalAddress getMemoryAddress() {
        return listenAddress instanceof LocalAddress ? (LocalAddress) listenAddress : null;
    }

    public void setMemoryAddress(LocalAddress memoryAddress) {
        setListenAddress(memoryAddress);
    }

    @SuppressWarnings("unchecked")
    public Set<InetAddress> getWhiteList() {
        Set<InetAddress> cached = whiteList;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (whiteList == null) {
                whiteList = (Set<InetAddress>) H2StoreCache.DEFAULT.asSet(WHITE_LIST_KEY_PREFIX);
            }
            return whiteList;
        }
    }

    public boolean isAllowed(InetAddress endpoint) {
        if (endpoint == null) {
            return false;
        }
        if (!whiteListEnabled) {
            return true;
        }
        return Sockets.isPrivateIp(endpoint) || getWhiteList().contains(endpoint);
    }

    public void allowWhiteList(InetAddress endpoint) {
        if (!whiteListEnabled || endpoint == null) {
            return;
        }
        ((H2StoreCache<InetAddress, Boolean>) H2StoreCache.DEFAULT).fastPut(WHITE_LIST_KEY_PREFIX, endpoint, Boolean.TRUE);
    }

    public void setSocksUdpRedundantMode(UdpRedundantMode socksUdpRedundantMode) {
        this.socksUdpRedundantMode = socksUdpRedundantMode != null
                ? socksUdpRedundantMode : UdpRedundantMode.REQUEST_ONLY;
    }

    public boolean isUdpPortHoppingEnabled() {
        return udpPortHopping != null && udpPortHopping.isEnabled()
                && (udpPortHopping.getHopCount() > 1
                        || (udpPortHopping.isAdaptive() && udpPortHopping.getMaxHopCount() > 1));
    }

    public void setUdp2rawSessionIdleSeconds(int udp2rawSessionIdleSeconds) {
        this.udp2rawSessionIdleSeconds = Math.max(1, udp2rawSessionIdleSeconds);
    }

    public void setUdp2rawMaxSessions(int udp2rawMaxSessions) {
        this.udp2rawMaxSessions = Math.max(1, udp2rawMaxSessions);
    }

    public void setUdp2rawAuthMode(Udp2rawAuthMode udp2rawAuthMode) {
        this.udp2rawAuthMode = udp2rawAuthMode != null ? udp2rawAuthMode : Udp2rawAuthMode.FIRST_PACKET_MAC;
    }

    public void setUdp2rawRedundantMode(UdpRedundantMode udp2rawRedundantMode) {
        this.udp2rawRedundantMode = udp2rawRedundantMode != null
                ? udp2rawRedundantMode : UdpRedundantMode.BIDIRECTIONAL;
    }

    public void setUdp2rawBadAuthThreshold(int udp2rawBadAuthThreshold) {
        this.udp2rawBadAuthThreshold = Math.max(1, udp2rawBadAuthThreshold);
    }

    public void setUdp2rawBadAuthFuseSeconds(int udp2rawBadAuthFuseSeconds) {
        this.udp2rawBadAuthFuseSeconds = Math.max(1, udp2rawBadAuthFuseSeconds);
    }

    public void setUdp2rawPeerRateLimitPerSecond(int udp2rawPeerRateLimitPerSecond) {
        this.udp2rawPeerRateLimitPerSecond = Math.max(0, udp2rawPeerRateLimitPerSecond);
    }

    public void setUdp2rawPeerRateLimitBurst(int udp2rawPeerRateLimitBurst) {
        this.udp2rawPeerRateLimitBurst = Math.max(0, udp2rawPeerRateLimitBurst);
    }

    public void setUdpPortHoppingEnabled(boolean enabled) {
        if (udpPortHopping == null) {
            udpPortHopping = new UdpPortHoppingConfig();
        }
        udpPortHopping.setEnabled(enabled);
    }

    public int getUdpPortHoppingHopCount() {
        return udpPortHopping != null ? udpPortHopping.getHopCount() : 1;
    }

    public void setUdpPortHoppingHopCount(int hopCount) {
        if (udpPortHopping == null) {
            udpPortHopping = new UdpPortHoppingConfig();
        }
        udpPortHopping.setHopCount(hopCount);
    }

    public int getUdpPortHoppingMinActiveHops() {
        return udpPortHopping != null ? udpPortHopping.getMinActiveHops() : 1;
    }

    public void setUdpPortHoppingMinActiveHops(int minActiveHops) {
        if (udpPortHopping == null) {
            udpPortHopping = new UdpPortHoppingConfig();
        }
        udpPortHopping.setMinActiveHops(minActiveHops);
    }

    public UdpPortHoppingMode getUdpPortHoppingMode() {
        return udpPortHopping != null ? udpPortHopping.getMode() : UdpPortHoppingMode.ROUND_ROBIN;
    }

    public void setUdpPortHoppingMode(UdpPortHoppingMode mode) {
        if (udpPortHopping == null) {
            udpPortHopping = new UdpPortHoppingConfig();
        }
        udpPortHopping.setMode(mode);
    }

    public boolean isUdpPortHoppingAdaptive() {
        return udpPortHopping != null && udpPortHopping.isAdaptive();
    }

    public void setUdpPortHoppingAdaptive(boolean adaptive) {
        if (udpPortHopping == null) {
            udpPortHopping = new UdpPortHoppingConfig();
        }
        udpPortHopping.setAdaptive(adaptive);
    }

    public int getUdpPortHoppingMinHopCount() {
        return udpPortHopping != null ? udpPortHopping.getMinHopCount() : 1;
    }

    public void setUdpPortHoppingMinHopCount(int minHopCount) {
        if (udpPortHopping == null) {
            udpPortHopping = new UdpPortHoppingConfig();
        }
        udpPortHopping.setMinHopCount(minHopCount);
    }

    public int getUdpPortHoppingMaxHopCount() {
        return udpPortHopping != null ? udpPortHopping.getMaxHopCount() : 1;
    }

    public void setUdpPortHoppingMaxHopCount(int maxHopCount) {
        if (udpPortHopping == null) {
            udpPortHopping = new UdpPortHoppingConfig();
        }
        udpPortHopping.setMaxHopCount(maxHopCount);
    }

    public long getUdpPortHoppingAdaptiveScaleUpBytes() {
        return udpPortHopping != null ? udpPortHopping.getAdaptiveScaleUpBytes() : 0L;
    }

    public void setUdpPortHoppingAdaptiveScaleUpBytes(long adaptiveScaleUpBytes) {
        if (udpPortHopping == null) {
            udpPortHopping = new UdpPortHoppingConfig();
        }
        udpPortHopping.setAdaptiveScaleUpBytes(adaptiveScaleUpBytes);
    }

    public long getUdpPortHoppingAdaptiveScaleUpActiveMillis() {
        return udpPortHopping != null ? udpPortHopping.getAdaptiveScaleUpActiveMillis() : 0L;
    }

    public void setUdpPortHoppingAdaptiveScaleUpActiveMillis(long adaptiveScaleUpActiveMillis) {
        if (udpPortHopping == null) {
            udpPortHopping = new UdpPortHoppingConfig();
        }
        udpPortHopping.setAdaptiveScaleUpActiveMillis(adaptiveScaleUpActiveMillis);
    }

    public int getUdpPortHoppingAdaptiveScaleUpCooldownMillis() {
        return udpPortHopping != null ? udpPortHopping.getAdaptiveScaleUpCooldownMillis() : 1000;
    }

    public void setUdpPortHoppingAdaptiveScaleUpCooldownMillis(int adaptiveScaleUpCooldownMillis) {
        if (udpPortHopping == null) {
            udpPortHopping = new UdpPortHoppingConfig();
        }
        udpPortHopping.setAdaptiveScaleUpCooldownMillis(adaptiveScaleUpCooldownMillis);
    }

    public int getUdpPortHoppingReplenishDelayMillis() {
        return udpPortHopping != null ? udpPortHopping.getReplenishDelayMillis() : 1000;
    }

    public void setUdpPortHoppingReplenishDelayMillis(int replenishDelayMillis) {
        if (udpPortHopping == null) {
            udpPortHopping = new UdpPortHoppingConfig();
        }
        udpPortHopping.setReplenishDelayMillis(replenishDelayMillis);
    }

    public void setUdpRelayControlMode(UdpRelayControlMode udpRelayControlMode) {
        this.udpRelayControlMode = udpRelayControlMode != null ? udpRelayControlMode : UdpRelayControlMode.AUTO;
    }

    public void setUdpRelayControlMaxRelaysPerGroup(int udpRelayControlMaxRelaysPerGroup) {
        this.udpRelayControlMaxRelaysPerGroup = Math.max(1,
                Math.min(UdpPortHoppingConfig.MAX_HOP_COUNT, udpRelayControlMaxRelaysPerGroup));
    }

    public void setUdpRelayGroupIdleMillis(long udpRelayGroupIdleMillis) {
        this.udpRelayGroupIdleMillis = Math.max(1_000L, udpRelayGroupIdleMillis);
    }

    public void setUdpRelayGroupHeartbeatMillis(long udpRelayGroupHeartbeatMillis) {
        this.udpRelayGroupHeartbeatMillis = Math.max(1_000L, udpRelayGroupHeartbeatMillis);
    }

    public void setUdpRelayControlFailureThreshold(int udpRelayControlFailureThreshold) {
        this.udpRelayControlFailureThreshold = Math.max(1, udpRelayControlFailureThreshold);
    }

    public void setUdpRelayControlBreakerOpenMillis(long udpRelayControlBreakerOpenMillis) {
        this.udpRelayControlBreakerOpenMillis = Math.max(1_000L, udpRelayControlBreakerOpenMillis);
    }
}

