package org.rx.socks.tcp;

import io.netty.channel.ChannelHandlerContext;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;
import org.rx.common.*;
import org.rx.socks.Sockets;
import org.rx.util.ManualResetEvent;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import static org.rx.common.Contract.as;
import static org.rx.common.Contract.require;

@Slf4j
public final class RemotingFactor {
    @Data
    @EqualsAndHashCode(callSuper = true)
    static class RemoteEventPack extends SessionPack {
        private String eventName;

        private boolean remoteOk;
        private EventArgs remoteArgs;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    private static class CallPack extends SessionPack {
        private String methodName;
        private Object[] parameters;
        private Object returnValue;
    }

    private static class ClientHandler implements MethodInterceptor {
        private static final NQuery<Method> objectMethods = NQuery.of(Object.class.getMethods());
        private static final TcpClientPool pool = new TcpClientPool();

        private final ManualResetEvent waitHandle;
        private CallPack resultPack;
        private InetSocketAddress serverAddress;
        private boolean enableDual;
        private TcpClient client;

        public ClientHandler(InetSocketAddress serverAddress, boolean enableDual) {
            this.serverAddress = serverAddress;
            this.enableDual = enableDual;
            waitHandle = new ManualResetEvent();
        }

        @Override
        public Object intercept(Object o, Method method, Object[] objects, MethodProxy methodProxy) throws Throwable {
            if (objectMethods.contains(method)) {
                return methodProxy.invokeSuper(o, objects);
            }
            String methodName = method.getName();

            SessionPack pack = null;
            switch (methodName) {
                case "attachEvent":
                    if (objects.length == 2) {
                        String eventName = (String) objects[0];
//                        BiConsumer event = (BiConsumer) objects[1];
                        methodProxy.invokeSuper(o, objects);
                        RemoteEventPack eventPack = SessionPack.create(RemoteEventPack.class);
                        eventPack.setEventName(eventName);
                        pack = eventPack;
                    }
                    break;
                case "raiseEvent":
                case "dynamicAttach":
//                    if (objects.length == 2) {
                        return methodProxy.invokeSuper(o, objects);
//                    }
//                    break;
            }
            if (pack == null) {
                CallPack callPack = SessionPack.create(CallPack.class);
                callPack.methodName = methodName;
                callPack.parameters = objects;
                pack = callPack;
            }

            if (enableDual) {
                if (client == null) {
                    client = pool.borrow(serverAddress);
                    client.setAutoReconnect(true);
                }
                client.<ErrorEventArgs<ChannelHandlerContext>>attachEvent(TcpClient.EventNames.Error, (s, e) -> {
                    e.setCancel(true);
                    log.error("!Error & Set!", e.getValue());
                    waitHandle.set();
                });
                client.<PackEventArgs<ChannelHandlerContext>>attachEvent(TcpClient.EventNames.Receive, (s, e) -> {
                    RemoteEventPack remoteEventPack;
                    if ((remoteEventPack = as(e.getValue(), RemoteEventPack.class)) != null) {
                        if (remoteEventPack.remoteArgs != null) {
                            EventTarget eventTarget = (EventTarget) o;
                            eventTarget.raiseEvent(remoteEventPack.eventName, remoteEventPack.remoteArgs);
                        }

                        if (resultPack == null) {
                            resultPack = new CallPack();
                        }
                        resultPack.returnValue = null;
                    } else {
                        resultPack = (CallPack) e.getValue();
                    }
                    waitHandle.set();
                });

                client.send(pack);
                waitHandle.waitOne(client.getConnectTimeout());
            } else {
                try (TcpClient client = pool.borrow(serverAddress)) {  //已连接
                    client.<ErrorEventArgs<ChannelHandlerContext>>attachEvent(TcpClient.EventNames.Error, (s, e) -> {
                        e.setCancel(true);
                        log.error("!Error & Set!", e.getValue());
                        waitHandle.set();
                    });
                    client.<PackEventArgs<ChannelHandlerContext>>attachEvent(TcpClient.EventNames.Receive, (s, e) -> {
                        resultPack = (CallPack) e.getValue();
                        waitHandle.set();
                    });

                    client.send(pack);
                    waitHandle.waitOne(client.getConnectTimeout());
                }
            }

            waitHandle.reset();
            return resultPack.returnValue;
        }

//        @SneakyThrows
//        private static Object getCglibProxyTargetObject(Object proxy) {
//            Field field = proxy.getClass().getDeclaredField("CGLIB$CALLBACK_0");
//            field.setAccessible(true);
//            Object dynamicAdvisedInterceptor = field.get(proxy);
//
////        Field advised = dynamicAdvisedInterceptor.getClass().getDeclaredField("advised");
////        advised.setAccessible(true);
////        Object target = ((AdvisedSupport) advised.get(dynamicAdvisedInterceptor)).getTargetSource().getTarget();
//            return dynamicAdvisedInterceptor;
//        }
    }

    private static final Object[] emptyParameter = new Object[0];
    private static final Map<Object, TcpServer<TcpServer.ClientSession>> host = new ConcurrentHashMap<>();

    public static <T> T create(Class<T> contract, String endpoint) {
        return create(contract, endpoint, false);
    }

    public static <T> T create(Class<T> contract, String endpoint, boolean enableDual) {
        require(contract);
//        require(contract, contract.isInterface());

        if (EventTarget.class.isAssignableFrom(contract)) {
            enableDual = true;
        }
        return (T) Enhancer.create(contract, new ClientHandler(Sockets.parseAddress(endpoint), enableDual));
    }

    public static <T> void listen(T contractInstance, int port) {
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
                SessionChannelId clientId = e.getClient().getId();
                RemoteEventPack eventPack;
                if ((eventPack = as(e.getValue(), RemoteEventPack.class)) != null) {
                    eventPack.remoteOk = true;
                    EventTarget eventTarget = (EventTarget) contractInstance;
                    eventTarget.attachEvent(eventPack.eventName, (sender, args) -> {
                        RemoteEventPack pack = SessionPack.create(RemoteEventPack.class);
                        pack.eventName = eventPack.eventName;
                        pack.remoteArgs = (EventArgs) args;
                        s.send(clientId, pack);
                    });
                    s.send(clientId, eventPack);
                    return;
                }

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
                s.send(clientId, pack);
            };
            server.start();
            return server;
        });
    }
}
