package org.rx.net.rpc;

import lombok.RequiredArgsConstructor;
import org.rx.core.Sys;
import org.rx.exception.InvalidException;
import org.rx.net.transport.DefaultRpcTcpClient;
import org.rx.net.transport.TcpClientConfig;

import java.util.concurrent.TimeoutException;

@RequiredArgsConstructor
class NonClientPool implements RpcTcpClientPool {
    private final TcpClientConfig template;

    @Override
    public DefaultRpcTcpClient borrowClient() {
        TcpClientConfig config = Sys.deepClone(template);
        DefaultRpcTcpClient client = new DefaultRpcTcpClient(config);
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
    public DefaultRpcTcpClient returnClient(DefaultRpcTcpClient client) {
        return client;
    }
}
