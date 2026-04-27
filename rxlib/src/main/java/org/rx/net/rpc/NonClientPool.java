package org.rx.net.rpc;

import lombok.RequiredArgsConstructor;
import org.rx.core.Sys;
import org.rx.exception.InvalidException;
import org.rx.net.transport.DefaultTcpClient;
import org.rx.net.transport.TcpClientConfig;

import java.util.concurrent.TimeoutException;

@RequiredArgsConstructor
class NonClientPool implements RpcTcpClientPool {
    private final TcpClientConfig template;

    @Override
    public DefaultTcpClient borrowClient() {
        TcpClientConfig config = Sys.deepClone(template);
        DefaultTcpClient client = new DefaultTcpClient(config);
        try {
            client.connect(config.getServerEndpoint());
        } catch (TimeoutException e) {
            if (!config.isEnableReconnect()) {
                throw InvalidException.sneaky(e);
            }
        }
        return client;
    }

    @Override
    public DefaultTcpClient returnClient(DefaultTcpClient client) {
        return client;
    }
}
