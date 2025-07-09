package org.rx.net;

import io.netty.util.AttributeKey;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.bean.FlagsEnum;
import org.rx.codec.CodecUtil;
import org.rx.core.Extends;
import org.rx.core.Linq;
import org.rx.core.RxConfig;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@Getter
@Setter
@ToString
public class SocketConfig implements Extends {
    private static final long serialVersionUID = 5312790348211652335L;

    public static final AttributeKey<SocketConfig> ATTR_CONF = AttributeKey.valueOf("conf");
    public static final AttributeKey<Boolean> ATTR_PSEUDO_SVR = AttributeKey.valueOf("pseudoSvr");
    public static final int DELAY_TIMEOUT_MILLIS = 30000;

    //Set true if live with current process.
    private boolean useSharedTcpEventLoop = true;
    private boolean enableLog;
    private MemoryMode memoryMode = MemoryMode.LOW;
    private int connectTimeoutMillis;
    private FlagsEnum<TransportFlags> transportFlags = TransportFlags.NONE.flags();
    // 1 = AES, 2 = XChaCha20Poly1305
    private short cipher = 2;
    private byte[] cipherKey;
    @Getter(lazy = true)
    private final Set<String> bypassHosts = bypassHosts();

    private Set<String> bypassHosts() {
        return new CopyOnWriteArraySet<>(RxConfig.INSTANCE.getNet().getBypassHosts());
    }

    public byte[] getCipherKey() {
        if (cipherKey == null) {
            RxConfig.NetConfig conf = RxConfig.INSTANCE.getNet();
            cipherKey = Linq.from(conf.getCiphers()).where(p -> p.startsWith(cipher + ","))
                    .select(p -> CodecUtil.convertFromBase64(p.substring(2))).first();
        }
        return cipherKey;
    }

    public SocketConfig() {
        RxConfig.NetConfig conf = RxConfig.INSTANCE.getNet();
        enableLog = conf.isEnableLog();
        connectTimeoutMillis = conf.getConnectTimeoutMillis();
    }
}
