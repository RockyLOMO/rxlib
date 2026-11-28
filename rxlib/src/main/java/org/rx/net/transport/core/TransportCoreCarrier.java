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
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthResponse;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthStatus;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.net.EgressCodec;
import org.rx.net.HandshakeMode;
import org.rx.net.SecurityMode;
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
    public static final String LIGHTWEIGHT_EXT_HANDLER = "rxRssLightweightExt";
    public static final String LIGHTWEIGHT_ACK_DECODER = "rxRssLightweightAck";
    /** Private SOCKS5 auth method for zero-added-RTT RSS lightweight capability negotiation (0x8c). */
    public static final Socks5AuthMethod METHOD_RSS_LIGHTWEIGHT = new Socks5AuthMethod(0x8c);

    public static final AttributeKey<Boolean> ATTR_AEAD_AUTHENTICATED = AttributeKey.valueOf("rxTransportCoreAeadAuth");
    /** Authenticated Core peerId on non-PSK connections; Socks5PasswordAuthRequestHandler enforces identity match. */
    public static final AttributeKey<String> ATTR_CORE_AUTH_USER = AttributeKey.valueOf("rxTransportCoreAuthUser");
    /** Client SocksConfig attached by Sockets.addTcpClientHandler for lightweight SOCKS5 negotiation. */
    public static final AttributeKey<SocksConfig> ATTR_CLIENT_SOCKS_CONFIG = AttributeKey.valueOf("rxRssClientSocksConfig");
    /** True when full RXTC TransportCoreCarrier.ClientHandler is active on this channel. */
    public static final AttributeKey<Boolean> ATTR_CORE_HANDSHAKE_ACTIVE = AttributeKey.valueOf("rxTransportCoreActive");
    /** True on server channel when METHOD_RSS_LIGHTWEIGHT (0x8c) was selected in SOCKS5 greeting. */
    public static final AttributeKey<Boolean> ATTR_LIGHTWEIGHT_NEGOTIATED = AttributeKey.valueOf("rxRssLightweightNegotiated");
    /** Authenticated user secret bytes temporarily stored between password auth and 0x8c ext completion (zeroed immediately). */
    public static final AttributeKey<byte[]> ATTR_LIGHTWEIGHT_AUTH_SECRET = AttributeKey.valueOf("rxRssLightweightAuthSecret");

    private static final byte LW_MAGIC_0 = 'R';
    private static final byte LW_MAGIC_1 = 'X';
    private static final byte LW_VERSION = 1;
    private static final int LW_FLAG_COMPRESS = 1 << 0;
    private static final int LW_FLAG_ADAPTIVE_UDP = 1 << 1;
    private static final int LW_FLAG_UDP_HOP = 1 << 2;
    private static final int LW_PROFILE_SHIFT = 3;
    private static final int LW_PROFILE_MASK = 0x3 << LW_PROFILE_SHIFT;
    private static final int LW_CLIENT_EXT_BYTES = 2 + 1 + 1 + 1 + 2 + 8 + TransportProtocol.CLIENT_NONCE_BYTES; // 35 B
    private static final int LW_SERVER_ACK_BYTES = 2 + 1 + 1 + 1 + 2 + 2 + 2 + TransportProtocol.SERVER_NONCE_BYTES + TransportProtocol.AUTH_TAG_BYTES; // 43 B

    private static final String[] PROFILE_TAGS = {"profile=daily", "profile=game", "profile=weak"};
    private static final int DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 10_000;
    private static final int MAX_PENDING_WRITES = 32;
    private static final int MAX_PENDING_BYTES = 64 * 1024;
    private static final int DEFAULT_MAX_INFLATE_BYTES = SocksConfig.DEFAULT_MAX_BUSINESS_INFLATE_BYTES;

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
            upstreamSupport = w.getUpstreamSupport();
        }

        adaptive = adaptive && (ctx.channel().remoteAddress() instanceof InetSocketAddress);
        if (wantAead && sc.effectiveSecurityMode() == SecurityMode.NONE) {
            throw new IllegalStateException("Protected RSS TCP connection cannot use SecurityMode.NONE");
        }
        byte[] authSecretBytes = wantAead
                ? (sc.getRssTcpPsk() != null ? sc.getRssTcpPsk() : configuredRssTcpPsk())
                : TransportProtocol.secretBytes(password);
        if (!TransportProtocol.hasAuthSecretBytes(authSecretBytes)) {
            throw new IllegalStateException(wantAead
                    ? "Missing RSS_TCP_AES_KEY for protected RSS TCP connection"
                    : "RSS Transport Core requires non-empty auth secret");
        }
        Consumer<Boolean> healthListener = adaptiveUdpHealthListener(upstreamSupport);
        int timeoutMs = sc.getConnectTimeoutMillis() > 0
                ? Math.max(DEFAULT_HANDSHAKE_TIMEOUT_MILLIS, sc.getConnectTimeoutMillis())
                : DEFAULT_HANDSHAKE_TIMEOUT_MILLIS;
        int maxInflate = sc.getMaxBusinessInflateBytes() > 0
                ? sc.getMaxBusinessInflateBytes()
                : DEFAULT_MAX_INFLATE_BYTES;
        return new ClientOptions(
                peerId, authSecretBytes, wantAead, wantCompress, compLevel,
                adaptive, profile, udpHop, adaptiveUdpAddr, localMtu,
                healthListener, timeoutMs, maxInflate);
    }

    private static Consumer<Boolean> adaptiveUdpHealthListener(UpstreamSupport support) {
        if (support == null) {
            return null;
        }
        return healthy -> {
            if (support.isClosed()) {
                return;
            }
            if (healthy) {
                support.recordSuccess(UpstreamSupport.Capability.ADAPTIVE_UDP);
            } else {
                support.recordFailure(UpstreamSupport.Capability.ADAPTIVE_UDP);
            }
        };
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
        Supplier<NonceReplayCache> replayCacheSupplier = server != null ? server::getCoreReplayCache : null;
        int compLevel = sc.getTcpCompressionLevel() > 0 ? Math.max(1, Math.min(9, sc.getTcpCompressionLevel())) : 3;
        int maxInflate = sc.getMaxBusinessInflateBytes() > 0
                ? sc.getMaxBusinessInflateBytes()
                : DEFAULT_MAX_INFLATE_BYTES;
        boolean rssPrivate = sc.isRssPrivateTransport();
        // On private RSS or adaptive servers in LIGHTWEIGHT mode, business zlib is negotiated explicitly via 0x8c or RXTC (never blindly before SOCKS5).
        boolean fallbackCompressRead = !requireCoreHandshake && !rssPrivate && !sc.isAdaptiveTcp() && sc.getTransportFlags() != null
                && sc.getTransportFlags().has(TransportFlags.COMPRESS_READ);
        boolean fallbackCompressWrite = !requireCoreHandshake && !rssPrivate && !sc.isAdaptiveTcp() && sc.getTransportFlags() != null
                && sc.getTransportFlags().has(TransportFlags.COMPRESS_WRITE);
        return new ServerOptions(
                psk, secretLookup, adaptivePort, sc.isUdpHop(), sc.resolveDailyUdpMtu(),
                compLevel, replayCacheSupplier, requireCoreHandshake, rssPrivate, DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
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
        final Supplier<NonceReplayCache> replayCacheSupplier;
        final boolean requireCoreHandshake;
        final boolean rejectNonSocksOnLightweight;
        final int handshakeTimeoutMillis;
        final int maxInflateBytes;
        final boolean fallbackCompressRead;
        final boolean fallbackCompressWrite;

        public ServerOptions(byte[] psk, Function<String, byte[]> secretLookup, AdaptiveUdpPort adaptivePort,
                             boolean serverUdpHop, int serverLocalUdpMtu, int serverCompressionLevel,
                             NonceReplayCache replayCache, boolean requireCoreHandshake,
                             int handshakeTimeoutMillis, int maxInflateBytes,
                             boolean fallbackCompressRead, boolean fallbackCompressWrite) {
            this(psk, secretLookup, adaptivePort, serverUdpHop, serverLocalUdpMtu, serverCompressionLevel,
                    replayCache == null ? null : () -> replayCache, requireCoreHandshake, false,
                    handshakeTimeoutMillis, maxInflateBytes, fallbackCompressRead, fallbackCompressWrite);
        }

        public ServerOptions(byte[] psk, Function<String, byte[]> secretLookup, AdaptiveUdpPort adaptivePort,
                             boolean serverUdpHop, int serverLocalUdpMtu, int serverCompressionLevel,
                             Supplier<NonceReplayCache> replayCacheSupplier, boolean requireCoreHandshake,
                             boolean rejectNonSocksOnLightweight, int handshakeTimeoutMillis, int maxInflateBytes,
                             boolean fallbackCompressRead, boolean fallbackCompressWrite) {
            this.psk = psk;
            this.secretLookup = secretLookup;
            this.adaptivePort = adaptivePort;
            this.serverUdpHop = serverUdpHop;
            this.serverLocalUdpMtu = serverLocalUdpMtu;
            this.serverCompressionLevel = serverCompressionLevel > 0 ? Math.max(1, Math.min(9, serverCompressionLevel)) : 3;
            this.replayCacheSupplier = replayCacheSupplier;
            this.requireCoreHandshake = requireCoreHandshake;
            this.rejectNonSocksOnLightweight = rejectNonSocksOnLightweight;
            this.handshakeTimeoutMillis = handshakeTimeoutMillis > 0 ? handshakeTimeoutMillis : DEFAULT_HANDSHAKE_TIMEOUT_MILLIS;
            this.maxInflateBytes = maxInflateBytes > 0 ? maxInflateBytes : DEFAULT_MAX_INFLATE_BYTES;
            this.fallbackCompressRead = fallbackCompressRead;
            this.fallbackCompressWrite = fallbackCompressWrite;
        }

        NonceReplayCache resolveReplayCache() {
            return replayCacheSupplier == null ? null : replayCacheSupplier.get();
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
                armIfStrict(ctx);
            }
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            armIfStrict(ctx);
            super.channelActive(ctx);
        }

        private void armIfStrict(ChannelHandlerContext ctx) {
            if (opts == null) {
                opts = optionsResolver.apply(ctx);
            }
            if (opts != null && opts.requireCoreHandshake) {
                arm(ctx);
            }
        }

        private void arm(ChannelHandlerContext ctx) {
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
            // Lightweight/hybrid server mode: bypass immediately when first byte is not 'R' ("RXTC")
            // without ever scheduling a Core deadline timer or allocating NonceReplayCache.
            if (!opts.requireCoreHandshake) {
                int b0 = in.getUnsignedByte(in.readerIndex());
                if (b0 != 'R') {
                    if (opts.rejectNonSocksOnLightweight && b0 != 0x05) {
                        completed = true;
                        cancelDeadline();
                        in.skipBytes(in.readableBytes());
                        ctx.close();
                        throw new CorruptedFrameException("Invalid RSS lightweight protocol prefix: 0x" + Integer.toHexString(b0));
                    }
                    bypassToPlainSocks(ctx, in, out);
                    return;
                }
                arm(ctx);
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
                NonceReplayCache replayCache = opts.resolveReplayCache();
                if (replayCache != null && !replayCache.recordIfAbsent(hello.clientNonce)) {
                    throw new CorruptedFrameException("Transport Core Hello nonce replay detected");
                }
                if (!wantAead && hello.peerId != null && !hello.peerId.isEmpty()) {
                    ctx.channel().attr(ATTR_CORE_AUTH_USER).set(hello.peerId);
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

    // -------------------------------------------------------------------------
    // Zero-Added-RTT RSS Lightweight Negotiation (piggybacked on SOCKS5 0x8c)
    // -------------------------------------------------------------------------

    /**
     * Returns true when a SOCKS5 client channel should offer {@link #METHOD_RSS_LIGHTWEIGHT} (0x8c)
     * to negotiate adaptive UDP and/or business zlib with zero added RTT. Pure plain non-AEAD
     * connections without compression return false and stay 100% standard SOCKS5.
     */
    public static boolean shouldOfferLightweight(Channel channel, boolean adaptiveTcp, boolean hasPasswordAuth) {
        if (channel == null || !hasPasswordAuth) {
            return false;
        }
        if (Boolean.TRUE.equals(channel.attr(ATTR_CORE_HANDSHAKE_ACTIVE).get())) {
            return false;
        }
        SocksConfig sc = channel.attr(ATTR_CLIENT_SOCKS_CONFIG).get();
        if (sc != null && sc.effectiveHandshakeMode() == HandshakeMode.CORE) {
            return false;
        }
        boolean wantCompress = sc != null && sc.getTransportFlags() != null
                && (sc.getTransportFlags().has(TransportFlags.COMPRESS_READ)
                || sc.getTransportFlags().has(TransportFlags.COMPRESS_WRITE));
        boolean wantAdaptive = adaptiveTcp && (channel.remoteAddress() instanceof InetSocketAddress);
        return wantAdaptive || wantCompress;
    }

    public static LightweightClientState beginLightweightClient(Channel channel, String password,
                                                                boolean adaptiveTcp, int adaptiveProfile,
                                                                boolean udpHop, InetSocketAddress adaptiveUdpTarget,
                                                                int localUdpMtu) {
        SocksConfig sc = channel.attr(ATTR_CLIENT_SOCKS_CONFIG).get();
        boolean wantCompress = sc != null && sc.getTransportFlags() != null
                && (sc.getTransportFlags().has(TransportFlags.COMPRESS_READ)
                || sc.getTransportFlags().has(TransportFlags.COMPRESS_WRITE));
        int compLevel = wantCompress
                ? Math.max(1, Math.min(9, sc.getTcpCompressionLevel() > 0 ? sc.getTcpCompressionLevel() : 3))
                : 0;
        boolean wantAdaptive = adaptiveTcp && (channel.remoteAddress() instanceof InetSocketAddress);
        int effectiveMtu = localUdpMtu > 0 ? localUdpMtu : (sc != null ? sc.resolveDailyUdpMtu() : 0);
        if (wantAdaptive && !RssUdpMtuBudget.valid(effectiveMtu)) {
            throw new IllegalArgumentException("Invalid adaptive UDP MTU: " + effectiveMtu);
        }
        byte[] secret = TransportProtocol.secretBytes(password);
        if (!TransportProtocol.hasAuthSecretBytes(secret)) {
            throw new IllegalStateException("RSS lightweight negotiation requires non-empty password secret");
        }
        int maxInflate = sc != null && sc.getMaxBusinessInflateBytes() > 0
                ? sc.getMaxBusinessInflateBytes() : DEFAULT_MAX_INFLATE_BYTES;
        return new LightweightClientState(
                TransportProtocol.randomSessionId(),
                TransportProtocol.randomBytes(TransportProtocol.CLIENT_NONCE_BYTES),
                secret,
                currentEgress(channel),
                wantCompress, compLevel,
                wantAdaptive, adaptiveProfile, udpHop, adaptiveUdpTarget, effectiveMtu, maxInflate);
    }

    public static ByteBuf encodeLightweightClientExt(io.netty.buffer.ByteBufAllocator alloc, LightweightClientState st) {
        int flags = 0;
        if (st.wantCompress) {
            flags |= LW_FLAG_COMPRESS;
        }
        if (st.wantAdaptive) {
            flags |= LW_FLAG_ADAPTIVE_UDP | ((st.adaptiveProfile & 0x3) << LW_PROFILE_SHIFT);
            if (st.udpHop) {
                flags |= LW_FLAG_UDP_HOP;
            }
        }
        ByteBuf buf = alloc.ioBuffer(LW_CLIENT_EXT_BYTES, LW_CLIENT_EXT_BYTES);
        buf.writeByte(LW_MAGIC_0);
        buf.writeByte(LW_MAGIC_1);
        buf.writeByte(LW_VERSION);
        buf.writeByte(flags);
        buf.writeByte(st.compLevel);
        buf.writeShort(st.wantAdaptive ? (st.localUdpMtu & 0xFFFF) : 0);
        buf.writeLong(st.sessionId);
        buf.writeBytes(st.clientNonce, 0, TransportProtocol.CLIENT_NONCE_BYTES);
        return buf;
    }

    public static void completeLightweightClient(ChannelHandlerContext ctx, LightweightAck ack,
                                                 LightweightClientState st, UpstreamSupport upstreamSupport) {
        if (ack == null || st == null) {
            throw new CorruptedFrameException("Missing RSS lightweight ack state");
        }
        try {
            if (!TransportProtocol.verifyLightweightAckMac(st.authSecretBytes, LW_VERSION, ack.flags,
                    st.expectedEgress.wireCode(), ack.serverCompLevel, ack.negotiatedMtu,
                    ack.primaryPort, ack.aliasPort, st.sessionId, st.clientNonce, ack.serverNonce, ack.serverMac)) {
                throw new CorruptedFrameException("RSS lightweight ack HMAC authentication failed");
            }
            boolean ackCompress = (ack.flags & LW_FLAG_COMPRESS) != 0;
            if (ackCompress != st.wantCompress) {
                throw new CorruptedFrameException("RSS lightweight compression negotiation mismatch");
            }
            boolean ackAdaptive = (ack.flags & LW_FLAG_ADAPTIVE_UDP) != 0;
            boolean ackUdpHop = (ack.flags & LW_FLAG_UDP_HOP) != 0;
            int ackProfile = (ack.flags & LW_PROFILE_MASK) >>> LW_PROFILE_SHIFT;
            if (ackAdaptive) {
                if (!st.wantAdaptive) {
                    throw new CorruptedFrameException("Unexpected server lightweight adaptive UDP activation");
                }
                if (ackProfile > 2 || ackProfile != st.adaptiveProfile) {
                    throw new CorruptedFrameException("RSS lightweight adaptive profile mismatch");
                }
                if (!st.udpHop && ackUdpHop) {
                    throw new CorruptedFrameException("Server enabled UDP hop when client disabled it");
                }
                if (ack.primaryPort <= 0 || ack.aliasPort <= 0
                        || (!ackUdpHop && ack.primaryPort != ack.aliasPort)
                        || !RssUdpMtuBudget.valid(ack.negotiatedMtu)
                        || RssUdpMtuBudget.negotiate(ack.negotiatedMtu, st.localUdpMtu) != ack.negotiatedMtu) {
                    throw new CorruptedFrameException("Incompatible lightweight adaptive UDP endpoints or MTU");
                }
            } else if (st.wantAdaptive) {
                DiagnosticMetrics.record("rss.tcp.adaptive.negotiationFallback", 1, PROFILE_TAGS[st.adaptiveProfile]);
            }

            ChannelPipeline pipeline = ctx.pipeline();
            if (ackAdaptive) {
                byte[] adaptiveKey = TransportProtocol.deriveAdaptiveKey(
                        st.authSecretBytes, st.sessionId, st.clientNonce, ack.serverNonce);
                AdaptiveTcpHandler.install(ctx.channel(), st.sessionId, adaptiveKey, null,
                        ack.primaryPort, ack.aliasPort, ackProfile, ackUdpHop,
                        st.adaptiveUdpTarget, ack.negotiatedMtu, adaptiveUdpHealthListener(upstreamSupport));
            }
            if (ackCompress) {
                installBusinessZlibAtCanonicalAnchor(pipeline, st.compLevel, st.maxInflateBytes);
            }
        } finally {
            Arrays.fill(st.authSecretBytes, (byte) 0);
        }
    }

    private static String installBusinessZlibAtCanonicalAnchor(ChannelPipeline pipeline, int compLevel, int maxInflateBytes) {
        ChannelHandler decoder = newBoundedZlibDecoder(maxInflateBytes);
        ChannelHandler encoder = ZlibCodecFactory.newZlibEncoder(ZlibWrapper.ZLIB, compLevel);
        if (pipeline.context("rssAdaptive") != null) {
            pipeline.addAfter("rssAdaptive", Sockets.ZIP_DECODER, decoder);
            pipeline.addAfter(Sockets.ZIP_DECODER, Sockets.ZIP_ENCODER, encoder);
        } else if (pipeline.context(EgressCodec.INNER_ANCHOR) != null) {
            pipeline.addAfter(EgressCodec.INNER_ANCHOR, Sockets.ZIP_DECODER, decoder);
            pipeline.addAfter(Sockets.ZIP_DECODER, Sockets.ZIP_ENCODER, encoder);
        } else if (pipeline.context(RssSocksCarrier.TCP_HANDLER) != null) {
            pipeline.addAfter(RssSocksCarrier.TCP_HANDLER, Sockets.ZIP_DECODER, decoder);
            pipeline.addAfter(Sockets.ZIP_DECODER, Sockets.ZIP_ENCODER, encoder);
        } else {
            pipeline.addFirst(Sockets.ZIP_ENCODER, encoder);
            pipeline.addFirst(Sockets.ZIP_DECODER, decoder);
        }
        return Sockets.ZIP_DECODER;
    }

    public static final class LightweightClientState {
        final long sessionId;
        final byte[] clientNonce;
        final byte[] authSecretBytes;
        final EgressCodec expectedEgress;
        final boolean wantCompress;
        final int compLevel;
        final boolean wantAdaptive;
        final int adaptiveProfile;
        final boolean udpHop;
        final InetSocketAddress adaptiveUdpTarget;
        final int localUdpMtu;
        final int maxInflateBytes;

        LightweightClientState(long sessionId, byte[] clientNonce, byte[] authSecretBytes, EgressCodec expectedEgress,
                               boolean wantCompress, int compLevel, boolean wantAdaptive, int adaptiveProfile,
                               boolean udpHop, InetSocketAddress adaptiveUdpTarget, int localUdpMtu, int maxInflateBytes) {
            this.sessionId = sessionId;
            this.clientNonce = clientNonce;
            this.authSecretBytes = authSecretBytes;
            this.expectedEgress = expectedEgress;
            this.wantCompress = wantCompress;
            this.compLevel = compLevel;
            this.wantAdaptive = wantAdaptive;
            this.adaptiveProfile = adaptiveProfile;
            this.udpHop = udpHop;
            this.adaptiveUdpTarget = adaptiveUdpTarget;
            this.localUdpMtu = localUdpMtu;
            this.maxInflateBytes = maxInflateBytes;
        }
    }

    public static final class LightweightAck {
        final int flags;
        final int serverCompLevel;
        final int negotiatedMtu;
        final int primaryPort;
        final int aliasPort;
        final byte[] serverNonce;
        final byte[] serverMac;

        LightweightAck(int flags, int serverCompLevel, int negotiatedMtu, int primaryPort, int aliasPort,
                       byte[] serverNonce, byte[] serverMac) {
            this.flags = flags;
            this.serverCompLevel = serverCompLevel;
            this.negotiatedMtu = negotiatedMtu;
            this.primaryPort = primaryPort;
            this.aliasPort = aliasPort;
            this.serverNonce = serverNonce;
            this.serverMac = serverMac;
        }
    }

    /** Client-side decoder for the 43-byte HMAC-authenticated RSS lightweight ack following RFC 1929 SUCCESS. */
    public static final class LightweightAckDecoder extends ByteToMessageDecoder {
        private boolean decoded;

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (decoded || !in.isReadable()) {
                return;
            }
            int pos = in.readerIndex();
            if (in.getByte(pos) != LW_MAGIC_0) {
                throw new CorruptedFrameException("Invalid RSS lightweight ack magic[0]");
            }
            if (in.readableBytes() >= 2 && in.getByte(pos + 1) != LW_MAGIC_1) {
                throw new CorruptedFrameException("Invalid RSS lightweight ack magic[1]");
            }
            if (in.readableBytes() >= 3 && in.getByte(pos + 2) != LW_VERSION) {
                throw new CorruptedFrameException("Unsupported RSS lightweight ack version");
            }
            if (in.readableBytes() < LW_SERVER_ACK_BYTES) {
                return;
            }
            in.skipBytes(3);
            int flags = in.readUnsignedByte();
            int serverCompLevel = in.readUnsignedByte();
            int negotiatedMtu = in.readUnsignedShort();
            int primaryPort = in.readUnsignedShort();
            int aliasPort = in.readUnsignedShort();
            byte[] serverNonce = new byte[TransportProtocol.SERVER_NONCE_BYTES];
            in.readBytes(serverNonce);
            byte[] serverMac = new byte[TransportProtocol.AUTH_TAG_BYTES];
            in.readBytes(serverMac);
            decoded = true;
            out.add(new LightweightAck(flags, serverCompLevel, negotiatedMtu, primaryPort, aliasPort, serverNonce, serverMac));
            ctx.pipeline().remove(this);
        }
    }

    /** Server-side decoder for the 35-byte RSS lightweight extension following RFC 1929 password auth. */
    public static final class LightweightServerExtHandler extends ByteToMessageDecoder {
        private static final DefaultSocks5PasswordAuthResponse SUCCESS =
                new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS);
        private boolean completed;

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            if (completed || !in.isReadable()) {
                return;
            }
            int pos = in.readerIndex();
            if (in.getByte(pos) != LW_MAGIC_0) {
                fail(ctx, in, "Invalid RSS lightweight ext magic[0]");
                return;
            }
            if (in.readableBytes() >= 2 && in.getByte(pos + 1) != LW_MAGIC_1) {
                fail(ctx, in, "Invalid RSS lightweight ext magic[1]");
                return;
            }
            if (in.readableBytes() >= 3 && in.getByte(pos + 2) != LW_VERSION) {
                fail(ctx, in, "Unsupported RSS lightweight ext version");
                return;
            }
            if (in.readableBytes() < LW_CLIENT_EXT_BYTES) {
                return;
            }
            byte[] secret = ctx.channel().attr(ATTR_LIGHTWEIGHT_AUTH_SECRET).getAndSet(null);
            try {
                if (!TransportProtocol.hasAuthSecretBytes(secret)) {
                    fail(ctx, in, "RSS lightweight ext arrived without authenticated password secret");
                    return;
                }
                in.skipBytes(3);
                int reqFlags = in.readUnsignedByte();
                in.readUnsignedByte(); // client compLevel
                int clientMtu = in.readUnsignedShort();
                long sessionId = in.readLong();
                byte[] clientNonce = new byte[TransportProtocol.CLIENT_NONCE_BYTES];
                in.readBytes(clientNonce);

                SocksProxyServer server = SocksProxyServer.ofChannel(ctx.channel());
                if (server == null) {
                    fail(ctx, in, "Missing SocksProxyServer");
                    return;
                }
                if (!server.getCoreReplayCache().recordIfAbsent(clientNonce)) {
                    fail(ctx, in, "RSS lightweight nonce replay detected");
                    return;
                }
                SocksConfig sc = server.getConfig();
                boolean wantAdaptive = (reqFlags & LW_FLAG_ADAPTIVE_UDP) != 0;
                int profile = (reqFlags & LW_PROFILE_MASK) >>> LW_PROFILE_SHIFT;
                if (wantAdaptive && (profile > 2 || !RssUdpMtuBudget.valid(clientMtu))) {
                    fail(ctx, in, "Invalid RSS lightweight adaptive profile or MTU");
                    return;
                }
                AdaptiveUdpPort adaptivePort = server.getAdaptivePort();
                boolean enableAdaptive = wantAdaptive && adaptivePort != null;
                boolean effectiveHop = enableAdaptive
                        && ((reqFlags & LW_FLAG_UDP_HOP) != 0)
                        && sc.isUdpHop()
                        && adaptivePort.isHopping();
                int primaryPort = enableAdaptive ? adaptivePort.port() : 0;
                int aliasPort = enableAdaptive ? (effectiveHop ? adaptivePort.aliasPort() : primaryPort) : 0;
                int negotiatedMtu = enableAdaptive
                        ? RssUdpMtuBudget.negotiate(sc.resolveDailyUdpMtu(), clientMtu)
                        : 0;
                boolean enableCompress = (reqFlags & LW_FLAG_COMPRESS) != 0;
                int compLevel = enableCompress
                        ? Math.max(1, Math.min(9, sc.getTcpCompressionLevel() > 0 ? sc.getTcpCompressionLevel() : 3))
                        : 0;

                int ackFlags = 0;
                if (enableCompress) {
                    ackFlags |= LW_FLAG_COMPRESS;
                }
                if (enableAdaptive) {
                    ackFlags |= LW_FLAG_ADAPTIVE_UDP | ((profile & 0x3) << LW_PROFILE_SHIFT);
                    if (effectiveHop) {
                        ackFlags |= LW_FLAG_UDP_HOP;
                    }
                }
                byte[] serverNonce = TransportProtocol.randomBytes(TransportProtocol.SERVER_NONCE_BYTES);
                EgressCodec selectedEgress = currentEgress(ctx.channel());
                byte[] serverMac = TransportProtocol.computeLightweightAckMac(secret, LW_VERSION, ackFlags,
                        selectedEgress.wireCode(), compLevel, negotiatedMtu, primaryPort, aliasPort,
                        sessionId, clientNonce, serverNonce);

                ByteBuf ackBuf = ctx.alloc().ioBuffer(LW_SERVER_ACK_BYTES, LW_SERVER_ACK_BYTES);
                ackBuf.writeByte(LW_MAGIC_0);
                ackBuf.writeByte(LW_MAGIC_1);
                ackBuf.writeByte(LW_VERSION);
                ackBuf.writeByte(ackFlags);
                ackBuf.writeByte(compLevel);
                ackBuf.writeShort(negotiatedMtu & 0xFFFF);
                ackBuf.writeShort(primaryPort & 0xFFFF);
                ackBuf.writeShort(aliasPort & 0xFFFF);
                ackBuf.writeBytes(serverNonce, 0, TransportProtocol.SERVER_NONCE_BYTES);
                ackBuf.writeBytes(serverMac, 0, TransportProtocol.AUTH_TAG_BYTES);

                ByteBuf leftover = in.isReadable() ? in.readRetainedSlice(in.readableBytes()) : null;
                boolean transferredLeftover = false;
                try {
                    completed = true;
                    // Flush RFC 1929 SUCCESS + 43-byte HMAC-authenticated ack in ONE TCP segment BEFORE installing post-auth codecs.
                    ctx.write(SUCCESS);
                    ctx.writeAndFlush(ackBuf).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);

                    ChannelPipeline pipeline = ctx.pipeline();
                    String firstPostHandler = null;
                    if (enableAdaptive) {
                        byte[] adaptiveKey = TransportProtocol.deriveAdaptiveKey(secret, sessionId, clientNonce, serverNonce);
                        AdaptiveTcpHandler.install(ctx.channel(), sessionId, adaptiveKey, adaptivePort,
                                primaryPort, aliasPort, profile, effectiveHop, null, negotiatedMtu, null);
                        firstPostHandler = "rssAdaptiveLength";
                    }
                    if (enableCompress) {
                        int maxInflate = sc.getMaxBusinessInflateBytes() > 0 ? sc.getMaxBusinessInflateBytes() : DEFAULT_MAX_INFLATE_BYTES;
                        String zipAnchor = installBusinessZlibAtCanonicalAnchor(pipeline, compLevel, maxInflate);
                        if (firstPostHandler == null) {
                            firstPostHandler = zipAnchor;
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
            } finally {
                if (secret != null) {
                    Arrays.fill(secret, (byte) 0);
                }
            }
        }

        private void fail(ChannelHandlerContext ctx, ByteBuf in, String message) {
            completed = true;
            in.skipBytes(in.readableBytes());
            ctx.close();
            throw new CorruptedFrameException(message);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            byte[] secret = ctx.channel().attr(ATTR_LIGHTWEIGHT_AUTH_SECRET).getAndSet(null);
            if (secret != null) {
                Arrays.fill(secret, (byte) 0);
            }
            super.channelInactive(ctx);
        }
    }
}
