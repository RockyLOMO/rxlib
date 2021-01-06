package org.rx.net.rpc;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.rx.bean.$;
import org.rx.bean.InterceptProxy;
import org.rx.bean.Tuple;
import org.rx.core.StringBuilder;
import org.rx.net.rpc.impl.StatefulRpcClient;
import org.rx.net.rpc.protocol.EventFlag;
import org.rx.net.rpc.protocol.EventPack;
import org.rx.net.rpc.protocol.MethodPack;
import org.rx.core.*;
import org.rx.net.rpc.packet.ErrorPacket;
import org.rx.util.BeanMapper;
import org.rx.util.function.BiAction;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static org.rx.bean.$.$;
import static org.rx.core.Contract.*;

@Slf4j
public final class Remoting {
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
                require(context);
                return context;
            }
        }

        @Getter
        private final RpcServer server;
        private final Map<String, EventBean> eventBeans = new ConcurrentHashMap<>();
    }

    private static final Map<Object, ServerBean> serverBeans = new ConcurrentHashMap<>();
    private static final Map<RpcClientConfig, RpcClientPool> clientPools = new ConcurrentHashMap<>();

    public static <T> T create(Class<T> contract, RpcClientConfig facadeConfig) {
        return create(contract, facadeConfig, null, null);
    }

    public static <T> T create(Class<T> contract, RpcClientConfig facadeConfig, BiAction<T> onInit) {
        return create(contract, facadeConfig, onInit, null);
    }

    @SneakyThrows
    public static <T> T create(Class<T> contract, RpcClientConfig facadeConfig, BiAction<T> onInit, BiAction<StatefulRpcClient> onInitClient) {
        require(contract, facadeConfig);

        Tuple<ManualResetEvent, MethodPack> resultPack = Tuple.of(new ManualResetEvent(), null);
        FastThreadLocal<Boolean> isCompute = new FastThreadLocal<>();
        $<StatefulRpcClient> sync = $();
        //onInit由调用方触发可能spring还没起来的情况
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
                case "attachEvent":
                    switch (args.length) {
                        case 2:
                            return invokeSuper(m, p);
                        case 3:
                            setReturnValue(resultPack, invokeSuper(m, p));
                            String eventName = (String) args[0];
                            pack = new EventPack(eventName, EventFlag.SUBSCRIBE);
                            log.info("clientSide event {} -> SUBSCRIBE", eventName);
                            break;
                    }
                    break;
                case "detachEvent":
                    if (args.length == 2) {
                        setReturnValue(resultPack, invokeSuper(m, p));
                        String eventName = (String) args[0];
                        pack = new EventPack(eventName, EventFlag.UNSUBSCRIBE);
                        log.info("clientSide event {} -> UNSUBSCRIBE", eventName);
                    }
                    break;
                case "raiseEvent":
                    if (args.length == 2) {
                        if (BooleanUtils.isTrue(isCompute.get())) {
                            return invokeSuper(m, p);
                        }
                        isCompute.remove();

                        setReturnValue(resultPack, invokeSuper(m, p));
                        EventPack eventPack = new EventPack((String) args[0], EventFlag.PUBLISH);
                        eventPack.eventArgs = (EventArgs) args[1];
                        pack = eventPack;
                        log.info("clientSide event {} -> PUBLISH", eventPack.eventName);
                    }
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
                RpcClientPool pool = clientPools.computeIfAbsent(facadeConfig, k -> {
                    log.info("RpcClientPool {}", toJsonString(k));
                    return RpcClientPool.createPool(k);
                });
                if (sync.v == null) {
                    init(sync.v = pool.borrowClient(), resultPack, p.getProxyObject(), isCompute);
                    if (onInit != null || onInitClient != null) {
                        sync.v.onReconnected = (s, e) -> {
                            if (onInit != null) {
                                onInit.toConsumer().accept((T) p.getProxyObject());
                            }
                            if (onInitClient != null) {
                                onInitClient.toConsumer().accept((StatefulRpcClient) s);
                            }
                        };
                        sync.v.raiseEvent(sync.v.onReconnected, new NEventArgs<>(facadeConfig.getServerEndpoint()));
                        //onHandshake returnObject的情况
                        if (sync.v == null) {
                            init(sync.v = pool.borrowClient(), resultPack, p.getProxyObject(), isCompute);
                        }
                    }
                }
                StatefulRpcClient client = sync.v;

                StringBuilder msg = new StringBuilder();
                MethodPack methodPack = as(pack, MethodPack.class);
                boolean debug = methodPack != null;
                if (debug) {
                    msg.appendLine("Rpc client %s.%s", contract.getSimpleName(), methodPack.methodName);
                    msg.appendLine("Request:\t%s", toJsonString(methodPack.parameters));
                }
                try {
                    client.send(pack);
                    if (debug) {
                        resultPack.left.waitOne(client.getConfig().getConnectTimeoutMillis());
                        resultPack.left.reset();
                    }
                    if (resultPack.right == null) {
                        if (debug) {
                            msg.appendLine("Response:\tNULL");
                        }
                    } else {
                        if (resultPack.right.errorMessage != null) {
                            throw new RemotingException(resultPack.right.errorMessage);
                        }
                        if (debug) {
                            msg.appendLine("Response:\t%s", toJsonString(resultPack.right.returnValue));
                        }
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
                    sync.v = pool.returnClient(client);
                }
            }
            return resultPack.right != null ? resultPack.right.returnValue : null;
        });
    }

    private static void init(StatefulRpcClient client, Tuple<ManualResetEvent, MethodPack> resultPack, Object proxyObject, FastThreadLocal<Boolean> isCompute) {
        client.onError = (s, e) -> {
            e.setCancel(true);
            resultPack.left.set();
        };
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

            resultPack.right = (MethodPack) e.getValue();
            resultPack.left.set();
        };
    }

    private static void setReturnValue(Tuple<ManualResetEvent, MethodPack> resultPack, Object value) {
        if (resultPack.right == null) {
            resultPack.right = new MethodPack(null, null);
        }
        resultPack.right.returnValue = value;
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
                                                log.info("serverSide event {} {} -> COMPUTE_ARGS", pack.eventName, computingClient.getId());
                                                eventBean.wait(s.getConfig().getConnectTimeoutMillis());
//                                                eventBean.wait.waitOne(s.getConfig().getConnectTimeoutMillis());
//                                                eventBean.wait.reset();
                                            } catch (Exception ex) {
                                                log.error("serverSide event {} {} -> COMPUTE_ARGS ERROR", pack.eventName, computingClient.getId(), ex);
                                            } finally {
                                                eventBean.contextMap.remove(pack.computeId);
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
