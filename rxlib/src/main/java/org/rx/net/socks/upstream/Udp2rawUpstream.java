package org.rx.net.socks.upstream;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AttributeKey;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Tasks;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.io.Bytes;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.SocksConnectionTagRegistry;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.socks.SocksUserTraffic;
import org.rx.net.socks.TrafficUser;
import org.rx.net.socks.UdpCompressConfig;
import org.rx.net.socks.UdpCompressStats;
import org.rx.net.socks.Udp2rawAuthMode;
import org.rx.net.socks.Udp2rawAuthenticator;
import org.rx.net.socks.Udp2rawCodec;
import org.rx.net.socks.Udp2rawFrame;
import org.rx.net.socks.Udp2rawFrameType;
import org.rx.net.socks.Udp2rawOpenRequest;
import org.rx.net.socks.Udp2rawOpenResult;
import org.rx.net.socks.Udp2rawPayloadSupport;
import org.rx.net.socks.Udp2rawSeqWindow;
import org.rx.net.socks.UdpManager;
import org.rx.net.socks.UdpRedundantConfig;
import org.rx.net.socks.UdpRedundantMode;
import org.rx.net.socks.UdpRedundantMultiplierResolver;
import org.rx.net.socks.UdpRedundantSupport;
import org.rx.net.socks.UdpRedundantStats;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;

