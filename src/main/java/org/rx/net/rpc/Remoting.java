package org.rx.net.rpc;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.rx.bean.$;
import org.rx.bean.IncrementGenerator;
import org.rx.bean.InterceptProxy;
import org.rx.bean.ProceedEventArgs;
import org.rx.net.Sockets;
import org.rx.net.rpc.impl.StatefulRpcClient;
import org.rx.net.rpc.protocol.EventFlag;
import org.rx.net.rpc.protocol.EventPack;
import org.rx.net.rpc.protocol.MethodPack;
import org.rx.core.*;
import org.rx.net.rpc.packet.ErrorPacket;
import org.rx.util.BeanMapper;
import org.rx.util.function.BiAction;
import org.rx.util.function.TripleAction;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;

import static org.rx.bean.$.$;
import static org.rx.core.App.*;

//snappy + protobuf
@Slf4j
public final class Remoting {
    public static class ClientBean {
        final ManualResetEvent waiter = new ManualResetEvent();
        MethodPack pack;
    }

    @RequiredArgsConstructor
    public static class ServerBean {
        @NoArgsConstructor
        @AllArgsConstructor
        static class EventContext {
            volatile EventArgs computedArgs;
            volatile RpcServerClient computingClient;
        }

        static class EventBean {
            Set<RpcServerClient> subscribe = ConcurrentHashMap.newKeySet();
            //            ManualResetEvent wait = new ManualResetEvent();
            Map<UUID, EventContext> contextMap = new ConcurrentHashMap<>();

            EventContext context(UUID id) {
                EventContext context = contextMap.get(id);
                Objects.requireNonNull(context);
                return context;
            }
        }

        @Getter
        private final RpcServer server;
        private final Map<String, EventBean> eventBeans = new ConcurrentHashMap<>();
    }

    private static final Map<Object, ServerBean> serverBeans = new ConcurrentHashMap<>();
    private static final Map<RpcClientConfig, RpcClientPool> clientPools = new ConcurrentHashMap<>();
    private static final IncrementGenerator idGenerator = new IncrementGenerator();
    private static final Map<StatefulRpcClient, Map<Integer, ClientBean>> clientBeans = new ConcurrentHashMap<>();

    public static <T> T create(Class<T> contract, RpcClientConfig facadeConfig) {
        return create(contract, facadeConfig, null);
    }

