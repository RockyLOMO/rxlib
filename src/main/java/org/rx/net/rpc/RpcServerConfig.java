package org.rx.net.rpc;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.net.SocketConfig;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class RpcServerConfig extends SocketConfig {
    public static final int HEARTBEAT_TIMEOUT = 60;
    public static final int DISABLE_VERSION = -1;
    public static final int LATEST_COMPUTE = 0;

    private static final long serialVersionUID = 8065323693541916068L;
    private int listenPort;
    private boolean enableSsl;
    private boolean enableCompress;
    private int capacity = 10000;
    private final List<Integer> eventBroadcastVersions = new ArrayList<>();
    private int eventComputeVersion = LATEST_COMPUTE;
}
