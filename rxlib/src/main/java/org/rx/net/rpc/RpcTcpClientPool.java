package org.rx.net.rpc;

import org.rx.net.transport.DefaultRpcTcpClient;

public interface RpcTcpClientPool {
    DefaultRpcTcpClient borrowClient();

    DefaultRpcTcpClient returnClient(DefaultRpcTcpClient client);
}
