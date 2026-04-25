package org.rx.net.rpc;

import org.rx.net.transport.DefaultTcpClient;

public interface RpcTcpClientPool {
    DefaultTcpClient borrowClient();

    DefaultTcpClient returnClient(DefaultTcpClient client);
}
