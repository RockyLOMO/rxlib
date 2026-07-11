package org.rx.net.socks.upstream;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.rx.core.Arrays;
import org.rx.core.Cache;
import org.rx.core.CachePolicy;
import org.rx.core.Tasks;
import org.rx.core.cache.MemoryCache;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.SocksConnectionTagRegistry;
import org.rx.net.socks.Socks5ClientHandler;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.socks.TcpWarmPoolKey;
import org.rx.net.support.UpstreamSupport;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
public class SocksTcpUpstream extends Upstream {
    private static final String FAKE_ENDPOINT_CACHE_PREFIX = "socks.fakeEndpoint.";
    private static final String FAKE_ENDPOINT_ROUTE_CACHE_PREFIX = "socks.fakeEndpointRoute.";
    private static final String FAKE_ENDPOINT_ACK_CACHE_PREFIX = "socks.fakeEndpointAck.";
    private static final int FAKE_ENDPOINT_LOCAL_CACHE_SECONDS = SocksRpcContract.FAKE_EXPIRE_SECONDS * 2;
    private static final int FAKE_ENDPOINT_ACK_CACHE_SECONDS = SocksRpcContract.FAKE_EXPIRE_SECONDS - 30;
    private static final int FAKE_ENDPOINT_RETRY_SECONDS = 5;
    private static final int FAKE_ENDPOINT_COLLISION_RETRIES = 3;
    private static final Object[] FAKE_ENDPOINT_LOCKS = new Object[64];
    private static final CompletableFuture<Boolean> REGISTERED = CompletableFuture.completedFuture(Boolean.TRUE);
    private static final CompletableFuture<Boolean> NOT_REGISTERED = CompletableFuture.completedFuture(Boolean.FALSE);
    private static final CompletableFuture<RegistrationOutcome> REGISTERED_OUTCOME =
            CompletableFuture.completedFuture(RegistrationOutcome.REGISTERED);
    private static final CompletableFuture<RegistrationOutcome> FAILED_OUTCOME =
            CompletableFuture.completedFuture(RegistrationOutcome.FAILED);
    private static final ConcurrentMap<String, CompletableFuture<RegistrationOutcome>> REGISTRATIONS = new ConcurrentHashMap<>();
    private static final AttributeKey<UpstreamSupport> ATTR_ACTIVE_SUPPORT =
            AttributeKey.valueOf("socksTcpUpstreamActiveSupport");

    private UpstreamSupport next;
    private boolean destinationPrepared;
    private CompletableFuture<Boolean> registrationFuture = REGISTERED;

    static {
        for (int i = 0; i < FAKE_ENDPOINT_LOCKS.length; i++) {
            FAKE_ENDPOINT_LOCKS[i] = new Object();
        }
    }

    public SocksTcpUpstream(InetSocketAddress dstEp, @NonNull SocksConfig config, @NonNull UpstreamSupport next) {
        super(dstEp, config);
        this.next = next;
    }

    public void reuse(InetSocketAddress dstEp, @NonNull SocksConfig config, @NonNull UpstreamSupport next) {
        super.reuse(dstEp, config);
        this.next = next;
        destinationPrepared = false;
        registrationFuture = REGISTERED;
    }

    @Override
    public void initChannel(Channel channel) {
        prepareDestination();
        initTransport(channel);
        initProxyHandler(channel);
    }

    public AuthenticEndpoint getServerEndpoint() {
        return next.getEndpoint();
    }

    public TcpWarmPoolKey warmPoolKey() {
        return TcpWarmPoolKey.from(next.getEndpoint(), config, config.getReactorName());
    }

    public InetSocketAddress prepareDestination() {
        if (destinationPrepared) {
            return destination;
        }
        destinationPrepared = true;
        SocksRpcContract facade = next.getFacade();
        if (facade == null
                || (!SocksRpcContract.FAKE_IPS.contains(destination.getHostString()) && !SocksRpcContract.FAKE_PORTS.contains(destination.getPort())
                && Sockets.isValidIp(destination.getHostString()))) {
            return destination;
        }

        InetSocketAddress realDestination = destination;
        String dstEpStr = Sockets.toString(realDestination);
        String routeCacheKey = fakeEndpointRouteCacheKey(next, dstEpStr);
        String fakeHost = selectFakeHost(routeCacheKey, dstEpStr);
        setFakeDestination(fakeHost);
        Boolean acknowledged = fakeEndpointAckCache().get(fakeEndpointAckCacheKey(fakeHost));
        registrationFuture = acknowledged == null
                ? ensureFakeEndpointRegistered(facade, routeCacheKey, fakeHost, dstEpStr, 0)
                : acknowledged.booleanValue() ? REGISTERED : NOT_REGISTERED;
        return destination;
    }

