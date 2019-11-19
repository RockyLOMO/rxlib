package org.rx.socks.tcp;

import io.netty.channel.ChannelHandlerContext;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;
import org.rx.beans.BeanMapper;
import org.rx.beans.BiTuple;
import org.rx.core.*;
import org.rx.socks.Sockets;
import org.rx.core.ManualResetEvent;
import org.rx.socks.tcp.packet.ErrorPacket;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.rx.core.App.Config;
import static org.rx.core.Contract.*;

@Slf4j
public final class RemotingFactor {
    public static class RemotingClient extends SessionClient {
        @Getter
        @Setter
        private boolean broadcast;

        public RemotingClient(ChannelHandlerContext ctx) {
            super(ctx);
        }
    }

    @RequiredArgsConstructor
    @Data
    private static class RemoteEventPack implements Serializable {
        private final String eventName;
        private final RemoteEventFlag flag;
        private boolean broadcast;
        private UUID id;
        private EventArgs remoteArgs;
    }

    private enum RemoteEventFlag {
        Register, Unregister, Post, PostBack
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
    private static class ClientHandler extends Disposable implements MethodInterceptor {
        private static final NQuery<Method> objectMethods = NQuery.of(Object.class.getMethods());
        private static final TcpClientPool pool = new TcpClientPool(p -> TcpConfig.client(p, Config.getAppId()), ThreadPool.MaxThreads);

        private final Class targetType;
        private final InetSocketAddress serverAddress;
        private final Consumer onDualInit;
        private final ManualResetEvent waitHandle = new ManualResetEvent();
        private CallPack resultPack;
        private TcpClient client;

        @Override
        protected void freeObjects() {
            if (client != null) {
                log.debug("client close");
                client.send(new RemoteEventPack(Strings.EMPTY, RemoteEventFlag.Unregister));
                client.close();
                client = null;
            }
        }

