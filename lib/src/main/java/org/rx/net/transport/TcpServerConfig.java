package org.rx.net.transport;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.net.SocketConfig;

@Data
@EqualsAndHashCode(callSuper = true)
public class TcpServerConfig extends SocketConfig {
    private static final long serialVersionUID = 2719107972925015607L;

    private final int listenPort;
    private int capacity = 10000;
    private int heartbeatTimeout = 60;
}
