package org.rx.net.rpc;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.core.ObjectPool;
import org.rx.core.Sys;
import org.rx.net.transport.DefaultTcpClient;
import org.rx.net.transport.TcpClientConfig;

@Slf4j
@RequiredArgsConstructor
class RpcClientPool extends Disposable implements RpcTcpClientPool {
    final ObjectPool<DefaultTcpClient> pool;

    public RpcClientPool(RpcClientConfig<?> template) {
        int minIdleSize = Math.max(1, template.getMinPoolSize());
        int maxSize = Math.max(minIdleSize, template.getMaxPoolSize());
        pool = new ObjectPool<>(minIdleSize, maxSize, () -> {
            TcpClientConfig config = Sys.deepClone(template.getTcpConfig());
            DefaultTcpClient c = new DefaultTcpClient(config);
            c.connect(config.getServerEndpoint());
            log.debug("Create RpcClient {}", c);
            return c;
        }, DefaultTcpClient::isConnected, null, c -> {
            c.getConfig().setEnableReconnect(false);
            c.onError.purge();
            c.onReceive.purge();
            c.onSend.purge();
            c.onDisconnected.purge();
            c.onConnected.purge();
            c.onReconnected.purge();
            c.onReconnecting.purge();
            c.onPong.purge();
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
    public DefaultTcpClient borrowClient() {
        checkNotClosed();

        return pool.borrow();
    }

    @Override
    public DefaultTcpClient returnClient(DefaultTcpClient client) {
        checkNotClosed();

        pool.recycle(client);
        return null;
    }
}
