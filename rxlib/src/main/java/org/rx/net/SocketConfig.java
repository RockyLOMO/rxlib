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
import org.rx.util.function.BiAction;

import java.io.Serializable;

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
     * 0 表示按 Sockets 推荐值自动选择；1 表示不启用；大于 1 表示显式指定 bind 数。
     */
    private int reusePortBindCount = 1;
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

    public void setTcpCompressionLevel(int tcpCompressionLevel) {
        this.tcpCompressionLevel = tcpCompressionLevel < 0 ? -1 : Math.min(9, tcpCompressionLevel);
    }

    public void setReusePortBindCount(int reusePortBindCount) {
        this.reusePortBindCount = Math.max(0, reusePortBindCount);
    }

    public SocketConfig() {
        RxConfig.NetConfig conf = RxConfig.INSTANCE.getNet();
//        debug = conf.isEnableLog();
        connectTimeoutMillis = conf.getConnectTimeoutMillis();
    }
}
