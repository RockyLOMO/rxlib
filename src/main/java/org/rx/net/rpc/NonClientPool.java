package org.rx.net.rpc;

import lombok.RequiredArgsConstructor;
import org.rx.net.transport.StatefulTcpClient;
import org.rx.net.transport.TcpClientConfig;

@RequiredArgsConstructor
class NonClientPool implements TcpClientPool {
    private final TcpClientConfig template;

    @Override
    public StatefulTcpClient borrowClient() {
        TcpClientConfig config = template.deepClone();
        StatefulTcpClient client = new StatefulTcpClient(config);
        client.connect(config.getServerEndpoint());
        return client;
    }

    @Override
    public StatefulTcpClient returnClient(StatefulTcpClient client) {
        return client;
    }
}
