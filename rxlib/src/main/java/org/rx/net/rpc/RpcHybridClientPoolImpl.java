package org.rx.net.rpc;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.core.ObjectPool;
import org.rx.core.Sys;
import org.rx.net.transport.hybrid.HybridClient;
import org.rx.net.transport.hybrid.HybridConfig;

import java.util.function.Consumer;

@Slf4j
@RequiredArgsConstructor
class RpcHybridClientPoolImpl extends Disposable implements RpcHybridClientPool {
    final ObjectPool<HybridClient> pool;

    public RpcHybridClientPoolImpl(RpcClientConfig<?> template) {
        int minIdleSize = Math.max(1, template.getMinPoolSize());
        int maxSize = Math.max(minIdleSize, template.getMaxPoolSize());
        pool = new ObjectPool<HybridClient>(minIdleSize, maxSize, () -> {
            HybridConfig config = Sys.deepClone(template.getHybridConfig());
            HybridClient client = new HybridClient(config);
            client.connect(config.getTcpClientConfig().getServerEndpoint());
            log.debug("Create RpcHybridClient {}", client);
            return client;
        }, HybridClient::isConnected, null, client -> {
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
    public HybridClient borrowClient() {
        checkNotClosed();
        return pool.borrow();
    }

    @Override
    public HybridClient returnClient(HybridClient client) {
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
                throw org.rx.exception.InvalidException.sneaky(e);
            }
            client.close();
        }
        return null;
    }

    @Override
    public boolean isHealthy() {
        return !isClosed() && pool.anyMatch(HybridClient::isConnected);
    }

    @Override
    public void forEachClient(Consumer<HybridClient> consumer) {
        pool.forEach(consumer);
    }
}
