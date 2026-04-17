package org.rx.net.socks;

import lombok.AccessLevel;
import io.netty.channel.local.LocalAddress;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.core.cache.H2StoreCache;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Set;

@Getter
@Setter
@ToString
public class SocksConfig extends SocketConfig {
    public enum TcpAsyncDnsMode {
        SYSTEM, INLAND, OUTLAND
    }

    public static final int DEF_READ_TIMEOUT_SECONDS = 60 * 4;
    public static final int DEF_UDP_READ_TIMEOUT_SECONDS = 60 * 20;
    private static final String WHITE_LIST_KEY_PREFIX = "socksWhiteList";

    private static final long serialVersionUID = 3526543718065617052L;
    private SocketAddress listenAddress;
    private int trafficShapingInterval = 10000;
    private int readTimeoutSeconds = DEF_READ_TIMEOUT_SECONDS;
    private int writeTimeoutSeconds;
    private int udpReadTimeoutSeconds = DEF_UDP_READ_TIMEOUT_SECONDS;
    private int udpWriteTimeoutSeconds;
    /**
     * TCP 出站建连时的 DNS 解析策略。
     * SYSTEM: 不指定 Netty 异步 DNS，退回 Bootstrap 默认解析；
     * INLAND/OUTLAND: 使用 Netty 异步 DNS，并分别走 RxConfig 配置的 inland/outland 服务器。
     */
    private TcpAsyncDnsMode tcpAsyncDnsMode = TcpAsyncDnsMode.SYSTEM;
    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    private transient volatile Set<InetAddress> whiteList;
    /**
     * 是否启用「非公网仅白名单」访问控制。默认 false（非公网一律放行）；为 true 时仅私网或 {@link #getWhiteList()} 内地址通过 {@link #isAllowed(InetAddress)}。
     * 为 false 时 {@link #allowWhiteList} 不再写入。
     */
    private boolean whiteListEnabled = false;
    private boolean enableUdp2raw;
    private InetSocketAddress udp2rawClient;
    private AuthenticEndpoint kcptunClient;
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
    /**
     * UDP 多倍发包配置。
     * 取值范围 [1, 5]，默认 1。
     * 用于游戏低延迟场景，以带宽换取丢包容忍度。
     */
    private UdpRedundantConfig udpRedundant;

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

    /**
     * 构建当前配置下的目的地倍率解析器；无规则时始终返回 {@link UdpRedundantMultiplierResolver#NO_MATCH}。
     */
    public UdpRedundantMultiplierResolver buildUdpRedundantMultiplierResolver() {
        if (udpRedundant == null) {
            return dst -> UdpRedundantMultiplierResolver.NO_MATCH;
        }
        return udpRedundant.buildMultiplierResolver();
    }

    /**
     * 是否配置了至少一条分目的地规则（用于决定是否安装冗余 Handler）。
     */
    public boolean hasUdpRedundantDestinationRules() {
        return udpRedundant != null && udpRedundant.hasDestinationRules();
    }

    public int getUdpRedundantMultiplier() {
        return udpRedundant != null ? udpRedundant.getMultiplier() : 1;
    }

    public void setUdpRedundantMultiplier(int multiplier) {
        if (udpRedundant == null) {
            udpRedundant = new UdpRedundantConfig();
        }
        udpRedundant.setMultiplier(multiplier);
    }

    public int getUdpRedundantIntervalMicros() {
        return udpRedundant != null ? udpRedundant.getIntervalMicros() : 0;
    }

    public void setUdpRedundantIntervalMicros(int intervalMicros) {
        if (udpRedundant == null) {
            udpRedundant = new UdpRedundantConfig();
        }
        udpRedundant.setIntervalMicros(intervalMicros);
    }

    public boolean isUdpRedundantAdaptive() {
        return udpRedundant != null && udpRedundant.isAdaptive();
    }

    public void setUdpRedundantAdaptive(boolean adaptive) {
        if (udpRedundant == null) {
            udpRedundant = new UdpRedundantConfig();
        }
        udpRedundant.setAdaptive(adaptive);
    }

    public int getUdpRedundantMinMultiplier() {
        return udpRedundant != null ? udpRedundant.getMinMultiplier() : 1;
    }

    public void setUdpRedundantMinMultiplier(int minMultiplier) {
        if (udpRedundant == null) {
            udpRedundant = new UdpRedundantConfig();
        }
        udpRedundant.setMinMultiplier(minMultiplier);
    }

    public int getUdpRedundantMaxMultiplier() {
        return udpRedundant != null ? udpRedundant.getMaxMultiplier() : 5;
    }

    public void setUdpRedundantMaxMultiplier(int maxMultiplier) {
        if (udpRedundant == null) {
            udpRedundant = new UdpRedundantConfig();
        }
        udpRedundant.setMaxMultiplier(maxMultiplier);
    }

    public double getUdpRedundantLossThresholdHigh() {
        return udpRedundant != null ? udpRedundant.getLossThresholdHigh() : 0.20;
    }

    public void setUdpRedundantLossThresholdHigh(double lossThresholdHigh) {
        if (udpRedundant == null) {
            udpRedundant = new UdpRedundantConfig();
        }
        udpRedundant.setLossThresholdHigh(lossThresholdHigh);
    }

    public double getUdpRedundantLossThresholdLow() {
        return udpRedundant != null ? udpRedundant.getLossThresholdLow() : 0.05;
    }

    public void setUdpRedundantLossThresholdLow(double lossThresholdLow) {
        if (udpRedundant == null) {
            udpRedundant = new UdpRedundantConfig();
        }
        udpRedundant.setLossThresholdLow(lossThresholdLow);
    }

    public int getUdpRedundantStablePeriods() {
        return udpRedundant != null ? udpRedundant.getStablePeriods() : 3;
    }

    public void setUdpRedundantStablePeriods(int stablePeriods) {
        if (udpRedundant == null) {
            udpRedundant = new UdpRedundantConfig();
        }
        udpRedundant.setStablePeriods(stablePeriods);
    }

    public List<UdpRedundantDestinationRule> getUdpRedundantDestinationRules() {
        if (udpRedundant == null) {
            udpRedundant = new UdpRedundantConfig();
        }
        return udpRedundant.getDestinationRules();
    }
}

