package org.rx.net.rpc;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.core.ObjectPool;
import org.rx.core.Sys;
import org.rx.net.transport.StatefulTcpClient;
import org.rx.net.transport.TcpClientConfig;

@Slf4j
@RequiredArgsConstructor
class RpcClientPool extends Disposable implements TcpClientPool {
    final ObjectPool<StatefulTcpClient> pool;

    public RpcClientPool(RpcClientConfig<?> template) {
        int minSize = Math.max(1, template.getMinPoolSize());
        int maxSize = Math.max(minSize, template.getMaxPoolSize());
        pool = new ObjectPool<>(minSize, maxSize, () -> {
            TcpClientConfig config = Sys.deepClone(template.getTcpConfig());
            StatefulTcpClient c = new StatefulTcpClient(config);
            c.connect(config.getServerEndpoint());
            log.debug("Create RpcClient {}", c);
            return c;
        }, StatefulTcpClient::isConnected, null, c -> {
            c.getConfig().setEnableReconnect(false);
            c.onError.purge();
            c.onReceive.purge();
            c.onSend.purge();
            c.onDisconnected.purge();
            c.onConnected.purge();
            c.onReconnected.purge();
            c.onReconnecting.purge();
        });
        pool.setBorrowTimeout(template.getTcpConfig().getConnectTimeoutMillis());
        pool.setLeakDetectionThreshold(pool.getIdleTimeout());
    }

    @Override
    protected void dispose() {
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
