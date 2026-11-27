package org.rx.net.rpc;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.net.transport.TcpServerConfig;
import org.rx.net.transport.core.TransportConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

@Getter
@Setter
@ToString
public class RpcServerConfig {
    private static final long serialVersionUID = 8065323693541916068L;
    public static final short EVENT_DISABLE_COMPUTE = -1;
    public static final short EVENT_LATEST_COMPUTE = 0;

    private final TransportConfig transportConfig;
    private final List<Integer> eventBroadcastVersions = new ArrayList<Integer>();
    private short eventComputeVersion = EVENT_DISABLE_COMPUTE;
    private RemotingCodecFactory codecFactory = FuryRemotingCodecFactory.createDefault();
    private Executor executor;
    private boolean executorForPing;
    private Set<String> sensitiveMethods = Collections.emptySet();

    public RpcServerConfig(TcpServerConfig tcpConfig) {
        this(new TransportConfig());
        transportConfig.setTcpServerConfig(tcpConfig);
    }

    public RpcServerConfig(TransportConfig transportConfig) {
        this.transportConfig = transportConfig == null ? new TransportConfig() : transportConfig;
    }

    public TcpServerConfig getTcpConfig() {
        return transportConfig.getTcpServerConfig();
    }
}
