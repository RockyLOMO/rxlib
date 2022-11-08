package org.rx.net.rpc;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.rx.bean.*;
import org.rx.exception.TraceHandler;
import org.rx.net.Sockets;
import org.rx.net.rpc.protocol.MetadataMessage;
import org.rx.net.transport.*;
import org.rx.net.rpc.protocol.EventFlag;
import org.rx.net.rpc.protocol.EventMessage;
import org.rx.net.rpc.protocol.MethodMessage;
import org.rx.core.*;
import org.rx.net.transport.protocol.ErrorPacket;
import org.rx.util.BeanMapper;
import org.rx.util.Snowflake;
import org.rx.util.function.TripleAction;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.*;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.util.internal.ThreadLocalRandom;

import java.util.concurrent.TimeoutException;

import static org.rx.bean.$.$;
import static org.rx.core.Sys.*;
import static org.rx.core.Extends.*;

//snappy + protobuf
@Slf4j
public final class Remoting {
    public static class ClientBean {
        final ResetEventWait syncRoot = new ResetEventWait();
        MethodMessage pack;
    }

    @RequiredArgsConstructor
    public static class ServerBean {
        @AllArgsConstructor
        @RequiredArgsConstructor
        static class EventContext {
            final EventArgs computedArgs;
            volatile TcpClient computingClient;
        }

        static class EventBean {
            final Set<TcpClient> subscribe = ConcurrentHashMap.newKeySet();
            final Map<Long, EventContext> contextMap = new ConcurrentHashMap<>();
        }

        final RpcServerConfig config;
        final TcpServer server;
        final Map<String, EventBean> eventBeans = new ConcurrentHashMap<>();
    }

    static final String HANDSHAKE_META_KEY = "HandshakeMeta";
    static final String M_0 = "raiseEvent", M_1 = "raiseEventAsync", M_2 = "attachEvent";
    static final Map<Object, ServerBean> serverBeans = new ConcurrentHashMap<>();
    static final Map<RpcClientConfig, TcpClientPool> clientPools = new ConcurrentHashMap<>();
    static final IdGenerator generator = new IdGenerator();
    static final Map<StatefulTcpClient, Map<Integer, ClientBean>> clientBeans = new ConcurrentHashMap<>();

