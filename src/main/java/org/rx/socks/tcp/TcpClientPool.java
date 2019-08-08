package org.rx.socks.tcp;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;
import org.apache.commons.pool2.BaseKeyedPooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;
import org.rx.common.Disposable;
import org.rx.common.EventArgs;
import org.rx.common.NEventArgs;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.function.BiConsumer;

@Slf4j
public final class TcpClientPool extends Disposable {
    private class ProxyHandle implements MethodInterceptor {
        private TcpClient client;

        public ProxyHandle(TcpClient client) {
            this.client = client;
        }

        @Override
        public Object intercept(Object o, Method method, Object[] objects, MethodProxy methodProxy) throws Throwable {
            if (method.getName().equals("close")) {
                pool.returnObject(client.getServerAddress(), client);
                return null;
            }
            return method.invoke(client, objects);
        }
    }

    private class TcpClientFactory extends BaseKeyedPooledObjectFactory<InetSocketAddress, TcpClient> {
        @Override
        public TcpClient create(InetSocketAddress inetSocketAddress) {
            TcpClient client = new TcpClient(inetSocketAddress, true, null);
            client.setConnectTimeout(pool.getMaxWaitMillis());
            EventArgs.raiseEvent(onCreate, _this(), new NEventArgs<>(client));
            client.connect(true);
            log.info("Create TcpClient {}", client.isConnected());
            return client;
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
            EventArgs.raiseEvent(onDestroy, _this(), new NEventArgs<>(p.getObject()));
        }
    }

    public volatile BiConsumer<TcpClientPool, NEventArgs<TcpClient>> onCreate, onDestroy;
    private final GenericKeyedObjectPool<InetSocketAddress, TcpClient> pool;

    private TcpClientPool _this() {
        return this;
    }

    public TcpClientPool() {
        this(30 * 1000, 0, 8);
    }

    public TcpClientPool(long timeout, int minSize, int maxSize) {
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
        return (TcpClient) Enhancer.create(TcpClient.class, new ProxyHandle(client));
    }
}
