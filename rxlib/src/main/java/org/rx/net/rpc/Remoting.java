package org.rx.net.rpc;

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
import org.rx.core.StringBuilder;
import org.rx.core.Sys;
import org.rx.core.ThreadPool;
import org.rx.core.Tasks;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.rpc.protocol.EventFlag;
import org.rx.net.rpc.protocol.EventMessage;
import org.rx.net.rpc.protocol.MetadataMessage;
import org.rx.net.rpc.protocol.MethodMessage;
import org.rx.net.transport.ClientDisconnectedException;
import org.rx.net.transport.TcpServer;
import org.rx.net.transport.TcpServerConfig;
import org.rx.net.transport.hybrid.HybridClient;
import org.rx.net.transport.hybrid.HybridConfig;
import org.rx.net.transport.hybrid.HybridServer;
import org.rx.net.transport.hybrid.HybridServerEventArgs;
import org.rx.net.transport.hybrid.HybridSession;
import org.rx.net.transport.protocol.ErrorPacket;
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
    }

    static final class ClientRef {
        final RpcClientConfig<?> config;
        final FastThreadLocal<Boolean> isCompute = new FastThreadLocal<Boolean>();
        final $<HybridClient> sync = $();
        final Set<String> subscribedEvents = ConcurrentHashMap.newKeySet();

        ClientRef(RpcClientConfig<?> config) {
            this.config = config;
        }
    }

    @RequiredArgsConstructor
    public static class ServerBean {
        static class EventContext {
            final EventArgs computedArgs;
            final Promise<EventArgs> computedPromise = new DefaultPromise<EventArgs>(GlobalEventExecutor.INSTANCE);
            volatile HybridSession computingSession;

            EventContext(EventArgs computedArgs) {
                this.computedArgs = computedArgs;
            }
        }

        static class EventBean {
            final Set<HybridSession> subscribe = ConcurrentHashMap.newKeySet();
            final Map<Long, EventContext> contextMap = new ConcurrentHashMap<Long, EventContext>();
            final AtomicBoolean listenerAttached = new AtomicBoolean();
        }

        final RpcServerConfig config;
        final HybridServer server;
        final Map<String, EventBean> eventBeans = new ConcurrentHashMap<String, EventBean>();
    }

    static final AttributeKey<MetadataMessage> HANDSHAKE_META_KEY = AttributeKey.valueOf("HandshakeMeta");
    static final String M_0 = "publishEvent", M_1 = "publishEventAsync", M_2 = "attachEvent";
    static final String M_PING = "__remotingPing";
    static final Map<Object, ServerBean> serverBeans = new ConcurrentHashMap<Object, ServerBean>();
    static final Map<Object, Object> serverInitLocks = new ConcurrentHashMap<Object, Object>();
    static final Map<RpcClientConfig, RpcHybridClientPool> clientPools = new ConcurrentHashMap<RpcClientConfig, RpcHybridClientPool>();
    static final Map<Object, ClientRef> facadeRefs = Collections.synchronizedMap(new IdentityHashMap<Object, ClientRef>());
    static final IdGenerator generator = new IdGenerator();
    static final Map<HybridClient, Map<Integer, ClientBean>> clientBeans = new ConcurrentHashMap<HybridClient, Map<Integer, ClientBean>>();
    static final Map<HybridClient, AtomicInteger> clientRefCounts = new ConcurrentHashMap<HybridClient, AtomicInteger>();

    public static RemotingClientHandle currentClientHandle() {
        RemotingContext ctx;
        try {
            ctx = RemotingContext.context();
        } catch (IllegalArgumentException e) {
            throw new InvalidException("No Remoting server RPC context");
        }
        HybridSession session = ctx.getClient();
        if (ctx.getServer() == null || session == null) {
            throw new InvalidException("No Remoting server RPC context");
        }
        return new RemotingClientHandle(ctx.getServer(), session.sessionId(), session.remotePeerId(), session.tcpRemoteEndpoint());
    }

    public static <TEvent extends EventArgs> boolean publishEventToCurrentClient(@NonNull String eventName, TEvent eventArgs) {
        return publishEventToClient(currentClientHandle(), eventName, eventArgs);
    }

    public static <TEvent extends EventArgs> boolean publishEventToClient(RemotingClientHandle client,
            @NonNull String eventName, TEvent eventArgs) {
        if (client == null || client.getServer() == null) {
            return false;
        }

        HybridSession session = client.getServer().getSession(client.getSessionId());
        if (!isSameClientSession(client, session)) {
            return false;
        }

        EventMessage pack = new EventMessage(eventName, EventFlag.BROADCAST);
        pack.eventArgs = eventArgs;
        try {
            session.send(pack, RemotingHybridOptions.event(pack));
            log.info("serverSide event {} {} -> DIRECTED", eventName, session.tcpRemoteEndpoint());
            return true;
        } catch (Exception e) {
            log.warn("serverSide event {} {} -> DIRECTED FAIL", eventName, client.getTcpRemoteEndpoint(), e);
            return false;
        }
    }

    private static EventDispatchMode resolveDispatchMode(EventArgs eventArgs) {
        RemotingEventArgs<?> remotingEventArgs = as(eventArgs, RemotingEventArgs.class);
        if (remotingEventArgs == null || remotingEventArgs.getDispatchMode() == null) {
            return EventDispatchMode.BROADCAST;
        }
        return remotingEventArgs.getDispatchMode();
    }

    private static boolean sendEventToSession(HybridSession session, String eventName, EventArgs eventArgs) {
        if (session == null || !session.isConnected()) {
            return false;
        }

        EventMessage pack = new EventMessage(eventName, EventFlag.BROADCAST);
        pack.eventArgs = eventArgs;
        try {
            session.send(pack, RemotingHybridOptions.event(pack));
            log.info("serverSide event {} {} -> DIRECT", eventName, session.tcpRemoteEndpoint());
            return true;
        } catch (Exception e) {
            log.warn("serverSide event {} {} -> DIRECT FAIL", eventName, session.tcpRemoteEndpoint(), e);
            return false;
        }
    }

    private static void dispatchPublishComputeAsync(ServerBean bean, ServerBean.EventBean eventBean, EventMessage p,
            HybridSession session, HybridServer server) {
        ServerBean.EventContext publishContext = new ServerBean.EventContext(p.eventArgs);
        EventMessage computePack = prepareComputePack(bean, eventBean, p.eventName, p.eventArgs, publishContext);
        if (computePack == null) {
            log.info("serverSide event {} {} -> PUBLISH {}", p.eventName, session.tcpRemoteEndpoint(), EventDispatchMode.COMPUTE);
            List<HybridSession> publishTargets = collectBroadcastTargets(bean, eventBean, publishContext);
            List<Integer> allow = bean.config.getEventBroadcastVersions();
            MetadataMessage meta = session == null ? null : session.attr(HANDSHAKE_META_KEY);
            if (session != null && session.isConnected() && session != publishContext.computingSession
                    && meta != null && (allow.isEmpty() || allow.contains(meta.getEventVersion()))) {
                boolean found = false;
                for (HybridSession target : publishTargets) {
                    if (target == session) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    publishTargets.add(session);
                }
            }
            doSendBroadcast(bean, p, publishContext, publishTargets);
            return;
        }

        publishContext.computedPromise.addListener(future -> {
            try {
                eventBean.contextMap.remove(computePack.computeId, publishContext);
                if (!future.isSuccess() && future.cause() != null) {
                    log.warn("serverSide event {} {} -> COMPUTE_ARGS FAIL", p.eventName,
                            publishContext.computingSession == null ? null : publishContext.computingSession.tcpRemoteEndpoint(), future.cause());
                }
                log.info("serverSide event {} {} -> PUBLISH {}", p.eventName, session.tcpRemoteEndpoint(), EventDispatchMode.COMPUTE);
                List<HybridSession> publishTargets = collectBroadcastTargets(bean, eventBean, publishContext);
                List<Integer> allow = bean.config.getEventBroadcastVersions();
                MetadataMessage meta = session == null ? null : session.attr(HANDSHAKE_META_KEY);
                if (session != null && session.isConnected() && session != publishContext.computingSession
                        && meta != null && (allow.isEmpty() || allow.contains(meta.getEventVersion()))) {
                    boolean found = false;
                    for (HybridSession target : publishTargets) {
                        if (target == session) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        publishTargets.add(session);
                    }
                }
                doSendBroadcast(bean, p, publishContext, publishTargets);
            } catch (Throwable ex) {
                log.error("serverSide event {} {} -> COMPUTE_BROADCAST ERROR", p.eventName, session.tcpRemoteEndpoint(), ex);
            }
        });

        long timeoutMillis = server.getConfig().getTcpServerConfig().getConnectTimeoutMillis();
        Tasks.setTimeout(() -> {
            if (!publishContext.computedPromise.isDone()) {
                log.warn("serverSide event {} {} -> COMPUTE_ARGS TIMEOUT", p.eventName,
                        publishContext.computingSession == null ? null : publishContext.computingSession.tcpRemoteEndpoint());
                publishContext.computedPromise.trySuccess(publishContext.computedArgs);
            }
        }, timeoutMillis);

        try {
            publishContext.computingSession.send(computePack, RemotingHybridOptions.CONTROL);
            log.info("serverSide event {} {} -> COMPUTE_ARGS WAIT {}", p.eventName,
                    publishContext.computingSession.tcpRemoteEndpoint(), timeoutMillis);
        } catch (Exception ex) {
            publishContext.computedPromise.tryFailure(ex);
            log.error("serverSide event {} {} -> COMPUTE_ARGS ERROR", p.eventName,
                    publishContext.computingSession == null ? null : publishContext.computingSession.tcpRemoteEndpoint(), ex);
        }
    }

    private static boolean isSameClientSession(RemotingClientHandle client, HybridSession session) {
        return session != null && session.isConnected()
                && Objects.equals(client.getPeerId(), session.remotePeerId())
                && Objects.equals(client.getTcpRemoteEndpoint(), session.tcpRemoteEndpoint());
    }

    @SneakyThrows
    public static <T> T createFacade(@NonNull Class<T> contract, @NonNull RpcClientConfig<T> config) {
        ensureClientCodec(config);
        DiagnosticMetrics.setNetComponent(config.getTcpConfig(), DiagnosticMetrics.NET_RPC_CLIENT);
        ClientRef ref = new ClientRef(config);
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
            RpcHybridClientPool pool = resolveClientPool(config);

            HybridClient client;
            HybridSession session;
            client = borrowFacadeClient(ref, p.getProxyObject(), pool);
            session = currentSession(client);

            MethodMessage methodMessage = as(pack, MethodMessage.class);
            boolean isMethodCall = methodMessage != null;
            int requestId = clientBean.pack == null ? -1 : clientBean.pack.id;
            try {
                if (isMethodCall) {
                    return Sys.callLog(contract, methodMessage.methodName,
                            () -> String.format("Client %s.%s [%s -> %s]", contract.getSimpleName(), methodMessage.methodName,
                                    currentLocalEndpoint(client), Sockets.toString(currentRemoteEndpoint(client))),
                            methodMessage.parameters, () -> {
                                HybridSession requestSession = resolveSession(client, session);
                                Map<Integer, ClientBean> waitMap = getClientBeans(client);
                                waitMap.put(clientBean.pack.id, clientBean);
                                try {
                                    sendRequest(client, requestSession, methodMessage);
                                } catch (ClientDisconnectedException e) {
                                    if (!client.getConfig().getTcpClientConfig().isEnableReconnect()) {
                                        throw e;
                                    }
                                }
                                return awaitMethodResponse(config, client, clientBean);
                            });
                }
                sendPacket(client, session, pack);
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
                    removeWaitBean(client, session, requestId);
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

        HybridClient client;
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

        HybridClient client;
        synchronized (ref.sync) {
            client = ref.sync.v;
        }
        if (client != null) {
            return isClientHealthy(client);
        }
        RpcHybridClientPool pool = clientPools.get(ref.config);
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

        RpcHybridClientPool pool = resolveClientPool(ref.config);
        HybridClient client = null;
        HybridSession session = null;
        ClientBean clientBean = new ClientBean();
        clientBean.pack = new MethodMessage(generator.increment(), M_PING, null, ThreadPool.traceId());
        int requestId = clientBean.pack.id;
        try {
            client = borrowFacadeClient(ref, facade, pool);
            session = currentSession(client);
            getClientBeans(client).put(requestId, clientBean);
            sendRequest(client, resolveSession(client, session), clientBean.pack);
            int waitMillis = timeoutMillis > 0 ? timeoutMillis : resolveRequestTimeout(ref.config, client);
            if (!clientBean.syncRoot.waitOne(waitMillis)) {
                return false;
            }
            clientBean.syncRoot.reset();
            return clientBean.pack.errorMessage == null && Boolean.TRUE.equals(clientBean.pack.returnValue);
        } catch (Throwable e) {
            log.debug("Remoting ping {} fail", ref.config.getTcpConfig().getServerEndpoint(), e);
            return false;
        } finally {
            if (client != null) {
                removeWaitBean(client, session, requestId);
                if (releaseClient(client) && (!ref.config.isUsePool() || !hasPendingWaitBeans(client))) {
                    recycleClient(pool, ref.sync, client);
                }
            }
        }
    }

    private static RpcHybridClientPool resolveClientPool(RpcClientConfig<?> config) {
        return clientPools.computeIfAbsent(config, k -> {
            log.info("RpcHybridClientPool {}", toJsonString(k));
            if (!config.isUsePool()) {
                return new NonHybridClientPool(config.getHybridConfig());
            }
            return new RpcHybridClientPoolImpl(config);
        });
    }

    private static void releaseClientPoolIfUnused(RpcClientConfig<?> config) {
        if (hasFacadeForConfig(config)) {
            return;
        }
        RpcHybridClientPool pool = clientPools.remove(config);
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

    private static boolean isClientHealthy(HybridClient client) {
        if (client == null || !client.isConnected()) {
            return false;
        }
        HybridSession session = currentSession(client);
        if (session == null || !session.isConnected()) {
            return false;
        }
        int heartbeatTimeoutSeconds = client.getConfig().getTcpClientConfig().getHeartbeatTimeout();
        long lastHeartbeatMillis = session.lastHeartbeatMillis();
        return heartbeatTimeoutSeconds <= 0 || lastHeartbeatMillis <= 0L
                || System.currentTimeMillis() - lastHeartbeatMillis <= heartbeatTimeoutSeconds * 2000L;
    }

    private static HybridClient borrowFacadeClient(ClientRef ref, Object proxyObject, RpcHybridClientPool pool) {
        if (ref.config.isUsePool()) {
            HybridClient client = pool.borrowClient();
            init(client, proxyObject, ref.config, ref.isCompute, ref.subscribedEvents);
            retainClient(client);
            return client;
        }

        if (ref.sync.v == null) {
            HybridClient candidate = pool.borrowClient();
            boolean returnCandidate = false;
            boolean assignedCandidate = false;
            boolean initOk = false;
            try {
                synchronized (ref.sync) {
                    if (ref.sync.v == null) {
                        ref.sync.v = candidate;
                        assignedCandidate = true;
                        init(candidate, proxyObject, ref.config, ref.isCompute, ref.subscribedEvents);
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
            HybridClient client = ref.sync.v;
            retainClient(client);
            return client;
        }
    }

    private static void init(HybridClient client, Object proxyObject, RpcClientConfig<?> config, FastThreadLocal<Boolean> isCompute,
            Set<String> subscribedEvents) {
        client.resetHandlers();
        AtomicReference<HybridSession> sessionRef = new AtomicReference<HybridSession>();
        AtomicBoolean initHandlerInvoked = new AtomicBoolean();
        client.onError.add((s, e) -> e.setCancel(true));
        client.onDisconnected.add((s, e) -> {
            if (!client.getConfig().getTcpClientConfig().isEnableReconnect()) {
                clientBeans.remove(client);
                clientRefCounts.remove(client);
            }
        });
        client.onReceive.add((s, e) -> handleClientReceive(client, sessionRef.get(), proxyObject, isCompute, e.getValue()));
        client.onSessionReady.add((s, e) -> onClientSessionReady(client, sessionRef, proxyObject, config, e.getValue(),
                subscribedEvents, initHandlerInvoked));
        HybridSession current = client.session();
        if (current != null) {
            onClientSessionReady(client, sessionRef, proxyObject, config, current, subscribedEvents, initHandlerInvoked);
        }
    }

    @SneakyThrows
    private static void onClientSessionReady(HybridClient client, AtomicReference<HybridSession> sessionRef,
            Object proxyObject, RpcClientConfig<?> config, HybridSession session,
            Set<String> subscribedEvents, AtomicBoolean initHandlerInvoked) {
        HybridSession previous = sessionRef.getAndSet(session);
        if (previous == session) {
            return;
        }

        session.send(new MetadataMessage(config.getEventVersion()), RemotingHybridOptions.CONTROL);
        List<String> replayEvents = new ArrayList<String>(subscribedEvents);
        if (initHandlerInvoked.compareAndSet(false, true)) {
            TripleAction<Object, HybridClient> initHandler = (TripleAction<Object, HybridClient>) config.getInitHandler();
            if (initHandler != null) {
                initHandler.invoke(proxyObject, client);
            }
        }
        if (previous != null) {
            resendSubscriptions(session, replayEvents);
        }
        resendPending(client, previous, session);
    }

    private static void resendSubscriptions(HybridSession session, Iterable<String> subscribedEvents) {
        for (String eventName : subscribedEvents) {
            session.send(new EventMessage(eventName, EventFlag.SUBSCRIBE), RemotingHybridOptions.CONTROL);
            log.info("clientSide event {} -> RESUBSCRIBE", eventName);
        }
    }

    private static void handleClientReceive(HybridClient client, HybridSession session, Object proxyObject,
            FastThreadLocal<Boolean> isCompute, Object value) {
        if (tryAs(value, EventMessage.class, x -> {
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
                                activeSession(client, session).send(x, RemotingHybridOptions.CONTROL);
                            } finally {
                                isCompute.remove();
                            }
                        }
                    }
                    break;
            }
        })) {
            return;
        }

        MethodMessage svrPack = (MethodMessage) value;
        ClientBean clientBean = getClientBeans(client).get(svrPack.id);
        if (clientBean == null) {
            log.warn("clientSide callback pack[{}] fail", svrPack.id);
            return;
        }
        clientBean.pack = svrPack;
        clientBean.syncRoot.set();
    }

    private static Map<Integer, ClientBean> getClientBeans(HybridClient client) {
        return clientBeans.computeIfAbsent(client, k -> new ConcurrentHashMap<Integer, ClientBean>());
    }

    private static void resendPending(HybridClient client, HybridSession oldSession, HybridSession newSession) {
        for (ClientBean value : getClientBeans(client).values()) {
            log.info("clientSide resent pack[{}] {}", value.pack.id, value.pack.methodName);
            try {
                newSession.send(value.pack, RemotingHybridOptions.CONTROL);
            } catch (ClientDisconnectedException ex) {
                log.warn("clientSide resent pack[{}] fail", value.pack.id);
            }
        }
    }

    @SneakyThrows
    private static Object awaitMethodResponse(RpcClientConfig<?> config, HybridClient client, ClientBean clientBean) {
        int timeoutMillis = resolveRequestTimeout(config, client);
        if (!clientBean.syncRoot.waitOne(timeoutMillis)) {
            if (!client.isConnected()) {
                throw new ClientDisconnectedException(currentRemoteEndpoint(client));
            }
            if (clientBean.pack.returnValue == null) {
                throw new TimeoutException(String.format("The method %s read timeout", clientBean.pack.methodName));
            }
        }
        clientBean.syncRoot.reset();
        if (clientBean.pack.errorMessage != null) {
            throw new RemotingException(clientBean.pack.errorMessage);
        }
        return clientBean.pack.returnValue;
    }

    private static void retainClient(HybridClient client) {
        if (client == null) {
            return;
        }
        clientRefCounts.computeIfAbsent(client, k -> new AtomicInteger()).incrementAndGet();
    }

    private static boolean releaseClient(HybridClient client) {
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

    private static void recycleClient(RpcHybridClientPool pool, $<HybridClient> sync, HybridClient client) {
        synchronized (sync) {
            if (sync.v == client) {
                sync.v = pool.returnClient(client);
                return;
            }
        }
        pool.returnClient(client);
    }

    private static void clearClient(HybridClient client) {
        clientBeans.remove(client);
        clientRefCounts.remove(client);
    }

    private static void removeWaitBean(HybridClient client, HybridSession session, int requestId) {
        if (requestId < 0) {
            return;
        }
        Map<Integer, ClientBean> waitMap = clientBeans.get(client);
        if (waitMap != null) {
            waitMap.remove(requestId);
        }
    }

    private static boolean hasPendingWaitBeans(HybridClient client) {
        if (client == null) {
            return false;
        }
        Map<Integer, ClientBean> waitMap = clientBeans.get(client);
        return waitMap != null && !waitMap.isEmpty();
    }

    private static int resolveRequestTimeout(RpcClientConfig<?> config, HybridClient client) {
        return config.getRequestTimeoutMillis() > 0 ? config.getRequestTimeoutMillis()
                : client.getConfig().getTcpClientConfig().getConnectTimeoutMillis();
    }

    private static HybridSession currentSession(HybridClient client) {
        return client == null ? null : client.session();
    }

    private static HybridSession resolveSession(HybridClient client, HybridSession session) {
        if (session != null && session.isConnected()) {
            return session;
        }
        HybridSession current = currentSession(client);
        if (current != null && current.isConnected()) {
            return current;
        }
        return null;
    }

    private static HybridSession activeSession(HybridClient client, HybridSession session) {
        HybridSession resolved = resolveSession(client, session);
        if (resolved == null || !resolved.isConnected()) {
            throw new ClientDisconnectedException(currentRemoteEndpoint(client));
        }
        return resolved;
    }

    private static String currentLocalEndpoint(HybridClient client) {
        HybridSession session = currentSession(client);
        InetSocketAddress local = session == null ? null : session.tcpLocalEndpoint();
        return Sockets.toString(local);
    }

    private static InetSocketAddress currentRemoteEndpoint(HybridClient client) {
        HybridSession session = currentSession(client);
        if (session != null && session.tcpRemoteEndpoint() != null) {
            return session.tcpRemoteEndpoint();
        }
        return client == null ? null : client.getConfig().getTcpClientConfig().getServerEndpoint();
    }

    private static void sendRequest(HybridClient client, HybridSession session, MethodMessage message) {
        activeSession(client, session).send(message, RemotingHybridOptions.METHOD);
    }

    private static void sendPacket(HybridClient client, HybridSession session, Object packet) {
        HybridSession active = activeSession(client, session);
        if (packet instanceof MetadataMessage) {
            active.send(packet, RemotingHybridOptions.CONTROL);
            return;
        }
        if (packet instanceof EventMessage) {
            active.send(packet, RemotingHybridOptions.event((EventMessage) packet));
            return;
        }
        if (packet instanceof MethodMessage) {
            active.send(packet, RemotingHybridOptions.METHOD);
            return;
        }
        active.send(packet);
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

    public static TcpServer register(Object contractInstance, int listenPort, boolean enableEventCompute) {
        RpcServerConfig conf = new RpcServerConfig(new TcpServerConfig(listenPort));
        if (enableEventCompute) {
            conf.setEventComputeVersion(RpcServerConfig.EVENT_LATEST_COMPUTE);
        }
        return register(contractInstance, conf);
    }

    public static TcpServer register(@NonNull Object contractInstance, @NonNull RpcServerConfig config) {
        return registerBean(contractInstance, config).server.tcpServer();
    }

    public static HybridServer registerHybrid(Object contractInstance, int listenPort, boolean enableEventCompute) {
        RpcServerConfig conf = new RpcServerConfig(new TcpServerConfig(listenPort));
        if (enableEventCompute) {
            conf.setEventComputeVersion(RpcServerConfig.EVENT_LATEST_COMPUTE);
        }
        return registerHybrid(contractInstance, conf);
    }

    public static HybridServer registerHybrid(@NonNull Object contractInstance, @NonNull RpcServerConfig config) {
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
        ensureServerCodec(config);
        DiagnosticMetrics.setNetComponent(config.getTcpConfig(), DiagnosticMetrics.NET_RPC_SERVER);
        ServerBean bean = new ServerBean(config, new HybridServer(config.getHybridConfig()));
        bean.server.onClosed.add((s, e) -> serverBeans.remove(contractInstance));
        bean.server.onDisconnected.add((s, e) -> cleanupSubscriptions(bean, e.getValue()));
        bean.server.onError.add((s, e) -> e.setCancel(true));
        bean.server.onReceive.add((s, e) -> onServerReceive(contractInstance, bean, s, e));
        bean.server.start();
        return bean;
    }

    private static void onServerReceive(Object contractInstance, ServerBean bean, HybridServer s, HybridServerEventArgs<Object> e) {
        HybridSession session = e.getSession();
        if (tryAs(e.getValue(), EventMessage.class, p -> {
            ServerBean.EventBean eventBean = bean.eventBeans.computeIfAbsent(p.eventName, x -> new ServerBean.EventBean());
            switch (p.flag) {
                case SUBSCRIBE:
                    if (eventBean.listenerAttached.compareAndSet(false, true)) {
                        EventPublisher<?> eventTarget = (EventPublisher<?>) contractInstance;
                        eventTarget.attachEvent(p.eventName, (sender, args) -> {
                            EventArgs eventArgs = (EventArgs) args;
                            EventDispatchMode dispatchMode = resolveDispatchMode(eventArgs);
                            if (dispatchMode == EventDispatchMode.DIRECT) {
                                if (!publishEventToCurrentClient(p.eventName, eventArgs)) {
                                    log.warn("serverSide event {} {} -> DIRECT FAIL", p.eventName, session.tcpRemoteEndpoint());
                                }
                                return;
                            }

                            ServerBean.EventContext eventContext = new ServerBean.EventContext(eventArgs);
                            if (dispatchMode == EventDispatchMode.COMPUTE) {
                                EventMessage computePack = prepareComputePack(bean, eventBean, p.eventName, eventArgs, eventContext);
                                if (computePack != null) {
                                    awaitComputedArgs(eventBean, eventContext, computePack, s);
                                }
                            }
                            List<HybridSession> broadcastTargets = collectBroadcastTargets(bean, eventBean, eventContext);
                            doSendBroadcast(bean, p, eventContext, broadcastTargets);
                        }, false);
                    }
                    log.info("serverSide event {} {} -> SUBSCRIBE", p.eventName, session.tcpRemoteEndpoint());
                    eventBean.subscribe.add(session);
                    break;
                case UNSUBSCRIBE:
                    log.info("serverSide event {} {} -> UNSUBSCRIBE", p.eventName, session.tcpRemoteEndpoint());
                    eventBean.subscribe.remove(session);
                    break;
                case PUBLISH:
                    EventDispatchMode dispatchMode = resolveDispatchMode(p.eventArgs);
                    if (dispatchMode == EventDispatchMode.DIRECT) {
                        sendEventToSession(session, p.eventName, p.eventArgs);
                        break;
                    }
                    if (dispatchMode == EventDispatchMode.COMPUTE) {
                        dispatchPublishComputeAsync(bean, eventBean, p, session, s);
                        break;
                    }

                    ServerBean.EventContext publishContext = new ServerBean.EventContext(p.eventArgs);
                    publishContext.computingSession = session;
                    log.info("serverSide event {} {} -> PUBLISH {}", p.eventName, session.tcpRemoteEndpoint(), dispatchMode);
                    List<HybridSession> publishTargets = collectBroadcastTargets(bean, eventBean, publishContext);
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
                                    context.computingSession == null ? null : context.computingSession.tcpRemoteEndpoint(), toJsonString(context.computedArgs));
                        } catch (Throwable ex) {
                            context.computedPromise.tryFailure(ex);
                            log.error("serverSide event {} {} -> COMPUTE_ARGS ERROR", p.eventName,
                                    context.computingSession == null ? null : context.computingSession.tcpRemoteEndpoint(), ex);
                        }
                    }
                    break;
            }
        })) {
            return;
        }
        if (tryAs(e.getValue(), MetadataMessage.class, p -> session.attr(HANDSHAKE_META_KEY, p))) {
            log.debug("Handshake: {}", toJsonString(e.getValue()));
            return;
        }

        MethodMessage pack = (MethodMessage) e.getValue();
        Executor executor = bean.config.getExecutor();
        if (M_PING.equals(pack.methodName)) {
            if (executor != null && bean.config.isExecutorForPing()) {
                executor.execute(() -> replyPing(session, pack));
            } else {
                replyPing(session, pack);
            }
            return;
        }
        if (executor == null) {
            invokeAndReply(contractInstance, s, session, pack);
        } else {
            executor.execute(() -> invokeAndReply(contractInstance, s, session, pack));
        }
    }

    private static void replyPing(HybridSession session, MethodMessage pack) {
        pack.returnValue = Boolean.TRUE;
        session.send(pack, RemotingHybridOptions.response(pack));
    }

    private static void invokeAndReply(Object contractInstance, HybridServer s, HybridSession session, MethodMessage pack) {
        try {
            pack.returnValue = Sys.callLog(contractInstance.getClass(), pack.methodName,
                    () -> String.format("Server %s.%s [%s -> %s]", contractInstance.getClass().getSimpleName(), pack.methodName,
                            s.getConfig().getTcpServerConfig().getListenPort(), Sockets.toString(session.tcpRemoteEndpoint())),
                    pack.parameters, () -> RemotingContext.invoke(() -> {
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
        session.send(pack, RemotingHybridOptions.response(pack));
    }

    private static void cleanupSubscriptions(ServerBean bean, HybridSession session) {
        if (session == null) {
            return;
        }
        for (ServerBean.EventBean eventBean : bean.eventBeans.values()) {
            eventBean.subscribe.remove(session);
        }
    }

    private static List<HybridSession> collectBroadcastTargets(ServerBean bean, ServerBean.EventBean eventBean,
            ServerBean.EventContext context) {
        List<Integer> allow = bean.config.getEventBroadcastVersions();
        List<HybridSession> targets = new ArrayList<HybridSession>(eventBean.subscribe.size());
        for (HybridSession client : eventBean.subscribe) {
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

        HybridSession computingSession = selectComputingSession(bean.config, eventBean);
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

    private static HybridSession selectComputingSession(RpcServerConfig config, ServerBean.EventBean eventBean) {
        Linq<HybridSession> subscribes = Linq.from(eventBean.subscribe).where(x -> x.isConnected() && x.attr(HANDSHAKE_META_KEY) != null);
        if (config.getEventComputeVersion() == RpcServerConfig.EVENT_LATEST_COMPUTE) {
            return subscribes.groupBy(x -> x.<MetadataMessage>attr(HANDSHAKE_META_KEY).getEventVersion(), (p1, p2) -> {
                int index = ThreadLocalRandom.current().nextInt(0, p2.count());
                return p2.skip(index).first();
            }).orderByDescending(x -> x.<MetadataMessage>attr(HANDSHAKE_META_KEY).getEventVersion()).firstOrDefault();
        }
        return subscribes.where(x -> x.<MetadataMessage>attr(HANDSHAKE_META_KEY).getEventVersion() == config.getEventComputeVersion())
                .orderByRand().firstOrDefault();
    }

    private static void awaitComputedArgs(ServerBean.EventBean eventBean, ServerBean.EventContext context, EventMessage pack, HybridServer server) {
        try {
            context.computingSession.send(pack, RemotingHybridOptions.CONTROL);
            log.info("serverSide event {} {} -> COMPUTE_ARGS WAIT {}", pack.eventName,
                    context.computingSession.tcpRemoteEndpoint(), server.getConfig().getTcpServerConfig().getConnectTimeoutMillis());
            if (!context.computedPromise.await(server.getConfig().getTcpServerConfig().getConnectTimeoutMillis())) {
                log.warn("serverSide event {} {} -> COMPUTE_ARGS TIMEOUT", pack.eventName, context.computingSession.tcpRemoteEndpoint());
            }
        } catch (Exception ex) {
            context.computedPromise.tryFailure(ex);
            log.error("serverSide event {} {} -> COMPUTE_ARGS ERROR", pack.eventName, context.computingSession.tcpRemoteEndpoint(), ex);
        } finally {
            eventBean.contextMap.remove(pack.computeId, context);
        }
    }

    private static void doSendBroadcast(ServerBean bean, EventMessage p, ServerBean.EventContext context,
            List<HybridSession> targets) {
        if (targets.isEmpty()) {
            return;
        }
        List<Integer> allow = bean.config.getEventBroadcastVersions();
        EventMessage pack = new EventMessage(p.eventName, EventFlag.BROADCAST);
        pack.eventArgs = context.computedArgs;
        tryAs(pack.eventArgs, RemotingEventArgs.class, x -> x.setBroadcastVersions(allow));
        for (HybridSession client : targets) {
            if (!client.isConnected()) {
                continue;
            }
            client.send(pack, RemotingHybridOptions.event(pack));
            log.info("serverSide event {} {} -> BROADCAST", pack.eventName, client.tcpRemoteEndpoint());
        }
    }

    private static void ensureClientCodec(RpcClientConfig<?> config) {
        RemotingCodecFactory factory = config.getCodecFactory();
        if (factory == null) {
            factory = FuryRemotingCodecFactory.createDefault();
            config.setCodecFactory(factory);
        }
        HybridConfig hybridConfig = config.getHybridConfig();
        hybridConfig.getUdpClientConfig().setCodec(factory.newCodec());
        config.getTcpConfig().setCodec(factory.newClientCodec(config));
    }

    private static void ensureServerCodec(RpcServerConfig config) {
        RemotingCodecFactory factory = config.getCodecFactory();
        if (factory == null) {
            factory = FuryRemotingCodecFactory.createDefault();
            config.setCodecFactory(factory);
        }
        HybridConfig hybridConfig = config.getHybridConfig();
        hybridConfig.getUdpClientConfig().setCodec(factory.newCodec());
        config.getTcpConfig().setCodec(factory.newServerCodec(config));
    }
}
