package org.rx.socks.tcp;

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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.rx.core.Contract.*;

@Slf4j
public final class RemotingFactor {
    public static class RemotingState implements Serializable {
        @Getter
        @Setter
        private boolean broadcast;
    }

    @RequiredArgsConstructor
    private static class HostValue {
        public final TcpServer<RemotingState> server;
        public final Map<UUID, BiTuple<SessionClient, ManualResetEvent, EventArgs>> eventHost = new ConcurrentHashMap<>();
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
    private static class ClientHandler<T> implements MethodInterceptor {
        private static final NQuery<Method> objectMethods = NQuery.of(Object.class.getMethods());

        private final Class targetType;
        private final String groupId;
        private final Consumer<T> onHandshake;
        private final Function<InetSocketAddress, InetSocketAddress> onReconnect;
        private final ManualResetEvent waitHandle = new ManualResetEvent();
        private volatile InetSocketAddress serverAddress;
        private CallPack resultPack;
        private TcpClient client;
        private AtomicReference<TcpClientPool> nonStatePool = new AtomicReference<>();

        @Override
        public Object intercept(Object o, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
            if (objectMethods.contains(method)) {
                return methodProxy.invokeSuper(o, args);
            }
            if (Reflects.isCloseMethod(method)) {
                closeHandshakeClient();
                return null;
            }

            String methodName = method.getName();
            Serializable pack = null;
            switch (methodName) {
                case "attachEvent":
                case "detachEvent":
                    if (args.length == 2) {
                        String eventName = (String) args[0];
                        BiConsumer event = (BiConsumer) args[1];
                        if (targetType.isInterface()) {
                            if (methodName.equals("detachEvent")) {
                                EventListener.getInstance().detach((EventTarget) o, eventName, event);
                            } else {
                                EventListener.getInstance().attach((EventTarget) o, eventName, event);
                            }
                        } else {
                            methodProxy.invokeSuper(o, args);
                        }
                        pack = new RemoteEventPack(eventName, RemoteEventFlag.Post);
                        log.info("client attach {} step1", eventName);
                    }
                    break;
                case "raiseEvent":
                    if (args.length == 2) {
                        if (targetType.isInterface() && args[0] instanceof String) {
                            String eventName = (String) args[0];
                            EventArgs event = (EventArgs) args[1];
                            EventListener.getInstance().raise((EventTarget) o, eventName, event);
                            return null;
                        }
                        return methodProxy.invokeSuper(o, args);
                    }
                    break;
                case "eventFlags":
                    if (args.length == 0) {
                        if (targetType.isInterface()) {
                            return EventTarget.EventFlags.DynamicAttach.add();
                        }
                        return methodProxy.invokeSuper(o, args);
                    }
                    break;
            }
            if (pack == null) {
                pack = new CallPack(methodName, args);
            }

            if (onHandshake != null || onReconnect != null) {
                if (client == null) {
                    initHandshakeClient((T) o);
                }
                client.send(pack);
                waitHandle.waitOne(client.getConfig().getConnectTimeout());
            } else {
                while (nonStatePool.get() == null
                        && !nonStatePool.compareAndSet(null, new TcpClientPool(p -> TcpConfig.client(p, groupId), ThreadPool.MaxThreads))) {
                }
                try (TcpClient client = nonStatePool.get().borrow(serverAddress)) {  //已连接
                    client.<NEventArgs<Throwable>>attachEvent(TcpClient.EventNames.Error, (s, e) -> {
                        e.setCancel(true);
                        log.error("Remoting Error", e.getValue());
                        waitHandle.set();
                    });
                    client.<NEventArgs<Serializable>>attachEvent(TcpClient.EventNames.Receive, (s, e) -> {
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

        private void initHandshakeClient(T proxyObject) {
            log.debug("initHandshakeClient {}", serverAddress);
            client = TcpConfig.client(serverAddress, groupId);
            client.connect(true);
            client.setAutoReconnect(true);
            if (onReconnect != null) {
                client.setAutoReconnect(false);
                client.attachEvent(TcpClient.EventNames.Disconnected, (s, e) -> {
                    log.info("client disconnected");
                    while (client == null || !client.isConnected()) {
                        log.info("client serverAddress changed to {}", serverAddress = onReconnect.apply(serverAddress));
                        closeHandshakeClient();
                        try {
                            initHandshakeClient(proxyObject);
                        } catch (Exception ex) {
                            log.debug("client reconnect error: {}", ex.getMessage());
                        }
                    }
                });
            }
            client.attachEvent(TcpClient.EventNames.Connected, (s, e) -> {
                log.info("client reconnected");
                client.send(new RemoteEventPack(Strings.EMPTY, RemoteEventFlag.Register));
                onHandshake.accept(proxyObject);
            });
            client.<NEventArgs<Throwable>>attachEvent(TcpClient.EventNames.Error, (s, e) -> {
                e.setCancel(true);
                log.error("Remoting Error", e.getValue());
                waitHandle.set();
            });
            client.<NEventArgs<Serializable>>attachEvent(TcpClient.EventNames.Receive, (s, e) -> {
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
                                    EventListener.getInstance().raise((EventTarget) proxyObject, remoteEventPack.eventName, remoteEventPack.remoteArgs);
                                } else {
                                    EventTarget eventTarget = (EventTarget) proxyObject;
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
//            log.debug("onHandshake {}", serverAddress);
//            onHandshake.accept(proxyObject);
        }

        private void closeHandshakeClient() {
            if (client == null) {
                return;
            }
            log.debug("client close");
            client.send(new RemoteEventPack(Strings.EMPTY, RemoteEventFlag.Unregister));
            client.close();
            client = null;
        }
    }

    private static final Map<Object, HostValue> host = new ConcurrentHashMap<>();

    public static <T> T create(Class<T> contract, String endpoint) {
        return create(contract, Sockets.parseEndpoint(endpoint), Strings.EMPTY, null);
    }

    public static <T> T create(Class<T> contract, InetSocketAddress endpoint, String groupId, Consumer<T> onHandshake) {
        return create(contract, endpoint, groupId, onHandshake, null);
    }

    public static <T> T create(Class<T> contract, InetSocketAddress endpoint, String groupId, Consumer<T> onHandshake, Function<InetSocketAddress, InetSocketAddress> onReconnect) {
        require(contract, endpoint);

        if (EventTarget.class.isAssignableFrom(contract) && onHandshake == null) {
            onHandshake = p -> {
            };
        }
        ClientHandler<T> handler = new ClientHandler<>(contract, groupId, onHandshake, onReconnect);
        handler.serverAddress = endpoint;
        T p = (T) Enhancer.create(contract, handler);
        if (onHandshake != null) {
            log.debug("onHandshake {}", endpoint);
            onHandshake.accept(p);
        }
        return p;
    }

    public static TcpServer<RemotingState> listen(Object contractInstance, int port) {
        require(contractInstance);

        return host.computeIfAbsent(contractInstance, k -> {
            TcpServer<RemotingState> server = TcpConfig.server(port, RemotingState.class);
            HostValue hostValue = new HostValue(server);
            server.onClosed = (s, e) -> host.remove(contractInstance);
            server.onError = (s, e) -> {
                e.setCancel(true);
                s.send(e.getClient(), new ErrorPacket(String.format("Remoting call error: %s", e.getValue().getMessage())));
            };
            server.onReceive = (s, e) -> {
                if (tryAs(e.getValue(), RemoteEventPack.class, p -> {
                    switch (p.flag) {
                        case Register:
                            e.getClient().getState().broadcast = true;
                            break;
                        case Unregister:
                            e.getClient().getState().broadcast = false;
                            break;
                        case Post:
                            EventTarget eventTarget = (EventTarget) contractInstance;
                            eventTarget.attachEvent(p.eventName, (sender, args) -> {
                                String groupId = e.getClient().getGroupId();
                                NQuery<SessionClient<RemotingState>> clients = NQuery.of(s.getClients(groupId)).where(x -> x.getState().broadcast);
                                if (!clients.any()) {
                                    log.warn("Group[{}].Client not found", groupId);
                                    return;
                                }
                                SessionClient<RemotingState> current = clients.contains(e.getClient()) ? e.getClient() : clients.first();
                                RemoteEventPack pack = new RemoteEventPack(p.eventName, RemoteEventFlag.PostBack);
                                pack.id = UUID.randomUUID();
                                pack.remoteArgs = (EventArgs) args;
                                BiTuple<SessionClient, ManualResetEvent, EventArgs> tuple = BiTuple.of(current, new ManualResetEvent(), pack.remoteArgs);
                                hostValue.eventHost.put(pack.id, tuple);

                                s.send(current, pack);
                                log.info("server raise {} -> {} step1", current.getId(), pack.eventName);
                                try {
                                    //必须等
                                    tuple.middle.waitOne(server.getConfig().getConnectTimeout());
                                    log.info("server raise {} -> {} step2", current.getId(), pack.eventName);
                                } catch (TimeoutException ex) {
                                    log.warn("remoteEvent {}", pack.eventName, ex);
                                } finally {
                                    BeanMapper.getInstance().map(tuple.right, args, BeanMapper.Flags.None);
                                    pack.broadcast = true;
                                    pack.remoteArgs = tuple.right;
                                    for (SessionClient<RemotingState> client : clients) {
                                        if (client == current) {
                                            continue;
                                        }
                                        s.send(client, pack);
                                        log.info("server raise {} broadcast {} ok", client.getId(), pack.eventName);
                                    }

                                    hostValue.eventHost.remove(pack.id);
                                    log.info("server raise {} -> {} done", current.getId(), pack.eventName);
                                }
                            }, false);  //combine不准
                            s.send(e.getClient(), p);
                            log.info("server attach {} {} ok", e.getClient().getId(), p.eventName);
                            break;
                        case PostBack:
                            BiTuple<SessionClient, ManualResetEvent, EventArgs> tuple = hostValue.eventHost.get(p.id);
                            if (tuple != null) {
                                log.info("server raise {} -> {} step3", tuple.left.getId(), p.eventName);
                                tuple.right = p.remoteArgs;
                                tuple.middle.set();
                            } else {
                                log.info("server raise {} step3 fail", p.eventName);
                            }
                            break;
                    }
                })) {
                    return;
                }

                CallPack pack = (CallPack) e.getValue();
                try {
                    pack.returnValue = Reflects.invokeMethod(contractInstance.getClass(), contractInstance, pack.methodName, pack.parameters);
                } catch (Exception ex) {
                    log.error("listen", ex);
                    pack.setErrorMessage(String.format("%s %s", ex.getClass(), ex.getMessage()));
                }
                java.util.Arrays.fill(pack.parameters, null);
                s.send(e.getClient(), pack);
            };
            server.start();
            return hostValue;
        }).server;
    }
}
