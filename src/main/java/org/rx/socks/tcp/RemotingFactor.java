package org.rx.socks.tcp;

import io.netty.channel.ChannelHandlerContext;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;
import org.rx.beans.BeanMapper;
import org.rx.beans.Tuple;
import org.rx.core.*;
import org.rx.socks.Sockets;
import org.rx.util.ManualResetEvent;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.rx.core.Contract.as;
import static org.rx.core.Contract.require;

@Slf4j
public final class RemotingFactor {
    @Data
    @EqualsAndHashCode(callSuper = true)
    private static class RemoteEventPack extends SessionPack {
        private String eventName;

        private UUID id;
        private EventArgs remoteArgs;

        private int flag;
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
        private Class targetType;
        private InetSocketAddress serverAddress;
        private Consumer onDualInit;
        private TcpClient client;

        public ClientHandler(Class targetType, InetSocketAddress serverAddress, Consumer onDualInit) {
            this.targetType = targetType;
            this.serverAddress = serverAddress;
            this.onDualInit = onDualInit;
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
                        BiConsumer event = (BiConsumer) objects[1];
                        if (targetType.isInterface()) {
                            EventListener.instance.attach(o, eventName, event);
                        } else {
                            methodProxy.invokeSuper(o, objects);
                        }
//                        methodProxy.invoke(o, objects);
//                        method.invoke(o,objects);
//                        EventTarget eventTarget = (EventTarget) o;
//                        eventTarget.attachEvent(eventName, event);
                        RemoteEventPack eventPack = SessionPack.create(RemoteEventPack.class);
                        eventPack.setEventName(eventName);
                        pack = eventPack;
                        log.info("client attach {} step1", eventName);
                    }
                    break;
                case "raiseEvent":
                case "dynamicAttach":
                    return methodProxy.invokeSuper(o, objects);
            }
            if (pack == null) {
                CallPack callPack = SessionPack.create(CallPack.class);
                callPack.methodName = methodName;
                callPack.parameters = objects;
                pack = callPack;
            }

