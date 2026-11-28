package org.rx.net.rpc;

import io.netty.buffer.ByteBuf;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.ThreadLocalRandom;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.rx.bean.$;
import org.rx.bean.DynamicProxyBean;
import org.rx.core.EventArgs;
import org.rx.core.EventPublisher;
import org.rx.core.Linq;
import org.rx.core.Reflects;
import org.rx.core.ResetEventWait;
import org.rx.core.RxConfig;
import org.rx.core.Sys;
import org.rx.core.Tasks;
import org.rx.core.ThreadPool;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.rpc.protocol.EventFlag;
import org.rx.net.rpc.protocol.EventMessage;
import org.rx.net.rpc.protocol.MetadataMessage;
import org.rx.net.rpc.protocol.MethodMessage;
import org.rx.net.transport.ClientDisconnectedException;
import org.rx.net.transport.TcpServerConfig;
import org.rx.net.transport.core.TransportClient;
import org.rx.net.transport.core.TransportServer;
import org.rx.net.transport.core.TransportSession;
import org.rx.util.BeanMapper;
import org.rx.util.IdGenerator;
import org.rx.util.Snowflake;
import org.rx.util.function.TripleAction;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.rx.bean.$.$;
import static org.rx.core.Extends.as;
import static org.rx.core.Extends.ifNull;
import static org.rx.core.Extends.tryAs;
import static org.rx.core.Extends.tryClose;
import static org.rx.core.Sys.proxy;
import static org.rx.core.Sys.toJsonString;

@Slf4j
public final class Remoting {
    public static class ClientBean {
        final ResetEventWait syncRoot = new ResetEventWait();
        MethodMessage pack;
        volatile boolean idempotent;
        volatile boolean sent;
        volatile Throwable error;
    }

    static final class ClientRef {
        final RpcClientConfig<?> config;
        final RemotingCodec codec;
        final FastThreadLocal<Boolean> isCompute = new FastThreadLocal<Boolean>();
        final $<TransportClient> sync = $();
        final Set<String> subscribedEvents = ConcurrentHashMap.newKeySet();

        ClientRef(RpcClientConfig<?> config, RemotingCodec codec) {
            this.config = config;
            this.codec = codec;
        }
    }

    @RequiredArgsConstructor
    public static class ServerBean {
        static class EventContext {
            final EventArgs computedArgs;
            final Promise<EventArgs> computedPromise = new DefaultPromise<EventArgs>(GlobalEventExecutor.INSTANCE);
            volatile TransportSession computingSession;

            EventContext(EventArgs computedArgs) {
                this.computedArgs = computedArgs;
            }
        }

        static class EventBean {
            final Set<TransportSession> subscribe = ConcurrentHashMap.newKeySet();
            final Map<Long, EventContext> contextMap = new ConcurrentHashMap<Long, EventContext>();
            final AtomicBoolean listenerAttached = new AtomicBoolean();
            final AtomicInteger subscribePacketCount = new AtomicInteger();
        }

        final RpcServerConfig config;
        final RemotingCodec codec;
        final TransportServer server;
        final Map<String, EventBean> eventBeans = new ConcurrentHashMap<String, EventBean>();
    }

    static final AttributeKey<MetadataMessage> HANDSHAKE_META_KEY = AttributeKey.valueOf("HandshakeMeta");
    static final String M_0 = "publishEvent", M_1 = "publishEventAsync", M_2 = "attachEvent";
    static final String M_PING = "__remotingPing";
    static final Map<Object, ServerBean> serverBeans = new ConcurrentHashMap<Object, ServerBean>();
    static final Map<Object, Object> serverInitLocks = new ConcurrentHashMap<Object, Object>();
    static final Map<RpcClientConfig, RpcTransportClientPool> clientPools = new ConcurrentHashMap<RpcClientConfig, RpcTransportClientPool>();
    static final Map<Object, ClientRef> facadeRefs = Collections.synchronizedMap(new IdentityHashMap<Object, ClientRef>());
    static final IdGenerator generator = new IdGenerator();
    static final Map<TransportClient, Map<Integer, ClientBean>> clientBeans = new ConcurrentHashMap<TransportClient, Map<Integer, ClientBean>>();
    static final Map<TransportClient, AtomicInteger> clientRefCounts = new ConcurrentHashMap<TransportClient, AtomicInteger>();

    public static RemotingClientHandle currentClientHandle() {
        RemotingContext ctx;
        try {
            ctx = RemotingContext.context();
        } catch (IllegalArgumentException e) {
            throw new InvalidException("No Remoting server RPC context");
        }
        TransportSession session = ctx.getClient();
        if (ctx.getServer() == null || session == null) {
            throw new InvalidException("No Remoting server RPC context");
        }
        return new RemotingClientHandle(ctx.getServer(), session.sessionId(), session.remotePeerId(), session.remoteEndpoint());
    }

    public static <TEvent extends EventArgs> boolean publishEventToCurrentClient(@NonNull String eventName, TEvent eventArgs) {
        return publishEventToClient(currentClientHandle(), eventName, eventArgs);
    }

    public static <TEvent extends EventArgs> boolean publishEventToClient(RemotingClientHandle client,
            @NonNull String eventName, TEvent eventArgs) {
        if (client == null || client.getServer() == null) {
            return false;
        }
        ServerBean bean = findServerBean(client.getServer());
        if (bean == null) {
            return false;
        }

        TransportSession session = client.getServer().getSession(client.getSessionId());
        if (!isSameClientSession(client, session)) {
            return false;
        }

        EventMessage pack = new EventMessage(eventName, EventFlag.BROADCAST);
        pack.eventArgs = eventArgs;
        try {
            sendEncoded(session, bean.codec, pack);
            log.info("serverSide event {} {} -> DIRECTED", eventName, session.remoteEndpoint());
            return true;
        } catch (Exception e) {
            log.warn("serverSide event {} {} -> DIRECTED FAIL", eventName, client.getRemoteEndpoint(), e);
            return false;
        }
    }

