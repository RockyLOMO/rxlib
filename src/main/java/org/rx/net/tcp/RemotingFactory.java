package org.rx.net.tcp;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;
import org.rx.core.StringBuilder;
import org.rx.util.BeanMapFlag;
import org.rx.util.BeanMapper;
import org.rx.bean.BiTuple;
import org.rx.core.*;
import org.rx.net.Sockets;
import org.rx.core.ManualResetEvent;
import org.rx.net.tcp.packet.ErrorPacket;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.rx.core.Contract.*;

@Slf4j
public final class RemotingFactory {
    @AllArgsConstructor
    public static class RemotingState {
        @Getter
        @Setter
        private boolean broadcast;
    }

    @RequiredArgsConstructor
    private static class HostValue {
        private final TcpServer server;
        private final Map<UUID, BiTuple<ITcpClient, ManualResetEvent, EventArgs>> eventHost = new ConcurrentHashMap<>();
    }

    @RequiredArgsConstructor
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
    private static class CallPack implements Serializable {
        private final String methodName;
        private final Object[] parameters;
        private Object returnValue;
        private String errorMessage;
    }

    @RequiredArgsConstructor
    private static class ClientHandler implements MethodInterceptor {
        public BiConsumer<Object, NEventArgs<TcpClient>> onHandshake;
        public Function<InetSocketAddress, InetSocketAddress> preReconnect;
        private final Class targetType;
        @Getter
        private final String groupId;
        @Getter
        private final boolean tryEpoll;
        private final ManualResetEvent waitHandle = new ManualResetEvent();
        private InetSocketAddress serverAddress;
        private volatile CallPack resultPack;
        private TcpClient client;
        private final Lazy<TcpClientPool> nonStatePool = new Lazy<>(() -> {
            TcpClientPool pool = new TcpClientPool();
            pool.onCreate = (s, e) -> e.setPoolingClient(TcpConfig.client(isTryEpoll(), e.getValue(), getGroupId()));
            return pool;
        });

