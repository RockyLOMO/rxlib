package org.rx.net.rpc;

import lombok.Data;
import org.rx.core.App;
import org.rx.net.MemoryMode;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class RpcServerConfig implements Serializable {
    public static final String GROUP_NAME = "℞Rpc";
    public static final int HEARTBEAT_TIMEOUT = 60;
    public static final int DISABLE_VERSION = -1;
    public static final int LATEST_COMPUTE = 0;

    private static final long serialVersionUID = 8065323693541916068L;
    private int listenPort;
    private MemoryMode memoryMode;
    private int connectTimeoutMillis = App.getConfig().getNetTimeoutMillis();
    private boolean enableSsl;
    private boolean enableCompress;
    private int capacity = 10000;
    private final List<Integer> eventBroadcastVersions = new ArrayList<>();
    private int eventComputeVersion = LATEST_COMPUTE;
}
