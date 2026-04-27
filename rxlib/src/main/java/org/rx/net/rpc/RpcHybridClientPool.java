package org.rx.net.rpc;

import org.rx.net.transport.hybrid.HybridClient;

public interface RpcHybridClientPool {
    HybridClient borrowClient();

    HybridClient returnClient(HybridClient client);
}