    public CompletableFuture<Boolean> prepareDestinationRegistration() {
        prepareDestination();
        return registrationFuture;
    }

    public static String cachedFakeEndpoint(String fakeHost) {
        return fakeEndpointCache().get(fakeEndpointCacheKey(fakeHost));
    }

    public static void invalidateFakeEndpointRegistration(String fakeHost) {
        fakeEndpointAckCache().remove(fakeEndpointAckCacheKey(fakeHost));
    }

    static Cache<String, String> fakeEndpointCache() {
        return Cache.getInstance(MemoryCache.class);
    }

    static Cache<String, Boolean> fakeEndpointAckCache() {
        return Cache.getInstance(MemoryCache.class);
    }

    private static String fakeEndpointCacheKey(String fakeHost) {
        return FAKE_ENDPOINT_CACHE_PREFIX + fakeHost;
    }

    private static String fakeEndpointAckCacheKey(String fakeHost) {
        return FAKE_ENDPOINT_ACK_CACHE_PREFIX + fakeHost;
    }

    private static String fakeEndpointRouteCacheKey(UpstreamSupport support, String endpoint) {
        AuthenticEndpoint server = support == null ? null : support.getEndpoint();
        return FAKE_ENDPOINT_ROUTE_CACHE_PREFIX + String.valueOf(server == null ? null : server.getEndpoint())
                + '|' + endpoint;
    }

    private static String selectFakeHost(String routeCacheKey, String endpoint) {
        Cache<String, String> cache = fakeEndpointCache();
        Object lock = FAKE_ENDPOINT_LOCKS[routeCacheKey.hashCode() & (FAKE_ENDPOINT_LOCKS.length - 1)];
        synchronized (lock) {
            String fakeHost = cache.get(routeCacheKey);
            if (SocksRpcContract.isFakeHost(fakeHost) && claimLocalMapping(fakeHost, endpoint)) {
                return fakeHost;
            }
            do {
                fakeHost = SocksRpcContract.newFakeHost();
            } while (!claimLocalMapping(fakeHost, endpoint));
            cache.put(routeCacheKey, fakeHost, CachePolicy.absolute(FAKE_ENDPOINT_LOCAL_CACHE_SECONDS));
            return fakeHost;
        }
    }

    private static boolean claimLocalMapping(String fakeHost, String endpoint) {
        Cache<String, String> cache = fakeEndpointCache();
        String key = fakeEndpointCacheKey(fakeHost);
        Object lock = FAKE_ENDPOINT_LOCKS[key.hashCode() & (FAKE_ENDPOINT_LOCKS.length - 1)];
        synchronized (lock) {
            String existing = cache.get(key);
            if (existing != null && !existing.equals(endpoint)) {
                return false;
            }
            cache.put(key, endpoint, CachePolicy.absolute(FAKE_ENDPOINT_LOCAL_CACHE_SECONDS));
            return true;
        }
    }

    private void setFakeDestination(String fakeHost) {
        destination = org.rx.net.Sockets.newUnresolvedEndpoint(fakeHost,
                Arrays.randomNext(SocksRpcContract.FAKE_PORT_OBFS));
    }

