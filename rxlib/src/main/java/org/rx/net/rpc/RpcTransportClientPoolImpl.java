package org.rx.net.rpc;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.core.ObjectPool;
import org.rx.core.Sys;
import org.rx.exception.InvalidException;
import org.rx.net.transport.core.TransportClient;
import org.rx.net.transport.core.TransportConfig;

import java.util.function.Consumer;

@Slf4j
@RequiredArgsConstructor
class RpcTransportClientPoolImpl extends Disposable implements RpcTransportClientPool {
    final ObjectPool<TransportClient> pool;

    public RpcTransportClientPoolImpl(RpcClientConfig<?> template) {
        int minIdleSize = Math.max(1, template.getMinPoolSize());
        int maxSize = Math.max(minIdleSize, template.getMaxPoolSize());
        pool = new ObjectPool<TransportClient>(minIdleSize, maxSize, () -> {
            TransportConfig config = Sys.deepClone(template.getTransportConfig());
            TransportClient client = new TransportClient(config);
            try {
                if (template.getLocalAddress() != null) {
                    client.connectLocal(template.getLocalAddress());
                } else {
                    client.connect(config.getTcpClientConfig().getServerEndpoint());
                }
            } catch (Throwable e) {
                client.close();
                throw e;
            }
            log.debug("Create RpcTransportClient {}", client);
            return client;
        }, TransportClient::isConnected, null, client -> {
            client.getConfig().getTcpClientConfig().setEnableReconnect(false);
            client.resetHandlers();
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
    public TransportClient borrowClient() {
        checkNotClosed();
        return pool.borrow();
    }

    @Override
    public TransportClient returnClient(TransportClient client) {
        if (client == null) {
            return null;
        }
        if (isClosed()) {
            client.close();
            return null;
        }
        try {
            pool.recycle(client);
        } catch (Throwable e) {
            if (!isClosed()) {
                throw InvalidException.sneaky(e);
            }
            client.close();
        }
        return null;
    }

    @Override
    public boolean isHealthy() {
        return !isClosed() && pool.anyMatch(TransportClient::isConnected);
    }

    @Override
    public void forEachClient(Consumer<TransportClient> consumer) {
        pool.forEach(consumer);
    }
}