    @SneakyThrows
    public static <T> T create(@NonNull Class<T> contract, @NonNull RpcClientConfig facadeConfig, TripleAction<T, StatefulRpcClient> onInit) {
        FastThreadLocal<Boolean> isCompute = new FastThreadLocal<>();
        $<StatefulRpcClient> sync = $();
        //onInit由调用方触发可能spring还没起来的情况
        return proxy(contract, (m, p) -> {
            if (Reflects.OBJECT_METHODS.contains(m)) {
                return p.fastInvokeSuper();
            }
            if (Reflects.isCloseMethod(m)) {
                synchronized (sync) {
                    if (sync.v != null) {
                        sync.v.close();
                        sync.v = null;
                    }
                }
                return null;
            }

            Serializable pack = null;
            Object[] args = p.arguments;
            ClientBean clientBean = new ClientBean();
            switch (m.getName()) {
                case "attachEvent":
                    switch (args.length) {
                        case 2:
                            return invokeSuper(m, p);
                        case 3:
                            setReturnValue(clientBean, invokeSuper(m, p));
                            String eventName = (String) args[0];
                            pack = new EventPack(eventName, EventFlag.SUBSCRIBE);
                            log.info("clientSide event {} -> SUBSCRIBE", eventName);
                            break;
                    }
                    break;
                case "detachEvent":
                    if (args.length == 2) {
                        setReturnValue(clientBean, invokeSuper(m, p));
                        String eventName = (String) args[0];
                        pack = new EventPack(eventName, EventFlag.UNSUBSCRIBE);
                        log.info("clientSide event {} -> UNSUBSCRIBE", eventName);
                    }
                    break;
                case "raiseEvent":
                case "raiseEventAsync":
                    if (args.length == 2) {
                        if (BooleanUtils.isTrue(isCompute.get())) {
                            return invokeSuper(m, p);
                        }
                        isCompute.remove();

                        setReturnValue(clientBean, invokeSuper(m, p));
                        EventPack eventPack = new EventPack((String) args[0], EventFlag.PUBLISH);
                        eventPack.eventArgs = (EventArgs) args[1];
                        pack = eventPack;
                        log.info("clientSide event {} -> PUBLISH", eventPack.eventName);
                    }
                    break;
                case "eventFlags":
                case "asyncScheduler":
                    if (args.length == 0) {
                        return invokeSuper(m, p);
                    }
                    break;
            }

            if (pack == null) {
                pack = clientBean.pack = new MethodPack(idGenerator.next(), m.getName(), args);
            }
            RpcClientPool pool = clientPools.computeIfAbsent(facadeConfig, k -> {
                log.info("RpcClientPool {}", toJsonString(k));
                return RpcClientPool.createPool(k);
            });

            synchronized (sync) {
                if (sync.v == null) {
                    init(sync.v = pool.borrowClient(), p.getProxyObject(), isCompute);
                    sync.v.onReconnected = (s, e) -> {
                        if (onInit != null) {
                            onInit.toConsumer().accept((T) p.getProxyObject(), (StatefulRpcClient) s);
                        }
                        s.asyncScheduler().run(() -> {
                            for (ClientBean value : getClientBeans((StatefulRpcClient) s).values()) {
                                if (value.waiter.getHoldCount() == 0) {
                                    continue;
                                }
                                log.info("clientSide resent pack[{}] {}", value.pack.id, value.pack.methodName);
                                try {
                                    s.send(value.pack);
                                } catch (ClientDisconnectedException ex) {
                                    log.warn("clientSide resent pack[{}] fail", value.pack.id);
                                }
                            }
                        });
                    };
                    if (onInit != null) {
                        sync.v.raiseEvent(sync.v.onReconnected, new NEventArgs<>(facadeConfig.getServerEndpoint()));
                        //onHandshake returnObject的情况
                        if (sync.v == null) {
                            init(sync.v = pool.borrowClient(), p.getProxyObject(), isCompute);
                        }
                    }
                }
            }
            StatefulRpcClient client = sync.v;
            Map<Integer, ClientBean> waitBeans = null;

            MethodPack methodPack = as(pack, MethodPack.class);
            ProceedEventArgs eventArgs = methodPack != null ? new ProceedEventArgs(contract, methodPack.parameters, false) : null;
            try {
                client.send(pack);
                if (eventArgs != null) {
                    waitBeans = getClientBeans(client);
                    waitBeans.put(clientBean.pack.id, clientBean);
                    try {
                        clientBean.waiter.waitOne(client.getConfig().getConnectTimeoutMillis());
                        clientBean.waiter.reset();
                    } catch (TimeoutException e) {
                        if (!client.isConnected()) {
                            throw new ClientDisconnectedException(e);
                        }
                        if (clientBean.pack.returnValue == null) {
                            throw e;
                        }
                    }
                }
                if (clientBean.pack.errorMessage != null) {
                    throw new RemotingException(clientBean.pack.errorMessage);
                }
            } catch (ClientDisconnectedException e) {
                if (!client.isAutoReconnect()) {
                    pool.returnClient(client);
                    sync.v = null;
                    throw e;
                }

                if (eventArgs == null) {
                    throw e;
                }
                waitBeans = getClientBeans(client);
                waitBeans.put(clientBean.pack.id, clientBean);
                try {
                    clientBean.waiter.waitOne(client.getConfig().getConnectTimeoutMillis());
                    clientBean.waiter.reset();
                } catch (TimeoutException ie) {
                    if (clientBean.pack.returnValue == null) {
                        eventArgs.setError(e);
                        throw e;
                    }
                }
            } catch (Exception e) {
                if (eventArgs != null) {
                    eventArgs.setError(e);
                }
                throw e;
            } finally {
                if (eventArgs != null) {
                    App.log(eventArgs, msg -> {
                        msg.appendLine("Rpc client %s.%s @ %s", contract.getSimpleName(), methodPack.methodName, client.getLocalAddress() == null ? "NULL" : Sockets.toString(client.getLocalAddress()));
                        msg.appendLine("Request:\t%s", toJsonString(methodPack.parameters));
                        if (eventArgs.getError() != null) {
                            msg.appendLine("Response:\t%s", eventArgs.getError().getMessage());
                        } else if (clientBean.pack == null) {
                            msg.appendLine("Response:\tNULL");
                        } else {
                            msg.appendLine("Response:\t%s", toJsonString(clientBean.pack.returnValue));
                        }
                    });
                }
                synchronized (sync) {
                    if (waitBeans != null) {
                        waitBeans.remove(clientBean.pack.id);
                        if (waitBeans.isEmpty()) {
                            sync.v = pool.returnClient(client);
                        }
                    }
                }
            }
            return clientBean.pack != null ? clientBean.pack.returnValue : null;
        });
    }

