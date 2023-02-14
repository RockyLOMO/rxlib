package org.rx.net;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.net.InetSocketAddress;

@Data
@RequiredArgsConstructor
public class SocketInfo implements Serializable {
    private static final long serialVersionUID = -786086629306321445L;
    final SocketProtocol protocol;
    final InetSocketAddress source, destination;
    final TcpStatus status;
    final long pid;
    String processName;
}
