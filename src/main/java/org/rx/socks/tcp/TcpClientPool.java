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
import org.rx.core.*;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.rx.core.App.Config;
import static org.rx.core.Contract.require;

@Slf4j
public final class TcpClientPool extends Disposable implements EventTarget<TcpClientPool> {
    private class ProxyHandle implements MethodInterceptor {
        private TcpClient client;

        public ProxyHandle(TcpClient client) {
            this.client = client;
        }

        @Override
        public Object intercept(Object o, Method method, Object[] objects, MethodProxy methodProxy) throws Throwable {
            if (method.getName().equals("close")) {
                pool.returnObject(client.getConfig().getEndpoint(), client);
                return null;
            }
//            return methodProxy.invokeSuper(o, objects); //有问题
            return methodProxy.invoke(client, objects);
        }
    }

    private class TcpClientFactory extends BaseKeyedPooledObjectFactory<InetSocketAddress, TcpClient> {
        @Override
        public TcpClient create(InetSocketAddress inetSocketAddress) {
            TcpClient client = createFunc.apply(inetSocketAddress);
            client.getConfig().setConnectTimeout((int) pool.getMaxWaitMillis());
            raiseEvent(onCreate, new NEventArgs<>(client));
            client.connect(true);
            log.debug("Create TcpClient {}", client.isConnected());
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
            raiseEvent(onDestroy, new NEventArgs<>(p.getObject()));
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

    public volatile BiConsumer<TcpClientPool, NEventArgs<TcpClient>> onCreate, onDestroy;
    private final GenericKeyedObjectPool<InetSocketAddress, TcpClient> pool;
    private Function<InetSocketAddress, TcpClient> createFunc;

    public TcpClientPool(Function<InetSocketAddress, TcpClient> createFunc) {
        this(createFunc, Config.getSocksTimeout(), 1, App.MaxInt);
    }

    public TcpClientPool(Function<InetSocketAddress, TcpClient> createFunc, long timeout, int minSize, int maxSize) {
        require(createFunc);

        this.createFunc = createFunc;
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
