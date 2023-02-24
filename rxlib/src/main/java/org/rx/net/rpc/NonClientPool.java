package org.rx.net.rpc;

import lombok.RequiredArgsConstructor;
import org.rx.core.Sys;
import org.rx.exception.InvalidException;
import org.rx.net.transport.StatefulTcpClient;
import org.rx.net.transport.TcpClientConfig;

import java.util.concurrent.TimeoutException;

@RequiredArgsConstructor
class NonClientPool implements TcpClientPool {
    private final TcpClientConfig template;

    @Override
    public StatefulTcpClient borrowClient() {
        TcpClientConfig config = Sys.deepClone(template);
        StatefulTcpClient client = new StatefulTcpClient(config);
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
    public StatefulTcpClient returnClient(StatefulTcpClient client) {
        return client;
    }
}