        @Override
        public Object intercept(Object o, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
            if (objectMethods.contains(method)) {
                return methodProxy.invokeSuper(o, args);
            }
            checkNotClosed();

            String methodName = method.getName();
            Serializable pack = null;
            switch (methodName) {
                case "close":
                    if (args.length == 0) {
                        close();
                        return null;
                    }
                    break;
                case "attachEvent":
                    if (args.length == 2) {
                        String eventName = (String) args[0];
                        BiConsumer event = (BiConsumer) args[1];
                        if (targetType.isInterface()) {
                            EventListener.getInstance().attach(o, eventName, event);
                        } else {
                            methodProxy.invokeSuper(o, args);
                        }
//                        methodProxy.invoke(o, objects);
//                        method.invoke(o,objects);
//                        EventTarget eventTarget = (EventTarget) o;
//                        eventTarget.attachEvent(eventName, event);
                        pack = new RemoteEventPack(eventName, RemoteEventFlag.Post);
                        log.info("client attach {} step1", eventName);
                    }
                    break;
                case "raiseEvent":
                    if (args.length == 2) {
                        String eventName = (String) args[0];
                        EventArgs event = (EventArgs) args[1];
                        if (targetType.isInterface()) {
                            EventListener.getInstance().raise(o, eventName, event);
                            return null;
                        } else {
                            return methodProxy.invokeSuper(o, args);
                        }
                    }
                case "dynamicAttach":
                    return methodProxy.invokeSuper(o, args);
            }
            if (pack == null) {
                pack = new CallPack(methodName, args);
            }

            if (onDualInit != null) {
                if (client == null) {
                    client = pool.borrow(serverAddress);
                    client.setAutoReconnect(true);
//                    log.info("onDualInit..");
//                    onDualInit.accept(o); //无效
                    client.<ErrorEventArgs<ChannelHandlerContext>>attachEvent(TcpClient.EventNames.Error, (s, e) -> {
                        e.setCancel(true);
                        log.error("Remoting Error", e.getValue());
                        waitHandle.set();
                    });
                    client.<NEventArgs<ChannelHandlerContext>>attachEvent(TcpClient.EventNames.Connected, (s, e) -> {
                        log.info("client reconnected");
                        client.send(new RemoteEventPack(Strings.EMPTY, RemoteEventFlag.Register));
                        onDualInit.accept(o);
                    });
                    client.<PackEventArgs<ChannelHandlerContext>>attachEvent(TcpClient.EventNames.Receive, (s, e) -> {
                        RemoteEventPack remoteEventPack;
                        if ((remoteEventPack = as(e.getValue(), RemoteEventPack.class)) != null) {
                            switch (remoteEventPack.flag) {
                                case Post:
                                    if (resultPack != null) {
                                        resultPack.returnValue = null;
                                    }
                                    log.info("client attach {} step2 ok", remoteEventPack.eventName);
                                    break;
                                case PostBack:
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
                                        if (!remoteEventPack.broadcast) {
                                            client.send(remoteEventPack);  //import
                                        }
                                        log.info("client raise {} ok", remoteEventPack.eventName);
                                    }
                                    return;
                            }
                        } else {
                            resultPack = (CallPack) e.getValue();
                        }
                        waitHandle.set();
                    });
                    client.send(new RemoteEventPack(Strings.EMPTY, RemoteEventFlag.Register));
                }

                client.send(pack);
                waitHandle.waitOne(client.getConfig().getConnectTimeout());
            } else {
                try (TcpClient client = pool.borrow(serverAddress)) {  //已连接
                    client.<ErrorEventArgs<ChannelHandlerContext>>attachEvent(TcpClient.EventNames.Error, (s, e) -> {
                        e.setCancel(true);
                        log.error("Remoting Error", e.getValue());
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
            log.debug("client send {} ok", pack.getClass());

            waitHandle.reset();
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

    private static final Map<Object, TcpServer<RemotingClient>> host = new ConcurrentHashMap<>();
    private static final Map<UUID, BiTuple<SessionClient, ManualResetEvent, EventArgs>> eventHost = new ConcurrentHashMap<>();

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

    public static <T> TcpServer<RemotingClient> listen(T contractInstance, int port) {
        require(contractInstance);

        Class contract = contractInstance.getClass();
        return host.computeIfAbsent(contractInstance, k -> {
            TcpServer<RemotingClient> server = TcpConfig.server(port, RemotingClient.class);
            server.onClosed = (s, e) -> host.remove(contractInstance);
            server.onError = (s, e) -> {
                e.setCancel(true);
                ErrorPacket pack = ErrorPacket.error(String.format("Remoting call error: %s", e.getValue().getMessage()));
                s.send(e.getClient(), pack);
            };
            server.onReceive = (s, e) -> {
                RemoteEventPack eventPack;
                if ((eventPack = as(e.getValue(), RemoteEventPack.class)) != null) {
                    switch (eventPack.flag) {
                        case Register:
                            e.getClient().broadcast = true;
                            break;
                        case Unregister:
                            e.getClient().broadcast = false;
                            break;
                        case Post:
                            EventTarget eventTarget = (EventTarget) contractInstance;
                            eventTarget.attachEvent(eventPack.eventName, (sender, args) -> {
                                NQuery<RemotingClient> clients = NQuery.of(s.getClients().get(e.getClient().getAppId())).where(p -> p.broadcast);
                                if (!clients.any()) {
                                    log.warn("Clients is empty");
                                    return;
                                }
                                RemotingClient current = clients.contains(e.getClient()) ? e.getClient() : clients.first();
                                RemoteEventPack pack = new RemoteEventPack(eventPack.eventName, RemoteEventFlag.PostBack);
                                pack.id = UUID.randomUUID();
                                pack.remoteArgs = (EventArgs) args;
                                BiTuple<SessionClient, ManualResetEvent, EventArgs> tuple = BiTuple.of(current, new ManualResetEvent(), pack.remoteArgs);
                                eventHost.put(pack.id, tuple);

                                s.send(current, pack);
                                log.info("server raise {} -> {} step1", current.getId(), pack.eventName);
                                try {
                                    tuple.middle.waitOne(server.getConfig().getConnectTimeout());
                                    log.info("server raise {} -> {} step2", current.getId(), pack.eventName);
                                } catch (TimeoutException ex) {
                                    log.warn("remoteEvent {}", pack.eventName, ex);
                                } finally {
                                    BeanMapper.getInstance().map(tuple.right, args, BeanMapper.Flags.None);
                                    pack.broadcast = true;
                                    pack.remoteArgs = tuple.right;
                                    for (RemotingClient client : clients) {
                                        if (client == current) {
                                            continue;
                                        }
                                        s.send(client, pack);
                                        log.info("server raise {} broadcast {} ok", client.getId(), pack.eventName);
                                    }

                                    eventHost.remove(pack.id);
                                    log.info("server raise {} -> {} done", current.getId(), pack.eventName);
                                }
                            });
                            s.send(e.getClient(), eventPack);
                            log.info("server attach {} {} ok", e.getClient().getId(), eventPack.eventName);
                            break;
                        case PostBack:
                            BiTuple<SessionClient, ManualResetEvent, EventArgs> tuple = eventHost.get(eventPack.id);
                            if (tuple != null) {
                                log.info("server raise {} -> {} step3", tuple.left.getId(), eventPack.eventName);
                                tuple.right = eventPack.remoteArgs;
                                tuple.middle.set();
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
                    throw new InvalidOperationException("Class %s Method %s not found", contract, pack.methodName);
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
}
