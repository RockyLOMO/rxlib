package org.rx.socks.tcp;

import io.netty.channel.ChannelHandlerContext;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;
import org.rx.common.InvalidOperationException;
import org.rx.common.Lazy;
import org.rx.common.NQuery;
import org.rx.socks.Sockets;
import org.rx.util.ManualResetEvent;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.common.Contract.require;

@Slf4j
public final class RemotingFactor {
    @Data
    @EqualsAndHashCode(callSuper = true)
    private static class CallPack extends SessionPack {
        private String methodName;
        private Object[] parameters;
        private Object returnValue;
    }

    private static class ClientHandler implements MethodInterceptor {
        private static final Lazy<TcpClientPool> pool = new Lazy<>(TcpClientPool.class);

        private final ManualResetEvent waitHandle;
        private CallPack resultPack;
        private InetSocketAddress serverAddress;
        private boolean enablePool;
        private TcpClient client;

        public ClientHandler(InetSocketAddress serverAddress, boolean enablePool) {
            this.serverAddress = serverAddress;
            this.enablePool = enablePool;
            waitHandle = new ManualResetEvent();
        }

        @Override
        public Object intercept(Object o, Method method, Object[] objects, MethodProxy methodProxy) throws Throwable {
            CallPack pack = SessionPack.create(CallPack.class);
            pack.methodName = method.getName();
            pack.parameters = objects;

            if (enablePool) {
                try (TcpClient client = pool.getValue().borrow(serverAddress)) {  //已连接
                    client.<TcpClient, ErrorEventArgs<ChannelHandlerContext>>attachEvent("onError", (s, e) -> {
                        e.setCancel(true);
                        log.error("!Error & Set!", e.getValue());
                        waitHandle.set();
                    });
                    client.<TcpClient, PackEventArgs<ChannelHandlerContext>>attachEvent("onReceive", (s, e) -> {
                        resultPack = (CallPack) e.getValue();
                        waitHandle.set();
                    });

                    client.send(pack);
                    waitHandle.waitOne(client.getConnectTimeout());
                }
            } else {
                if (client == null) {
                    client = new TcpClient(serverAddress, true, null);
                    client.setAutoReconnect(true);
                    client.connect(true);
                    client.onError = (s, e) -> {
                        e.setCancel(true);
                        log.error("!Error & Set!", e.getValue());
                        waitHandle.set();
                    };
                    client.onReceive = (s, e) -> {
                        resultPack = (CallPack) e.getValue();
                        waitHandle.set();
                    };
                }
                client.send(pack);
                waitHandle.waitOne(client.getConnectTimeout());
            }

            waitHandle.reset();
            return resultPack.returnValue;
        }
    }

    private static final Object[] emptyParameter = new Object[0];
    private static final Map<Object, TcpServer<TcpServer.ClientSession>> host = new ConcurrentHashMap<>();

    public static <T> T create(Class<T> contract, String endPoint) {
        return create(contract, endPoint, false);
    }

    public static <T> T create(Class<T> contract, String endPoint, boolean enablePool) {
        require(contract);
        require(contract, contract.isInterface());

        return (T) Enhancer.create(contract, new ClientHandler(Sockets.parseAddress(endPoint), enablePool));
    }

    public static void listen(Object contractInstance, int port) {
        require(contractInstance);

        Class contract = contractInstance.getClass();
        host.computeIfAbsent(contractInstance, k -> {
            TcpServer<TcpServer.ClientSession> server = new TcpServer<>(port, true);
            server.onError = (s, e) -> {
                e.setCancel(true);
                SessionPack pack = SessionPack.error(String.format("Remoting call error: %s", e.getValue().getMessage()));
                s.send(e.getClient().getId(), pack);
            };
            server.onReceive = (s, e) -> {
                CallPack pack = (CallPack) e.getValue();
                Method method = NQuery.of(contract.getMethods()).where(p -> p.getName().equals(pack.methodName) && p.getParameterCount() == pack.parameters.length).firstOrDefault();
                if (method == null) {
                    throw new InvalidOperationException(String.format("Class %s Method %s not found", contract, pack.methodName));
                }
                try {
                    pack.returnValue = method.invoke(contractInstance, pack.parameters);
                } catch (Exception ex) {
                    log.error("listen", ex);
                    pack.setErrorMessage(String.format("%s %s", ex.getClass(), ex.getMessage()));
                }
                pack.parameters = emptyParameter;
                s.send(e.getClient().getId(), pack);
            };
            server.start();
            return server;
        });
    }
}
