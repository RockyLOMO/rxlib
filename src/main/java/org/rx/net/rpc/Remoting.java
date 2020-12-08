package org.rx.net.rpc;

import io.netty.util.Attribute;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.rx.bean.$;
import org.rx.bean.InterceptProxy;
import org.rx.core.StringBuilder;
import org.rx.net.rpc.impl.RpcClientPool;
import org.rx.net.rpc.impl.StatefulRpcClient;
import org.rx.net.rpc.protocol.EventFlag;
import org.rx.net.rpc.protocol.EventPack;
import org.rx.net.rpc.protocol.MethodPack;
import org.rx.core.*;
import org.rx.net.Sockets;
import org.rx.net.rpc.packet.ErrorPacket;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;

import static org.rx.bean.$.$;
import static org.rx.core.Contract.*;

@Slf4j
public final class Remoting {
    @RequiredArgsConstructor
    public static class ServerBean {
        static class EventBean {
            Set<RpcServerClient> subscribe = ConcurrentHashMap.newKeySet();
            volatile EventArgs computedArgs;
            volatile RpcServerClient computingClient;
        }

        @Getter
        private final RpcServer server;
        private final Map<String, EventBean> eventBeans = new ConcurrentHashMap<>();
    }

    private static final Map<Object, ServerBean> serverBeans = new ConcurrentHashMap<>();
    private static final Lazy<RpcClientPool> clientPool = new Lazy<>(RpcClientPool::new);

    public static <T> T create(Class<T> contract, String endpoint) {
        return create(contract, Sockets.parseEndpoint(endpoint));
    }

    public static <T> T create(Class<T> contract, InetSocketAddress endpoint) {
        RpcClientConfig config = new RpcClientConfig();
        config.setServerEndpoint(endpoint);
        return create(contract, config, null);
    }

    public static <T> T create(Class<T> contract, RpcClientConfig facadeConfig, BiConsumer<T, StatefulRpcClient> onHandshake) {
        require(contract, facadeConfig);

        ManualResetEvent wait = new ManualResetEvent();
        $<MethodPack> resultPack = $();
        $<StatefulRpcClient> sync = $();
        return proxy(contract, (m, p) -> {
            if (Reflects.OBJECT_METHODS.contains(m)) {
                return p.fastInvokeSuper();
            }
            if (Reflects.isCloseMethod(m)) {
                if (sync.v != null) {
                    sync.v.close();
                    sync.v = null;
                }
                return null;
            }

            Serializable pack = null;
            Object[] args = p.arguments;
            switch (m.getName()) {
                case "attachEvent": {
                    if (args.length != 3) {
                        return invokeSuper(m, p);
                    }
                    String eventName = (String) args[0];
                    pack = new EventPack(eventName, EventFlag.SUBSCRIBE);
                    log.info("clientSide event {} -> SUBSCRIBE", eventName);
                }
                break;
                case "detachEvent": {
                    if (args.length != 2) {
                        return invokeSuper(m, p);
                    }
                    String eventName = (String) args[0];
                    pack = new EventPack(eventName, EventFlag.UNSUBSCRIBE);
                    log.info("clientSide event {} -> UNSUBSCRIBE", eventName);
                }
                break;
                case "raiseEvent":
                    if (args.length != 2) {
                        return invokeSuper(m, p);
                    }
                    EventPack eventPack = new EventPack((String) args[0], EventFlag.PUBLISH);
                    eventPack.eventArgs = (EventArgs) args[1];
                    pack = eventPack;
                    log.info("clientSide event {} -> PUBLISH", eventPack.eventName);
                    break;
                case "eventFlags":
                    if (args.length == 0) {
                        return invokeSuper(m, p);
                    }
                    break;
            }

            if (pack == null) {
                pack = new MethodPack(m.getName(), args);
            }
            synchronized (sync) {
                if (sync.v == null) {
                    init(sync.v = clientPool.getValue().borrow(facadeConfig.getServerEndpoint()), wait, resultPack);
                    if (onHandshake != null) {
                        sync.v.setAutoReconnect(true);
                        onHandshake.accept((T) p.getProxyObject(), sync.v);
                        //onHandshake returnObject的情况
                        if (sync.v == null) {
                            init(sync.v = clientPool.getValue().borrow(facadeConfig.getServerEndpoint()), wait, resultPack);
                        }
                    }
                }
                StatefulRpcClient client = sync.v;

                StringBuilder msg = new StringBuilder();
                boolean debug = tryAs(pack, MethodPack.class, x -> {
                    msg.appendLine("Rpc client %s.%s", contract.getSimpleName(), x.methodName);
                    msg.appendLine("Request:\t%s", toJsonString(x.parameters));
                });
                try {
                    client.send(pack);
                    if (pack instanceof MethodPack) {
                        wait.waitOne(client.getConfig().getConnectTimeoutMillis());
                        wait.reset();
                    }
                    if (debug) {
                        msg.appendLine("Response:\t%s", resultPack.v != null ? resultPack.v.errorMessage != null ? resultPack.v.errorMessage : toJsonString(resultPack.v.returnValue) : "null");
                    }
                    if (resultPack.v != null && resultPack.v.errorMessage != null) {
                        throw new RemotingException(resultPack.v.errorMessage);
                    }
                } catch (Exception e) {
                    if (debug) {
                        msg.appendLine("Response:\t%s", e.getMessage());
                    }
                    throw e;
                } finally {
                    if (debug) {
                        log.debug(msg.toString());
                    }
                }

                client.close();
                Attribute<Boolean> attr = client.attr(RpcClientPool.Stateful);
                if (!BooleanUtils.isTrue(attr.get())) {
                    sync.v = null;
                }
            }
            return resultPack.v != null ? resultPack.v.returnValue : null;
        });
    }

