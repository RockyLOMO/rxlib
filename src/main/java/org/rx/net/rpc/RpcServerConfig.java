package org.rx.net.rpc;

import lombok.Data;
import org.rx.core.App;
import org.rx.net.MemoryMode;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class RpcServerConfig implements Serializable {
    public static final int MAX_OBJECT_SIZE = 1048576 * 2;
    public static final int DISABLE_VERSION = -1;
    public static final int LATEST_COMPUTE = 0;

    private static final long serialVersionUID = 8065323693541916068L;
    private boolean tryEpoll = true;
    private int listenPort;
    private int workThread;
    private MemoryMode memoryMode;
    private int connectTimeoutMillis = App.getConfig().getNetTimeoutMillis();
    private boolean enableSsl;
    private boolean enableCompress;
    private int capacity = 10000;
    private final List<Integer> eventBroadcastVersions = new ArrayList<>();
    private int eventComputeVersion = LATEST_COMPUTE;
}
