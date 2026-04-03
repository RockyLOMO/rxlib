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
     * UDP 多倍发包倍率。1 = 不冗余（默认），2 = 双发，3 = 三发。
     * 用于游戏低延迟场景，以带宽换取丢包容忍度。
     * 取值范围 [1, 5]，超过 5 将被限定为 5。
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
}