    private static void init(StatefulRpcClient client, ManualResetEvent wait, $<MethodPack> resultPack) {
        client.onError = (s, e) -> {
            e.setCancel(true);
            wait.set();
        };
        client.onReceive = (s, e) -> {
            if (tryAs(e.getValue(), EventPack.class, x -> {
                switch (x.flag) {
                    case BROADCAST:
                    case COMPUTE_ARGS:
                        try {
                            client.raiseEvent(x.eventName, x.eventArgs);
                            log.info("clientSide event {} -> {} ok", x.eventName, x.flag);
                        } catch (Exception ex) {
                            log.error("clientSide event {} -> {}", x.eventName, x.flag, ex);
                        } finally {
                            if (x.flag == EventFlag.COMPUTE_ARGS) {
                                s.send(x);  //import
                            }
                        }
                        break;
                }
            })) {
                return;  //import
            }

            resultPack.v = (MethodPack) e.getValue();
            wait.set();
        };
    }

    @SneakyThrows
    private static Object invokeSuper(Method m, InterceptProxy p) {
        if (m.isDefault()) {
            return Reflects.invokeDefaultMethod(m, p.getProxyObject(), p.arguments);
        }
        return p.fastInvokeSuper();
    }

    public static ServerBean listen(Object contractInstance, int listenPort) {
        RpcServerConfig config = new RpcServerConfig();
        config.setListenPort(listenPort);
        return listen(contractInstance, config);
    }

