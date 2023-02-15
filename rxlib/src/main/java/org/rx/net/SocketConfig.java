package org.rx.net;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.bean.FlagsEnum;
import org.rx.core.Extends;
import org.rx.core.RxConfig;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@Getter
@Setter
@ToString
public class SocketConfig implements Extends {
    private static final long serialVersionUID = 5312790348211652335L;
    public static final int DELAY_TIMEOUT_MILLIS = 30000;

    //Set true if live with current process.
    private boolean useSharedTcpEventLoop = true;
    private boolean enableLog = RxConfig.INSTANCE.getNet().isEnableLog();
    private MemoryMode memoryMode = MemoryMode.LOW;
    private int connectTimeoutMillis = RxConfig.INSTANCE.getNet().getConnectTimeoutMillis();
    private FlagsEnum<TransportFlags> transportFlags = TransportFlags.NONE.flags();
    private byte[] aesKey;
    @Getter(lazy = true)
    private final Set<String> bypassList = bypassList();

    public byte[] getAesKey() {
        if (aesKey == null) {
            return RxConfig.INSTANCE.getAesKey().getBytes(StandardCharsets.UTF_8);
        }
        return aesKey;
    }

    private Set<String> bypassList() {
        return new CopyOnWriteArraySet<>(RxConfig.INSTANCE.getNet().getLanIps());
    }
}