    @SneakyThrows
    public static <T> T createFacade(@NonNull Class<T> contract, @NonNull RpcClientConfig<T> config) {
        FastThreadLocal<Boolean> isCompute = new FastThreadLocal<>();
        $<StatefulTcpClient> sync = $();
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
                case M_0:
                case M_1:
                    if (args.length == 2) {
                        if (!(args[0] instanceof String) || BooleanUtils.isTrue(isCompute.get())) {
                            return invokeSuper(m, p);
                        }
                        isCompute.remove();

                        setReturnValue(clientBean, invokeSuper(m, p));
                        EventMessage eventMessage = new EventMessage((String) args[0], EventFlag.PUBLISH);
                        eventMessage.eventArgs = (EventArgs) args[1];
                        pack = eventMessage;
                        log.info("clientSide event {} -> PUBLISH", eventMessage.eventName);
                    }
                    break;
                case M_2:
                    switch (args.length) {
                        case 2:
                            return invokeSuper(m, p);
                        case 3:
                            setReturnValue(clientBean, invokeSuper(m, p));
                            String eventName = (String) args[0];
                            pack = new EventMessage(eventName, EventFlag.SUBSCRIBE);
                            log.info("clientSide event {} -> SUBSCRIBE", eventName);
                            break;
                    }
                    break;
                case "detachEvent":
                    if (args.length == 2) {
                        setReturnValue(clientBean, invokeSuper(m, p));
                        String eventName = (String) args[0];
                        pack = new EventMessage(eventName, EventFlag.UNSUBSCRIBE);
                        log.info("clientSide event {} -> UNSUBSCRIBE", eventName);
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
                pack = clientBean.pack = new MethodMessage(generator.increment(), m.getName(), args, ThreadPool.traceId());
            }
            TcpClientPool pool = clientPools.computeIfAbsent(config, k -> {
                log.info("RpcClientPool {}", toJsonString(k));
                if (!config.isUsePool()) {
                    return new NonClientPool(config.getTcpConfig());
                }
                return new RpcClientPool(config);
            });

            if (sync.v == null) {
                synchronized (sync) {
                    if (sync.v == null) {
                        init(sync.v = pool.borrowClient(), p.getProxyObject(), isCompute);
                        TripleAction<T, StatefulTcpClient> initFn = (o, c) -> {
                            c.send(new MetadataMessage(config.getEventVersion()));
                            TripleAction<T, StatefulTcpClient> initHandler = config.getInitHandler();
                            if (initHandler != null) {
                                initHandler.invoke(o, c);
                            }
                        };
                        sync.v.onReconnected.combine((s, e) -> {
                            initFn.invoke((T) p.getProxyObject(), (StatefulTcpClient) s);
                            s.asyncScheduler().runAsync(() -> each(getClientBeans((StatefulTcpClient) s).values(), val -> {
                                if (val.syncRoot.getHoldCount() == 0) {
                                    return;
                                }
                                log.info("clientSide resent pack[{}] {}", val.pack.id, val.pack.methodName);
                                try {
                                    s.send(val.pack);
                                } catch (ClientDisconnectedException ex) {
                                    log.warn("clientSide resent pack[{}] fail", val.pack.id);
                                }
                            }));
                        });
                        initFn.invoke((T) p.getProxyObject(), sync.v);
                        //onHandshake returnObject的情况
                        if (sync.v == null) {
                            init(sync.v = pool.borrowClient(), p.getProxyObject(), isCompute);
                        }
                    }
                }
            }
            StatefulTcpClient client = sync.v;
            Map<Integer, ClientBean> waitBeans = null;

            MethodMessage methodMessage = as(pack, MethodMessage.class);
            ProceedEventArgs eventArgs = methodMessage != null ? new ProceedEventArgs(contract, methodMessage.parameters, false) : null;
            try {
                client.send(pack);
                if (eventArgs != null) {
                    waitBeans = getClientBeans(client);
                    waitBeans.put(clientBean.pack.id, clientBean);
                    try {
                        clientBean.syncRoot.waitOne(client.getConfig().getConnectTimeoutMillis());
                        clientBean.syncRoot.reset();
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
                if (!client.getConfig().isEnableReconnect()) {
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
                    clientBean.syncRoot.waitOne(client.getConfig().getConnectTimeoutMillis());
                    clientBean.syncRoot.reset();
                } catch (TimeoutException ie) {
                    if (clientBean.pack.returnValue == null) {
                        eventArgs.setError(e);
                        throw e;
                    }
                }
            } catch (Throwable e) {
                if (eventArgs != null) {
                    eventArgs.setError(e);
                }
                throw e;
            } finally {
                if (eventArgs != null) {
                    log(eventArgs, msg -> {
                        msg.appendLine("Client invoke %s.%s [%s -> %s]", contract.getSimpleName(), methodMessage.methodName,
                                Sockets.toString(client.getLocalEndpoint()),
                                Sockets.toString(client.getConfig().getServerEndpoint()));
                        msg.appendLine("Request:\t%s", toJsonString(methodMessage.parameters))
                                .appendLine("Response:\t%s", clientBean.pack == null ? "NULL" : toJsonString(clientBean.pack.returnValue));
                        if (eventArgs.getError() != null) {
                            msg.appendLine("Error:\t%s", eventArgs.getError().getMessage());
                        }
                    });
                }
                if (waitBeans != null) {
                    waitBeans.remove(clientBean.pack.id);
                    if (waitBeans.isEmpty()) {
                        synchronized (sync) {
                            sync.v = pool.returnClient(client);
                        }
                    }
                }
            }
            return clientBean.pack != null ? clientBean.pack.returnValue : null;
        });
    }

