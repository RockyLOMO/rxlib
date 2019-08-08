package org.rx.socks.tcp;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;
import org.rx.common.InvalidOperationException;
import org.rx.common.NQuery;
import org.rx.util.ManualResetEvent;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.common.Contract.require;

/**
 * Created by IntelliJ IDEA.
 * User: za-wangxiaoming
 * Date: 2019/8/6
 */
@Slf4j
public class RemotingFactor {
    @Data
    @EqualsAndHashCode(callSuper = true)
    private static class CallPack extends SessionPack {
        private String methodName;
        private Object[] parameters;
        private Object returnValue;
    }

    private static class ClientHandler implements MethodInterceptor {
        private TcpClient client;
        private ManualResetEvent waitHandle;
        private CallPack resultPack;

        public ClientHandler(String endPoint) {
            client = new TcpClient(endPoint, true);
            client.onError = (s, e) -> {
                e.setCancel(true);
                log.error("!Error & Set!", e.getValue());
                waitHandle.set();
            };
            client.onReceive = (s, e) -> {
                resultPack = (CallPack) e.getValue();
                waitHandle.set();
            };
            client.setAutoReconnect(true);
            client.connect(true);

            waitHandle = new ManualResetEvent();
        }

        @Override
        public Object intercept(Object o, Method method, Object[] objects, MethodProxy methodProxy) throws Throwable {
            CallPack pack = SessionPack.create(CallPack.class);
            pack.methodName = method.getName();
            pack.parameters = objects;
            client.send(pack);

            waitHandle.waitOne(client.getConnectTimeout());
            waitHandle.reset();
            return resultPack.returnValue;
        }
    }

    private static final Object[] emptyParameter = new Object[0];
    private static final Map<Object, TcpServer<TcpServer.ClientSession>> host = new ConcurrentHashMap<>();

    public static <T> T create(Class<T> contract, String endPoint) {
        require(contract);
        require(contract, contract.isInterface());

        return (T) Enhancer.create(contract, new ClientHandler(endPoint));
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