    private static ServerBean findServerBean(TransportServer server) {
        for (ServerBean bean : serverBeans.values()) {
            if (bean.server == server) {
                return bean;
            }
        }
        return null;
    }

    private static EventDispatchMode resolveDispatchMode(EventArgs eventArgs) {
        RemotingEventArgs<?> remotingEventArgs = as(eventArgs, RemotingEventArgs.class);
        if (remotingEventArgs == null || remotingEventArgs.getDispatchMode() == null) {
            return EventDispatchMode.BROADCAST;
        }
        return remotingEventArgs.getDispatchMode();
    }

    private static boolean sendEventToSession(RemotingCodec codec, TransportSession session, String eventName, EventArgs eventArgs) {
        if (session == null || !session.isConnected()) {
            return false;
        }

        EventMessage pack = new EventMessage(eventName, EventFlag.BROADCAST);
        pack.eventArgs = eventArgs;
        try {
            sendEncoded(session, codec, pack);
            log.info("serverSide event {} {} -> DIRECT", eventName, session.remoteEndpoint());
            return true;
        } catch (Exception e) {
            log.warn("serverSide event {} {} -> DIRECT FAIL", eventName, session.remoteEndpoint(), e);
            return false;
        }
    }

    private static void dispatchPublishComputeAsync(ServerBean bean, ServerBean.EventBean eventBean, EventMessage p,
            TransportSession session, TransportServer server) {
        ServerBean.EventContext publishContext = new ServerBean.EventContext(p.eventArgs);
        EventMessage computePack = prepareComputePack(bean, eventBean, p.eventName, p.eventArgs, publishContext);
        if (computePack == null) {
            log.info("serverSide event {} {} -> PUBLISH {}", p.eventName, session.remoteEndpoint(), EventDispatchMode.COMPUTE);
            List<TransportSession> publishTargets = collectBroadcastTargets(bean, eventBean, publishContext);
            addCallerIfBroadcastAllowed(bean, session, publishContext, publishTargets);
            doSendBroadcast(bean, p, publishContext, publishTargets);
            return;
        }

        publishContext.computedPromise.addListener(future -> {
            try {
                eventBean.contextMap.remove(computePack.computeId, publishContext);
                if (!future.isSuccess() && future.cause() != null) {
                    log.warn("serverSide event {} {} -> COMPUTE_ARGS FAIL", p.eventName,
                            publishContext.computingSession == null ? null : publishContext.computingSession.remoteEndpoint(), future.cause());
                }
                log.info("serverSide event {} {} -> PUBLISH {}", p.eventName, session.remoteEndpoint(), EventDispatchMode.COMPUTE);
                List<TransportSession> publishTargets = collectBroadcastTargets(bean, eventBean, publishContext);
                addCallerIfBroadcastAllowed(bean, session, publishContext, publishTargets);
                doSendBroadcast(bean, p, publishContext, publishTargets);
            } catch (Throwable ex) {
                log.error("serverSide event {} {} -> COMPUTE_BROADCAST ERROR", p.eventName, session.remoteEndpoint(), ex);
            }
        });

        long timeoutMillis = server.getConfig().getTcpServerConfig().getConnectTimeoutMillis();
        Tasks.setTimeout(() -> {
            if (!publishContext.computedPromise.isDone()) {
                log.warn("serverSide event {} {} -> COMPUTE_ARGS TIMEOUT", p.eventName,
                        publishContext.computingSession == null ? null : publishContext.computingSession.remoteEndpoint());
                publishContext.computedPromise.trySuccess(publishContext.computedArgs);
            }
        }, timeoutMillis);

        try {
            sendEncoded(publishContext.computingSession, bean.codec, computePack);
            log.info("serverSide event {} {} -> COMPUTE_ARGS WAIT {}", p.eventName,
                    publishContext.computingSession.remoteEndpoint(), timeoutMillis);
        } catch (Exception ex) {
            publishContext.computedPromise.tryFailure(ex);
            log.error("serverSide event {} {} -> COMPUTE_ARGS ERROR", p.eventName,
                    publishContext.computingSession == null ? null : publishContext.computingSession.remoteEndpoint(), ex);
        }
    }

    private static void addCallerIfBroadcastAllowed(ServerBean bean, TransportSession session,
            ServerBean.EventContext publishContext, List<TransportSession> publishTargets) {
        List<Integer> allow = bean.config.getEventBroadcastVersions();
        MetadataMessage meta = session == null ? null : session.attr(HANDSHAKE_META_KEY);
        if (session != null && session.isConnected() && session != publishContext.computingSession
                && meta != null && (allow.isEmpty() || allow.contains(meta.getEventVersion()))) {
            for (TransportSession target : publishTargets) {
                if (target == session) {
                    return;
                }
            }
            publishTargets.add(session);
        }
    }

    private static boolean isSameClientSession(RemotingClientHandle client, TransportSession session) {
        return session != null && session.isConnected()
                && Objects.equals(client.getPeerId(), session.remotePeerId())
                && Objects.equals(client.getRemoteEndpoint(), session.remoteEndpoint());
    }

