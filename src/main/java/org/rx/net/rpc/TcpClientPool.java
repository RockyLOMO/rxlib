package org.rx.net.rpc;

import org.rx.net.transport.StatefulTcpClient;

public interface TcpClientPool {
    StatefulTcpClient borrowClient();

    StatefulTcpClient returnClient(StatefulTcpClient client);
}
