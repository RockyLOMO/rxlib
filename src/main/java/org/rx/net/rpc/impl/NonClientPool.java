package org.rx.net.rpc.impl;

import lombok.RequiredArgsConstructor;
import org.rx.core.App;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.rpc.RpcClientPool;

@RequiredArgsConstructor
public class NonClientPool implements RpcClientPool {
    private final RpcClientConfig template;

    @Override
    public StatefulRpcClient borrowClient() {
        RpcClientConfig config = App.deepClone(template);
        StatefulRpcClient client = new StatefulRpcClient(config);
        client.connect(true);
        return client;
    }

    @Override
    public StatefulRpcClient returnClient(StatefulRpcClient client) {
        return client;
    }
}
