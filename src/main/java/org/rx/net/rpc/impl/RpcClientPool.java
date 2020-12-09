package org.rx.net.rpc.impl;

import io.netty.util.Attribute;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.pool2.BaseKeyedPooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;
import org.rx.core.App;
import org.rx.core.Disposable;
import org.rx.core.Reflects;
import org.rx.net.rpc.RpcClientConfig;

import java.net.InetSocketAddress;

import static org.rx.core.Contract.*;

@Slf4j
public class RpcClientPool extends Disposable {
    public static final String Stateful = "_Stateful", Invalidated = "_Invalidated";

    class Factory extends BaseKeyedPooledObjectFactory<InetSocketAddress, StatefulRpcClient> {
        @Override
        public StatefulRpcClient create(InetSocketAddress inetSocketAddress) {
            RpcClientConfig config = App.deepClone(template);
            config.setServerEndpoint(inetSocketAddress);
            config.setConnectTimeoutMillis((int) pool.getMaxWaitMillis());
            StatefulRpcClient client = new StatefulRpcClient(config);
            client.setAutoReconnect(false);
            log.debug("Create RpcClient {}", client);
            client.connect(true);
            return client;
        }

        @Override
        public PooledObject<StatefulRpcClient> wrap(StatefulRpcClient client) {
            return new DefaultPooledObject<>(client);
        }

        @Override
        public boolean validateObject(InetSocketAddress key, PooledObject<StatefulRpcClient> p) {
            return p.getObject().isConnected();
        }

        @Override
        public void destroyObject(InetSocketAddress key, PooledObject<StatefulRpcClient> p) throws Exception {
            StatefulRpcClient client = p.getObject();
            if (client.hasAttr(RpcClientPool.Stateful)) {
                return;
            }
            client.close();
        }

        @Override
        public void passivateObject(InetSocketAddress key, PooledObject<StatefulRpcClient> p) throws Exception {
            StatefulRpcClient client = p.getObject();
            client.setAutoReconnect(false);
            client.onError = null;
            client.onSend = null;
            client.onReceive = null;
            client.onConnected = null;
            client.onDisconnected = null;
        }
    }

    private final RpcClientConfig template;
    private final GenericKeyedObjectPool<InetSocketAddress, StatefulRpcClient> pool;

    public RpcClientPool() {
        this(CONFIG.getNetMinPoolSize(), CONFIG.getNetMaxPoolSize(), new RpcClientConfig());
    }

    public RpcClientPool(int minSize, int maxSize, RpcClientConfig template) {
        this.template = template;
        GenericKeyedObjectPoolConfig<StatefulRpcClient> config = new GenericKeyedObjectPoolConfig<>();
        config.setLifo(true);
        config.setTestOnBorrow(true);
        config.setJmxEnabled(false);
        config.setMaxWaitMillis(template.getConnectTimeoutMillis());
        config.setMinIdlePerKey(minSize);
        config.setMaxIdlePerKey(maxSize);
        config.setMaxTotal(maxSize);
        pool = new GenericKeyedObjectPool<>(new Factory(), config);
    }

    @Override
    protected void freeObjects() {
        pool.close();
    }

    @SneakyThrows
    public StatefulRpcClient borrow(InetSocketAddress key) {
        checkNotClosed();

        StatefulRpcClient client = pool.borrowObject(key);
        log.debug("Take RpcClient {}", client);
        return proxy(StatefulRpcClient.class, (m, p) -> {
            Reflects.copyPublicFields(p.getProxyObject(), client);
            if (Reflects.isCloseMethod(m)) {
                Attribute<Boolean> attr = client.attr(Stateful);
                if (BooleanUtils.isTrue(attr.get())) {
                    if (!client.hasAttr(Invalidated)) {
                        invalidate(client);
                        client.attr(Invalidated).set(true);
                    }
                    return null;
                }
                pool.returnObject(client.getConfig().getServerEndpoint(), client);
                log.debug("Return RpcClient {}", client);
                return null;
            }
            if (client.isAutoReconnect() || "attachEvent".equals(m.getName())) {
                client.attr(Stateful).set(true);
            }
            return p.fastInvoke(client);
        });
    }

    @SneakyThrows
    private void invalidate(StatefulRpcClient client) {
        log.debug("Invalidate RpcClient {}", client);
        pool.invalidateObject(client.getConfig().getServerEndpoint(), client);
    }
}