    @SneakyThrows
    public static <T> T createFacade(@NonNull Class<T> contract, @NonNull RpcClientConfig<T> config) {
        RemotingCodec codec = resolveClientCodec(config);
        DiagnosticMetrics.setNetComponent(config.getTcpConfig(), DiagnosticMetrics.NET_RPC_CLIENT);
        ClientRef ref = new ClientRef(config, codec);
        T facade = proxy(contract, (m, p) -> {
            if (Reflects.OBJECT_METHODS.contains(m)) {
                return p.fastInvokeSuper();
            }
            if (Reflects.isCloseMethod(m)) {
                closeFacade(p.getProxyObject(), ref);
                return null;
            }

            Object pack = null;
            Object[] args = p.arguments;
            ClientBean clientBean = new ClientBean();
            switch (m.getName()) {
                case M_0:
                case M_1:
                    if (args.length == 2) {
                        if (!(args[0] instanceof String) || BooleanUtils.isTrue(ref.isCompute.get())) {
                            return invokeSuper(m, p);
                        }
                        EventArgs eventArgs = (EventArgs) args[1];
                        EventDispatchMode dispatchMode = resolveDispatchMode(eventArgs);
                        ref.isCompute.remove();

                        if (dispatchMode == EventDispatchMode.BROADCAST) {
                            setReturnValue(clientBean, invokeSuper(m, p));
                        }
                        EventMessage eventMessage = new EventMessage((String) args[0], EventFlag.PUBLISH);
                        eventMessage.eventArgs = eventArgs;
                        pack = eventMessage;
                        log.info("clientSide event {} -> PUBLISH {}", eventMessage.eventName, dispatchMode);
                    }
                    break;
                case M_2:
                    switch (args.length) {
                        case 2:
                            return invokeSuper(m, p);
                        case 3:
                            if (config.isUsePool()) {
                                throw new InvalidException("Remoting event subscription requires statefulMode");
                            }
                            setReturnValue(clientBean, invokeSuper(m, p));
                            String eventName = (String) args[0];
                            ref.subscribedEvents.add(eventName);
                            pack = new EventMessage(eventName, EventFlag.SUBSCRIBE);
                            log.info("clientSide event {} -> SUBSCRIBE", eventName);
                            break;
                    }
                    break;
                case "detachEvent":
                    if (args.length == 2) {
                        setReturnValue(clientBean, invokeSuper(m, p));
                        String eventName = (String) args[0];
                        ref.subscribedEvents.remove(eventName);
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
            RpcTransportClientPool pool = resolveClientPool(config);

            TransportClient client = borrowFacadeClient(ref, p.getProxyObject(), pool);
            TransportSession session = currentSession(client);

            MethodMessage methodMessage = as(pack, MethodMessage.class);
            boolean isMethodCall = methodMessage != null;
            int requestId = clientBean.pack == null ? -1 : clientBean.pack.id;
            try {
                if (isMethodCall) {
                    clientBean.idempotent = config.isIdempotentMethod(methodMessage.methodName);
                    return Sys.callLog(contract, methodMessage.methodName,
                            () -> String.format("Client %s.%s [%s -> %s]", contract.getSimpleName(), methodMessage.methodName,
                                    currentLocalEndpoint(client), Sockets.toString(currentRemoteEndpoint(client))),
                            config.getSensitiveMethods().contains(methodMessage.methodName) ? null : methodMessage.parameters, () -> {
                                TransportSession requestSession = resolveSession(client, session);
                                Map<Integer, ClientBean> waitMap = getClientBeans(client);
                                waitMap.put(clientBean.pack.id, clientBean);
                                try {
                                    TransportSession target = activeSession(client, requestSession);
                                    clientBean.sent = true;
                                    sendEncoded(target, ref.codec, methodMessage);
                                } catch (ClientDisconnectedException e) {
                                    if (!client.getConfig().getTcpClientConfig().isEnableReconnect()
                                            || (clientBean.sent && !clientBean.idempotent)) {
                                        throw e;
                                    }
                                }
                                return awaitMethodResponse(config, client, clientBean);
                            });
                }
                sendEncoded(activeSession(client, session), ref.codec, pack);
            } catch (ClientDisconnectedException e) {
                if (!client.getConfig().getTcpClientConfig().isEnableReconnect()) {
                    synchronized (ref.sync) {
                        if (ref.sync.v == client) {
                            ref.sync.v = null;
                        }
                    }
                    throw e;
                }

                if (isMethodCall) {
                    throw e;
                } else if (isSubscriptionPacket(pack)) {
                    log.info("clientSide event subscription deferred until reconnect");
                } else {
                    throw e;
                }
            } finally {
                if (requestId >= 0) {
                    removeWaitBean(client, requestId);
                }
                if (releaseClient(client) && (!config.isUsePool() || !hasPendingWaitBeans(client))) {
                    recycleClient(pool, ref.sync, client);
                }
            }
            return clientBean.pack != null ? clientBean.pack.returnValue : null;
        });
        facadeRefs.put(facade, ref);
        return facade;
    }

    private static void closeFacade(Object proxyObject, ClientRef ref) {
        synchronized (facadeRefs) {
            facadeRefs.remove(proxyObject);
        }

        TransportClient client;
        synchronized (ref.sync) {
            client = ref.sync.v;
            ref.sync.v = null;
            ref.subscribedEvents.clear();
        }
        if (client != null) {
            clearClient(client);
            client.close();
        }
        releaseClientPoolIfUnused(ref.config);
    }

    public static boolean ping(Object facade) {
        return ping(facade, 0);
    }

    public static boolean isHealthy(Object facade) {
        ClientRef ref = facadeRefs.get(facade);
        if (ref == null) {
            return false;
        }

        TransportClient client;
        synchronized (ref.sync) {
            client = ref.sync.v;
        }
        if (client != null) {
            return isClientHealthy(client);
        }
        RpcTransportClientPool pool = clientPools.get(ref.config);
        return pool != null && pool.isHealthy();
    }

    public static boolean ping(Object facade, int timeoutMillis) {
        if (facade == null) {
            return false;
        }
        ClientRef ref = facadeRefs.get(facade);
        if (ref == null) {
            return false;
        }

        RpcTransportClientPool pool = resolveClientPool(ref.config);
        TransportClient client = null;
        ClientBean clientBean = new ClientBean();
        clientBean.pack = new MethodMessage(generator.increment(), M_PING, null, ThreadPool.traceId());
        int requestId = clientBean.pack.id;
        try {
            client = borrowFacadeClient(ref, facade, pool);
            TransportSession session = currentSession(client);
            getClientBeans(client).put(requestId, clientBean);
            clientBean.sent = true;
            sendEncoded(activeSession(client, resolveSession(client, session)), ref.codec, clientBean.pack);
            int waitMillis = timeoutMillis > 0 ? timeoutMillis : resolveRequestTimeout(ref.config, client);
            if (!clientBean.syncRoot.waitOne(waitMillis)) {
                return false;
            }
            clientBean.syncRoot.reset();
            return clientBean.error == null && clientBean.pack.errorMessage == null && Boolean.TRUE.equals(clientBean.pack.returnValue);
        } catch (Throwable e) {
            log.debug("Remoting ping {} fail", ref.config.getTcpConfig().getServerEndpoint(), e);
            return false;
        } finally {
            if (client != null) {
                removeWaitBean(client, requestId);
                if (releaseClient(client) && (!ref.config.isUsePool() || !hasPendingWaitBeans(client))) {
                    recycleClient(pool, ref.sync, client);
                }
            }
        }
    }

    private static RpcTransportClientPool resolveClientPool(RpcClientConfig<?> config) {
        return clientPools.computeIfAbsent(config, k -> {
            log.info("RpcTransportClientPool {}", toJsonString(k));
            if (!config.isUsePool()) {
                return new NonTransportClientPool(config.getTransportConfig(), config.getLocalAddress());
            }
            return new RpcTransportClientPoolImpl(config);
        });
    }

    private static void releaseClientPoolIfUnused(RpcClientConfig<?> config) {
        if (hasFacadeForConfig(config)) {
            return;
        }
        RpcTransportClientPool pool = clientPools.remove(config);
        if (pool == null) {
            return;
        }
        pool.forEachClient(Remoting::clearClient);
        tryClose(pool);
    }

    private static boolean hasFacadeForConfig(RpcClientConfig<?> config) {
        synchronized (facadeRefs) {
            for (ClientRef ref : facadeRefs.values()) {
                if (ref.config == config) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isClientHealthy(TransportClient client) {
        if (client == null || !client.isConnected()) {
            return false;
        }
        TransportSession session = currentSession(client);
        if (session == null || !session.isConnected()) {
            return false;
        }
        int heartbeatTimeoutSeconds = client.getConfig().getTcpClientConfig().getHeartbeatTimeout();
        long lastHeartbeatMillis = session.lastHeartbeatMillis();
        return heartbeatTimeoutSeconds <= 0 || lastHeartbeatMillis <= 0L
                || System.currentTimeMillis() - lastHeartbeatMillis <= heartbeatTimeoutSeconds * 2000L;
    }

    private static TransportClient borrowFacadeClient(ClientRef ref, Object proxyObject, RpcTransportClientPool pool) {
        if (ref.config.isUsePool()) {
            TransportClient client = pool.borrowClient();
            init(client, proxyObject, ref);
            retainClient(client);
            return client;
        }

        if (ref.sync.v == null) {
            TransportClient candidate = pool.borrowClient();
            boolean returnCandidate = false;
            boolean assignedCandidate = false;
            boolean initOk = false;
            try {
                synchronized (ref.sync) {
                    if (ref.sync.v == null) {
                        ref.sync.v = candidate;
                        assignedCandidate = true;
                        init(candidate, proxyObject, ref);
                        initOk = true;
                    } else {
                        returnCandidate = true;
                    }
                }
            } catch (Throwable e) {
                if (assignedCandidate && !initOk) {
                    synchronized (ref.sync) {
                        if (ref.sync.v == candidate) {
                            ref.sync.v = null;
                        }
                    }
                    clearClient(candidate);
                    pool.returnClient(candidate);
                }
                throw InvalidException.sneaky(e);
            }
            if (returnCandidate) {
                pool.returnClient(candidate);
            }
        }

        synchronized (ref.sync) {
            TransportClient client = ref.sync.v;
            retainClient(client);
            return client;
        }
    }

    private static void init(TransportClient client, Object proxyObject, ClientRef ref) {
        client.resetHandlers();
        AtomicReference<TransportSession> sessionRef = new AtomicReference<TransportSession>();
        AtomicBoolean initHandlerInvoked = new AtomicBoolean();
        client.onError.add((s, e) -> e.setCancel(true));
        client.onDisconnected.add((s, e) -> {
            failNonIdempotentPending(client);
            if (!client.getConfig().getTcpClientConfig().isEnableReconnect()) {
                clientBeans.remove(client);
                clientRefCounts.remove(client);
            }
        });
        client.setReceiveHandler((session, frame) -> {
            Object value;
            try {
                value = ref.codec.decode(frame);
            } catch (Exception ex) {
                log.error("clientSide decode fail {}", session.remoteEndpoint(), ex);
                session.close();
                return;
            }
            if (value instanceof MethodMessage) {
                completeMethodResponse(client, (MethodMessage) value);
                return;
            }
            if (value instanceof EventMessage) {
                EventMessage event = (EventMessage) value;
                client.asyncScheduler().runAsync(() -> {
                    handleClientEvent(client, sessionRef.get(), ref.codec, proxyObject, ref.isCompute, event);
                    return null;
                });
            }
        });
        client.onSessionReady.add((s, e) -> onClientSessionReady(client, sessionRef, proxyObject, ref,
                e.getValue(), initHandlerInvoked));
        TransportSession current = client.session();
        if (current != null) {
            onClientSessionReady(client, sessionRef, proxyObject, ref, current, initHandlerInvoked);
        }
    }

    @SneakyThrows
    private static void onClientSessionReady(TransportClient client, AtomicReference<TransportSession> sessionRef,
            Object proxyObject, ClientRef ref, TransportSession session, AtomicBoolean initHandlerInvoked) {
        TransportSession previous = sessionRef.getAndSet(session);
        if (previous == session) {
            return;
        }

        sendEncoded(session, ref.codec, new MetadataMessage(ref.config.getEventVersion()));
        List<String> replayEvents = new ArrayList<String>(ref.subscribedEvents);
        if (initHandlerInvoked.compareAndSet(false, true)) {
            @SuppressWarnings("unchecked")
            TripleAction<Object, TransportClient> initHandler = (TripleAction<Object, TransportClient>) ref.config.getInitHandler();
            if (initHandler != null) {
                initHandler.invoke(proxyObject, client);
            }
        }
        if (previous != null) {
            resendSubscriptions(session, ref.codec, replayEvents);
        }
        resendPending(client, ref.codec, session);
    }

    private static void resendSubscriptions(TransportSession session, RemotingCodec codec, Iterable<String> subscribedEvents) {
        for (String eventName : subscribedEvents) {
            sendEncoded(session, codec, new EventMessage(eventName, EventFlag.SUBSCRIBE));
            log.info("clientSide event {} -> RESUBSCRIBE", eventName);
        }
    }

    private static void completeMethodResponse(TransportClient client, MethodMessage svrPack) {
        ClientBean clientBean = getClientBeans(client).get(svrPack.id);
        if (clientBean == null) {
            log.warn("clientSide callback pack[{}] fail", svrPack.id);
            return;
        }
        clientBean.pack = svrPack;
        clientBean.syncRoot.set();
    }

    private static void handleClientEvent(TransportClient client, TransportSession session, RemotingCodec codec,
            Object proxyObject, FastThreadLocal<Boolean> isCompute, EventMessage x) {
        switch (x.flag) {
            case BROADCAST:
            case COMPUTE_ARGS:
                try {
                    isCompute.set(true);
                    EventPublisher<?> target = (EventPublisher<?>) proxyObject;
                    target.publishEvent(x.eventName, x.eventArgs);
                    log.info("clientSide event {} -> {} OK & args={}", x.eventName, x.flag, toJsonString(x.eventArgs));
                } catch (Exception ex) {
                    log.error("clientSide event {} -> {}", x.eventName, x.flag, ex);
                } finally {
                    if (x.flag == EventFlag.COMPUTE_ARGS) {
                        try {
                            sendEncoded(activeSession(client, session), codec, x);
                        } finally {
                            isCompute.remove();
                        }
                    } else {
                        isCompute.remove();
                    }
                }
                break;
            default:
                break;
        }
    }

    private static Map<Integer, ClientBean> getClientBeans(TransportClient client) {
        return clientBeans.computeIfAbsent(client, k -> new ConcurrentHashMap<Integer, ClientBean>());
    }

    private static void failNonIdempotentPending(TransportClient client) {
        Map<Integer, ClientBean> waitMap = clientBeans.get(client);
        if (waitMap == null || waitMap.isEmpty()) {
            return;
        }
        InetSocketAddress ep = currentRemoteEndpoint(client);
        for (Map.Entry<Integer, ClientBean> entry : waitMap.entrySet()) {
            ClientBean bean = entry.getValue();
            if (bean != null && bean.sent && !bean.idempotent) {
                if (waitMap.remove(entry.getKey(), bean)) {
                    bean.error = new ClientDisconnectedException(ep);
                    bean.syncRoot.set();
                }
            }
        }
    }

    private static void resendPending(TransportClient client, RemotingCodec codec, TransportSession newSession) {
        Map<Integer, ClientBean> waitMap = clientBeans.get(client);
        if (waitMap == null || waitMap.isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, ClientBean> entry : waitMap.entrySet()) {
            ClientBean value = entry.getValue();
            if (value == null || value.pack == null) {
                continue;
            }
            if (value.sent && !value.idempotent) {
                if (waitMap.remove(entry.getKey(), value)) {
                    value.error = new ClientDisconnectedException(currentRemoteEndpoint(client));
                    value.syncRoot.set();
                }
                continue;
            }
            log.info("clientSide resent pack[{}] {}", value.pack.id, value.pack.methodName);
            try {
                value.sent = true;
                sendEncoded(newSession, codec, value.pack);
            } catch (ClientDisconnectedException ex) {
                log.warn("clientSide resent pack[{}] fail", value.pack.id);
            }
        }
    }

    @SneakyThrows
    private static Object awaitMethodResponse(RpcClientConfig<?> config, TransportClient client, ClientBean clientBean) {
        int timeoutMillis = resolveRequestTimeout(config, client);
        if (!clientBean.syncRoot.waitOne(timeoutMillis)) {
            if (clientBean.error != null) {
                throw clientBean.error;
            }
            if (!client.isConnected()) {
                throw new ClientDisconnectedException(currentRemoteEndpoint(client));
            }
            if (clientBean.pack.returnValue == null) {
                throw new TimeoutException(String.format("The method %s read timeout", clientBean.pack.methodName));
            }
        }
        clientBean.syncRoot.reset();
        if (clientBean.error != null) {
            throw clientBean.error;
        }
        if (clientBean.pack.errorMessage != null) {
            throw new RemotingException(clientBean.pack.errorMessage);
        }
        return clientBean.pack.returnValue;
    }

    private static void retainClient(TransportClient client) {
        if (client == null) {
            return;
        }
        clientRefCounts.computeIfAbsent(client, k -> new AtomicInteger()).incrementAndGet();
    }

    private static boolean releaseClient(TransportClient client) {
        if (client == null) {
            return true;
        }

        AtomicInteger ref = clientRefCounts.get(client);
        if (ref == null) {
            return true;
        }

        int remain = ref.decrementAndGet();
        if (remain > 0) {
            return false;
        }
        clientRefCounts.remove(client, ref);
        return true;
    }

    private static void recycleClient(RpcTransportClientPool pool, $<TransportClient> sync, TransportClient client) {
        synchronized (sync) {
            if (sync.v == client) {
                sync.v = pool.returnClient(client);
                return;
            }
        }
        pool.returnClient(client);
    }

    private static void clearClient(TransportClient client) {
        clientBeans.remove(client);
        clientRefCounts.remove(client);
    }

    private static void removeWaitBean(TransportClient client, int requestId) {
        if (requestId < 0) {
            return;
        }
        Map<Integer, ClientBean> waitMap = clientBeans.get(client);
        if (waitMap != null) {
            waitMap.remove(requestId);
        }
    }

    private static boolean hasPendingWaitBeans(TransportClient client) {
        if (client == null) {
            return false;
        }
        Map<Integer, ClientBean> waitMap = clientBeans.get(client);
        return waitMap != null && !waitMap.isEmpty();
    }

    private static int resolveRequestTimeout(RpcClientConfig<?> config, TransportClient client) {
        return config.getRequestTimeoutMillis() > 0 ? config.getRequestTimeoutMillis()
                : client.getConfig().getTcpClientConfig().getConnectTimeoutMillis();
    }

    private static TransportSession currentSession(TransportClient client) {
        return client == null ? null : client.session();
    }

    private static TransportSession resolveSession(TransportClient client, TransportSession session) {
        if (session != null && session.isConnected()) {
            return session;
        }
        TransportSession current = currentSession(client);
        if (current != null && current.isConnected()) {
            return current;
        }
        return null;
    }

    private static TransportSession activeSession(TransportClient client, TransportSession session) {
        TransportSession resolved = resolveSession(client, session);
        if (resolved == null || !resolved.isConnected()) {
            throw new ClientDisconnectedException(currentRemoteEndpoint(client));
        }
        return resolved;
    }

    private static String currentLocalEndpoint(TransportClient client) {
        TransportSession session = currentSession(client);
        InetSocketAddress local = session == null ? null : session.localEndpoint();
        return Sockets.toString(local);
    }

    private static InetSocketAddress currentRemoteEndpoint(TransportClient client) {
        TransportSession session = currentSession(client);
        if (session != null && session.remoteEndpoint() != null) {
            return session.remoteEndpoint();
        }
        return client == null ? null : client.getConfig().getTcpClientConfig().getServerEndpoint();
    }

    @SneakyThrows
    private static void sendEncoded(TransportSession session, RemotingCodec codec, Object packet) {
        if (session == null || !session.isConnected()) {
            throw new ClientDisconnectedException(session == null ? null : session.remoteEndpoint());
        }
        ByteBuf buf = codec.encode(session.channel().alloc(), packet);
        session.send(buf);
    }

    private static boolean isSubscriptionPacket(Object packet) {
        EventMessage event = as(packet, EventMessage.class);
        return event != null && (event.flag == EventFlag.SUBSCRIBE || event.flag == EventFlag.UNSUBSCRIBE);
    }

    private static void setReturnValue(ClientBean clientBean, Object value) {
        if (clientBean.pack == null) {
            clientBean.pack = new MethodMessage(generator.increment(), null, null, ThreadPool.traceId());
        }
        clientBean.pack.returnValue = value;
    }

    @SneakyThrows
    private static Object invokeSuper(Method m, DynamicProxyBean p) {
        if (m.isDefault()) {
            return Reflects.invokeDefaultMethod(m, p.getProxyObject(), p.arguments);
        }
        return p.fastInvokeSuper();
    }

    public static TransportServer register(Object contractInstance, int listenPort, boolean enableEventCompute) {
        RpcServerConfig conf = new RpcServerConfig(new TcpServerConfig(listenPort));
        if (enableEventCompute) {
            conf.setEventComputeVersion(RpcServerConfig.EVENT_LATEST_COMPUTE);
        }
        return register(contractInstance, conf);
    }

    public static TransportServer register(@NonNull Object contractInstance, @NonNull RpcServerConfig config) {
        return registerBean(contractInstance, config).server;
    }

    private static ServerBean registerBean(@NonNull Object contractInstance, @NonNull RpcServerConfig config) {
        ServerBean existing = serverBeans.get(contractInstance);
        if (existing != null) {
            return existing;
        }
        Object initLock = serverInitLocks.computeIfAbsent(contractInstance, k -> new Object());
        synchronized (initLock) {
            try {
                existing = serverBeans.get(contractInstance);
                if (existing != null) {
                    return existing;
                }
                ServerBean bean = doRegister(contractInstance, config);
                serverBeans.put(contractInstance, bean);
                return bean;
            } finally {
                serverInitLocks.remove(contractInstance);
            }
        }
    }

    private static ServerBean doRegister(@NonNull Object contractInstance, @NonNull RpcServerConfig config) {
        RemotingCodec codec = resolveServerCodec(config);
        DiagnosticMetrics.setNetComponent(config.getTcpConfig(), DiagnosticMetrics.NET_RPC_SERVER);
        ServerBean bean = new ServerBean(config, codec, new TransportServer(config.getTransportConfig()));
        bean.server.onClosed.add((s, e) -> serverBeans.remove(contractInstance));
        bean.server.onDisconnected.add((s, e) -> cleanupSubscriptions(bean, e.getValue()));
        bean.server.onError.add((s, e) -> e.setCancel(true));
        bean.server.setReceiveHandler((session, frame) -> {
            Object value;
            try {
                value = codec.decode(frame);
            } catch (Exception ex) {
                log.error("serverSide decode fail {}", session.remoteEndpoint(), ex);
                session.close();
                return;
            }
            // Apply MetadataMessage immediately on EventLoop so subsequent frames always observe HANDSHAKE_META_KEY.
            if (value instanceof MetadataMessage) {
                session.attr(HANDSHAKE_META_KEY, (MetadataMessage) value);
                log.debug("Handshake: {}", toJsonString(value));
                return;
            }
            Executor executor = bean.config.getExecutor();
            if (value instanceof MethodMessage) {
                MethodMessage pack = (MethodMessage) value;
                if (M_PING.equals(pack.methodName)) {
                    if (executor != null && bean.config.isExecutorForPing()) {
                        executor.execute(() -> replyPing(bean.codec, session, pack));
                    } else {
                        replyPing(bean.codec, session, pack);
                    }
                    return;
                }
                if (executor != null) {
                    executor.execute(() -> invokeAndReply(contractInstance, bean, session, pack));
                } else {
                    bean.server.asyncScheduler().runAsync(() -> {
                        invokeAndReply(contractInstance, bean, session, pack);
                        return null;
                    });
                }
                return;
            }
            if (value instanceof EventMessage) {
                EventMessage event = (EventMessage) value;
                bean.server.asyncScheduler().runAsync(() -> {
                    handleServerEvent(contractInstance, bean, bean.server, session, event);
                    return null;
                });
            }
        });
        if (config.getLocalAddress() != null) {
            bean.server.startLocal(config.getLocalAddress());
        } else {
            bean.server.start();
        }
        return bean;
    }

    private static void handleServerEvent(Object contractInstance, ServerBean bean, TransportServer s,
            TransportSession session, EventMessage p) {
        ServerBean.EventBean eventBean = bean.eventBeans.computeIfAbsent(p.eventName, x -> new ServerBean.EventBean());
        switch (p.flag) {
            case SUBSCRIBE:
                eventBean.subscribePacketCount.incrementAndGet();
                if (eventBean.listenerAttached.compareAndSet(false, true)) {
                    EventPublisher<?> eventTarget = (EventPublisher<?>) contractInstance;
                    eventTarget.attachEvent(p.eventName, (sender, args) -> {
                        EventArgs eventArgs = (EventArgs) args;
                        EventDispatchMode dispatchMode = resolveDispatchMode(eventArgs);
                        if (dispatchMode == EventDispatchMode.DIRECT) {
                            if (!publishEventToCurrentClient(p.eventName, eventArgs)) {
                                log.warn("serverSide event {} {} -> DIRECT FAIL", p.eventName, session.remoteEndpoint());
                            }
                            return;
                        }

                        ServerBean.EventContext eventContext = new ServerBean.EventContext(eventArgs);
                        if (dispatchMode == EventDispatchMode.COMPUTE) {
                            EventMessage computePack = prepareComputePack(bean, eventBean, p.eventName, eventArgs, eventContext);
                            if (computePack != null) {
                                awaitComputedArgs(bean, eventBean, eventContext, computePack, s);
                            }
                        }
                        List<TransportSession> broadcastTargets = collectBroadcastTargets(bean, eventBean, eventContext);
                        doSendBroadcast(bean, p, eventContext, broadcastTargets);
                    }, false);
                }
                log.info("serverSide event {} {} -> SUBSCRIBE", p.eventName, session.remoteEndpoint());
                eventBean.subscribe.add(session);
                break;
            case UNSUBSCRIBE:
                log.info("serverSide event {} {} -> UNSUBSCRIBE", p.eventName, session.remoteEndpoint());
                eventBean.subscribe.remove(session);
                break;
            case PUBLISH:
                EventDispatchMode dispatchMode = resolveDispatchMode(p.eventArgs);
                if (dispatchMode == EventDispatchMode.DIRECT) {
                    sendEventToSession(bean.codec, session, p.eventName, p.eventArgs);
                    break;
                }
                if (dispatchMode == EventDispatchMode.COMPUTE) {
                    dispatchPublishComputeAsync(bean, eventBean, p, session, s);
                    break;
                }

                ServerBean.EventContext publishContext = new ServerBean.EventContext(p.eventArgs);
                publishContext.computingSession = session;
                log.info("serverSide event {} {} -> PUBLISH {}", p.eventName, session.remoteEndpoint(), dispatchMode);
                List<TransportSession> publishTargets = collectBroadcastTargets(bean, eventBean, publishContext);
                doSendBroadcast(bean, p, publishContext, publishTargets);
                break;
            case COMPUTE_ARGS:
                ServerBean.EventContext context = eventBean.contextMap.remove(p.computeId);
                if (context == null) {
                    log.warn("serverSide event {} [{}] -> COMPUTE_ARGS FAIL", p.eventName, p.computeId);
                } else {
                    try {
                        BeanMapper.DEFAULT.map(p.eventArgs, context.computedArgs);
                        context.computedPromise.trySuccess(context.computedArgs);
                        log.info("serverSide event {} {} -> COMPUTE_ARGS OK & args={}", p.eventName,
                                context.computingSession == null ? null : context.computingSession.remoteEndpoint(),
                                toJsonString(context.computedArgs));
                    } catch (Throwable ex) {
                        context.computedPromise.tryFailure(ex);
                        log.error("serverSide event {} {} -> COMPUTE_ARGS ERROR", p.eventName,
                                context.computingSession == null ? null : context.computingSession.remoteEndpoint(), ex);
                    }
                }
                break;
            default:
                break;
        }
    }

    private static void replyPing(RemotingCodec codec, TransportSession session, MethodMessage pack) {
        pack.returnValue = Boolean.TRUE;
        try {
            sendEncoded(session, codec, pack);
        } catch (ClientDisconnectedException ignored) {
        }
    }

    private static void invokeAndReply(Object contractInstance, ServerBean bean, TransportSession session, MethodMessage pack) {
        RpcServerConfig config = bean.config;
        TransportServer s = bean.server;
        try {
            pack.returnValue = Sys.callLog(contractInstance.getClass(), pack.methodName,
                    () -> String.format("Server %s.%s [%s -> %s]", contractInstance.getClass().getSimpleName(), pack.methodName,
                            s.getConfig().getTcpServerConfig().getListenPort(), Sockets.toString(session.remoteEndpoint())),
                    config.getSensitiveMethods().contains(pack.methodName) ? null : pack.parameters, () -> RemotingContext.invoke(() -> {
                        String traceName = RxConfig.INSTANCE.getThreadPool().getTraceName();
                        if (traceName != null) {
                            ThreadPool.startTrace(pack.traceId, true);
                        }
                        try {
                            return Reflects.invokeMethod(contractInstance, pack.methodName, pack.parameters);
                        } finally {
                            ThreadPool.endTrace();
                        }
                    }, s, session));
        } catch (Throwable ex) {
            Throwable cause = ifNull(ex.getCause(), ex);
            pack.errorMessage = String.format("%s %s", cause.getClass().getSimpleName(), cause.getMessage());
        }
        if (pack.parameters != null) {
            Arrays.fill(pack.parameters, null);
        }
        try {
            sendEncoded(session, bean.codec, pack);
        } catch (ClientDisconnectedException ignored) {
        }
    }

    private static void cleanupSubscriptions(ServerBean bean, TransportSession session) {
        if (session == null) {
            return;
        }
        for (ServerBean.EventBean eventBean : bean.eventBeans.values()) {
            eventBean.subscribe.remove(session);
        }
    }

    private static List<TransportSession> collectBroadcastTargets(ServerBean bean, ServerBean.EventBean eventBean,
            ServerBean.EventContext context) {
        List<Integer> allow = bean.config.getEventBroadcastVersions();
        List<TransportSession> targets = new ArrayList<TransportSession>(eventBean.subscribe.size());
        for (TransportSession client : eventBean.subscribe) {
            if (!client.isConnected()) {
                eventBean.subscribe.remove(client);
                continue;
            }
            MetadataMessage meta;
            if (client == context.computingSession || (meta = client.attr(HANDSHAKE_META_KEY)) == null
                    || (!allow.isEmpty() && !allow.contains(meta.getEventVersion()))) {
                continue;
            }
            targets.add(client);
        }
        return targets;
    }

    private static EventMessage prepareComputePack(ServerBean bean, ServerBean.EventBean eventBean, String eventName,
            EventArgs args, ServerBean.EventContext context) {
        if (bean.config.getEventComputeVersion() == RpcServerConfig.EVENT_DISABLE_COMPUTE) {
            context.computingSession = null;
            return null;
        }

        TransportSession computingSession = selectComputingSession(bean.config, eventBean);
        if (computingSession == null) {
            log.warn("serverSide event {} subscribe empty", eventName);
            return null;
        }
        context.computingSession = computingSession;
        EventMessage pack = new EventMessage(eventName, EventFlag.COMPUTE_ARGS);
        pack.computeId = Snowflake.DEFAULT.nextId();
        pack.eventArgs = args;
        eventBean.contextMap.put(pack.computeId, context);
        return pack;
    }

    private static TransportSession selectComputingSession(RpcServerConfig config, ServerBean.EventBean eventBean) {
        Linq<TransportSession> subscribes = Linq.from(eventBean.subscribe).where(x -> x.isConnected() && x.attr(HANDSHAKE_META_KEY) != null);
        if (config.getEventComputeVersion() == RpcServerConfig.EVENT_LATEST_COMPUTE) {
            return subscribes.groupBy(x -> x.<MetadataMessage>attr(HANDSHAKE_META_KEY).getEventVersion(), (p1, p2) -> {
                int index = ThreadLocalRandom.current().nextInt(0, p2.count());
                return p2.skip(index).first();
            }).orderByDescending(x -> x.<MetadataMessage>attr(HANDSHAKE_META_KEY).getEventVersion()).firstOrDefault();
        }
        return subscribes.where(x -> x.<MetadataMessage>attr(HANDSHAKE_META_KEY).getEventVersion() == config.getEventComputeVersion())
                .orderByRand().firstOrDefault();
    }

    private static void awaitComputedArgs(ServerBean bean, ServerBean.EventBean eventBean, ServerBean.EventContext context,
            EventMessage pack, TransportServer server) {
        try {
            sendEncoded(context.computingSession, bean.codec, pack);
            log.info("serverSide event {} {} -> COMPUTE_ARGS WAIT {}", pack.eventName,
                    context.computingSession.remoteEndpoint(), server.getConfig().getTcpServerConfig().getConnectTimeoutMillis());
            if (!context.computedPromise.await(server.getConfig().getTcpServerConfig().getConnectTimeoutMillis())) {
                log.warn("serverSide event {} {} -> COMPUTE_ARGS TIMEOUT", pack.eventName, context.computingSession.remoteEndpoint());
            }
        } catch (Exception ex) {
            context.computedPromise.tryFailure(ex);
            log.error("serverSide event {} {} -> COMPUTE_ARGS ERROR", pack.eventName, context.computingSession.remoteEndpoint(), ex);
        } finally {
            eventBean.contextMap.remove(pack.computeId, context);
        }
    }

    private static void doSendBroadcast(ServerBean bean, EventMessage p, ServerBean.EventContext context,
            List<TransportSession> targets) {
        if (targets.isEmpty()) {
            return;
        }
        List<Integer> allow = bean.config.getEventBroadcastVersions();
        EventMessage pack = new EventMessage(p.eventName, EventFlag.BROADCAST);
        pack.eventArgs = context.computedArgs;
        tryAs(pack.eventArgs, RemotingEventArgs.class, x -> x.setBroadcastVersions(allow));
        for (TransportSession client : targets) {
            if (!client.isConnected()) {
                continue;
            }
            try {
                sendEncoded(client, bean.codec, pack);
                log.info("serverSide event {} {} -> BROADCAST", pack.eventName, client.remoteEndpoint());
            } catch (ClientDisconnectedException ignored) {
            }
        }
    }

    private static RemotingCodec resolveClientCodec(RpcClientConfig<?> config) {
        RemotingCodecFactory factory = config.getCodecFactory();
        if (factory == null) {
            factory = FuryRemotingCodecFactory.createDefault();
            config.setCodecFactory(factory);
        }
        return factory.newCodec();
    }

    private static RemotingCodec resolveServerCodec(RpcServerConfig config) {
        RemotingCodecFactory factory = config.getCodecFactory();
        if (factory == null) {
            factory = FuryRemotingCodecFactory.createDefault();
            config.setCodecFactory(factory);
        }
        return factory.newCodec();
    }
}
