package org.rx.net.rpc;

import io.netty.channel.local.LocalAddress;
import lombok.RequiredArgsConstructor;
import org.rx.core.Sys;
import org.rx.exception.InvalidException;
import org.rx.net.transport.core.TransportClient;
import org.rx.net.transport.core.TransportConfig;

import java.util.concurrent.TimeoutException;

@RequiredArgsConstructor
class NonTransportClientPool implements RpcTransportClientPool {
    private final TransportConfig template;
    private final LocalAddress localAddress;

    NonTransportClientPool(TransportConfig template) {
        this(template, null);
    }

    @Override
    public TransportClient borrowClient() {
        TransportConfig config = Sys.deepClone(template);
        TransportClient client = new TransportClient(config);
        try {
            if (localAddress != null) {
                client.connectLocal(localAddress);
            } else {
                client.connect(config.getTcpClientConfig().getServerEndpoint());
            }
        } catch (TimeoutException e) {
            if (localAddress != null || !config.getTcpClientConfig().isEnableReconnect()) {
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
