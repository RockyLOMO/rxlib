package org.rx.net.socks;

import io.netty.channel.local.LocalAddress;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.core.cache.H2StoreCache;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.SocketConfig;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;

@Getter
@Setter
@ToString
public class SocksConfig extends SocketConfig {
    public static final int DEF_READ_TIMEOUT_SECONDS = 60 * 4;
    public static final int DEF_UDP_READ_TIMEOUT_SECONDS = 60 * 20;

    private static final long serialVersionUID = 3526543718065617052L;
    private int listenPort;
    private LocalAddress memoryAddress;
    private int trafficShapingInterval = 10000;
    private int readTimeoutSeconds = DEF_READ_TIMEOUT_SECONDS;
    private int writeTimeoutSeconds;
    private int udpReadTimeoutSeconds = DEF_UDP_READ_TIMEOUT_SECONDS;
    private int udpWriteTimeoutSeconds;
    @Getter(lazy = true)
    private final Set<InetAddress> whiteList = whiteList();
    private boolean enableUdp2raw;
    private InetSocketAddress udp2rawClient;
    private AuthenticEndpoint kcptunClient;
    private boolean tcpWarmPoolEnabled;
    private int tcpWarmPoolMinSize = 2;
    private long tcpWarmPoolMaxIdleMillis = 60_000L;
    private long tcpWarmPoolRefillIntervalMillis = 1_000L;
    private boolean udpLeasePoolEnabled;
    private int udpLeasePoolMinSize = 2;
    private int udpLeasePoolMaxSize = 32;
    private long udpLeasePoolMaxIdleMillis = 300_000L;
    private int udpLeaseRpcBreakerThreshold = 3;
    private int udpLeaseRpcBreakerOpenSeconds = 30;
    /**
     * UDP 多倍发包配置。
     * 取值范围 [1, 5]，默认 1。
     * 用于游戏低延迟场景，以带宽换取丢包容忍度。
     */
    private UdpRedundantConfig udpRedundant;

    private Set<InetAddress> whiteList() {
        return H2StoreCache.DEFAULT.asSet();
    }

    public SocksConfig(int listenPort) {
        this.listenPort = listenPort;
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

