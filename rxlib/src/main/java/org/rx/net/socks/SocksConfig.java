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
        SYSTEM, DIRECT, REMOTE
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
     * DIRECT/REMOTE: 使用 Netty 异步 DNS，并分别走直连/远程 DNS Client。
     */
    private TcpAsyncDnsMode tcpAsyncDnsMode = TcpAsyncDnsMode.DIRECT;
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
    /**
     * UDP 单包压缩配置。
     * 仅对代理链上的隧道对端生效，用于回收多倍发包带来的带宽开销。
     */
    private UdpCompressConfig udpCompress;
    /**
     * UDP SOCKS5 upstream 端口跳跃配置。
     * 默认关闭；开启后同一逻辑 upstream 可持有多个远端 UDP relay 端口。
     */
    private UdpPortHoppingConfig udpPortHopping;

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

    public boolean isUdpPortHoppingEnabled() {
        return udpPortHopping != null && udpPortHopping.isEnabled() && udpPortHopping.getHopCount() > 1;
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

    public boolean isUdpCompressEnabled() {
        return udpCompress != null && udpCompress.isEnabled();
    }

    public void setUdpCompressEnabled(boolean enabled) {
        if (udpCompress == null) {
            udpCompress = new UdpCompressConfig();
        }
        udpCompress.setEnabled(enabled);
    }

    public UdpCompressCodec getUdpCompressCodec() {
        return udpCompress != null ? udpCompress.getCodec() : UdpCompressCodec.LZ4_FAST;
    }

    public void setUdpCompressCodec(UdpCompressCodec codec) {
        if (udpCompress == null) {
            udpCompress = new UdpCompressConfig();
        }
        udpCompress.setCodec(codec);
    }

    public int getUdpCompressMinPayloadBytes() {
        return udpCompress != null ? udpCompress.getMinPayloadBytes() : 96;
    }

    public void setUdpCompressMinPayloadBytes(int minPayloadBytes) {
        if (udpCompress == null) {
            udpCompress = new UdpCompressConfig();
        }
        udpCompress.setMinPayloadBytes(minPayloadBytes);
    }

    public int getUdpCompressMinSavingsBytes() {
        return udpCompress != null ? udpCompress.getMinSavingsBytes() : 24;
    }

    public void setUdpCompressMinSavingsBytes(int minSavingsBytes) {
        if (udpCompress == null) {
            udpCompress = new UdpCompressConfig();
        }
        udpCompress.setMinSavingsBytes(minSavingsBytes);
    }

    public double getUdpCompressMinSavingsRatio() {
        return udpCompress != null ? udpCompress.getMinSavingsRatio() : 0.12D;
    }

    public void setUdpCompressMinSavingsRatio(double minSavingsRatio) {
        if (udpCompress == null) {
            udpCompress = new UdpCompressConfig();
        }
        udpCompress.setMinSavingsRatio(minSavingsRatio);
    }

    public int getUdpCompressCompressionLevel() {
        return udpCompress != null ? udpCompress.getCompressionLevel() : UdpCompressConfig.DEFAULT_COMPRESSION_LEVEL;
    }

    public void setUdpCompressCompressionLevel(int compressionLevel) {
        if (udpCompress == null) {
            udpCompress = new UdpCompressConfig();
        }
        udpCompress.setCompressionLevel(compressionLevel);
    }

    public int getUdpCompressDictionaryId() {
        return udpCompress != null ? udpCompress.getDictionaryId() : 0;
    }

    public void setUdpCompressDictionaryId(int dictionaryId) {
        if (udpCompress == null) {
            udpCompress = new UdpCompressConfig();
        }
        udpCompress.setDictionaryId(dictionaryId);
    }

    public boolean isUdpCompressAdaptiveBypass() {
        return udpCompress == null || udpCompress.isAdaptiveBypass();
    }

    public void setUdpCompressAdaptiveBypass(boolean adaptiveBypass) {
        if (udpCompress == null) {
            udpCompress = new UdpCompressConfig();
        }
        udpCompress.setAdaptiveBypass(adaptiveBypass);
    }

    public int getUdpCompressAdaptiveBypassWindowSeconds() {
        return udpCompress != null ? udpCompress.getAdaptiveBypassWindowSeconds() : 30;
    }

    public void setUdpCompressAdaptiveBypassWindowSeconds(int adaptiveBypassWindowSeconds) {
        if (udpCompress == null) {
            udpCompress = new UdpCompressConfig();
        }
        udpCompress.setAdaptiveBypassWindowSeconds(adaptiveBypassWindowSeconds);
    }
}