    private static void init(StatefulTcpClient client, Object proxyObject, FastThreadLocal<Boolean> isCompute) {
        client.onError.combine((s, e) -> e.setCancel(true));
        client.onReceive.combine((s, e) -> {
            if (tryAs(e.getValue(), EventMessage.class, x -> {
                switch (x.flag) {
                    case BROADCAST:
                    case COMPUTE_ARGS:
                        try {
                            isCompute.set(true);
                            EventTarget<?> target = (EventTarget<?>) proxyObject;
                            target.raiseEvent(x.eventName, x.eventArgs);
                            log.info("clientSide event {} -> {} OK & args={}", x.eventName, x.flag, toJsonString(x.eventArgs));
                        } catch (Exception ex) {
                            TraceHandler.INSTANCE.log("clientSide event {} -> {}", x.eventName, x.flag, ex);
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

            MethodMessage svrPack = (MethodMessage) e.getValue();
//            log.debug("recv: {}", svrPack.returnValue);
            ClientBean clientBean = getClientBeans(client).get(svrPack.id);
            if (clientBean == null) {
                log.warn("clientSide callback pack[{}] fail", svrPack.id);
                return;
            }
            clientBean.pack = svrPack;
            clientBean.syncRoot.set();
        });
    }

    private static Map<Integer, ClientBean> getClientBeans(StatefulTcpClient client) {
        return clientBeans.computeIfAbsent(client, k -> new ConcurrentHashMap<>());
    }

    private static void setReturnValue(ClientBean clientBean, Object value) {
        if (clientBean.pack == null) {
            clientBean.pack = new MethodMessage(generator.increment(), null, null, ThreadPool.traceId());
        }
        clientBean.pack.returnValue = value;
    }

    @SneakyThrows
    private static Object invokeSuper(Method m, DynamicProxy p) {
        if (m.isDefault()) {
            return Reflects.invokeDefaultMethod(m, p.getProxyObject(), p.arguments);
        }
        return p.fastInvokeSuper();
    }

    public static TcpServer register(Object contractInstance, int listenPort, boolean enableEventCompute) {
        RpcServerConfig conf = new RpcServerConfig(new TcpServerConfig(listenPort));
        if (enableEventCompute) {
            conf.setEventComputeVersion(RpcServerConfig.EVENT_LATEST_COMPUTE);
        }
        return register(contractInstance, conf);
    }

    public static TcpServer register(@NonNull Object contractInstance, @NonNull RpcServerConfig config) {
        return serverBeans.computeIfAbsent(contractInstance, k -> {
            ServerBean bean = new ServerBean(config, new TcpServer(config.getTcpConfig()));
            bean.server.onClosed.combine((s, e) -> serverBeans.remove(contractInstance));
            bean.server.onError.combine((s, e) -> {
                e.setCancel(true);
                e.getClient().send(new ErrorPacket(String.format("server error: %s", e.getValue().toString())));
            });
            bean.server.onReceive.combine((s, e) -> {
                if (tryAs(e.getValue(), EventMessage.class, p -> {
                    ServerBean.EventBean eventBean = bean.eventBeans.computeIfAbsent(p.eventName, x -> new ServerBean.EventBean());
                    switch (p.flag) {
                        case SUBSCRIBE:
                            EventTarget<?> eventTarget = (EventTarget<?>) contractInstance;
                            eventTarget.attachEvent(p.eventName, (sender, args) -> {
                                synchronized (eventBean) {
                                    ServerBean.EventContext eCtx = new ServerBean.EventContext(args);
                                    if (config.getEventComputeVersion() == RpcServerConfig.EVENT_DISABLE_COMPUTE) {
                                        eCtx.computingClient = null;
                                    } else {
                                        TcpClient computingClient;
                                        Linq<TcpClient> subscribes = Linq.from(eventBean.subscribe).where(x -> x.attr(HANDSHAKE_META_KEY) != null);
                                        if (config.getEventComputeVersion() == RpcServerConfig.EVENT_LATEST_COMPUTE) {
                                            computingClient = subscribes.groupBy(x -> x.<MetadataMessage>attr(HANDSHAKE_META_KEY).getEventVersion(), (p1, p2) -> {
                                                int i = ThreadLocalRandom.current().nextInt(0, p2.count());
                                                return p2.skip(i).first();
                                            }).orderByDescending(x -> x.<MetadataMessage>attr(HANDSHAKE_META_KEY).getEventVersion()).firstOrDefault();
                                        } else {
                                            computingClient = subscribes.where(x -> x.<MetadataMessage>attr(HANDSHAKE_META_KEY).getEventVersion() == config.getEventComputeVersion())
                                                    .orderByRand().firstOrDefault();
                                        }
                                        if (computingClient == null) {
                                            log.warn("serverSide event {} subscribe empty", p.eventName);
                                        } else {
                                            eCtx.computingClient = computingClient;
                                            EventMessage pack = new EventMessage(p.eventName, EventFlag.COMPUTE_ARGS);
                                            pack.computeId = Snowflake.DEFAULT.nextId();
                                            pack.eventArgs = args;
                                            eventBean.contextMap.put(pack.computeId, eCtx);
                                            try {
                                                computingClient.send(pack);
                                                log.info("serverSide event {} {} -> COMPUTE_ARGS WAIT {}", pack.eventName, computingClient.getRemoteEndpoint(), s.getConfig().getConnectTimeoutMillis());
                                                eventBean.wait(s.getConfig().getConnectTimeoutMillis());
                                            } catch (Exception ex) {
                                                TraceHandler.INSTANCE.log("serverSide event {} {} -> COMPUTE_ARGS ERROR", pack.eventName, computingClient.getRemoteEndpoint(), ex);
                                            } finally {
                                                //delay purge
                                                Tasks.setTimeout(() -> eventBean.contextMap.remove(pack.computeId), s.getConfig().getConnectTimeoutMillis() * 2L);
                                            }
                                        }
                                    }
                                    broadcast(bean, p, eventBean, eCtx);
                                }
                            }, false); //必须false
                            log.info("serverSide event {} {} -> SUBSCRIBE", p.eventName, e.getClient().getRemoteEndpoint());
                            eventBean.subscribe.add(e.getClient());
                            break;
                        case UNSUBSCRIBE:
                            log.info("serverSide event {} {} -> UNSUBSCRIBE", p.eventName, e.getClient().getRemoteEndpoint());
                            eventBean.subscribe.remove(e.getClient());
                            break;
                        case PUBLISH:
                            synchronized (eventBean) {
                                log.info("serverSide event {} {} -> PUBLISH", p.eventName, e.getClient().getRemoteEndpoint());
                                broadcast(bean, p, eventBean, new ServerBean.EventContext(p.eventArgs, e.getClient()));
                            }
                            break;
                        case COMPUTE_ARGS:
                            synchronized (eventBean) {
                                ServerBean.EventContext ctx = eventBean.contextMap.get(p.computeId);
                                if (ctx == null) {
                                    log.warn("serverSide event {} [{}] -> COMPUTE_ARGS FAIL", p.eventName, p.computeId);
                                } else {
                                    //赋值原引用对象
                                    BeanMapper.DEFAULT.map(p.eventArgs, ctx.computedArgs);
                                    log.info("serverSide event {} {} -> COMPUTE_ARGS OK & args={}", p.eventName, ctx.computingClient.getRemoteEndpoint(), toJsonString(ctx.computedArgs));
                                }
                                eventBean.notifyAll();
                            }
                            break;
                    }
                })) {
                    return;
                }
                if (tryAs(e.getValue(), MetadataMessage.class, p -> e.getClient().attr(HANDSHAKE_META_KEY, p))) {
                    log.debug("Handshake: {}", toJsonString(e.getValue()));
                    return;
                }

                MethodMessage pack = (MethodMessage) e.getValue();
                ProceedEventArgs args = new ProceedEventArgs(contractInstance.getClass(), pack.parameters, false);
                try {
                    pack.returnValue = RemotingContext.invoke(() -> args.proceed(() -> {
                        String tn = RxConfig.INSTANCE.getThreadPool().getTraceName();
                        if (tn != null) {
                            ThreadPool.startTrace(pack.traceId);
                        }
                        try {
                            return Reflects.invokeMethod(contractInstance, pack.methodName, pack.parameters);
                        } finally {
                            ThreadPool.endTrace();
                        }
                    }), s, e.getClient());
                } catch (Throwable ex) {
                    Throwable cause = ifNull(ex.getCause(), ex);
                    args.setError(ex);
                    pack.errorMessage = String.format("%s %s", cause.getClass().getSimpleName(), cause.getMessage());
                } finally {
                    log(args, msg -> {
                        msg.appendLine("Server invoke %s.%s [%s]-> %s", contractInstance.getClass().getSimpleName(), pack.methodName,
                                s.getConfig().getListenPort(), Sockets.toString(e.getClient().getRemoteEndpoint()));
                        msg.appendLine("Request:\t%s", toJsonString(args.getParameters()))
                                .appendLine("Response:\t%s", toJsonString(args.getReturnValue()));
                        if (args.getError() != null) {
                            msg.appendLine("Error:\t%s", pack.errorMessage);
                        }
                    });
                }
                Arrays.fill(pack.parameters, null);
                e.getClient().send(pack);
            });
            bean.server.start();
            return bean;
        }).server;
    }

    private static void broadcast(ServerBean s, EventMessage p, ServerBean.EventBean eventBean, ServerBean.EventContext context) {
        List<Integer> allow = s.config.getEventBroadcastVersions();
        EventMessage pack = new EventMessage(p.eventName, EventFlag.BROADCAST);
        pack.eventArgs = context.computedArgs;
        tryAs(pack.eventArgs, RemotingEventArgs.class, x -> x.setBroadcastVersions(allow));
        for (TcpClient client : eventBean.subscribe) {
            if (!client.isConnected()) {
                eventBean.subscribe.remove(client);
                continue;
            }
            MetadataMessage meta;
            if (client == context.computingClient || (meta = client.attr(HANDSHAKE_META_KEY)) == null
                    || (!allow.isEmpty() && !allow.contains(meta.getEventVersion()))) {
                continue;
            }

            client.send(pack);
            log.info("serverSide event {} {} -> BROADCAST", pack.eventName, client.getRemoteEndpoint());
        }
    }
}
