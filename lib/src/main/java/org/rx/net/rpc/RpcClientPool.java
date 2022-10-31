package org.rx.net.rpc;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.core.ObjectPool;
import org.rx.net.transport.StatefulTcpClient;
import org.rx.net.transport.TcpClientConfig;

@Slf4j
@RequiredArgsConstructor
class RpcClientPool extends Disposable implements TcpClientPool {
    final ObjectPool<StatefulTcpClient> pool;

    public RpcClientPool(RpcClientConfig<?> template) {
        int minSize = Math.max(2, template.getMinPoolSize());
        int maxSize = Math.max(minSize, template.getMaxPoolSize());
        pool = new ObjectPool<>(minSize, maxSize, () -> {
            TcpClientConfig config = template.getTcpConfig().deepClone();
            StatefulTcpClient client = new StatefulTcpClient(config);
            client.connect(config.getServerEndpoint());
            log.debug("Create RpcClient {}", client);
            return client;
        }, StatefulTcpClient::isConnected, client -> {
            client.getConfig().setEnableReconnect(false);
            client.onError.purge();
            client.onReceive.purge();
            client.onSend.purge();
            client.onDisconnected.purge();
            client.onConnected.purge();
            client.onReconnected.purge();
            client.onReconnecting.purge();
        });
        pool.setBorrowTimeout(template.getTcpConfig().getConnectTimeoutMillis());
    }

    @Override
    protected void freeObjects() {
        pool.close();
    }

    @SneakyThrows
    @Override
    public StatefulTcpClient borrowClient() {
        checkNotClosed();

        StatefulTcpClient client = pool.borrow();
        log.debug("Take RpcClient {}", client);
        return client;
    }

    @SneakyThrows
    @Override
    public StatefulTcpClient returnClient(StatefulTcpClient client) {
        checkNotClosed();

        if (!client.getConfig().isEnableReconnect() && !client.isConnected()) {
            pool.retire(client);
            return null;
        }

        pool.recycle(client);
        log.debug("Return RpcClient {}", client);
        return null;
    }
}
