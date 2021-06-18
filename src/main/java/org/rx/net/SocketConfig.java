package org.rx.net;

import lombok.Data;
import org.rx.bean.DeepCloneable;
import org.rx.core.App;

@Data
public class SocketConfig implements DeepCloneable {
    public static final int DELAY_TIMEOUT_MILLIS = 30000;

    private MemoryMode memoryMode = MemoryMode.LOW;
    private int connectTimeoutMillis = App.getConfig().getNetTimeoutMillis();
}
