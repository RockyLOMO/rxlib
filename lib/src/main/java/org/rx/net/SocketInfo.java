package org.rx.net;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.net.InetSocketAddress;

@Data
@RequiredArgsConstructor
public class SocketInfo implements Serializable {
    final SocketProtocol protocol;
    final InetSocketAddress source, destination;
    final TcpStatus status;
    final long pid;
    String processName;
}
