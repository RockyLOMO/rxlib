package org.rx.net;

import io.netty.util.AttributeKey;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.bean.FlagsEnum;
import org.rx.bean.Tuple;
import org.rx.codec.CodecUtil;
import org.rx.core.Extends;
import org.rx.core.RxConfig;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@Getter
@Setter
@ToString
public class SocketConfig implements Extends {
    public static AttributeKey<Tuple<Short, byte[]>> ATTR_CIPHER_KEY = AttributeKey.valueOf("cipherKey");

    private static final long serialVersionUID = 5312790348211652335L;
    public static final int DELAY_TIMEOUT_MILLIS = 30000;

    //Set true if live with current process.
    private boolean useSharedTcpEventLoop = true;
    private boolean enableLog;
    private MemoryMode memoryMode = MemoryMode.LOW;
    private int connectTimeoutMillis;
    private FlagsEnum<TransportFlags> transportFlags = TransportFlags.NONE.flags();
    // 1 = AES, 2 = XChaCha20Poly1305
    private short cipher;
    private byte[] cipherKey;
    @Getter(lazy = true)
    private final Set<String> bypassHosts = bypassHosts();

    private Set<String> bypassHosts() {
        return new CopyOnWriteArraySet<>(RxConfig.INSTANCE.getNet().getBypassHosts());
    }

    public byte[] getCipherKey() {
        if (cipherKey == null) {
            RxConfig.NetConfig conf = RxConfig.INSTANCE.getNet();
            cipherKey = CodecUtil.deserializeFromBase64(conf.getCipherKeys().get(cipher));
        }
        return cipherKey;
    }

    public SocketConfig() {
        RxConfig.NetConfig conf = RxConfig.INSTANCE.getNet();
        enableLog = conf.isEnableLog();
        connectTimeoutMillis = conf.getConnectTimeoutMillis();
        cipher = conf.getCipher();
    }
}