    private static void init(StatefulRpcClient client, Object proxyObject, FastThreadLocal<Boolean> isCompute) {
        client.onError = (s, e) -> e.setCancel(true);
        client.onReceive = (s, e) -> {
            if (tryAs(e.getValue(), EventPack.class, x -> {
                switch (x.flag) {
                    case BROADCAST:
                    case COMPUTE_ARGS:
                        try {
                            isCompute.set(true);
                            EventTarget<?> target = (EventTarget<?>) proxyObject;
                            target.raiseEvent(x.eventName, x.eventArgs);
                            log.info("clientSide event {} -> {} OK & args={}", x.eventName, x.flag, toJsonString(x.eventArgs));
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

            MethodPack svrPack = (MethodPack) e.getValue();
//            log.debug("recv: {}", svrPack.returnValue);
            ClientBean clientBean = getClientBeans(client).get(svrPack.id);
            if (clientBean == null) {
                log.warn("clientSide callback pack[{}] fail", svrPack.id);
                return;
            }
            clientBean.pack = svrPack;
            clientBean.waiter.set();
        };
    }

    private static Map<Integer, ClientBean> getClientBeans(StatefulRpcClient client) {
        return clientBeans.computeIfAbsent(client, k -> new ConcurrentHashMap<>());
    }

    private static void setReturnValue(ClientBean clientBean, Object value) {
        if (clientBean.pack == null) {
            clientBean.pack = new MethodPack(idGenerator.next(), null, null);
        }
        clientBean.pack.returnValue = value;
    }

    @SneakyThrows
    private static Object invokeSuper(Method m, InterceptProxy p) {
        if (m.isDefault()) {
            return Reflects.invokeDefaultMethod(m, p.getProxyObject(), p.arguments);
        }
        return p.fastInvokeSuper();
    }

    public static ServerBean listen(Object contractInstance, int listenPort) {
        return listen(contractInstance, new RpcServerConfig(listenPort));
    }

    public static ServerBean listen(@NonNull Object contractInstance, @NonNull RpcServerConfig config) {
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
                                    ServerBean.EventContext context = new ServerBean.EventContext();
                                    context.computedArgs = args;
                                    if (config.getEventComputeVersion() == RpcServerConfig.DISABLE_VERSION) {
                                        context.computingClient = null;
                                    } else {
                                        RpcServerClient computingClient = null;
                                        if (config.getEventComputeVersion() == RpcServerConfig.LATEST_COMPUTE) {
                                            computingClient = NQuery.of(eventBean.subscribe).groupBy(x -> x.getHandshakePacket().getEventVersion(), (p1, p2) -> {
                                                int i = ThreadLocalRandom.current().nextInt(0, p2.count());
                                                return p2.skip(i).first();
                                            }).orderByDescending(x -> x.getHandshakePacket().getEventVersion()).firstOrDefault();
                                        } else {
                                            List<RpcServerClient> list = NQuery.of(eventBean.subscribe).where(x -> x.getHandshakePacket().getEventVersion() == config.getEventComputeVersion()).toList();
                                            if (!list.isEmpty()) {
                                                computingClient = list.get(ThreadLocalRandom.current().nextInt(0, list.size()));
                                            }
                                        }
                                        if (computingClient == null) {
                                            log.warn("serverSide event {} subscribe empty", p.eventName);
                                        } else {
                                            context.computingClient = computingClient;
                                            EventPack pack = new EventPack(p.eventName, EventFlag.COMPUTE_ARGS);
                                            pack.computeId = UUID.randomUUID();
                                            pack.eventArgs = args;
                                            eventBean.contextMap.put(pack.computeId, context);
                                            try {
                                                s.send(computingClient, pack);
                                                log.info("serverSide event {} {} -> COMPUTE_ARGS WAIT {}", pack.eventName, computingClient.getId(), s.getConfig().getConnectTimeoutMillis());
                                                eventBean.wait(s.getConfig().getConnectTimeoutMillis());
//                                                eventBean.wait.waitOne(s.getConfig().getConnectTimeoutMillis());
//                                                eventBean.wait.reset();
                                            } catch (Exception ex) {
                                                log.error("serverSide event {} {} -> COMPUTE_ARGS ERROR", pack.eventName, computingClient.getId(), ex);
                                            } finally {
                                                //delay purge
                                                Tasks.scheduleOnce(() -> eventBean.contextMap.remove(pack.computeId), s.getConfig().getConnectTimeoutMillis() * 2L);
                                            }
                                        }
                                    }
                                    broadcast(s, p, eventBean, context);
                                }
                            }, false); //必须false
                            log.info("serverSide event {} {} -> SUBSCRIBE", p.eventName, e.getClient().getId());
                            eventBean.subscribe.add(e.getClient());
                            break;
                        case UNSUBSCRIBE:
                            log.info("serverSide event {} {} -> UNSUBSCRIBE", p.eventName, e.getClient().getId());
                            eventBean.subscribe.remove(e.getClient());
                            break;
                        case PUBLISH:
                            synchronized (eventBean) {
                                log.info("serverSide event {} {} -> PUBLISH", p.eventName, e.getClient().getId());
                                broadcast(s, p, eventBean, new ServerBean.EventContext(p.eventArgs, e.getClient()));
                            }
                            break;
                        case COMPUTE_ARGS:
                            synchronized (eventBean) {
                                ServerBean.EventContext context = eventBean.context(p.computeId);
                                //赋值原引用对象
                                BeanMapper.getInstance().map(p.eventArgs, context.computedArgs);
                                log.info("serverSide event {} {} -> COMPUTE_ARGS OK & args={}", p.eventName, context.computingClient.getId(), toJsonString(context.computedArgs));
                                eventBean.notifyAll();
//                            eventBean.wait.set();
                            }
                            break;
                    }
                })) {
                    return;
                }

                MethodPack pack = (MethodPack) e.getValue();
                ProceedEventArgs args = new ProceedEventArgs(contractInstance.getClass(), pack.parameters, false);
                try {
                    pack.returnValue = quietly(() -> args.proceed(() -> Reflects.invokeMethod(contractInstance, pack.methodName, pack.parameters)));
                } catch (Exception ex) {
                    Throwable cause = ex.getCause();
                    args.setError(ex);
                    pack.errorMessage = String.format("ERROR: %s %s", cause.getClass().getSimpleName(), cause.getMessage());
                } finally {
                    App.log(args, msg -> {
                        msg.appendLine("Rpc server %s.%s -> %s", contractInstance.getClass().getSimpleName(), pack.methodName, Sockets.toString(e.getClient().getRemoteAddress()));
                        msg.appendLine("Request:\t%s", toJsonString(args.getParameters()));
                        if (args.getError() != null) {
                            msg.appendLine("Response:\t%s", pack.errorMessage);
                        } else {
                            msg.appendLine("Response:\t%s", toJsonString(args.getReturnValue()));
                        }
                    });
                }
                java.util.Arrays.fill(pack.parameters, null);
                s.send(e.getClient(), pack);
            };
            bean.server.start();
            return bean;
        });
    }

    private static void broadcast(RpcServer s, EventPack p, ServerBean.EventBean eventBean, ServerBean.EventContext context) {
        List<Integer> allow = s.getConfig().getEventBroadcastVersions();
        EventPack pack = new EventPack(p.eventName, EventFlag.BROADCAST);
        pack.eventArgs = context.computedArgs;
        tryAs(pack.eventArgs, RemotingEventArgs.class, x -> x.setBroadcastVersions(allow));
        for (RpcServerClient client : eventBean.subscribe) {
            if (!s.isConnected(client)) {
                eventBean.subscribe.remove(client);
                continue;
            }
            if (client == context.computingClient
                    || (!allow.isEmpty() && !allow.contains(client.getHandshakePacket().getEventVersion()))) {
                continue;
            }

            s.send(client, pack);
            log.info("serverSide event {} {} -> BROADCAST", pack.eventName, client.getId());
        }
    }
}
