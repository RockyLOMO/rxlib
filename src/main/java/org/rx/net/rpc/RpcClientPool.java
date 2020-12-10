package org.rx.net.rpc;

import org.rx.net.rpc.impl.NonClientPool;
import org.rx.net.rpc.impl.RpcClientPoolImpl;
import org.rx.net.rpc.impl.StatefulRpcClient;

public interface RpcClientPool {
    static RpcClientPool createPool(RpcClientConfig config) {
        if (!config.isUsePool()) {
            return new NonClientPool(config);
        }
        return new RpcClientPoolImpl(config);
    }

    StatefulRpcClient borrowClient();

    StatefulRpcClient returnClient(StatefulRpcClient client);
}
