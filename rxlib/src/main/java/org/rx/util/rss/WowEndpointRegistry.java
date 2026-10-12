package org.rx.util.rss;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.rx.net.http.HttpServer;
import org.rx.net.http.ServerRequest;
import org.rx.net.http.ServerResponse;
import org.rx.net.transport.hybrid.HybridSession;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** One selected game host. All writes are authenticated RPC observations, never HTTP input. */
public final class WowEndpointRegistry implements HttpServer.Handler {
    public static final String RPC_METHOD = "observeWowEndpoint";
    public static final String OBSERVED_IP_METHOD = "getObservedPublicIp";
    public static final java.util.Set<String> SENSITIVE_METHODS = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList(RPC_METHOD, OBSERVED_IP_METHOD)));
    private final String clientId;
    private final byte[] writerKey, queryKey;
    private final long maxAgeMillis;
    private final LongSupplier clock;
    private final IdentityHashMap<HybridSession, Long> generations = new IdentityHashMap<>();
    private long generation, version;
    private final AtomicReference<Snapshot> current = new AtomicReference<>();

    static final class Snapshot {
        final HybridSession session;
        final String ip, version;
        final long observedAt, generation;
        final boolean ready;

        Snapshot(HybridSession session, String ip, long observedAt, long generation, String version, boolean ready) {
            this.session = session;
            this.ip = ip;
            this.observedAt = observedAt;
            this.generation = generation;
            this.version = version;
            this.ready = ready;
        }
    }

    public WowEndpointRegistry(String clientId, String writerKey, String queryKey, long maxAgeMillis) {
        this(clientId, writerKey, queryKey, maxAgeMillis, System::currentTimeMillis);
    }

    WowEndpointRegistry(String clientId, String writerKey, String queryKey, long maxAgeMillis, LongSupplier clock) {
        this.clientId = clientId == null ? "" : clientId;
        this.writerKey = bytes(writerKey);
        this.queryKey = bytes(queryKey);
        this.maxAgeMillis = Math.max(1000, maxAgeMillis);
        this.clock = clock;
    }

    public static WowEndpointRegistry fromEnvironment() {
        return new WowEndpointRegistry(System.getenv("RSS_WOW_CLIENT_ID"), System.getenv("RSS_WOW_WRITER_KEY"),
                System.getenv("RSS_WOW_QUERY_KEY"), 45000);
    }

    private static byte[] bytes(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }

    private boolean enabled() {
        return !clientId.isEmpty() && writerKey.length >= 32 && queryKey.length >= 32
                && !MessageDigest.isEqual(writerKey, queryKey);
    }

    /** Called only after RSS RPC token + stateful handshake verification. Never performs DNS. */
    synchronized boolean observe(String identity, String credential, HybridSession session, String readyIp, boolean ready) {
        return observe(identity, credential, session, readyIp, ready, false);
    }

    synchronized String observePeer(String identity, String credential, HybridSession session) {
        if (!observe(identity, credential, session, null, false, true)) return null;
        return current.get().ip;
    }

    private boolean observe(String identity, String credential, HybridSession session, String readyIp, boolean ready, boolean preserveReadiness) {
        if (!enabled() || !clientId.equals(identity) || !MessageDigest.isEqual(writerKey, bytes(credential))
                || session == null || !session.isConnected()) return false;
        InetSocketAddress peer = session.tcpRemoteEndpoint();
        InetAddress address = peer == null ? null : peer.getAddress();
        if (!publicIpv4(address)) return false;
        long now = clock.getAsLong();
        Iterator<HybridSession> stale = generations.keySet().iterator();
        while (stale.hasNext()) if (!stale.next().isConnected()) stale.remove();
        Long order = generations.get(session);
        if (order == null) {
            if (generations.size() >= 16) return false;
            order = ++generation;
            generations.put(session, order);
        }
        Snapshot old = current.get();
        // An old still-alive socket must not overwrite a newer authenticated connection's IP.
        if (old != null && order < old.generation) return false;
        String ip = address.getHostAddress();
        String epoch = old != null && old.ip.equals(ip) ? old.version : Long.toString(version = Math.max(version + 1, now));
        boolean acknowledged = old != null && old.ip.equals(ip) && (preserveReadiness ? old.ready : ready && ip.equals(readyIp));
        current.set(new Snapshot(session, ip, now, order, epoch, acknowledged));
        return true;
    }

    static boolean publicIpv4(InetAddress address) {
        return org.rx.net.Sockets.isGlobalIpv4(address);
    }

    @Override
    public HttpMethod[] method() { return new HttpMethod[]{HttpMethod.GET}; }

    @Override
    public void handle(ServerRequest request, ServerResponse response) {
        response.getHeaders().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        if (!enabled()) { failure(response, 503, "disabled"); return; }
        String authorization = request.getHeaders().get(HttpHeaderNames.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")
                || !MessageDigest.isEqual(queryKey, bytes(authorization.substring(7)))) {
            failure(response, 401, "unauthorized"); return;
        }
        Snapshot snapshot = current.get();
        long now = clock.getAsLong();
        if (snapshot == null || !snapshot.session.isConnected() || now < snapshot.observedAt
                || now - snapshot.observedAt > maxAgeMillis) {
            failure(response, 503, "offline"); return;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("ok", true); value.put("ip", snapshot.ip);
        value.put("auth_port", 3724); value.put("world_port", 8085);
        value.put("observed_at", Instant.ofEpochMilli(snapshot.observedAt).toString());
        value.put("online", true); value.put("version", snapshot.version);
        value.put("ready", snapshot.ready);
        response.jsonBody(value);
    }

    private static void failure(ServerResponse response, int code, String error) {
        response.setStatus(HttpResponseStatus.valueOf(code));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("ok", false); value.put("online", false); value.put("error", error);
        response.jsonBody(value);
    }
}