import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class Udp2rawUpstream extends Upstream {
    private static final String METRIC_PREFIX = "socks.udp2raw";
    private static final AttributeKey<ConcurrentMap<TunnelKey, TunnelState>> ATTR_TUNNEL =
            AttributeKey.valueOf("udp2rawUpstreamTunnel");
    private static final AttributeKey<ConcurrentMap<TunnelKey, CompletableFuture<TunnelState>>> ATTR_TUNNEL_INIT =
            AttributeKey.valueOf("udp2rawUpstreamTunnelInit");

    private final UpstreamSupport next;
    private final TunnelKey tunnelKey;

    public Udp2rawUpstream(UnresolvedEndpoint dstEp, @NonNull SocksConfig config, @NonNull UpstreamSupport next) {
        super(dstEp, config);
        this.next = next;
        this.tunnelKey = new TunnelKey(next.getEndpoint());
    }

    public AuthenticEndpoint getServerEndpoint() {
        return next.getEndpoint();
    }

    public Object tunnelAffinity() {
        return tunnelKey;
    }

    public InetSocketAddress getUdpEntryAddress(Channel channel) {
        TunnelState state = activeState(channel);
        return state != null ? state.udpTransportAddress : null;
    }

    public boolean ownsUdpEntryAddress(Channel channel, InetSocketAddress sender) {
        TunnelState state = activeState(channel);
        return state != null && sameAddress(state.udpTransportAddress, sender);
    }

    @Override
    public void initChannel(Channel channel) {
        initChannelAsync(channel);
    }

    @Override
    public CompletableFuture<Void> initChannelAsync(Channel channel) {
        TunnelState active = activeState(channel);
        if (active != null) {
            return CompletableFuture.completedFuture(null);
        }

        ConcurrentMap<TunnelKey, CompletableFuture<TunnelState>> initMap = initMap(channel);
        CompletableFuture<TunnelState> created = new CompletableFuture<>();
        CompletableFuture<TunnelState> existing = initMap.putIfAbsent(tunnelKey, created);
        if (existing != null) {
            return existing.thenApply(v -> null);
        }

        Tasks.runAsync(() -> initializeTunnelOffLoop(channel, created));
        return created.thenApply(v -> null);
    }

    public boolean writeSocks5Request(Channel relay, ByteBuf payload,
            InetSocketAddress clientSource, UnresolvedEndpoint dstEp,
            SocksContext context, boolean retained) {
        TunnelState state = activeState(relay);
        if (state == null) {
            if (retained) {
                Bytes.release(payload);
            }
            DiagnosticMetrics.record(METRIC_PREFIX + ".drop.count", 1D, "reason=tunnel-not-ready");
            return false;
        }

        int trafficBytes = payload.readableBytes();
        ByteBuf body = retained ? payload : payload.retain();
        ByteBuf compressed = null;
        ByteBuf encoded = null;
        ByteBuf authTag = null;
        try {
            ConnState conn = state.conn(clientSource, dstEp);
            boolean firstPacket = conn.isFirstPacket();
            UdpRedundantConfig requestRedundant = UdpRedundantSupport.udp2rawConfigForRequest(
                    state.redundantConfig, state.redundantMode);
            Udp2rawFrame frame = Udp2rawFrame.data(state.sessionHi, state.sessionLo,
                    conn.connId, conn.nextRequestSeq());
            int flags = 0;
            if (firstPacket) {
                flags |= Udp2rawCodec.FLAG_NEW_CONN | Udp2rawCodec.FLAG_HAS_CLIENT | Udp2rawCodec.FLAG_HAS_DST;
                frame.setClientSource(clientSource);
                frame.setDestination(dstEp);
            }
            compressed = Udp2rawPayloadSupport.compress(relay.alloc(), body, state.compressConfig,
                    state.compressStats, state.udpTransportAddress, "request");
            if (compressed != null) {
                flags |= Udp2rawCodec.FLAG_COMPRESSED;
                body.release();
                body = compressed;
                compressed = null;
            }
            if (Udp2rawPayloadSupport.isRedundantEnabled(requestRedundant)) {
                flags |= Udp2rawCodec.FLAG_REDUNDANT;
            }
            if (Udp2rawAuthenticator.requiresAuth(state.authMode, firstPacket, flags)) {
                flags |= Udp2rawCodec.FLAG_AUTH_TAG;
            }
            frame.setFlags(flags);
            if ((flags & Udp2rawCodec.FLAG_AUTH_TAG) != 0) {
                authTag = Udp2rawAuthenticator.sign(relay.alloc(), state.sessionSecret, frame, body);
                frame.setAuthTag(authTag);
            }
            encoded = Udp2rawCodec.encode(relay.alloc(), frame, body);
            body = null;

            SocksUserTraffic.recordWrite(relay, context, trafficBytes, 1L);
            Sockets.UdpWriteResult result = Udp2rawPayloadSupport.writeEncoded(relay, encoded,
                    state.udpTransportAddress, requestRedundant, state.redundantStats,
                    state.redundantResolver, "flow=request");
            encoded = null;
            if (result != Sockets.UdpWriteResult.ACCEPTED) {
                log.warn("udp2raw drop request to {} for {} result={}", state.udpTransportAddress, dstEp, result);
                return false;
            }
            if (firstPacket) {
                conn.markFirstPacketSent();
            }
            state.touch();
            return true;
        } catch (Throwable e) {
            Bytes.release(body);
            Bytes.release(compressed);
            Bytes.release(encoded);
            DiagnosticMetrics.record(METRIC_PREFIX + ".drop.count", 1D, "reason=request-encode-fail");
            throw e;
        } finally {
            Bytes.release(authTag);
        }
    }

    public DatagramPacket buildRequestPacket(Channel relay, ByteBuf payload,
            InetSocketAddress clientSource, UnresolvedEndpoint dstEp) {
        TunnelState state = activeState(relay);
        if (state == null) {
            DiagnosticMetrics.record(METRIC_PREFIX + ".drop.count", 1D, "reason=tunnel-not-ready");
            return null;
        }

        ByteBuf body = payload;
        ByteBuf compressed = null;
        ByteBuf encoded = null;
        ByteBuf authTag = null;
        try {
            ConnState conn = state.conn(clientSource, dstEp);
            boolean firstPacket = conn.isFirstPacket();
            UdpRedundantConfig requestRedundant = UdpRedundantSupport.udp2rawConfigForRequest(
                    state.redundantConfig, state.redundantMode);
            Udp2rawFrame frame = Udp2rawFrame.data(state.sessionHi, state.sessionLo,
                    conn.connId, conn.nextRequestSeq());
            int flags = 0;
            if (firstPacket) {
                flags |= Udp2rawCodec.FLAG_NEW_CONN | Udp2rawCodec.FLAG_HAS_CLIENT | Udp2rawCodec.FLAG_HAS_DST;
                frame.setClientSource(clientSource);
                frame.setDestination(dstEp);
            }
            compressed = Udp2rawPayloadSupport.compress(relay.alloc(), body, state.compressConfig,
                    state.compressStats, state.udpTransportAddress, "request");
            if (compressed != null) {
                flags |= Udp2rawCodec.FLAG_COMPRESSED;
                body.release();
                body = compressed;
                compressed = null;
            }
            if (Udp2rawPayloadSupport.isRedundantEnabled(requestRedundant)) {
                flags |= Udp2rawCodec.FLAG_REDUNDANT;
            }
            if (Udp2rawAuthenticator.requiresAuth(state.authMode, firstPacket, flags)) {
                flags |= Udp2rawCodec.FLAG_AUTH_TAG;
            }
            frame.setFlags(flags);
            if ((flags & Udp2rawCodec.FLAG_AUTH_TAG) != 0) {
                authTag = Udp2rawAuthenticator.sign(relay.alloc(), state.sessionSecret, frame, body);
                frame.setAuthTag(authTag);
            }
            encoded = Udp2rawCodec.encode(relay.alloc(), frame, body);
            body = null;
            DatagramPacket packet = new DatagramPacket(encoded, state.udpTransportAddress);
            encoded = null;
            if (firstPacket) {
                conn.markFirstPacketSent();
            }
            state.touch();
            return packet;
        } catch (Throwable e) {
            Bytes.release(body);
            Bytes.release(compressed);
            Bytes.release(encoded);
            DiagnosticMetrics.record(METRIC_PREFIX + ".drop.count", 1D, "reason=request-encode-fail");
            throw e;
        } finally {
            Bytes.release(authTag);
        }
    }

    public boolean writeRequest(Channel relay, ByteBuf payload,
            InetSocketAddress clientSource, UnresolvedEndpoint dstEp, boolean retained) {
        TunnelState state = activeState(relay);
        if (state == null) {
            if (retained) {
                Bytes.release(payload);
            }
            DiagnosticMetrics.record(METRIC_PREFIX + ".drop.count", 1D, "reason=tunnel-not-ready");
            return false;
        }

        ByteBuf body = retained ? payload : payload.retain();
        ByteBuf compressed = null;
        ByteBuf encoded = null;
        ByteBuf authTag = null;
        try {
            ConnState conn = state.conn(clientSource, dstEp);
            boolean firstPacket = conn.isFirstPacket();
            UdpRedundantConfig requestRedundant = UdpRedundantSupport.udp2rawConfigForRequest(
                    state.redundantConfig, state.redundantMode);
            Udp2rawFrame frame = Udp2rawFrame.data(state.sessionHi, state.sessionLo,
                    conn.connId, conn.nextRequestSeq());
            int flags = 0;
            if (firstPacket) {
                flags |= Udp2rawCodec.FLAG_NEW_CONN | Udp2rawCodec.FLAG_HAS_CLIENT | Udp2rawCodec.FLAG_HAS_DST;
                frame.setClientSource(clientSource);
                frame.setDestination(dstEp);
            }
            compressed = Udp2rawPayloadSupport.compress(relay.alloc(), body, state.compressConfig,
                    state.compressStats, state.udpTransportAddress, "request");
            if (compressed != null) {
                flags |= Udp2rawCodec.FLAG_COMPRESSED;
                body.release();
                body = compressed;
                compressed = null;
            }
            if (Udp2rawPayloadSupport.isRedundantEnabled(requestRedundant)) {
                flags |= Udp2rawCodec.FLAG_REDUNDANT;
            }
            if (Udp2rawAuthenticator.requiresAuth(state.authMode, firstPacket, flags)) {
                flags |= Udp2rawCodec.FLAG_AUTH_TAG;
            }
            frame.setFlags(flags);
            if ((flags & Udp2rawCodec.FLAG_AUTH_TAG) != 0) {
                authTag = Udp2rawAuthenticator.sign(relay.alloc(), state.sessionSecret, frame, body);
                frame.setAuthTag(authTag);
            }
            encoded = Udp2rawCodec.encode(relay.alloc(), frame, body);
            body = null;

            Sockets.UdpWriteResult result = Udp2rawPayloadSupport.writeEncoded(relay, encoded,
                    state.udpTransportAddress, requestRedundant, state.redundantStats,
                    state.redundantResolver, "flow=request");
            encoded = null;
            if (result != Sockets.UdpWriteResult.ACCEPTED) {
                log.warn("udp2raw drop request to {} for {} result={}", state.udpTransportAddress, dstEp, result);
                return false;
            }
            if (firstPacket) {
                conn.markFirstPacketSent();
            }
            state.touch();
            return true;
        } catch (Throwable e) {
            Bytes.release(body);
            Bytes.release(compressed);
            Bytes.release(encoded);
            DiagnosticMetrics.record(METRIC_PREFIX + ".drop.count", 1D, "reason=request-encode-fail");
            throw e;
        } finally {
            Bytes.release(authTag);
        }
    }

    public ByteBuf decodeSocks5Response(Channel relay, DatagramPacket in) {
        Udp2rawResponse response = decodeResponse(relay, in);
        if (response == null) {
            return null;
        }
        return UdpManager.socks5Encode(response.payload, response.sourceAddress);
    }

    public Udp2rawResponse decodeResponse(Channel relay, DatagramPacket in) {
        TunnelState state = activeState(relay);
        if (state == null) {
            DiagnosticMetrics.record(METRIC_PREFIX + ".drop.count", 1D, "reason=tunnel-not-ready");
            return null;
        }

        ByteBuf content = in.content();
        Udp2rawFrame frame;
        try {
            frame = Udp2rawCodec.decode(content);
        } catch (Throwable e) {
            DiagnosticMetrics.record(METRIC_PREFIX + ".drop.count", 1D, "reason=bad-response");
            log.warn("udp2raw discard bad response from {}", in.sender(), e);
            return null;
        }
        if (frame.getType() != Udp2rawFrameType.DATA
                || frame.getSessionHi() != state.sessionHi
                || frame.getSessionLo() != state.sessionLo) {
            DiagnosticMetrics.record(METRIC_PREFIX + ".drop.count", 1D, "reason=bad-response-session");
            return null;
        }
        if (!frame.hasFlag(Udp2rawCodec.FLAG_HAS_SRC) || frame.getSourceAddress() == null) {
            DiagnosticMetrics.record(METRIC_PREFIX + ".drop.count", 1D, "reason=missing-response-source");
            return null;
        }

        ConnState conn = state.connById.get(frame.getConnId());
        if (conn == null) {
            DiagnosticMetrics.record(METRIC_PREFIX + ".drop.count", 1D, "reason=unknown-conn");
            return null;
        }
        boolean redundantFrame = frame.hasFlag(Udp2rawCodec.FLAG_REDUNDANT);
        if (redundantFrame) {
            state.recordRedundantReceived();
        }
        if (!conn.responseWindow.checkAndMark(frame.getPacketSeq())) {
            DiagnosticMetrics.record(METRIC_PREFIX + ".redundant.duplicate.drop.count", 1D, "direction=response");
            return null;
        }
        if (redundantFrame) {
            state.recordRedundantUnique("response");
        }
        state.touch();
        ByteBuf payload = content.slice();
        if (frame.hasFlag(Udp2rawCodec.FLAG_COMPRESSED)) {
            ByteBuf decoded = Udp2rawPayloadSupport.decompress(relay.alloc(), payload, "response");
            if (decoded == null) {
                DiagnosticMetrics.record(METRIC_PREFIX + ".drop.count", 1D, "reason=response-decompress-fail");
                return null;
            }
            return new Udp2rawResponse(frame.getSourceAddress(), decoded);
        }
        return new Udp2rawResponse(frame.getSourceAddress(), payload.retain());
    }

    private void initializeTunnelOffLoop(Channel channel, CompletableFuture<TunnelState> future) {
        TunnelState state = null;
        Throwable error = null;
        try {
            state = openTunnel(channel);
        } catch (Throwable e) {
            error = e;
        }

        final TunnelState finalState = state;
        final Throwable finalError = error;
        channel.eventLoop().execute(() -> completeTunnelInit(channel, future, finalState, finalError));
    }

    private TunnelState openTunnel(Channel channel) {
        SocksRpcContract facade = next.getFacade();
        SocksConfig socksConfig = (SocksConfig) config;
        if (facade == null) {
            if (socksConfig.isUdp2rawRequireRpc()) {
                throw new IllegalStateException("udp2raw requires SocksRpcContract facade for " + next.getEndpoint());
            }
            throw new IllegalStateException("udp2raw static PSK mode is not implemented");
        }

        Udp2rawOpenRequest request = new Udp2rawOpenRequest();
        request.setClientId(resolveClientId(socksConfig));
        request.setClientBindAddress(channel.localAddress() instanceof InetSocketAddress
                ? (InetSocketAddress) channel.localAddress() : null);
        request.setMaxSessions(socksConfig.getUdp2rawMaxSessions());
        request.setIdleTimeoutSeconds(socksConfig.getUdp2rawSessionIdleSeconds());
        request.setCompress(socksConfig.getUdpCompress());
        request.setRedundant(socksConfig.getUdpRedundant());
        request.setRedundantMode(socksConfig.getUdp2rawRedundantMode());
        bindTrafficIdentity(channel, request);

        Udp2rawOpenResult result = facade.openUdp2rawTunnel(request, SocksRpcContract.rpcToken());
        if (result == null || !result.isSupported() || !result.isSuccess()) {
            String reason = result == null ? "null-result"
                    : !result.isSupported() ? "unsupported"
                    : result.getErrorCode();
            DiagnosticMetrics.record(METRIC_PREFIX + ".tunnel.open.count", 1D, "result=fail,reason=" + reason);
            throw new IllegalStateException("udp2raw tunnel open failed: " + reason);
        }
        if (result.getSessionSecret() == null || result.getSessionSecret().length == 0) {
            throw new IllegalStateException("udp2raw tunnel open returned empty session secret");
        }
        InetSocketAddress entryAddress = normalizeEntryAddress(result.getUdpEntryAddress());
        if (entryAddress == null) {
            throw new IllegalStateException("udp2raw tunnel open returned empty entry address");
        }
        Udp2rawAuthMode authMode = result.getCapabilities() != null && result.getCapabilities().getAuthMode() != null
                ? result.getCapabilities().getAuthMode()
                : socksConfig.getUdp2rawAuthMode();
        UdpCompressConfig compressConfig = result.getCapabilities() != null && result.getCapabilities().isCompress()
                && socksConfig.isUdpCompressEnabled()
                ? UdpCompressConfig.fromSocksConfig(socksConfig) : null;
        UdpRedundantConfig redundantConfig = result.getCapabilities() != null && result.getCapabilities().isRedundant()
                && Udp2rawPayloadSupport.isRedundantEnabled(UdpRedundantConfig.fromSocksConfig(socksConfig))
                ? UdpRedundantConfig.fromSocksConfig(socksConfig) : null;
        InetSocketAddress transportAddress = next.getUdpClient() != null ? next.getUdpClient() : entryAddress;
        return new TunnelState(facade, result.getTunnelId(), result.getSessionHi(), result.getSessionLo(),
                result.getSessionSecret(), entryAddress, transportAddress, authMode, compressConfig, redundantConfig,
                socksConfig.getUdp2rawRedundantMode(),
                result.getExpireAtMillis());
    }

    private void completeTunnelInit(Channel channel, CompletableFuture<TunnelState> future,
            TunnelState state, Throwable error) {
        CompletableFuture<TunnelState> activeInit = initMap(channel).get(tunnelKey);
        if (activeInit != future) {
            closeState(state, "init-race");
            return;
        }
        initMap(channel).remove(tunnelKey, future);

        TunnelState active = activeState(channel);
        if (active != null) {
            closeState(state, "already-active");
            future.complete(active);
            return;
        }
        if (error != null) {
            future.completeExceptionally(error);
            return;
        }
        if (state == null || !state.isValid()) {
            future.completeExceptionally(new IllegalStateException("udp2raw tunnel state is invalid"));
            return;
        }
        if (!channel.isActive()) {
            closeState(state, "channel-closed");
            future.completeExceptionally(new ClosedChannelException());
            return;
        }

        tunnelMap(channel).put(tunnelKey, state);
        state.retain(next);
        channel.closeFuture().addListener(f -> invalidateState(channel, state, "channel-close"));
        DiagnosticMetrics.record(METRIC_PREFIX + ".tunnel.client.active.count",
                tunnelMap(channel).size(), "action=open");
        future.complete(state);
    }

    private TunnelState activeState(Channel channel) {
        ConcurrentMap<TunnelKey, TunnelState> map = channel.attr(ATTR_TUNNEL).get();
        TunnelState state = map != null ? map.get(tunnelKey) : null;
        if (state == null) {
            return null;
        }
        if (state.isValid()) {
            return state;
        }
        invalidateState(channel, state, "expired");
        return null;
    }

    private void invalidateState(Channel channel, TunnelState state, String reason) {
        if (state == null) {
            return;
        }
        ConcurrentMap<TunnelKey, TunnelState> map = channel.attr(ATTR_TUNNEL).get();
        if (map == null || !map.remove(tunnelKey, state)) {
            return;
        }
        state.releaseActive();
        closeState(state, reason);
        DiagnosticMetrics.record(METRIC_PREFIX + ".tunnel.client.active.count",
                map.size(), "action=close");
    }

    private void closeState(TunnelState state, String reason) {
        if (state == null || state.closed.getAndSet(true)) {
            return;
        }
        if (state.facade == null || state.tunnelId == null) {
            return;
        }
        Tasks.runAsync(() -> {
            try {
                state.facade.closeUdp2rawTunnel(state.tunnelId, SocksRpcContract.rpcToken());
            } catch (Throwable e) {
                log.warn("udp2raw tunnel close failed tunnel={} reason={}", state.tunnelId, reason, e);
            }
        });
    }

    private ConcurrentMap<TunnelKey, TunnelState> tunnelMap(Channel channel) {
        ConcurrentMap<TunnelKey, TunnelState> map = channel.attr(ATTR_TUNNEL).get();
        if (map != null) {
            return map;
        }
        ConcurrentMap<TunnelKey, TunnelState> created = new ConcurrentHashMap<>();
        ConcurrentMap<TunnelKey, TunnelState> old = channel.attr(ATTR_TUNNEL).setIfAbsent(created);
        return old != null ? old : created;
    }

    private ConcurrentMap<TunnelKey, CompletableFuture<TunnelState>> initMap(Channel channel) {
        ConcurrentMap<TunnelKey, CompletableFuture<TunnelState>> map = channel.attr(ATTR_TUNNEL_INIT).get();
        if (map != null) {
            return map;
        }
        ConcurrentMap<TunnelKey, CompletableFuture<TunnelState>> created = new ConcurrentHashMap<>();
        ConcurrentMap<TunnelKey, CompletableFuture<TunnelState>> old =
                channel.attr(ATTR_TUNNEL_INIT).setIfAbsent(created);
        return old != null ? old : created;
    }

    private String resolveClientId(SocksConfig socksConfig) {
        String reactorName = socksConfig.getReactorName();
        return reactorName != null && reactorName.length() > 0 ? reactorName : "udp2raw-client";
    }

    private void bindTrafficIdentity(Channel channel, Udp2rawOpenRequest request) {
        String connectionTag = SocksConnectionTagRegistry.resolve(channel);
        if (hasText(connectionTag)) {
            request.setConnectionTag(connectionTag);
        }

        String trafficUser = null;
        AuthenticEndpoint endpoint = next.getEndpoint();
        if (endpoint != null) {
            trafficUser = endpoint.getParameters().get(SocksConnectionTagRegistry.PARAM_NAME);
        }
        if (!hasText(trafficUser) && channel != null) {
            TrafficUser user = channel.attr(SocksUserTraffic.ATTR_USER).get();
            if (user != null && !user.isAnonymous()) {
                trafficUser = user.getUsername();
            }
        }
        if (hasText(trafficUser)) {
            request.setTrafficUser(trafficUser);
        }
    }

    private InetSocketAddress normalizeEntryAddress(InetSocketAddress entryAddress) {
        if (entryAddress == null) {
            return null;
        }
        if (entryAddress.getAddress() == null || entryAddress.getAddress().isAnyLocalAddress()) {
            InetSocketAddress serverAddress = next.getEndpoint() != null ? next.getEndpoint().getInetEndpoint() : null;
            if (serverAddress != null) {
                if (serverAddress.getAddress() != null) {
                    return new InetSocketAddress(serverAddress.getAddress(), entryAddress.getPort());
                }
                return InetSocketAddress.createUnresolved(serverAddress.getHostString(), entryAddress.getPort());
            }
        }
        return entryAddress;
    }

    private static boolean sameAddress(InetSocketAddress a, InetSocketAddress b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.getPort() != b.getPort()) {
            return false;
        }
        if (a.getAddress() != null && b.getAddress() != null) {
            return a.getAddress().equals(b.getAddress());
        }
        return a.getHostString().equalsIgnoreCase(b.getHostString());
    }

    private static boolean hasText(String value) {
        return value != null && value.length() > 0;
    }

    static final class TunnelState {
        final SocksRpcContract facade;
        final String tunnelId;
        final long sessionHi;
        final long sessionLo;
        final byte[] sessionSecret;
        final InetSocketAddress udpEntryAddress;
        final InetSocketAddress udpTransportAddress;
        final Udp2rawAuthMode authMode;
        final UdpCompressConfig compressConfig;
        final UdpCompressStats compressStats;
        final UdpRedundantConfig redundantConfig;
        final UdpRedundantMode redundantMode;
        final UdpRedundantMultiplierResolver redundantResolver;
        final UdpRedundantStats redundantStats;
        final long expireAtMillis;
        final ConcurrentMap<RouteKey, ConnState> routes = new ConcurrentHashMap<>();
        final ConcurrentMap<Long, ConnState> connById = new ConcurrentHashMap<>();
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicBoolean activeRetained = new AtomicBoolean();
        final AtomicLong nextRedundantAdjustAtMillis = new AtomicLong();
        volatile UpstreamSupport activeSupport;
        volatile long lastActiveAtMillis;

        TunnelState(SocksRpcContract facade, String tunnelId, long sessionHi, long sessionLo,
                byte[] sessionSecret, InetSocketAddress udpEntryAddress, InetSocketAddress udpTransportAddress,
                Udp2rawAuthMode authMode, UdpCompressConfig compressConfig,
                UdpRedundantConfig redundantConfig, UdpRedundantMode redundantMode,
                long expireAtMillis) {
            this.facade = facade;
            this.tunnelId = tunnelId;
            this.sessionHi = sessionHi;
            this.sessionLo = sessionLo;
            this.sessionSecret = sessionSecret;
            this.udpEntryAddress = udpEntryAddress;
            this.udpTransportAddress = udpTransportAddress;
            this.authMode = authMode != null ? authMode : Udp2rawAuthMode.FIRST_PACKET_MAC;
            this.compressConfig = compressConfig;
            this.compressStats = Udp2rawPayloadSupport.isCompressEnabled(compressConfig)
                    ? new UdpCompressStats(compressConfig) : null;
            this.redundantConfig = redundantConfig;
            this.redundantMode = redundantMode != null ? redundantMode : UdpRedundantMode.BIDIRECTIONAL;
            this.redundantResolver = Udp2rawPayloadSupport.isRedundantEnabled(redundantConfig)
                    ? redundantConfig.buildMultiplierResolver() : null;
            this.redundantStats = Udp2rawPayloadSupport.newAdaptiveStats(redundantConfig);
            this.expireAtMillis = expireAtMillis;
            this.lastActiveAtMillis = System.currentTimeMillis();
            if (this.redundantStats != null) {
                nextRedundantAdjustAtMillis.set(this.lastActiveAtMillis
                        + Udp2rawPayloadSupport.REDUNDANT_ADJUST_INTERVAL_MILLIS);
            }
        }

        boolean isValid() {
            return !closed.get() && udpEntryAddress != null && udpTransportAddress != null
                    && (expireAtMillis <= 0L || System.currentTimeMillis() < expireAtMillis);
        }

        ConnState conn(InetSocketAddress clientSource, UnresolvedEndpoint destination) {
            RouteKey key = new RouteKey(clientSource, destination);
            ConnState state = routes.get(key);
            if (state != null) {
                return state;
            }
            for (;;) {
                long connId = ThreadLocalRandom.current().nextLong();
                if (connId == 0L || connById.containsKey(connId)) {
                    continue;
                }
                ConnState created = new ConnState(connId, clientSource, destination);
                ConnState oldByRoute = routes.putIfAbsent(key, created);
                if (oldByRoute != null) {
                    return oldByRoute;
                }
                ConnState oldById = connById.putIfAbsent(connId, created);
                if (oldById == null) {
                    return created;
                }
                routes.remove(key, created);
            }
        }

        void touch() {
            lastActiveAtMillis = System.currentTimeMillis();
        }

        void recordRedundantReceived() {
            if (redundantStats != null) {
                redundantStats.recordReceived();
            }
        }

        void recordRedundantUnique(String direction) {
            if (redundantStats == null) {
                return;
            }
            redundantStats.recordUnique();
            Udp2rawPayloadSupport.adjustAdaptiveStats(redundantStats, nextRedundantAdjustAtMillis, direction);
        }

        void retain(UpstreamSupport support) {
            if (support != null && activeRetained.compareAndSet(false, true)) {
                activeSupport = support;
                support.retainConnection();
            }
        }

        void releaseActive() {
            UpstreamSupport support = activeSupport;
            if (support != null && activeRetained.compareAndSet(true, false)) {
                support.releaseConnection();
                activeSupport = null;
            }
        }
    }

    static final class TunnelKey {
        final String endpoint;
        final int hash;

        TunnelKey(AuthenticEndpoint endpoint) {
            this.endpoint = String.valueOf(endpoint);
            this.hash = this.endpoint.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TunnelKey)) {
                return false;
            }
            TunnelKey that = (TunnelKey) o;
            return endpoint.equals(that.endpoint);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    static final class ConnState {
        final long connId;
        final InetSocketAddress clientSource;
        final UnresolvedEndpoint destination;
        final AtomicLong requestSeq = new AtomicLong();
        final Udp2rawSeqWindow responseWindow = new Udp2rawSeqWindow();
        final AtomicBoolean firstPacketSent = new AtomicBoolean();

        ConnState(long connId, InetSocketAddress clientSource, UnresolvedEndpoint destination) {
            this.connId = connId;
            this.clientSource = clientSource;
            this.destination = destination;
        }

        long nextRequestSeq() {
            return requestSeq.incrementAndGet();
        }

        boolean isFirstPacket() {
            return !firstPacketSent.get();
        }

        void markFirstPacketSent() {
            firstPacketSent.compareAndSet(false, true);
        }
    }

    static final class RouteKey {
        final InetSocketAddress clientSource;
        final UnresolvedEndpoint destination;
        final int hash;

        RouteKey(InetSocketAddress clientSource, UnresolvedEndpoint destination) {
            this.clientSource = clientSource;
            this.destination = destination;
            int h = clientSource != null ? clientSource.hashCode() : 0;
            h = 31 * h + (destination != null ? destination.hashCode() : 0);
            this.hash = h;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof RouteKey)) {
                return false;
            }
            RouteKey that = (RouteKey) o;
            return (clientSource == null ? that.clientSource == null : clientSource.equals(that.clientSource))
                    && (destination == null ? that.destination == null : destination.equals(that.destination));
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    public static final class Udp2rawResponse {
        private final InetSocketAddress sourceAddress;
        private final ByteBuf payload;

        Udp2rawResponse(InetSocketAddress sourceAddress, ByteBuf payload) {
            this.sourceAddress = sourceAddress;
            this.payload = payload;
        }

        public InetSocketAddress getSourceAddress() {
            return sourceAddress;
        }

        public ByteBuf getPayload() {
            return payload;
        }
    }
}
