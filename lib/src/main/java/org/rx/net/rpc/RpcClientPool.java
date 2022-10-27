package org.rx.net.rpc;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.rx.core.Disposable;
import org.rx.net.transport.StatefulTcpClient;
import org.rx.net.transport.TcpClientConfig;

@Slf4j
@RequiredArgsConstructor
class RpcClientPool extends Disposable implements TcpClientPool {
    private final GenericObjectPool<StatefulTcpClient> pool;

    public RpcClientPool(RpcClientConfig<?> template) {
        int minSize = Math.max(2, template.getMinPoolSize());
        int maxSize = Math.max(minSize, template.getMaxPoolSize());
        GenericObjectPoolConfig<StatefulTcpClient> config = new GenericObjectPoolConfig<>();
        config.setLifo(true);
        config.setTestOnBorrow(true);
        config.setTestOnReturn(true);
        config.setJmxEnabled(false);
        config.setMaxWaitMillis(template.getTcpConfig().getConnectTimeoutMillis());
        config.setMinIdle(minSize);
        config.setMaxIdle(maxSize);
        config.setMaxTotal(maxSize);
        pool = new GenericObjectPool<>(new BasePooledObjectFactory<StatefulTcpClient>() {
            @Override
            public StatefulTcpClient create() throws Exception {
                TcpClientConfig config = template.getTcpConfig().deepClone();
                StatefulTcpClient client = new StatefulTcpClient(config);
                client.connect(config.getServerEndpoint());
                log.debug("Create RpcClient {}", client);
                return client;
            }

            @Override
            public PooledObject<StatefulTcpClient> wrap(StatefulTcpClient client) {
                return new DefaultPooledObject<>(client);
            }

            @Override
            public boolean validateObject(PooledObject<StatefulTcpClient> p) {
                return p.getObject().isConnected();
            }

            @Override
            public void destroyObject(PooledObject<StatefulTcpClient> p) throws Exception {
                p.getObject().close();
            }

            @Override
            public void passivateObject(PooledObject<StatefulTcpClient> p) throws Exception {
                StatefulTcpClient client = p.getObject();
                client.getConfig().setEnableReconnect(false);
                client.onError.purge();
                client.onReceive.purge();
                client.onSend.purge();
                client.onDisconnected.purge();
                client.onConnected.purge();
                client.onReconnected.purge();
                client.onReconnecting.purge();
            }
        }, config);
    }

    @Override
    protected void freeObjects() {
        pool.close();
    }

    @SneakyThrows
    @Override
    public StatefulTcpClient borrowClient() {
        checkNotClosed();

        StatefulTcpClient client = pool.borrowObject();
        log.debug("Take RpcClient {}", client);
        return client;
    }

    @SneakyThrows
    @Override
    public StatefulTcpClient returnClient(StatefulTcpClient client) {
        checkNotClosed();

        try {
            if (!client.getConfig().isEnableReconnect() && !client.isConnected()) {
                pool.invalidateObject(client);
                return null;
            }

            pool.returnObject(client); //对同一对象return多次会hang
        } catch (IllegalStateException e) {
            log.warn("returnClient", e);
        }
        log.debug("Return RpcClient {}", client);
        return null;
    }
}
