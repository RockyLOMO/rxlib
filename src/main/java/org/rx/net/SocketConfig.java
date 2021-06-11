package org.rx.net;

import lombok.Data;
import org.rx.core.App;

import java.io.Serializable;

@Data
public class SocketConfig implements Serializable {
    public static final int DELAY_TIMEOUT_MILLIS = 30000;

    private MemoryMode memoryMode = MemoryMode.LOW;
    private int connectTimeoutMillis = App.getConfig().getNetTimeoutMillis();
}
