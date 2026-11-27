package org.rx.net.rpc;

import lombok.RequiredArgsConstructor;
import org.rx.core.Sys;
import org.rx.exception.InvalidException;
import org.rx.net.transport.core.TransportClient;
import org.rx.net.transport.core.TransportConfig;

import java.util.concurrent.TimeoutException;

@RequiredArgsConstructor
class NonTransportClientPool implements RpcTransportClientPool {
    private final TransportConfig template;

    @Override
    public TransportClient borrowClient() {
        TransportConfig config = Sys.deepClone(template);
        TransportClient client = new TransportClient(config);
        try {
            client.connect(config.getTcpClientConfig().getServerEndpoint());
        } catch (TimeoutException e) {
            if (!config.getTcpClientConfig().isEnableReconnect()) {
                client.close();
                throw InvalidException.sneaky(e);
            }
        }
        return client;
    }

    @Override
    public TransportClient returnClient(TransportClient client) {
        return client;
    }
}
