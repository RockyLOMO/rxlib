package org.rx.net.tcp;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import org.apache.commons.pool2.BaseKeyedPooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;
import org.rx.core.*;

import java.net.InetSocketAddress;
import java.util.function.BiConsumer;

import static org.rx.core.Contract.CONFIG;
import static org.rx.core.Contract.require;

@Slf4j
public final class TcpClientPool extends Disposable implements EventTarget<TcpClientPool> {
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class EventArgs extends NEventArgs<InetSocketAddress> {
        private TcpClient poolingClient;
    }

    private class TcpClientFactory extends BaseKeyedPooledObjectFactory<InetSocketAddress, TcpClient> {
        @Override
        public TcpClient create(InetSocketAddress inetSocketAddress) {
            EventArgs args = new EventArgs();
            args.setValue(inetSocketAddress);
            raiseEvent(onCreate, args);
            require(args.poolingClient);
            args.poolingClient.getConfig().setConnectTimeoutMillis((int) pool.getMaxWaitMillis());
            args.poolingClient.connect(true);
            log.debug("Create TcpClient {}", args.poolingClient);
            return args.poolingClient;
        }

        @Override
        public PooledObject<TcpClient> wrap(TcpClient tcpClient) {
            return new DefaultPooledObject<>(tcpClient);
        }

        @Override
        public boolean validateObject(InetSocketAddress key, PooledObject<TcpClient> p) {
            return p.getObject().isConnected();
        }

        @Override
        public void destroyObject(InetSocketAddress key, PooledObject<TcpClient> p) throws Exception {
            super.destroyObject(key, p);
            p.getObject().close();
            EventArgs args = new EventArgs();
            args.setValue(key);
            args.poolingClient = p.getObject();
            raiseEvent(onDestroy, args);
        }

        @Override
        public void passivateObject(InetSocketAddress key, PooledObject<TcpClient> p) throws Exception {
            super.passivateObject(key, p);
            TcpClient client = p.getObject();
            client.setAutoReconnect(false);
            client.onError = null;
            client.onSend = null;
            client.onReceive = null;
            client.onConnected = null;
            client.onDisconnected = null;
        }
    }

    public volatile BiConsumer<TcpClientPool, EventArgs> onCreate, onDestroy;
    private final GenericKeyedObjectPool<InetSocketAddress, TcpClient> pool;

    public TcpClientPool() {
        this(CONFIG.getNetMinPoolSize(), CONFIG.getNetMaxPoolSize(), CONFIG.getNetTimeoutMillis());
    }

    public TcpClientPool(int minSize, int maxSize, long timeout) {
        GenericKeyedObjectPoolConfig<TcpClient> config = new GenericKeyedObjectPoolConfig<>();
        config.setLifo(true);
        config.setTestOnBorrow(true);
        config.setJmxEnabled(false);
        config.setMaxWaitMillis(timeout);
        config.setMinIdlePerKey(minSize);
        config.setMaxIdlePerKey(maxSize);
        config.setMaxTotal(maxSize);
        pool = new GenericKeyedObjectPool<>(new TcpClientFactory(), config);
    }

    @Override
    protected void freeObjects() {
        pool.close();
    }

    @SneakyThrows
    public TcpClient borrow(InetSocketAddress key) {
        checkNotClosed();

        TcpClient client = pool.borrowObject(key);
        log.debug("Take TcpClient {}", client);
        return (TcpClient) Enhancer.create(TcpClient.class, (MethodInterceptor) (o, method, objects, methodProxy) -> {
            if (Reflects.isCloseMethod(method)) {
                pool.returnObject(client.getConfig().getEndpoint(), client);
                log.debug("Return TcpClient {}", client);
                return null;
            }
            return methodProxy.invoke(client, objects);
        });
    }
}
