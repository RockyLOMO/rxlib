package org.rx.net.transport;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.net.SocketConfig;

import javax.validation.constraints.NotNull;
import java.net.InetSocketAddress;

@Getter
@Setter
@ToString
public class TcpClientConfig extends SocketConfig {
    private static final long serialVersionUID = -1177044491381236637L;

    @NotNull
    private volatile InetSocketAddress serverEndpoint;
    /** Optional local source IP/port to bind before connecting (enforces real multi-NIC egress). */
    private volatile InetSocketAddress localAddress;
    private volatile boolean enableReconnect;
    private int waitConnectMillis = 4000;
    private int heartbeatTimeout = 60;
    private TcpChannelCodec codec;
}
