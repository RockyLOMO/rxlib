package org.rx.net;

import lombok.Data;
import org.rx.bean.DeepCloneable;
import org.rx.bean.FlagsEnum;
import org.rx.core.App;

import java.nio.charset.StandardCharsets;

@Data
public class SocketConfig implements DeepCloneable {
    public static final int DELAY_TIMEOUT_MILLIS = 30000;

    private MemoryMode memoryMode = MemoryMode.LOW;
    private int connectTimeoutMillis = App.getConfig().getNetTimeoutMillis();
    private boolean enableNettyLog;
    private FlagsEnum<TransportFlags> transportFlags = TransportFlags.NONE.flags();
    private byte[] aesKey;

    public byte[] getAesKey() {
        if (aesKey == null) {
            return "℞FREEDOM".getBytes(StandardCharsets.UTF_8);
        }
        return aesKey;
    }
}