    public static ServerBean listen(Object contractInstance, RpcServerConfig config) {
        require(contractInstance, config);

        return serverBeans.computeIfAbsent(contractInstance, k -> {
            ServerBean bean = new ServerBean(new RpcServer(config));
            bean.server.onClosed = (s, e) -> serverBeans.remove(contractInstance);
            bean.server.onError = (s, e) -> {
                e.setCancel(true);
                s.send(e.getClient(), new ErrorPacket(String.format("Rpc error: %s", e.getValue().getMessage())));
            };
            bean.server.onReceive = (s, e) -> {
                if (tryAs(e.getValue(), EventPack.class, p -> {
                    ServerBean.EventBean eventBean = bean.eventBeans.computeIfAbsent(p.eventName, x -> new ServerBean.EventBean());
                    switch (p.flag) {
                        case SUBSCRIBE:
                            EventTarget<?> eventTarget = (EventTarget<?>) contractInstance;
                            eventTarget.attachEvent(p.eventName, (sender, args) -> {
                                synchronized (eventBean) {
                                    if (eventBean.computedArgs == null) {
                                        RpcServerClient latestRndClient = NQuery.of(eventBean.subscribe).groupBy(x -> x.getHandshakePacket().getEventPriority(), (p1, p2) -> {
                                            int i = ThreadLocalRandom.current().nextInt(0, p2.count());
                                            return p2.skip(i).first();
                                        }).orderByDescending(x -> x.getHandshakePacket().getEventPriority()).firstOrDefault();
                                        if (latestRndClient == null) {
                                            log.warn("Event {} subscribe client is empty", p.eventName);
                                            return;
                                        }

                                        EventPack pack = new EventPack(p.eventName, EventFlag.COMPUTE_ARGS);
                                        pack.eventArgs = args;
                                        s.send(latestRndClient, pack);
                                        log.info("serverSide event {} -> client {} COMPUTE_ARGS", pack.eventName, latestRndClient.getId());
                                        eventBean.computingClient = latestRndClient;
                                        try {
                                            eventBean.wait(s.getConfig().getConnectTimeoutMillis());
                                        } catch (Exception ex) {
                                            log.error("serverSide event {} -> client {}", pack.eventName, latestRndClient.getId(), ex);
                                            eventBean.computedArgs = args;
                                        }
                                    }
                                    broadcast(s, p, eventBean);
                                }
                            }, false); //必须false
                            log.info("serverSide event {} -> client {} SUBSCRIBE", p.eventName, e.getClient().getId());
                            eventBean.subscribe.add(e.getClient());
                            break;
                        case UNSUBSCRIBE:
                            log.info("serverSide event {} -> client {} UNSUBSCRIBE", p.eventName, e.getClient().getId());
                            eventBean.subscribe.remove(e.getClient());
                            break;
                        case PUBLISH:
                            synchronized (eventBean) {
                                eventBean.computedArgs = p.eventArgs;
                                eventBean.computingClient = e.getClient();
                                log.info("serverSide event {} -> client {} PUBLISH", p.eventName, e.getClient().getId());
                                broadcast(s, p, eventBean);
                            }
                            break;
                        case COMPUTE_ARGS:
                            synchronized (eventBean) {
                                eventBean.computedArgs = p.eventArgs;
                                log.info("serverSide event {} -> client {} COMPUTE_ARGS", p.eventName, eventBean.computingClient.getId());
                                eventBean.notifyAll();
                            }
                            break;
                    }
                })) {
                    return;
                }

                MethodPack pack = (MethodPack) e.getValue();
                StringBuilder msg = new StringBuilder();
                msg.appendLine("Rpc server %s.%s", contractInstance.getClass().getSimpleName(), pack.methodName);
                msg.appendLine("Request:\t%s", toJsonString(pack.parameters));
                try {
                    pack.returnValue = Reflects.invokeMethod(contractInstance, pack.methodName, pack.parameters);
                    msg.appendLine("Response:\t%s", toJsonString(pack.returnValue));
                } catch (Exception ex) {
                    Throwable cause = ex.getCause();
                    log.error("Rpc", cause);
                    pack.errorMessage = String.format("ERROR: %s %s", cause.getClass().getSimpleName(), cause.getMessage());
                    msg.appendLine("Response:\t%s", pack.errorMessage);
                }
                log.debug(msg.toString());
                java.util.Arrays.fill(pack.parameters, null);
                s.send(e.getClient(), pack);
            };
            bean.server.start();
            return bean;
        });
    }

    private static void broadcast(RpcServer s, EventPack p, ServerBean.EventBean eventBean) {
        synchronized (eventBean) {
            try {
                EventPack pack = new EventPack(p.eventName, EventFlag.BROADCAST);
                pack.eventArgs = eventBean.computedArgs;
                for (RpcServerClient client : eventBean.subscribe) {
                    if (!s.isConnected(client)) {
                        eventBean.subscribe.remove(client);
                        continue;
                    }
                    if (client == eventBean.computingClient) {
                        continue;
                    }
                    s.send(client, pack);
                    log.info("serverSide event {} -> client {} BROADCAST", pack.eventName, client.getId());
                }
            } finally {
                eventBean.computingClient = null;
            }
        }
    }
}