package org.rx.net.rpc;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.core.ObjectPool;
import org.rx.net.transport.StatefulTcpClient;
import org.rx.net.transport.TcpClientConfig;

import java.util.concurrent.TimeoutException;

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
            try {
                client.connect(config.getServerEndpoint());
            } catch (TimeoutException e) {
                if (!config.isEnableReconnect()) {
                    throw e;
                }
            }
            log.debug("Create RpcClient {}", client);
            return client;
        }, c -> c.getConfig().isEnableReconnect() || c.isConnected(), client -> {
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
        pool.setLeakDetectionThreshold(pool.getIdleTimeout());
    }

    @Override
    protected void freeObjects() {
        pool.close();
    }

    @SneakyThrows
    @Override
    public StatefulTcpClient borrowClient() {
        checkNotClosed();

        return pool.borrow();
    }

    @Override
    public StatefulTcpClient returnClient(StatefulTcpClient client) {
        checkNotClosed();

        pool.recycle(client);
        return null;
    }
}
