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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

//@RequiredArgsConstructor
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
    /**
     * UDP 多倍发包独立配置（可选）。
     * 设置后优先使用此配置，否则使用下面的独立字段（向后兼容）。
     */
    private UdpRedundantConfig udpRedundantConfig;

    // ===================== 向后兼容字段 =====================
    // 以下字段保持向后兼容，当 udpRedundantConfig 为 null 时使用

    /**
     * UDP 多倍发包倍率。
     * 取值范围 [1, 5]，默认 1。
     * 用于游戏低延迟场景，以带宽换取丢包容忍度。
     */
    private int udpRedundantMultiplier = 1;
    /**
     * 冗余副本之间的发送间隔（微秒）。
     * 0 = 同一时刻发送（默认）；> 0 = 每个冗余副本间隔发送。
     * 建议 200~1000μs，用于应对突发丢包（burst loss）。
     */
    private int udpRedundantIntervalMicros = 0;
    /**
     * 是否启用自适应倍率调整。
     * 启用后根据实际丢包率动态调整 multiplier，范围在 [udpRedundantMinMultiplier, udpRedundantMaxMultiplier] 之间。
     * udpRedundantMultiplier 作为初始值。
     */
    private boolean udpRedundantAdaptive = false;
    /**
     * 自适应模式最小倍率。默认 1 = 网络好时可完全关闭冗余。
     */
    private int udpRedundantMinMultiplier = 1;
    /**
     * 自适应模式最大倍率。默认 5。
     */
    private int udpRedundantMaxMultiplier = 5;
    /**
     * 自适应丢包率上阈值（0~1）。超过此值增加倍率。默认 0.20（20%）。
     */
    private double udpRedundantLossThresholdHigh = 0.20;
    /**
     * 自适应丢包率下阈值（0~1）。低于此值降低倍率。默认 0.05（5%）。
     */
    private double udpRedundantLossThresholdLow = 0.05;
    /**
     * 防抖周期数。连续多少个调整周期（每周期 2 秒）满足条件才实际调整。默认 3。
     */
    private int udpRedundantStablePeriods = 3;
    /**
     * 分目的地倍率规则，列表顺序为优先级（先匹配先生效）。
     * 命中规则时覆盖 {@link #udpRedundantMultiplier} 或自适应倍率；未命中则使用全局配置。
     */
    private List<UdpRedundantDestinationRule> udpRedundantDestinationRules = new ArrayList<>();

    private Set<InetAddress> whiteList() {
        return H2StoreCache.DEFAULT.asSet();
    }

    public SocksConfig(int listenPort) {
        this.listenPort = listenPort;
    }

    public void setUdpRedundantMultiplier(int udpRedundantMultiplier) {
        this.udpRedundantMultiplier = Math.max(1, Math.min(5, udpRedundantMultiplier));
    }

    public void setUdpRedundantMaxMultiplier(int udpRedundantMaxMultiplier) {
        this.udpRedundantMaxMultiplier = Math.max(this.udpRedundantMinMultiplier, Math.min(5, udpRedundantMaxMultiplier));
    }

    /**
     * 构建当前配置下的目的地倍率解析器；无规则时始终返回 {@link UdpRedundantMultiplierResolver#NO_MATCH}。
     */
    public UdpRedundantMultiplierResolver buildUdpRedundantMultiplierResolver() {
        List<UdpRedundantDestinationRule> rules = udpRedundantDestinationRules;
        if (rules == null || rules.isEmpty()) {
            return dst -> UdpRedundantMultiplierResolver.NO_MATCH;
        }
        List<UdpRedundantDestinationRule> snapshot = new ArrayList<>(rules);
        return destination -> {
            if (destination == null) {
                return UdpRedundantMultiplierResolver.NO_MATCH;
            }
            for (UdpRedundantDestinationRule r : snapshot) {
                if (r != null && r.matches(destination)) {
                    return r.getMultiplier();
                }
            }
            return UdpRedundantMultiplierResolver.NO_MATCH;
        };
    }

    /**
     * 是否配置了至少一条分目的地规则（用于决定是否安装冗余 Handler）。
     */
    public boolean hasUdpRedundantDestinationRules() {
        if (udpRedundantConfig != null) {
            return udpRedundantConfig.hasDestinationRules();
        }
        return udpRedundantDestinationRules != null && !udpRedundantDestinationRules.isEmpty();
    }

    /**
     * 获取UDP冗余配置，优先返回独立配置对象。
     */
    public UdpRedundantConfig getUdpRedundantConfig() {
        return udpRedundantConfig;
    }

    /**
     * 设置UDP冗余独立配置对象。
     */
    public void setUdpRedundantConfig(UdpRedundantConfig udpRedundantConfig) {
        this.udpRedundantConfig = udpRedundantConfig;
    }

    /**
     * 获取UDP冗余倍率，优先从独立配置获取。
     */
    public int getUdpRedundantMultiplier() {
        if (udpRedundantConfig != null) {
            return udpRedundantConfig.getMultiplier();
        }
        return udpRedundantMultiplier;
    }

    /**
     * 获取UDP冗余间隔，优先从独立配置获取。
     */
    public int getUdpRedundantIntervalMicros() {
        if (udpRedundantConfig != null) {
            return udpRedundantConfig.getIntervalMicros();
        }
        return udpRedundantIntervalMicros;
    }

    /**
     * 是否启用自适应，优先从独立配置获取。
     */
    public boolean isUdpRedundantAdaptive() {
        if (udpRedundantConfig != null) {
            return udpRedundantConfig.isAdaptive();
        }
        return udpRedundantAdaptive;
    }

    /**
     * 获取最小倍率，优先从独立配置获取。
     */
    public int getUdpRedundantMinMultiplier() {
        if (udpRedundantConfig != null) {
            return udpRedundantConfig.getMinMultiplier();
        }
        return udpRedundantMinMultiplier;
    }

    /**
     * 获取最大倍率，优先从独立配置获取。
     */
    public int getUdpRedundantMaxMultiplier() {
        if (udpRedundantConfig != null) {
            return udpRedundantConfig.getMaxMultiplier();
        }
        return udpRedundantMaxMultiplier;
    }

    /**
     * 获取丢包率上阈值，优先从独立配置获取。
     */
    public double getUdpRedundantLossThresholdHigh() {
        if (udpRedundantConfig != null) {
            return udpRedundantConfig.getLossThresholdHigh();
        }
        return udpRedundantLossThresholdHigh;
    }

    /**
     * 获取丢包率下阈值，优先从独立配置获取。
     */
    public double getUdpRedundantLossThresholdLow() {
        if (udpRedundantConfig != null) {
            return udpRedundantConfig.getLossThresholdLow();
        }
        return udpRedundantLossThresholdLow;
    }

    /**
     * 获取防抖周期数，优先从独立配置获取。
     */
    public int getUdpRedundantStablePeriods() {
        if (udpRedundantConfig != null) {
            return udpRedundantConfig.getStablePeriods();
        }
        return udpRedundantStablePeriods;
    }

    /**
     * 获取分目的地规则列表，优先从独立配置获取。
     */
    public List<UdpRedundantDestinationRule> getUdpRedundantDestinationRules() {
        if (udpRedundantConfig != null) {
            return udpRedundantConfig.getDestinationRules();
        }
        return udpRedundantDestinationRules;
    }
}