        @Override
        public Object intercept(Object o, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
            if (Reflects.OBJECT_METHODS.contains(method)) {
                return methodProxy.invokeSuper(o, args);
            }
            if (Reflects.isCloseMethod(method)) {
                closeClient();
                return null;
            }

            String methodName = method.getName();
            Serializable pack = null;
            switch (methodName) {
                case "attachEvent":
                case "detachEvent":
                    if (args.length == 2 || args.length == 3) {
                        String eventName = (String) args[0];
                        BiConsumer event = (BiConsumer) args[1];
                        if (targetType.isInterface()) {
                            if (methodName.equals("detachEvent")) {
                                EventListener.getInstance().detach((EventTarget) o, eventName, event);
                            } else {
                                boolean combine = args.length != 3 || (boolean) args[2];
                                EventListener.getInstance().attach((EventTarget) o, eventName, event, combine);
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
                            return EventTarget.EventFlags.DynamicAttach.flags();
                        }
                        return methodProxy.invokeSuper(o, args);
                    }
                    break;
            }
            if (pack == null) {
                pack = new CallPack(methodName, args);
            }

            synchronized (this) {
                if (onHandshake != null || preReconnect != null) {
                    initHandshake(o);
                    send(client, pack);
                } else {
                    try (TcpClient client = nonStatePool.getValue().borrow(serverAddress)) {  //已连接
                        client.<NEventArgs<Throwable>>attachEvent(TcpClient.EventNames.Error, (s, e) -> {
                            e.setCancel(true);
                            waitHandle.set();
                        });
                        client.<NEventArgs<Serializable>>attachEvent(TcpClient.EventNames.Receive, (s, e) -> {
                            resultPack = (CallPack) e.getValue();
                            waitHandle.set();
                        });
                        send(client, pack);
                    }
                }
            }
            return resultPack != null ? resultPack.returnValue : null;
        }

        private void send(TcpClient client, Serializable pack) throws TimeoutException {
            StringBuilder msg = new StringBuilder();
            boolean debug = tryAs(pack, CallPack.class, p -> {
                msg.appendLine("Rpc client %s.%s", targetType.getSimpleName(), p.methodName);
                msg.appendLine("Request:\t%s", toJsonString(p.parameters));
            });
            try {
                client.send(pack);
                waitHandle.waitOne(client.getConfig().getConnectTimeoutMillis());
                waitHandle.reset();
                if (debug) {
                    msg.appendLine("Response:\t%s", resultPack != null ? resultPack.errorMessage != null ? resultPack.errorMessage : toJsonString(resultPack.returnValue) : "null");
                }
                if (resultPack != null) {
                    if (resultPack.errorMessage != null) {
                        String errMsg = resultPack.errorMessage;
                        resultPack.errorMessage = null;
                        throw new RemotingException(errMsg);
                    }
                }
            } catch (TimeoutException e) {
                if (debug) {
                    msg.appendLine("Response:\t%s", e.getMessage());
                }
                throw e;
            } finally {
                if (debug) {
                    log.debug(msg.toString());
                }
            }
        }

        private synchronized void initHandshake(Object proxyObject) {
            if (client != null) {
                return;
            }
            log.debug("client initHandshake {}", serverAddress);
            client = TcpConfig.client(tryEpoll, serverAddress, groupId);
            client.setAutoReconnect(true);
            client.setPreReconnect(preReconnect);
            client.attachEvent(TcpClient.EventNames.Connected, (s, e) -> {
                log.debug("client onHandshake {}", serverAddress);
                s.send(new RemoteEventPack(Strings.EMPTY, RemoteEventFlag.Register));
                if (onHandshake == null) {
                    return;
                }
                onHandshake.accept(proxyObject, new NEventArgs<>(s));
            });
            client.<NEventArgs<Throwable>>attachEvent(TcpClient.EventNames.Error, (s, e) -> {
                e.setCancel(true);
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
                                log.error("client raise {}", remoteEventPack.eventName, ex);
                            } finally {
                                if (!remoteEventPack.broadcast) {
                                    s.send(remoteEventPack);  //import
                                }
                                log.info("client raise {} ok", remoteEventPack.eventName);
                            }
                            return;  //import
                    }
                } else {
                    resultPack = (CallPack) e.getValue();
                }
                waitHandle.set();
            });
            client.connect(true);
        }

        private synchronized void closeClient() {
            if (client == null) {
                return;
            }
            log.debug("client close");
            if (client.isConnected()) {
                client.send(new RemoteEventPack(Strings.EMPTY, RemoteEventFlag.Unregister));
            }
            client.close();
            client = null;
        }
    }

    private static final String AttrRemotingState = "RS";
    private static final Map<Object, HostValue> host = new ConcurrentHashMap<>();

    public static <T> T create(Class<T> contract, String endpoint) {
        return create(contract, Sockets.parseEndpoint(endpoint), contract.getSimpleName(), null);
    }

    public static <T> T create(Class<T> contract, InetSocketAddress endpoint, String groupId, BiConsumer<T, NEventArgs<TcpClient>> onHandshake) {
        return create(contract, endpoint, groupId, onHandshake, null, true);
    }

    public static <T> T create(Class<T> contract, InetSocketAddress endpoint, String groupId, BiConsumer<T, NEventArgs<TcpClient>> onHandshake, Function<InetSocketAddress, InetSocketAddress> preReconnect, boolean tryEpoll) {
        require(contract, endpoint);

        if (EventTarget.class.isAssignableFrom(contract) && onHandshake == null) {
            onHandshake = (s, e) -> {
            };
        }
        ClientHandler handler = new ClientHandler(contract, groupId, tryEpoll);
        handler.serverAddress = endpoint;
        handler.onHandshake = (BiConsumer<Object, NEventArgs<TcpClient>>) onHandshake;
        handler.preReconnect = preReconnect;
        T p = (T) Enhancer.create(contract, handler);
        if (handler.onHandshake != null || handler.preReconnect != null) {
            handler.initHandshake(p);
        }
        return p;
    }

    public static TcpServer listen(Object contractInstance, int port) {
        require(contractInstance);

        return host.computeIfAbsent(contractInstance, k -> {
            TcpServer server = TcpConfig.server(true, port);
            HostValue hostValue = new HostValue(server);
            server.onClosed = (s, e) -> host.remove(contractInstance);
            server.onError = (s, e) -> {
                e.setCancel(true);
                e.getClient().send(new ErrorPacket(String.format("Rpc error: %s", e.getValue().getMessage())));
            };
            server.onReceive = (s, e) -> {
                if (tryAs(e.getValue(), RemoteEventPack.class, p -> {
                    switch (p.flag) {
                        case Register:
                            e.getClient().<RemotingState>attr(AttrRemotingState).set(new RemotingState(true));
                            break;
                        case Unregister:  //客户端退出会自动处理
                            e.getClient().<RemotingState>attr(AttrRemotingState).get().broadcast = false;
                            break;
                        case Post:
                            EventTarget eventTarget = (EventTarget) contractInstance;
                            eventTarget.attachEvent(p.eventName, (sender, args) -> {
                                String groupId = e.getClient().getGroupId();
                                NQuery<ITcpClient> clients = NQuery.of(s.getClients(groupId)).where(x -> x.<RemotingState>attr(AttrRemotingState).get().broadcast);
                                if (!clients.any()) {
                                    log.warn("Group[{}].Client not found", groupId);
                                    return;
                                }
                                ITcpClient current = clients.contains(e.getClient()) ? e.getClient() : clients.first();
                                RemoteEventPack pack = new RemoteEventPack(p.eventName, RemoteEventFlag.PostBack);
                                pack.id = UUID.randomUUID();
                                pack.remoteArgs = (EventArgs) args;
                                BiTuple<ITcpClient, ManualResetEvent, EventArgs> tuple = BiTuple.of(current, new ManualResetEvent(), pack.remoteArgs);
                                hostValue.eventHost.put(pack.id, tuple);

                                current.send(pack);
                                log.info("server raise {} -> {} step1", current.getId(), pack.eventName);
                                try {
                                    //必须等
                                    tuple.middle.waitOne(server.getConfig().getConnectTimeoutMillis());
                                    log.info("server raise {} -> {} step2", current.getId(), pack.eventName);
                                } catch (TimeoutException ex) {
                                    log.warn("server raise {}", pack.eventName, ex);
                                } finally {
                                    BeanMapper.getInstance().map(tuple.right, args, BeanMapFlag.None.flags());
                                    pack.broadcast = true;
                                    pack.remoteArgs = tuple.right;
                                    for (ITcpClient client : clients) {
                                        if (client == current) {
                                            continue;
                                        }
                                        client.send(pack);
                                        log.info("server raise {} broadcast {} ok", client.getId(), pack.eventName);
                                    }

                                    hostValue.eventHost.remove(pack.id);
                                    log.info("server raise {} -> {} done", current.getId(), pack.eventName);
                                }
                            }, false);  //combine不准
                            e.getClient().send(p);
                            log.info("server attach {} {} ok", e.getClient().getId(), p.eventName);
                            break;
                        case PostBack:
                            BiTuple<ITcpClient, ManualResetEvent, EventArgs> tuple = hostValue.eventHost.get(p.id);
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
                StringBuilder msg = new StringBuilder();
                msg.appendLine("Rpc server %s.%s", contractInstance.getClass().getSimpleName(), pack.methodName);
                msg.appendLine("Request:\t%s", toJsonString(pack.parameters));
                try {
                    pack.returnValue = Reflects.invokeMethod(contractInstance.getClass(), contractInstance, pack.methodName, pack.parameters);
                    msg.appendLine("Response:\t%s", toJsonString(pack.returnValue));
                } catch (Exception ex) {
                    Throwable cause = ex.getCause();
                    log.error("Rpc", cause);
                    pack.errorMessage = String.format("ERROR: %s %s", cause.getClass().getSimpleName(), cause.getMessage());
                    msg.appendLine("Response:\t%s", pack.errorMessage);
                }
                log.debug(msg.toString());
                java.util.Arrays.fill(pack.parameters, null);
                e.getClient().send(pack);
            };
            server.start();
            return hostValue;
        }).server;
    }
}