    private CompletableFuture<Boolean> ensureFakeEndpointRegistered(SocksRpcContract facade, String routeCacheKey,
            String fakeHost, String endpoint, int collisionAttempt) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        registerFakeEndpointCandidate(facade, fakeHost, endpoint).whenComplete((outcome, error) -> {
            if (error != null || outcome == RegistrationOutcome.FAILED) {
                result.complete(Boolean.FALSE);
                return;
            }
            if (outcome == RegistrationOutcome.REGISTERED) {
                setFakeDestination(fakeHost);
                result.complete(Boolean.TRUE);
                return;
            }
            releaseCollidingLocalMapping(routeCacheKey, fakeHost, endpoint);
            if (collisionAttempt >= FAKE_ENDPOINT_COLLISION_RETRIES) {
                log.warn("Fake endpoint token collision retries exhausted endpoint={}", endpoint);
                result.complete(Boolean.FALSE);
                return;
            }
            String nextFakeHost = selectFakeHost(routeCacheKey, endpoint);
            setFakeDestination(nextFakeHost);
            ensureFakeEndpointRegistered(facade, routeCacheKey, nextFakeHost, endpoint, collisionAttempt + 1)
                    .whenComplete((registered, retryError) -> {
                        if (retryError != null) {
                            result.completeExceptionally(retryError);
                        } else {
                            result.complete(registered);
                        }
                    });
        });
        return result;
    }

    private static void releaseCollidingLocalMapping(String routeCacheKey, String fakeHost, String endpoint) {
        Cache<String, String> cache = fakeEndpointCache();
        String endpointCacheKey = fakeEndpointCacheKey(fakeHost);
        Object lock = FAKE_ENDPOINT_LOCKS[endpointCacheKey.hashCode() & (FAKE_ENDPOINT_LOCKS.length - 1)];
        synchronized (lock) {
            if (endpoint.equals(cache.get(endpointCacheKey))) {
                cache.remove(endpointCacheKey);
            }
        }
        if (fakeHost.equals(cache.get(routeCacheKey))) {
            cache.remove(routeCacheKey);
        }
        fakeEndpointAckCache().remove(fakeEndpointAckCacheKey(fakeHost));
    }

    private static CompletableFuture<RegistrationOutcome> registerFakeEndpointCandidate(SocksRpcContract facade,
            String fakeHost, String endpoint) {
        String ackCacheKey = fakeEndpointAckCacheKey(fakeHost);
        Cache<String, Boolean> ackCache = fakeEndpointAckCache();
        Boolean acknowledged = ackCache.get(ackCacheKey);
        if (acknowledged != null) {
            return acknowledged.booleanValue() ? REGISTERED_OUTCOME : FAILED_OUTCOME;
        }

        CompletableFuture<RegistrationOutcome> promise = new CompletableFuture<>();
        CompletableFuture<RegistrationOutcome> existing = REGISTRATIONS.putIfAbsent(ackCacheKey, promise);
        if (existing != null) {
            return existing;
        }

        try {
            Tasks.runAsync(() -> facade.fakeEndpoint(fakeHost, endpoint, SocksRpcContract.rpcToken()))
                    .whenComplete((result, error) -> {
                        RegistrationOutcome outcome;
                        if (error != null) {
                            outcome = RegistrationOutcome.FAILED;
                            ackCache.put(ackCacheKey, Boolean.FALSE,
                                    CachePolicy.absolute(FAKE_ENDPOINT_RETRY_SECONDS));
                            logFakeEndpointRegistrationFailure(fakeHost, endpoint, error);
                        } else if (BooleanUtils.isTrue(result)) {
                            outcome = RegistrationOutcome.REGISTERED;
                            ackCache.put(ackCacheKey, Boolean.TRUE,
                                    CachePolicy.absolute(FAKE_ENDPOINT_ACK_CACHE_SECONDS));
                        } else {
                            outcome = RegistrationOutcome.COLLISION;
                            log.warn("Fake endpoint token collision fakeHost={} endpoint={}", fakeHost, endpoint);
                        }
                        REGISTRATIONS.remove(ackCacheKey, promise);
                        promise.complete(outcome);
                    });
        } catch (Exception error) {
            ackCache.put(ackCacheKey, Boolean.FALSE, CachePolicy.absolute(FAKE_ENDPOINT_RETRY_SECONDS));
            logFakeEndpointRegistrationFailure(fakeHost, endpoint, error);
            REGISTRATIONS.remove(ackCacheKey, promise);
            promise.complete(RegistrationOutcome.FAILED);
        }
        return promise;
    }

    private static void logFakeEndpointRegistrationFailure(String fakeHost, String endpoint, Throwable error) {
        log.warn("Fake endpoint async registration failed fakeHost={} endpoint={} cause={} message={}",
                fakeHost, endpoint, error.getClass().getName(), error.getMessage());
        if (log.isDebugEnabled()) {
            log.debug("Fake endpoint async registration full failure fakeHost={} endpoint={}",
                    fakeHost, endpoint, error);
        }
    }

    private enum RegistrationOutcome {
        REGISTERED,
        COLLISION,
        FAILED
    }

    public void initTransport(Channel channel) {
        bindActiveConnection(channel);
        Sockets.addTcpClientHandler(channel, config, next.getEndpoint().getInetEndpoint());
        String trafficUser = next.getEndpoint().getParameters().get(SocksConnectionTagRegistry.PARAM_NAME);
        if (trafficUser != null) {
            // 内部无鉴权链路通过连接本地地址回绑统计用户，不走热路径。
            SocksConnectionTagRegistry.bindOnActive(channel, trafficUser);
        }
    }

    public void initProxyHandler(Channel channel) {
        AuthenticEndpoint svrEp = next.getEndpoint();
        Socks5ClientHandler proxyHandler = new Socks5ClientHandler(svrEp.getConnectEndpoint(), svrEp.getUsername(), svrEp.getPassword());
        proxyHandler.setConnectTimeoutMillis(config.getConnectTimeoutMillis());
        channel.pipeline().addLast(proxyHandler);
    }

    public void bindActiveConnection(Channel channel) {
        if (channel == null || next == null) {
            return;
        }
        if (channel.attr(ATTR_ACTIVE_SUPPORT).setIfAbsent(next) != null) {
            return;
        }
        next.retainConnection();
        channel.closeFuture().addListener(f -> next.releaseConnection());
    }

    @Override
    public SocketAddress connectAddressHint() {
        return next.getEndpoint().getConnectEndpoint();
    }
}
