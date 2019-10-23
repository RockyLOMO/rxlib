package org.rx.socks.tcp;

import io.netty.channel.ChannelHandlerContext;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;
import org.rx.beans.BeanMapper;
import org.rx.core.*;
import org.rx.socks.Sockets;
import org.rx.core.ManualResetEvent;
import org.rx.socks.tcp.packet.ErrorPacket;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.rx.core.App.Config;
import static org.rx.core.Contract.*;

@Slf4j
public final class RemotingFactor {
    @RequiredArgsConstructor
    @Data
    private static class RemoteEventPack implements Serializable {
        private final String eventName;

        private UUID id;
        private EventArgs remoteArgs;
        private int flag;
    }

    @RequiredArgsConstructor
    @Data
    private static class CallPack implements Serializable {
        private final String methodName;
        private final Object[] parameters;
        private Object returnValue;

        private String errorMessage;
    }

    @RequiredArgsConstructor
    private static class ClientHandler implements MethodInterceptor {
        private static final NQuery<Method> objectMethods = NQuery.of(Object.class.getMethods());
        private static final TcpClientPool pool = new TcpClientPool(p -> TcpConfig.packetClient(p, Config.getAppId()));

        private final Class targetType;
        private final InetSocketAddress serverAddress;
        private final Consumer onDualInit;
        private final ManualResetEvent waitHandle = new ManualResetEvent();
        private CallPack resultPack;
        private TcpClient client;

        @Override
        public Object intercept(Object o, Method method, Object[] objects, MethodProxy methodProxy) throws Throwable {
            if (objectMethods.contains(method)) {
                return methodProxy.invokeSuper(o, objects);
            }
            String methodName = method.getName();

            Serializable pack = null;
            switch (methodName) {
                case "attachEvent":
                    if (objects.length == 2) {
                        String eventName = (String) objects[0];
                        BiConsumer event = (BiConsumer) objects[1];
                        if (targetType.isInterface()) {
                            EventListener.getInstance().attach(o, eventName, event);
                        } else {
                            methodProxy.invokeSuper(o, objects);
                        }
//                        methodProxy.invoke(o, objects);
//                        method.invoke(o,objects);
//                        EventTarget eventTarget = (EventTarget) o;
//                        eventTarget.attachEvent(eventName, event);
                        pack = new RemoteEventPack(eventName);
                        log.info("client attach {} step1", eventName);
                    }
                    break;
                case "raiseEvent":
                case "dynamicAttach":
                    return methodProxy.invokeSuper(o, objects);
            }
            if (pack == null) {
                pack = new CallPack(methodName, objects);
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
                                        EventListener.getInstance().raise(o, remoteEventPack.eventName, remoteEventPack.remoteArgs);
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
                waitHandle.waitOne(client.getConfig().getConnectTimeout());
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
                    waitHandle.waitOne(client.getConfig().getConnectTimeout());
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

    private static final Map<Object, TcpServer<SessionClient>> host = new ConcurrentHashMap<>();
    private static final Map<UUID, EventArgs> eventHost = new ConcurrentHashMap<>();

    public static <T> T create(Class<T> contract, String endpoint) {
        return create(contract, Sockets.parseEndpoint(endpoint), null);
    }

    public static <T> T create(Class<T> contract, InetSocketAddress endpoint, Consumer<T> onDualInit) {
        require(contract);

        if (EventTarget.class.isAssignableFrom(contract) && onDualInit == null) {
            onDualInit = p -> {
            };
        }
        T p = (T) Enhancer.create(contract, new ClientHandler(contract, endpoint, onDualInit));
        if (onDualInit != null) {
            log.info("onDualInit..");
            onDualInit.accept(p);
        }
        return p;
    }

    public static <T> TcpServer<SessionClient> listen(T contractInstance, int port) {
        require(contractInstance);

        Class contract = contractInstance.getClass();
        return host.computeIfAbsent(contractInstance, k -> {
            TcpServer<SessionClient> server = TcpConfig.packetServer(port, null);
            server.onError = (s, e) -> {
                e.setCancel(true);
                ErrorPacket pack = ErrorPacket.error(String.format("Remoting call error: %s", e.getValue().getMessage()));
                s.send(e.getClient(), pack);
            };
            server.onReceive = (s, e) -> {
                RemoteEventPack eventPack;
                if ((eventPack = as(e.getValue(), RemoteEventPack.class)) != null) {
                    switch (eventPack.flag) {
                        case 0:
                            EventTarget eventTarget = (EventTarget) contractInstance;
                            eventTarget.attachEvent(eventPack.eventName, (sender, args) -> {
                                RemoteEventPack pack = new RemoteEventPack(eventPack.eventName);
                                pack.id = UUID.randomUUID();
                                pack.remoteArgs = (EventArgs) args;
                                pack.flag = 1;
                                Set<SessionClient> clients = s.getClients().get(e.getClient().getAppId());
                                if (clients == null) {
                                    log.warn("Clients is empty");
                                    return;
                                }
                                for (SessionClient client : clients) {
                                    s.send(client, pack);
                                }
                                log.info("server raise {} step1", pack.eventName);

                                eventHost.put(pack.id, pack.remoteArgs);
                                try {
                                    log.info("server raise {} step2", pack.eventName);
                                    BeanMapper.getInstance().map(pack.remoteArgs, args, BeanMapper.Flags.None);
                                } finally {
                                    eventHost.remove(pack.id);
                                    log.info("server raise {} done", pack.eventName);
                                }
                            });
                            s.send(e.getClient(), eventPack);
                            log.info("server attach {} ok", eventPack.eventName);
                            break;
                        case 1:
                            EventArgs args = eventHost.get(eventPack.id);
                            if (args != null) {
                                log.info("server raise {} step3", eventPack.eventName);
                                args = eventPack.remoteArgs;
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
                java.util.Arrays.fill(pack.parameters, null);
                s.send(e.getClient(), pack);
            };
            server.start();
            return server;
        });
    }

    public static <T> void stopListen(T contractInstance) {
        require(contractInstance);

        TcpServer<SessionClient> server = host.remove(contractInstance);
        if (server == null) {
            return;
        }
        server.close();
    }
}
