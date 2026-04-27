package org.rx.net.rpc;

import lombok.RequiredArgsConstructor;
import org.rx.core.Sys;
import org.rx.exception.InvalidException;
import org.rx.net.transport.hybrid.HybridClient;
import org.rx.net.transport.hybrid.HybridConfig;

import java.util.concurrent.TimeoutException;

@RequiredArgsConstructor
class NonHybridClientPool implements RpcHybridClientPool {
    private final HybridConfig template;

    @Override
    public HybridClient borrowClient() {
        HybridConfig config = Sys.deepClone(template);
        HybridClient client = new HybridClient(config);
        try {
            client.connect(config.getTcpClientConfig().getServerEndpoint());
        } catch (TimeoutException e) {
            if (!config.getTcpClientConfig().isEnableReconnect()) {
                throw InvalidException.sneaky(e);
            }
        }
        return client;
    }

    @Override
    public HybridClient returnClient(HybridClient client) {
        return client;
    }
}