            if (onDualInit != null) {
                if (client == null) {
                    client = pool.borrow(serverAddress);
                    client.setAutoReconnect(true);
//                    log.info("onDualInit..");
//                    onDualInit.accept(o); //无效
                }
                client.<ErrorEventArgs<ChannelHandlerContext>>attachEvent(TcpClient.EventNames.Error, (s, e) -> {
                    e.setCancel(true);
                    log.error("!Error & Set!", e.getValue());
                    waitHandle.set();
                });
                client.<NEventArgs<ChannelHandlerContext>>attachEvent(TcpClient.EventNames.Connected, (s, e) -> {
                    log.info("client reconnected");
                    onDualInit.accept(o);
                });
                client.<PackEventArgs<ChannelHandlerContext>>attachEvent(TcpClient.EventNames.Receive, (s, e) -> {
                    RemoteEventPack remoteEventPack;
                    if ((remoteEventPack = as(e.getValue(), RemoteEventPack.class)) != null) {
                        switch (remoteEventPack.flag) {
                            case 0:
                                if (resultPack != null) {
                                    resultPack.returnValue = null;
                                }
                                log.info("client attach {} step2 ok", remoteEventPack.eventName);
                                break;
                            case 1:
                                try {
                                    if (targetType.isInterface()) {
                                        EventListener.instance.raise(o, remoteEventPack.eventName, remoteEventPack.remoteArgs);
                                    } else {
                                        EventTarget eventTarget = (EventTarget) o;
                                        eventTarget.raiseEvent(remoteEventPack.eventName, remoteEventPack.remoteArgs);
                                    }
                                } catch (Exception ex) {
                                    log.error("client raise {} error", remoteEventPack.eventName, ex);
                                } finally {
                                    client.send(remoteEventPack);  //import
                                    log.info("client raise {} ok", remoteEventPack.eventName);
                                }
                                return;
                        }
                    } else {
                        resultPack = (CallPack) e.getValue();
                    }
                    waitHandle.set();
                });

                client.send(pack);
                log.info("client send {} step1", pack.getClass());
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
            log.info("client send {} step2 ok", pack.getClass());
            return resultPack != null ? resultPack.returnValue : null;
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
    private static final Map<UUID, Tuple<ManualResetEvent, EventArgs>> eventHost = new ConcurrentHashMap<>();

    public static <T> T create(Class<T> contract, String endpoint) {
        return create(contract, endpoint, null);
    }

    public static <T> T create(Class<T> contract, String endpoint, Consumer<T> onDualInit) {
        require(contract);

        if (EventTarget.class.isAssignableFrom(contract) && onDualInit == null) {
            onDualInit = p -> {
            };
        }
        T p = (T) Enhancer.create(contract, new ClientHandler(contract, Sockets.parseAddress(endpoint), onDualInit));
        if (onDualInit != null) {
            log.info("onDualInit..");
            onDualInit.accept(p);
        }
        return p;
    }

    public static <T> void listen(T contractInstance, int port) {
        listen(contractInstance, port, null);
    }

    public static <T> void listen(T contractInstance, int port, Long connectTimeout) {
        require(contractInstance);

        Class contract = contractInstance.getClass();
        host.computeIfAbsent(contractInstance, k -> {
            TcpServer<TcpServer.ClientSession> server = new TcpServer<>(port, true);
            if (connectTimeout != null) {
                server.setConnectTimeout(connectTimeout);
            }
            server.onError = (s, e) -> {
                e.setCancel(true);
                SessionPack pack = SessionPack.error(String.format("Remoting call error: %s", e.getValue().getMessage()));
                s.send(e.getClient().getId(), pack);
            };
            server.onReceive = (s, e) -> {
                SessionChannelId clientId = e.getClient().getId();
                RemoteEventPack eventPack;
                if ((eventPack = as(e.getValue(), RemoteEventPack.class)) != null) {
                    switch (eventPack.flag) {
                        case 0:
                            EventTarget eventTarget = (EventTarget) contractInstance;
                            eventTarget.attachEvent(eventPack.eventName, (sender, args) -> {
                                RemoteEventPack pack = SessionPack.create(RemoteEventPack.class);
                                pack.eventName = eventPack.eventName;
                                pack.id = UUID.randomUUID();
                                pack.remoteArgs = (EventArgs) args;
                                pack.flag = 1;
                                Set<TcpServer.ClientSession> clients = s.getClients().get(clientId.sessionId());
                                if (clients == null) {
                                    log.warn("Clients sessionId not found");
                                    return;
                                }
                                for (TcpServer.ClientSession client : clients) {
                                    s.send(client.getId(), pack);
                                }
                                log.info("server raise {} step1", pack.eventName);

                                Tuple<ManualResetEvent, EventArgs> tuple = Tuple.of(new ManualResetEvent(), pack.remoteArgs);
                                eventHost.put(pack.id, tuple);
                                try {
                                    tuple.left.waitOne(server.getConnectTimeout());
                                    log.info("server raise {} step2", pack.eventName);
                                    BeanMapper.getInstance().map(tuple.right, args, BeanMapper.Flags.None);
                                } catch (TimeoutException ex) {
                                    log.warn("remoteEvent {}", pack.eventName, ex);
                                } finally {
                                    eventHost.remove(pack.id);
                                    log.info("server raise {} done", pack.eventName);
                                }
                            });
                            s.send(clientId, eventPack);
                            log.info("server attach {} ok", eventPack.eventName);
                            break;
                        case 1:
                            Tuple<ManualResetEvent, EventArgs> tuple = eventHost.get(eventPack.id);
                            if (tuple != null) {
                                log.info("server raise {} step3", eventPack.eventName);
                                tuple.right = eventPack.remoteArgs;
                                tuple.left.set();
                            } else {
                                log.info("server raise {} step3 fail", eventPack.eventName);
                            }
                            break;
                    }
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
