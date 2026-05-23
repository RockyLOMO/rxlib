package org.rx.net;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.bean.FlagsEnum;
import org.rx.codec.CodecUtil;
import org.rx.core.Linq;
import org.rx.core.RxConfig;
import org.rx.net.udp.UdpCompressCodec;
import org.rx.net.udp.UdpCompressConfig;
import org.rx.net.udp.UdpRedundantConfig;
import org.rx.net.udp.UdpRedundantDestinationRule;
import org.rx.net.udp.UdpRedundantMultiplierResolver;
import org.rx.util.function.BiAction;

import java.io.Serializable;
import java.util.List;

@Getter
@Setter
@ToString
public class SocketConfig implements Serializable {
    private static final long serialVersionUID = 5312790348211652335L;

    public static final AttributeKey<SocketConfig> ATTR_CONF = AttributeKey.valueOf("conf");
    public static final AttributeKey<Boolean> ATTR_PSEUDO_SVR = AttributeKey.valueOf("pseudoSvr");
    static final AttributeKey<BiAction<Channel>> ATTR_INIT_FN = AttributeKey.valueOf("_initFn");

    public static final SocketConfig EMPTY = new SocketConfig();

    private boolean debug;
    private String reactorName;
    private OptimalSettings optimalSettings;
    private int connectTimeoutMillis;
    private FlagsEnum<TransportFlags> transportFlags;
    /**
     * Linux epoll 下 SO_REUSEPORT 监听分片数量。
     * 仅在通过 {@link org.rx.net.Sockets#bindChannels(io.netty.bootstrap.ServerBootstrap, java.net.SocketAddress, SocketConfig)}
     * 或 {@link org.rx.net.Sockets#bindChannels(io.netty.bootstrap.Bootstrap, java.net.SocketAddress, SocketConfig)}
     * 绑定固定 Inet 端口时生效。
     * -1 表示未设置，使用 RxConfig 全局值；0 表示按 Sockets 推荐值自动选择；
     * 1 表示不启用；大于 1 表示显式指定 bind 数。
     */
    private int reusePortBindCount = -1;
    /**
     * UDP channel 写侧 pending 字节软上限。0 表示退回旧的 WBM/default 逻辑。
     */
    private int udpWriteLimitBytes = 1024 * 1024;
    /**
     * 共享 UDP inbound 回写时的单源 pending 字节软上限。0 表示关闭单源限制。
     */
    private int udpWritePerSourceLimitBytes = 256 * 1024;
    /**
     * 最终 UDP datagram payload 字节上限，不含 IP/UDP header；0 表示关闭 MTU 限制。
     */
    private int udpMtu;
    /**
     * UDP 多倍发包配置。
     * 取值范围 [1, 5]，默认 1。
     * 用于游戏低延迟场景，以带宽换取丢包容忍度。
     */
    private UdpRedundantConfig udpRedundant;
    /**
     * UDP 单包压缩配置。
     * 用于回收多倍发包带来的带宽开销。
     */
    private UdpCompressConfig udpCompress;
    /**
     * TCP zlib 压缩级别。
     * -1 表示保持 Netty 默认值，其余取值范围为 [0, 9]。
     */
    private int tcpCompressionLevel = -1;
    // 1 = AES, 2 = XChaCha20Poly1305
    private short cipher = 2;
    private byte[] cipherKey;

    public FlagsEnum<TransportFlags> getTransportFlags() {
        if (transportFlags == null) {
            transportFlags = TransportFlags.NONE.flags();
        }
        return transportFlags;
    }

    public byte[] getCipherKey() {
        if (cipherKey == null) {
            RxConfig.NetConfig conf = RxConfig.INSTANCE.getNet();
            cipherKey = Linq.from(conf.getCiphers()).where(p -> p.startsWith(cipher + ","))
                    .select(p -> CodecUtil.convertFromBase64(p.substring(2))).first();
        }
        return cipherKey;
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

    public void setTcpCompressionLevel(int tcpCompressionLevel) {
        this.tcpCompressionLevel = tcpCompressionLevel < 0 ? -1 : Math.min(9, tcpCompressionLevel);
    }

    public void setReusePortBindCount(int reusePortBindCount) {
        this.reusePortBindCount = Math.max(-1, reusePortBindCount);
    }

    public void setUdpWriteLimitBytes(int udpWriteLimitBytes) {
        this.udpWriteLimitBytes = Math.max(0, udpWriteLimitBytes);
    }

    public void setUdpWritePerSourceLimitBytes(int udpWritePerSourceLimitBytes) {
        this.udpWritePerSourceLimitBytes = Math.max(0, udpWritePerSourceLimitBytes);
    }

    public void setUdpMtu(int udpMtu) {
        this.udpMtu = Math.max(0, udpMtu);
    }

    public SocketConfig() {
        RxConfig.NetConfig conf = RxConfig.INSTANCE.getNet();
//        debug = conf.isEnableLog();
        connectTimeoutMillis = conf.getConnectTimeoutMillis();
    }
}
