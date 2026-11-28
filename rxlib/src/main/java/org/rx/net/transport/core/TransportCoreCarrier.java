package org.rx.net.transport.core;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.PendingWriteQueue;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.compression.JdkZlibDecoder;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.net.EgressCodec;
import org.rx.net.Sockets;
import org.rx.net.TransportFlags;
import org.rx.net.rss.RssUdpMtuBudget;
import org.rx.net.rss.tcp.AdaptiveTcpHandler;
import org.rx.net.rss.tcp.AdaptiveUdpPort;
import org.rx.net.socks.Authenticator;
import org.rx.net.socks.RssSocksCarrier;
import org.rx.net.socks.Socks5ClientHandler;
import org.rx.net.socks.Socks5WarmupHandler;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.support.UpstreamSupport;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Shared Transport Core v2 raw-stream carrier ({@code RXTC} v2 + {@link TransportProtocol#FLAG_RAW_STREAM}).
 * <p>
 * Unifies RSS↔RSS private enhanced TCP links (all 4 profiles, optional {@link AdaptiveTcpHandler} with
 * primary/alias UDP hopping &amp; MTU negotiation, single-layer {@link TransportAeadCodec} replacing
 * {@code RssHopAes}, and per-port business {@code zlib 1..9} strictly inside AEAD and above
 * {@link AdaptiveTcpHandler}) onto the single {@code RXTC} v2 handshake, eliminating first-byte ambiguity
 * between physical {@link EgressCodec#AUTO} and inner business compression while leaving standard SOCKS5,
 * Shadowsocks, DNS, and native UDP untouched.
 */
public final class TransportCoreCarrier {
    public static final String HANDLER_NAME = "rxTransportCoreCarrier";
    public static final String AEAD_HANDLER_NAME = "rxTransportAead";
    public static final AttributeKey<Boolean> ATTR_AEAD_AUTHENTICATED = AttributeKey.valueOf("rxTransportCoreAeadAuth");

    private static final String[] PROFILE_TAGS = {"profile=daily", "profile=game", "profile=weak"};
    private static final int DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 10_000;
    private static final int MAX_PENDING_WRITES = 32;
    private static final int MAX_PENDING_BYTES = 64 * 1024;
    private static final int DEFAULT_MAX_INFLATE_BYTES = 1 << 22; // 4 MiB

    private static final class PskHolder {
        static final byte[] VALUE = parsePsk(System.getenv("RSS_TCP_AES_KEY"));
    }

    private TransportCoreCarrier() {
    }

    public static byte[] parsePsk(String base64) {
        if (base64 == null) {
            return null;
        }
        byte[] key = Base64.getDecoder().decode(base64);
        if (key.length != 32) {
            Arrays.fill(key, (byte) 0);
            throw new IllegalArgumentException("RSS_TCP_AES_KEY requires 32 random bytes");
        }
        return key;
    }

    public static boolean isRssTcpPskConfigured() {
        return PskHolder.VALUE != null;
    }

    public static byte[] configuredRssTcpPsk() {
        return PskHolder.VALUE;
    }

    public static boolean isAeadAuthenticated(ChannelPipeline pipeline) {
        return pipeline != null && Boolean.TRUE.equals(pipeline.channel().attr(ATTR_AEAD_AUTHENTICATED).get());
    }

    public static ChannelHandler clientHandler(SocksConfig config) {
        return new ClientHandler(ctx -> resolveClientOptions(ctx, config));
    }

    public static ChannelHandler clientHandler(ClientOptions options) {
        return new ClientHandler(ctx -> options);
    }

    public static ChannelHandler serverHandler(SocksConfig config, boolean requireCoreHandshake) {
        return new ServerHandler(ctx -> resolveServerOptions(ctx, config, requireCoreHandshake));
    }

    public static ChannelHandler serverHandler(ServerOptions options) {
        return new ServerHandler(ctx -> options);
    }

    private static ClientOptions resolveClientOptions(ChannelHandlerContext ctx, SocksConfig sc) {
        ChannelHandler downstream = findDownstreamSocksHandler(ctx);
        boolean wantAead = sc.getTransportFlags() != null && sc.getTransportFlags().has(TransportFlags.RSS_PSK_AES);
        boolean wantCompress = sc.getTransportFlags() != null
                && (sc.getTransportFlags().has(TransportFlags.COMPRESS_READ)
                || sc.getTransportFlags().has(TransportFlags.COMPRESS_WRITE));
        int compLevel = wantCompress
                ? Math.max(1, Math.min(9, sc.getTcpCompressionLevel() > 0 ? sc.getTcpCompressionLevel() : 3))
                : 0;
        boolean adaptive = sc.isAdaptiveTcp();
        int profile = sc.getAdaptiveProfile();
        boolean udpHop = sc.isUdpHop();
        InetSocketAddress adaptiveUdpAddr = null;
        int localMtu = sc.resolveDailyUdpMtu();
        String peerId = "";
        String password = sc.getRssCoreAuthSecret();
        UpstreamSupport upstreamSupport = null;

        if (downstream instanceof Socks5ClientHandler) {
            Socks5ClientHandler h = (Socks5ClientHandler) downstream;
            if (h.username() != null) {
                peerId = h.username();
            }
            if (h.password() != null) {
                password = h.password();
            }
            boolean isConnect = h.getCommandType() == Socks5CommandType.CONNECT;
            adaptive = isConnect && (h.isAdaptiveTcp() || sc.isAdaptiveTcp());
            profile = h.isAdaptiveTcp() ? h.getAdaptiveProfile() : sc.getAdaptiveProfile();
            udpHop = h.isAdaptiveTcp() ? h.isUdpHop() : sc.isUdpHop();
            adaptiveUdpAddr = h.getAdaptiveUdpAddress();
            if (h.getAdaptiveUdpMtu() > 0) {
                localMtu = h.getAdaptiveUdpMtu();
            }
            upstreamSupport = h.getUpstreamSupport();
        } else if (downstream instanceof Socks5WarmupHandler) {
            Socks5WarmupHandler w = (Socks5WarmupHandler) downstream;
            if (w.username() != null) {
                peerId = w.username();
            }
            if (w.password() != null) {
                password = w.password();
            }
            adaptive = w.isAdaptiveTcp() || sc.isAdaptiveTcp();
            profile = w.isAdaptiveTcp() ? w.getAdaptiveProfile() : sc.getAdaptiveProfile();
            udpHop = w.isAdaptiveTcp() ? w.isUdpHop() : sc.isUdpHop();
            adaptiveUdpAddr = w.getAdaptiveUdpAddress();
            if (w.getAdaptiveUdpMtu() > 0) {
                localMtu = w.getAdaptiveUdpMtu();
            }
        }

        adaptive = adaptive && (ctx.channel().remoteAddress() instanceof InetSocketAddress);
        byte[] authSecretBytes = wantAead
                ? (sc.getRssTcpPsk() != null ? sc.getRssTcpPsk() : configuredRssTcpPsk())
                : TransportProtocol.secretBytes(password);
        if (!TransportProtocol.hasAuthSecretBytes(authSecretBytes)) {
            throw new IllegalStateException(wantAead
                    ? "Missing RSS_TCP_AES_KEY for protected RSS TCP connection"
                    : "RSS Transport Core requires non-empty auth secret");
        }
        UpstreamSupport finalSupport = upstreamSupport;
        Consumer<Boolean> healthListener = finalSupport == null ? null : healthy -> {
            if (healthy) {
                finalSupport.recordSuccess(UpstreamSupport.Capability.ADAPTIVE_UDP);
            } else {
                finalSupport.recordFailure(UpstreamSupport.Capability.ADAPTIVE_UDP);
            }
        };
        int timeoutMs = sc.getConnectTimeoutMillis() > 0
                ? Math.max(DEFAULT_HANDSHAKE_TIMEOUT_MILLIS, sc.getConnectTimeoutMillis())
                : DEFAULT_HANDSHAKE_TIMEOUT_MILLIS;
        int maxInflate = sc.effectiveClientEgressConfig().getMaxInflateBytes() > 0
                ? sc.effectiveClientEgressConfig().getMaxInflateBytes()
                : DEFAULT_MAX_INFLATE_BYTES;
        return new ClientOptions(
                peerId, authSecretBytes, wantAead, wantCompress, compLevel,
                adaptive, profile, udpHop, adaptiveUdpAddr, localMtu,
                healthListener, timeoutMs, maxInflate);
    }

    private static ServerOptions resolveServerOptions(ChannelHandlerContext ctx, SocksConfig sc, boolean requireCoreHandshake) {
        SocksProxyServer server = SocksProxyServer.ofChannel(ctx.channel());
        byte[] psk = sc.getRssTcpPsk() != null ? sc.getRssTcpPsk() : configuredRssTcpPsk();
        Authenticator authenticator = server != null ? server.getAuthenticator() : null;
        String fallbackSecret = sc.getRssCoreAuthSecret();
        Function<String, byte[]> secretLookup = peerId -> {
            String secret = authenticator != null ? authenticator.lookupSecret(peerId) : null;
            if ((secret == null || secret.isEmpty()) && fallbackSecret != null && !fallbackSecret.isEmpty()) {
                secret = fallbackSecret;
            }
            return TransportProtocol.secretBytes(secret);
        };
        AdaptiveUdpPort adaptivePort = server != null ? server.getAdaptivePort() : null;
        NonceReplayCache replayCache = server != null ? server.getCoreReplayCache() : null;
        int compLevel = sc.getTcpCompressionLevel() > 0 ? Math.max(1, Math.min(9, sc.getTcpCompressionLevel())) : 3;
        int maxInflate = sc.effectiveServerEgressConfig().getMaxInflateBytes() > 0
                ? sc.effectiveServerEgressConfig().getMaxInflateBytes()
                : DEFAULT_MAX_INFLATE_BYTES;
        boolean fallbackCompressRead = !requireCoreHandshake && sc.getTransportFlags() != null
                && sc.getTransportFlags().has(TransportFlags.COMPRESS_READ);
        boolean fallbackCompressWrite = !requireCoreHandshake && sc.getTransportFlags() != null
                && sc.getTransportFlags().has(TransportFlags.COMPRESS_WRITE);
        return new ServerOptions(
                psk, secretLookup, adaptivePort, sc.isUdpHop(), sc.resolveDailyUdpMtu(),
                compLevel, replayCache, requireCoreHandshake, DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
                maxInflate, fallbackCompressRead, fallbackCompressWrite);
    }

    private static ChannelHandler findDownstreamSocksHandler(ChannelHandlerContext ctx) {
        boolean pastSelf = false;
        for (Map.Entry<String, ChannelHandler> entry : ctx.pipeline()) {
            if (!pastSelf) {
                if (entry.getValue() == ctx.handler()) {
                    pastSelf = true;
                }
                continue;
            }
            if (RssSocksCarrier.TCP_HANDLER.equals(entry.getKey())) {
                continue;
            }
            ChannelHandler h = entry.getValue();
            if (h instanceof Socks5ClientHandler || h instanceof Socks5WarmupHandler) {
                return h;
            }
        }
        return null;
    }

    private static EgressCodec currentEgress(Channel ch) {
        EgressCodec codec = ch.attr(EgressCodec.ATTR_SELECTED).get();
        return codec == null ? EgressCodec.NONE : codec;
    }

    private static void forwardLeftover(ChannelPipeline pipeline, String firstPostHandlerName, ByteBuf leftover) {
        if (leftover == null) {
            return;
        }
        if (!leftover.isReadable() || !pipeline.channel().isOpen()) {
            leftover.release();
            return;
        }
        if (firstPostHandlerName == null) {
            pipeline.fireChannelRead(leftover);
            return;
        }
        List<String> names = pipeline.names();
        int idx = names.indexOf(firstPostHandlerName);
        if (idx < 0) {
            leftover.release();
            return;
        }
        if (idx == 0) {
            pipeline.fireChannelRead(leftover);
            return;
        }
        ChannelHandlerContext prevCtx = pipeline.context(names.get(idx - 1));
        if (prevCtx != null) {
            prevCtx.fireChannelRead(leftover);
        } else {
            leftover.release();
        }
    }

    private static ChannelHandler newBoundedZlibDecoder(int maxInflateBytes) {
        return new JdkZlibDecoder(ZlibWrapper.ZLIB, Math.max(4096, maxInflateBytes)) {
            @Override
            protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
                try {
                    super.decode(ctx, in, out);
                } catch (Exception malformed) {
                    in.skipBytes(in.readableBytes());
                    ctx.close();
                    throw malformed;
                }
            }
        };
    }

    /** Bounded anti-replay cache for 20-byte client handshake nonces (O(1) insert/evict, zero background threads). */
    public static final class NonceReplayCache {
        private static final int DEFAULT_MAX_ENTRIES = 16_384;
        private static final long DEFAULT_TTL_MILLIS = 120_000L;

        private final int maxEntries;
        private final long ttlMillis;
        private final LinkedHashMap<NonceKey, Long> seen;

        public NonceReplayCache() {
            this(DEFAULT_MAX_ENTRIES, DEFAULT_TTL_MILLIS);
        }

        public NonceReplayCache(int maxEntries, long ttlMillis) {
            this.maxEntries = Math.max(64, maxEntries);
            this.ttlMillis = Math.max(1000L, ttlMillis);
            this.seen = new LinkedHashMap<NonceKey, Long>(256, 0.75f, false);
        }

        public boolean recordIfAbsent(byte[] clientNonce) {
            if (clientNonce == null || clientNonce.length != TransportProtocol.CLIENT_NONCE_BYTES) {
                return false;
            }
            long now = System.nanoTime();
            long expireNanos = TimeUnit.MILLISECONDS.toNanos(ttlMillis);
            NonceKey key = new NonceKey(clientNonce.clone());
            synchronized (seen) {
                Iterator<Map.Entry<NonceKey, Long>> it = seen.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<NonceKey, Long> head = it.next();
                    if (now - head.getValue() > expireNanos) {
                        it.remove();
                    } else {
                        break;
                    }
                }
                if (seen.containsKey(key)) {
                    return false;
                }
                while (seen.size() >= maxEntries) {
                    Iterator<NonceKey> evictIt = seen.keySet().iterator();
                    if (evictIt.hasNext()) {
                        evictIt.next();
                        evictIt.remove();
                    } else {
                        break;
                    }
                }
                seen.put(key, now);
                return true;
            }
        }

        private static final class NonceKey {
            private final byte[] bytes;
            private final int hash;

            NonceKey(byte[] bytes) {
                this.bytes = bytes;
                this.hash = Arrays.hashCode(bytes);
            }

            @Override
            public int hashCode() {
                return hash;
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof NonceKey && Arrays.equals(bytes, ((NonceKey) o).bytes);
            }
        }
    }

    public static final class ClientOptions {
        final String peerId;
        final byte[] authSecretBytes;
        final boolean aead;
        final boolean compress;
        final int compressionLevel;
        final boolean adaptiveUdp;
        final int adaptiveProfile;
        final boolean udpHop;
        final InetSocketAddress adaptiveUdpTarget;
        final int localUdpMtu;
        final Consumer<Boolean> udpHealthListener;
        final int handshakeTimeoutMillis;
        final int maxInflateBytes;

        public ClientOptions(String peerId, byte[] authSecretBytes, boolean aead, boolean compress, int compressionLevel,
                             boolean adaptiveUdp, int adaptiveProfile, boolean udpHop, InetSocketAddress adaptiveUdpTarget,
                             int localUdpMtu, Consumer<Boolean> udpHealthListener,
                             int handshakeTimeoutMillis, int maxInflateBytes) {
            this.peerId = peerId == null ? "" : peerId;
            this.authSecretBytes = authSecretBytes;
            this.aead = aead;
            this.compress = compress;
            this.compressionLevel = compress ? Math.max(1, Math.min(9, compressionLevel > 0 ? compressionLevel : 3)) : 0;
            this.adaptiveUdp = adaptiveUdp;
            this.adaptiveProfile = adaptiveProfile;
            this.udpHop = udpHop;
            this.adaptiveUdpTarget = adaptiveUdpTarget;
            this.localUdpMtu = localUdpMtu;
            this.udpHealthListener = udpHealthListener;
            this.handshakeTimeoutMillis = handshakeTimeoutMillis > 0 ? handshakeTimeoutMillis : DEFAULT_HANDSHAKE_TIMEOUT_MILLIS;
            this.maxInflateBytes = maxInflateBytes > 0 ? maxInflateBytes : DEFAULT_MAX_INFLATE_BYTES;
        }
    }

    public static final class ServerOptions {
        final byte[] psk;
        final Function<String, byte[]> secretLookup;
        final AdaptiveUdpPort adaptivePort;
        final boolean serverUdpHop;
        final int serverLocalUdpMtu;
        final int serverCompressionLevel;
        final NonceReplayCache replayCache;
        final boolean requireCoreHandshake;
        final int handshakeTimeoutMillis;
        final int maxInflateBytes;
        final boolean fallbackCompressRead;
        final boolean fallbackCompressWrite;

        public ServerOptions(byte[] psk, Function<String, byte[]> secretLookup, AdaptiveUdpPort adaptivePort,
                             boolean serverUdpHop, int serverLocalUdpMtu, int serverCompressionLevel,
                             NonceReplayCache replayCache, boolean requireCoreHandshake,
                             int handshakeTimeoutMillis, int maxInflateBytes,
                             boolean fallbackCompressRead, boolean fallbackCompressWrite) {
            this.psk = psk;
            this.secretLookup = secretLookup;
            this.adaptivePort = adaptivePort;
            this.serverUdpHop = serverUdpHop;
            this.serverLocalUdpMtu = serverLocalUdpMtu;
            this.serverCompressionLevel = serverCompressionLevel > 0 ? Math.max(1, Math.min(9, serverCompressionLevel)) : 3;
            this.replayCache = replayCache;
            this.requireCoreHandshake = requireCoreHandshake;
            this.handshakeTimeoutMillis = handshakeTimeoutMillis > 0 ? handshakeTimeoutMillis : DEFAULT_HANDSHAKE_TIMEOUT_MILLIS;
            this.maxInflateBytes = maxInflateBytes > 0 ? maxInflateBytes : DEFAULT_MAX_INFLATE_BYTES;
            this.fallbackCompressRead = fallbackCompressRead;
            this.fallbackCompressWrite = fallbackCompressWrite;
        }
    }

    static final class ClientHandler extends ByteToMessageDecoder implements ChannelOutboundHandler {
        private final Function<ChannelHandlerContext, ClientOptions> optionsResolver;
        private ClientOptions opts;
        private long sessionId;
        private byte[] clientNonce;
        private EgressCodec expectedEgress;
        private PendingWriteQueue pending;
        private ScheduledFuture<?> deadline;
        private boolean sentHello;
        private boolean completed;

        ClientHandler(Function<ChannelHandlerContext, ClientOptions> optionsResolver) {
            this.optionsResolver = optionsResolver;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            super.handlerAdded(ctx);
            if (ctx.channel().isActive()) {
                begin(ctx);
            }
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            begin(ctx);
            super.channelActive(ctx);
        }

        private void begin(ChannelHandlerContext ctx) {
            if (sentHello || completed) {
                return;
            }
            sentHello = true;
            try {
                opts = optionsResolver.apply(ctx);
                if (opts == null || !TransportProtocol.hasAuthSecretBytes(opts.authSecretBytes)) {
                    throw new IllegalStateException("TransportCoreCarrier requires authenticated client options");
                }
                if (opts.adaptiveUdp && !RssUdpMtuBudget.valid(opts.localUdpMtu)) {
                    throw new IllegalArgumentException("Invalid adaptive UDP MTU: " + opts.localUdpMtu);
                }
                expectedEgress = currentEgress(ctx.channel());
                sessionId = TransportProtocol.randomSessionId();
                clientNonce = TransportProtocol.randomBytes(TransportProtocol.CLIENT_NONCE_BYTES);
                int flags = TransportProtocol.FLAG_RAW_STREAM | TransportProtocol.FLAG_AUTH;
                if (opts.aead) {
                    flags |= TransportProtocol.FLAG_AEAD;
                }
                if (opts.compress) {
                    flags |= TransportProtocol.FLAG_COMPRESS;
                }
                if (opts.adaptiveUdp) {
                    flags |= TransportProtocol.FLAG_ADAPTIVE_UDP | TransportProtocol.encodeProfileFlag(opts.adaptiveProfile);
                    if (opts.udpHop) {
                        flags |= TransportProtocol.FLAG_UDP_HOP;
                    }
                }
                int wireMtu = opts.adaptiveUdp ? opts.localUdpMtu : 0;
                ByteBuf hello = TransportProtocol.encodeHello(ctx.alloc(), flags, expectedEgress,
                        opts.compressionLevel, wireMtu, sessionId, clientNonce, opts.peerId, opts.authSecretBytes);
                deadline = ctx.executor().schedule(() -> {
                    if (!completed && ctx.channel().isOpen()) {
                        ctx.close();
                    }
                }, opts.handshakeTimeoutMillis, TimeUnit.MILLISECONDS);
                ctx.writeAndFlush(hello).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                if (!ctx.channel().config().isAutoRead()) {
                    ctx.read();
                }
            } catch (Throwable e) {
                fail(ctx, e);
            }
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            if (completed) {
                return;
            }
            try {
                TransportProtocol.HelloAck ack = TransportProtocol.tryDecodeHelloAck(in);
                if (ack == null) {
                    return;
                }
                if ((ack.flags & TransportProtocol.FLAG_RAW_STREAM) == 0
                        || (ack.flags & TransportProtocol.FLAG_AUTH) == 0) {
                    throw new CorruptedFrameException("Transport Core HelloAck missing required RAW_STREAM/AUTH flags");
                }
                if (ack.sessionId != sessionId) {
                    throw new CorruptedFrameException("Transport Core HelloAck sessionId mismatch");
                }
                if (ack.egressCode != expectedEgress.wireCode()) {
                    throw new CorruptedFrameException("Transport Core HelloAck egress mismatch");
                }
                if (!TransportProtocol.verifyHelloAckAuth(opts.authSecretBytes, clientNonce, ack)) {
                    throw new CorruptedFrameException("Transport Core HelloAck authentication failed");
                }
                boolean ackAead = (ack.flags & TransportProtocol.FLAG_AEAD) != 0;
                if (ackAead != opts.aead) {
                    throw new CorruptedFrameException("Transport Core AEAD negotiation mismatch");
                }
                boolean ackCompress = (ack.flags & TransportProtocol.FLAG_COMPRESS) != 0;
                if (ackCompress != opts.compress) {
                    throw new CorruptedFrameException("Transport Core compression negotiation mismatch");
                }
                boolean ackAdaptive = (ack.flags & TransportProtocol.FLAG_ADAPTIVE_UDP) != 0;
                boolean ackUdpHop = (ack.flags & TransportProtocol.FLAG_UDP_HOP) != 0;
                int ackProfile = 0;
                if (ackAdaptive) {
                    if (!opts.adaptiveUdp) {
                        throw new CorruptedFrameException("Unexpected server ADAPTIVE_UDP activation");
                    }
                    ackProfile = TransportProtocol.decodeProfileFlag(ack.flags);
                    if (ackProfile != opts.adaptiveProfile) {
                        throw new CorruptedFrameException("Transport Core adaptive profile mismatch");
                    }
                    if (!opts.udpHop && ackUdpHop) {
                        throw new CorruptedFrameException("Server enabled UDP hop when client disabled it");
                    }
                    if (ack.serverUdpPort <= 0 || ack.serverUdpAliasPort <= 0
                            || (!ackUdpHop && ack.serverUdpPort != ack.serverUdpAliasPort)
                            || !RssUdpMtuBudget.valid(ack.udpMtu)
                            || RssUdpMtuBudget.negotiate(ack.udpMtu, opts.localUdpMtu) != ack.udpMtu) {
                        throw new CorruptedFrameException("Incompatible adaptive UDP endpoints or MTU");
                    }
                } else if (opts.adaptiveUdp) {
                    DiagnosticMetrics.record("rss.tcp.adaptive.negotiationFallback", 1, PROFILE_TAGS[opts.adaptiveProfile]);
                }

                ByteBuf leftover = in.isReadable() ? in.readRetainedSlice(in.readableBytes()) : null;
                boolean transferredLeftover = false;
                try {
                    completed = true;
                    cancelDeadline();

                    ChannelPipeline pipeline = ctx.pipeline();
                    String selfName = ctx.name();
                    String firstPostHandler = null;

                    if (ackAdaptive) {
                        byte[] adaptiveKey = TransportProtocol.deriveAdaptiveKey(
                                opts.authSecretBytes, sessionId, clientNonce, ack.serverNonce);
                        AdaptiveTcpHandler.install(ctx.channel(), sessionId, adaptiveKey, null,
                                ack.serverUdpPort, ack.serverUdpAliasPort, ackProfile, ackUdpHop,
                                opts.adaptiveUdpTarget, ack.udpMtu, opts.udpHealthListener);
                        firstPostHandler = "rssAdaptiveLength";
                    }
                    if (ackAead) {
                        byte[] aeadMaterial = TransportProtocol.deriveAeadKeyMaterial(
                                opts.authSecretBytes, sessionId, clientNonce, ack.serverNonce);
                        try {
                            pipeline.addBefore(selfName, AEAD_HANDLER_NAME, new TransportAeadCodec(true, aeadMaterial));
                        } finally {
                            Arrays.fill(aeadMaterial, (byte) 0);
                        }
                        ctx.channel().attr(ATTR_AEAD_AUTHENTICATED).set(Boolean.TRUE);
                        if (firstPostHandler == null) {
                            firstPostHandler = AEAD_HANDLER_NAME;
                        }
                    }
                    if (ackCompress) {
                        pipeline.addBefore(selfName, Sockets.ZIP_DECODER, newBoundedZlibDecoder(opts.maxInflateBytes));
                        pipeline.addBefore(selfName, Sockets.ZIP_ENCODER,
                                ZlibCodecFactory.newZlibEncoder(ZlibWrapper.ZLIB, opts.compressionLevel));
                        if (firstPostHandler == null) {
                            firstPostHandler = Sockets.ZIP_DECODER;
                        }
                    }

                    // Drain queued outbound writes through newly installed ZIP_ENCODER -> AEAD -> Adaptive -> Egress
                    PendingWriteQueue queued = pending;
                    pending = null;
                    if (queued != null && !queued.isEmpty()) {
                        while (!queued.isEmpty()) {
                            queued.removeAndWrite();
                        }
                        ctx.flush();
                    }

                    if (firstPostHandler == null && leftover != null) {
                        transferredLeftover = true;
                        out.add(leftover);
                    }
                    pipeline.remove(this);
                    if (firstPostHandler != null && leftover != null) {
                        transferredLeftover = true;
                        forwardLeftover(pipeline, firstPostHandler, leftover);
                    }
                } finally {
                    if (leftover != null && !transferredLeftover) {
                        leftover.release();
                    }
                }
            } catch (Exception e) {
                in.skipBytes(in.readableBytes());
                fail(ctx, e);
                throw e;
            }
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (completed) {
                ctx.write(msg, promise);
                return;
            }
            if (!(msg instanceof ByteBuf)) {
                ReferenceCountUtil.release(msg);
                promise.tryFailure(new IllegalStateException("Invalid write during Transport Core handshake"));
                ctx.close();
                return;
            }
            if (pending == null) {
                pending = new PendingWriteQueue(ctx);
            }
            if (pending.size() >= MAX_PENDING_WRITES
                    || pending.bytes() + ((ByteBuf) msg).readableBytes() > MAX_PENDING_BYTES) {
                ReferenceCountUtil.release(msg);
                promise.tryFailure(new IllegalStateException("Transport Core handshake write limit exceeded"));
                ctx.close();
                return;
            }
            pending.add(msg, promise);
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
            if (completed) {
                ctx.flush();
            }
        }

        @Override
        public void bind(ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise) {
            ctx.bind(localAddress, promise);
        }

        @Override
        public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
            ctx.connect(remoteAddress, localAddress, promise);
        }

        @Override
        public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) {
            ctx.disconnect(promise);
        }

        @Override
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
            ctx.close(promise);
        }

        @Override
        public void deregister(ChannelHandlerContext ctx, ChannelPromise promise) {
            ctx.deregister(promise);
        }

        @Override
        public void read(ChannelHandlerContext ctx) {
            ctx.read();
        }

        private void fail(ChannelHandlerContext ctx, Throwable cause) {
            completed = true;
            cancelDeadline();
            failPending(cause);
            ctx.close();
        }

        private void cancelDeadline() {
            ScheduledFuture<?> task = deadline;
            if (task != null) {
                deadline = null;
                task.cancel(false);
            }
        }

        private void failPending(Throwable cause) {
            PendingWriteQueue queued = pending;
            if (queued != null) {
                pending = null;
                queued.removeAndFailAll(cause);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            cancelDeadline();
            failPending(new ClosedChannelException());
            super.channelInactive(ctx);
        }

        @Override
        protected void handlerRemoved0(ChannelHandlerContext ctx) {
            cancelDeadline();
            failPending(new ClosedChannelException());
        }
    }

    static final class ServerHandler extends ByteToMessageDecoder {
        private final Function<ChannelHandlerContext, ServerOptions> optionsResolver;
        private ServerOptions opts;
        private ScheduledFuture<?> deadline;
        private boolean completed;

        ServerHandler(Function<ChannelHandlerContext, ServerOptions> optionsResolver) {
            this.optionsResolver = optionsResolver;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            super.handlerAdded(ctx);
            if (ctx.channel().isActive()) {
                arm(ctx);
            }
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            arm(ctx);
            super.channelActive(ctx);
        }

        private void arm(ChannelHandlerContext ctx) {
            if (opts == null) {
                opts = optionsResolver.apply(ctx);
            }
            if (deadline == null && !completed && opts != null) {
                deadline = ctx.executor().schedule(() -> {
                    if (!completed && ctx.channel().isOpen()) {
                        ctx.close();
                    }
                }, opts.handshakeTimeoutMillis, TimeUnit.MILLISECONDS);
            }
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            if (completed || !in.isReadable()) {
                return;
            }
            if (opts == null) {
                opts = optionsResolver.apply(ctx);
            }
            // Hybrid plain-SOCKS5 + adaptive server mode: bypass cleanly if first byte is not 'R' ("RXTC").
            if (!opts.requireCoreHandshake) {
                int b0 = in.getUnsignedByte(in.readerIndex());
                if (b0 != 'R') {
                    bypassToPlainSocks(ctx, in, out);
                    return;
                }
            }
            try {
                TransportProtocol.Hello hello = TransportProtocol.tryDecodeHello(in);
                if (hello == null) {
                    return;
                }
                if ((hello.flags & TransportProtocol.FLAG_RAW_STREAM) == 0
                        || (hello.flags & TransportProtocol.FLAG_AUTH) == 0) {
                    throw new CorruptedFrameException("Transport Core Hello missing required RAW_STREAM/AUTH flags");
                }
                EgressCodec selectedEgress = currentEgress(ctx.channel());
                if (hello.egressCode != selectedEgress.wireCode()) {
                    throw new CorruptedFrameException("Transport Core Hello egress mismatch");
                }
                boolean wantAead = (hello.flags & TransportProtocol.FLAG_AEAD) != 0;
                byte[] authSecretBytes;
                if (wantAead) {
                    authSecretBytes = opts.psk;
                } else {
                    authSecretBytes = opts.secretLookup != null ? opts.secretLookup.apply(hello.peerId) : null;
                }
                if (!TransportProtocol.verifyHelloAuth(authSecretBytes, hello)) {
                    throw new CorruptedFrameException("Transport Core Hello authentication failed");
                }
                if (opts.replayCache != null && !opts.replayCache.recordIfAbsent(hello.clientNonce)) {
                    throw new CorruptedFrameException("Transport Core Hello nonce replay detected");
                }

                boolean wantAdaptive = (hello.flags & TransportProtocol.FLAG_ADAPTIVE_UDP) != 0;
                int profile = wantAdaptive ? TransportProtocol.decodeProfileFlag(hello.flags) : 0;
                if (wantAdaptive && !RssUdpMtuBudget.valid(hello.udpMtu)) {
                    throw new CorruptedFrameException("Invalid client adaptive UDP MTU: " + hello.udpMtu);
                }
                boolean enableAdaptive = wantAdaptive && opts.adaptivePort != null;
                boolean effectiveHop = enableAdaptive
                        && ((hello.flags & TransportProtocol.FLAG_UDP_HOP) != 0)
                        && opts.serverUdpHop
                        && opts.adaptivePort.isHopping();
                int primaryPort = enableAdaptive ? opts.adaptivePort.port() : 0;
                int aliasPort = enableAdaptive ? (effectiveHop ? opts.adaptivePort.aliasPort() : primaryPort) : 0;
                int negotiatedMtu = enableAdaptive
                        ? RssUdpMtuBudget.negotiate(opts.serverLocalUdpMtu, hello.udpMtu)
                        : 0;

                boolean enableCompress = (hello.flags & TransportProtocol.FLAG_COMPRESS) != 0;
                int compLevel = enableCompress ? opts.serverCompressionLevel : 0;

                int ackFlags = TransportProtocol.FLAG_RAW_STREAM | TransportProtocol.FLAG_AUTH;
                if (wantAead) {
                    ackFlags |= TransportProtocol.FLAG_AEAD;
                }
                if (enableCompress) {
                    ackFlags |= TransportProtocol.FLAG_COMPRESS;
                }
                if (enableAdaptive) {
                    ackFlags |= TransportProtocol.FLAG_ADAPTIVE_UDP | TransportProtocol.encodeProfileFlag(profile);
                    if (effectiveHop) {
                        ackFlags |= TransportProtocol.FLAG_UDP_HOP;
                    }
                }

                byte[] serverNonce = TransportProtocol.randomBytes(TransportProtocol.SERVER_NONCE_BYTES);
                ByteBuf ackBuf = TransportProtocol.encodeHelloAck(ctx.alloc(), ackFlags, selectedEgress, compLevel,
                        negotiatedMtu, primaryPort, aliasPort, hello.sessionId, hello.clientNonce, serverNonce,
                        "", authSecretBytes);

                ByteBuf leftover = in.isReadable() ? in.readRetainedSlice(in.readableBytes()) : null;
                boolean transferredLeftover = false;
                try {
                    completed = true;
                    cancelDeadline();

                    // Write HelloAck BEFORE installing post-handshake codecs so ack traverses physical egress in plaintext.
                    ctx.writeAndFlush(ackBuf).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);

                    ChannelPipeline pipeline = ctx.pipeline();
                    String selfName = ctx.name();
                    String firstPostHandler = null;

                    if (enableAdaptive) {
                        byte[] adaptiveKey = TransportProtocol.deriveAdaptiveKey(
                                authSecretBytes, hello.sessionId, hello.clientNonce, serverNonce);
                        AdaptiveTcpHandler.install(ctx.channel(), hello.sessionId, adaptiveKey, opts.adaptivePort,
                                primaryPort, aliasPort, profile, effectiveHop, null, negotiatedMtu, null);
                        firstPostHandler = "rssAdaptiveLength";
                    }
                    if (wantAead) {
                        byte[] aeadMaterial = TransportProtocol.deriveAeadKeyMaterial(
                                authSecretBytes, hello.sessionId, hello.clientNonce, serverNonce);
                        try {
                            pipeline.addBefore(selfName, AEAD_HANDLER_NAME, new TransportAeadCodec(false, aeadMaterial));
                        } finally {
                            Arrays.fill(aeadMaterial, (byte) 0);
                        }
                        ctx.channel().attr(ATTR_AEAD_AUTHENTICATED).set(Boolean.TRUE);
                        if (firstPostHandler == null) {
                            firstPostHandler = AEAD_HANDLER_NAME;
                        }
                    }
                    if (enableCompress) {
                        pipeline.addBefore(selfName, Sockets.ZIP_DECODER, newBoundedZlibDecoder(opts.maxInflateBytes));
                        pipeline.addBefore(selfName, Sockets.ZIP_ENCODER,
                                ZlibCodecFactory.newZlibEncoder(ZlibWrapper.ZLIB, compLevel));
                        if (firstPostHandler == null) {
                            firstPostHandler = Sockets.ZIP_DECODER;
                        }
                    }

                    if (firstPostHandler == null && leftover != null) {
                        transferredLeftover = true;
                        out.add(leftover);
                    }
                    pipeline.remove(this);
                    if (firstPostHandler != null && leftover != null) {
                        transferredLeftover = true;
                        forwardLeftover(pipeline, firstPostHandler, leftover);
                    }
                } finally {
                    if (leftover != null && !transferredLeftover) {
                        leftover.release();
                    }
                }
            } catch (Exception e) {
                completed = true;
                cancelDeadline();
                in.skipBytes(in.readableBytes());
                ctx.close();
                throw e;
            }
        }

        private void bypassToPlainSocks(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            completed = true;
            cancelDeadline();
            ChannelPipeline pipeline = ctx.pipeline();
            String selfName = ctx.name();
            String firstPost = null;
            if (opts.fallbackCompressRead) {
                pipeline.addBefore(selfName, Sockets.ZIP_DECODER, newBoundedZlibDecoder(opts.maxInflateBytes));
                firstPost = Sockets.ZIP_DECODER;
            }
            if (opts.fallbackCompressWrite) {
                pipeline.addBefore(selfName, Sockets.ZIP_ENCODER,
                        ZlibCodecFactory.newZlibEncoder(ZlibWrapper.ZLIB, opts.serverCompressionLevel));
            }
            if (firstPost == null) {
                ByteBuf leftover = in.readRetainedSlice(in.readableBytes());
                out.add(leftover);
                pipeline.remove(this);
            } else {
                ByteBuf leftover = in.readRetainedSlice(in.readableBytes());
                pipeline.remove(this);
                forwardLeftover(pipeline, firstPost, leftover);
            }
        }

        private void cancelDeadline() {
            ScheduledFuture<?> task = deadline;
            if (task != null) {
                deadline = null;
                task.cancel(false);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            cancelDeadline();
            super.channelInactive(ctx);
        }

        @Override
        protected void handlerRemoved0(ChannelHandlerContext ctx) {
            cancelDeadline();
        }
    }
}
