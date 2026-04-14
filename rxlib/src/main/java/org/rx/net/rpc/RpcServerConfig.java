package org.rx.net.rpc;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.rx.net.transport.TcpServerConfig;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Getter
@Setter
@ToString
public class RpcServerConfig {
    private static final long serialVersionUID = 8065323693541916068L;
    public static final short EVENT_DISABLE_COMPUTE = -1;
    public static final short EVENT_LATEST_COMPUTE = 0;

    private final TcpServerConfig tcpConfig;
    private final List<Integer> eventBroadcastVersions = new ArrayList<>();
    private short eventComputeVersion = EVENT_DISABLE_COMPUTE;
}
