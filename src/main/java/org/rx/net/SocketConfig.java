package org.rx.net;

import lombok.Data;
import lombok.Getter;
import org.rx.bean.DeepCloneable;
import org.rx.bean.FlagsEnum;
import org.rx.bean.RxConfig;
import org.rx.core.Container;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Pattern;

@Data
public class SocketConfig implements DeepCloneable {
    private static final long serialVersionUID = 5312790348211652335L;
    public static final int DELAY_TIMEOUT_MILLIS = 30000;

    private boolean enableNettyLog;
    //随进程存活设为true
    private boolean useSharedTcpEventLoop = true;
    private MemoryMode memoryMode = MemoryMode.LOW;
    private int connectTimeoutMillis = Container.get(RxConfig.class).getNetTimeoutMillis();
    private FlagsEnum<TransportFlags> transportFlags = TransportFlags.NONE.flags();
    private byte[] aesKey;
    @Getter(lazy = true)
    private final Set<String> bypassList = bypassList();

    public byte[] getAesKey() {
        if (aesKey == null) {
            return "℞FREEDOM".getBytes(StandardCharsets.UTF_8);
        }
        return aesKey;
    }

    private Set<String> bypassList() {
        return new CopyOnWriteArraySet<>(Sockets.DEFAULT_NAT_IPS);
    }

    public boolean isBypass(String host) {
        for (String regex : getBypassList()) {
            if (Pattern.matches(regex, host)) {
                return true;
            }
        }
        return false;
    }
}
