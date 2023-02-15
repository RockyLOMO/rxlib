package org.rx.net;

import lombok.*;

import java.io.Serializable;
import java.net.InetSocketAddress;

@RequiredArgsConstructor
@Getter
@Setter
@ToString
public class SocketInfo implements Serializable {
    private static final long serialVersionUID = -786086629306321445L;
    final SocketProtocol protocol;
    final InetSocketAddress source, destination;
    final TcpStatus status;
    final long pid;
    String processName;
}
