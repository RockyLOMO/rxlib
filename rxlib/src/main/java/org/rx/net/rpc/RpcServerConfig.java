package org.rx.net.rpc;

import lombok.*;
import org.rx.net.transport.TcpServerConfig;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Getter
@Setter
@ToString
public class RpcServerConfig {
    private static final long serialVersionUID = 8065323693541916068L;
    public static final int EVENT_DISABLE_COMPUTE = -1;
    public static final int EVENT_LATEST_COMPUTE = 0;

    private final TcpServerConfig tcpConfig;
    private final List<Integer> eventBroadcastVersions = new ArrayList<>();
    private int eventComputeVersion = EVENT_DISABLE_COMPUTE;
}
