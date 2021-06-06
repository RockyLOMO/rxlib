package org.rx.net.rpc.impl;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.rx.core.App;
import org.rx.core.Disposable;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.rpc.RpcClientPool;

@Slf4j
@RequiredArgsConstructor
public class RpcClientPoolImpl extends Disposable implements RpcClientPool {
    private final GenericObjectPool<StatefulRpcClient> pool;

    public RpcClientPoolImpl(RpcClientConfig template) {
        int maxSize = Math.max(1, template.getMaxPoolSize());
        GenericObjectPoolConfig<StatefulRpcClient> config = new GenericObjectPoolConfig<>();
        config.setLifo(true);
        config.setTestOnBorrow(true);
        config.setJmxEnabled(false);
        config.setMaxWaitMillis(template.getConnectTimeoutMillis());
        config.setMinIdle(App.getConfig().getNetMinPoolSize());
        config.setMaxIdle(maxSize);
        config.setMaxTotal(maxSize);
        pool = new GenericObjectPool<>(new BasePooledObjectFactory<StatefulRpcClient>() {
            @Override
            public StatefulRpcClient create() throws Exception {
                RpcClientConfig config = App.deepClone(template);
                StatefulRpcClient client = new StatefulRpcClient(config);
                client.connect(true);
                log.debug("Create RpcClient {}", client);
                return client;
            }

            @Override
            public PooledObject<StatefulRpcClient> wrap(StatefulRpcClient client) {
                return new DefaultPooledObject<>(client);
            }

            @Override
            public boolean validateObject(PooledObject<StatefulRpcClient> p) {
                return p.getObject().isConnected();
            }

            @Override
            public void destroyObject(PooledObject<StatefulRpcClient> p) throws Exception {
                p.getObject().close();
            }

            @Override
            public void passivateObject(PooledObject<StatefulRpcClient> p) throws Exception {
                StatefulRpcClient client = p.getObject();
                client.setAutoReconnect(false);
                client.onError = null;
                client.onReceive = client.onSend = null;
                client.onDisconnected = client.onConnected = null;
                client.onReconnected = client.onReconnecting = null;
            }
        }, config);
    }

    @Override
    protected void freeObjects() {
        pool.close();
    }

    @SneakyThrows
    @Override
    public StatefulRpcClient borrowClient() {
        checkNotClosed();

        StatefulRpcClient client = pool.borrowObject();
        log.debug("Take RpcClient {}", client);
        return client;
    }

    @Override
    public StatefulRpcClient returnClient(StatefulRpcClient client) {
        checkNotClosed();

        log.debug("Return RpcClient {}", client);
        pool.returnObject(client); //调用多次同一对象会hang
//        log.debug("Return RpcClient {} ok", client);
        return null;
    }
}
