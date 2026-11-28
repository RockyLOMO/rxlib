package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.compression.JdkZlibDecoder;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
import io.netty.util.AttributeKey;
import org.apache.commons.lang3.time.FastDateFormat;
import org.rx.diagnostic.DiagnosticMetrics;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Shared physical TCP egress codec for RSS, RPC, RRP, and NodeService.
 * <p>
 * Wire formats:
 * <ul>
 *     <li>{@link #NONE}: pass-through, installs zero handlers, buffers, or timers.</li>
 *     <li>{@link #ZLIB_STORED}: pure RFC 1950 continuous zlib level-0 stream (no private preface).</li>
 *     <li>{@link #HTTP}: HTTP/1.1 framing (client {@code POST /v1/stream}, server {@code HTTP/1.1 200 OK})
 *         with raw binary payload and private {@code Content-Type: application/json} convention (Scheme A).</li>
 *     <li>{@link #AUTO}: server-only one-shot per-connection selector over explicit {@code allowedCodecs}
 *         using strictly disjoint protocol starts (never compression-level heuristics).</li>
 * </ul>
 * UDP fast paths never visit these handlers.
 */
public enum EgressCodec {
    NONE("none", (byte) 0),
    ZLIB_STORED("zlib-stored", (byte) 1),
    HTTP("http", (byte) 2),
    AUTO("auto", (byte) -1);

    public enum InnerProtocol {
        TRANSPORT_CORE,
        RSS_SOCKS
    }

    public enum HttpBodyEncoding {
        NONE("none"),
        ZLIB_STORED("zlib-stored");

        private final String wireName;

        HttpBodyEncoding(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static HttpBodyEncoding parse(String name) {
            if (name == null || name.trim().isEmpty() || "none".equalsIgnoreCase(name.trim())) {
                return NONE;
            }
            if ("zlib-stored".equalsIgnoreCase(name.trim())) {
                return ZLIB_STORED;
            }
            throw new IllegalArgumentException("Unknown HTTP egress bodyEncoding: " + name);
        }
    }

    /** Insert adaptive TCP length/stream handlers strictly AFTER this physical-layer anchor. */
    public static final String INNER_ANCHOR = "rxEgressInner";
    public static final String AUTO_DETECTOR_NAME = "rxEgressAuto";
    public static final AttributeKey<EgressCodec> ATTR_SELECTED = AttributeKey.valueOf("rxEgressSelected");

    public static final int DEFAULT_MAX_HEADER_BYTES = 4096;
    public static final int DEFAULT_MAX_BODY_BYTES = 16 * 1024 * 1024;
    public static final int DEFAULT_MAX_INFLATE_BYTES = 4 * 1024 * 1024;
    public static final int DEFAULT_DETECT_TIMEOUT_MILLIS = 5000;

    private static final byte[] HTTP_POST_PREFIX = "POST ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HTTP_GET_PREFIX = "GET ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RXTC_MAGIC = new byte[]{'R', 'X', 'T', 'C'};

    // Built-in HTTP pseudo-header parameter sets preserved from legacy HttpPseudoHeaderEncoder.
    // Used strictly as private HTTP header text on already-authorized connections; never drives DNS/routing/TLS.
    static final String[] HTTP_PREFIXES = {
            "api", "v1", "v2", "rest", "web"
    };
    static final String[] HTTP_RESOURCES = {
            "user", "login", "auth", "profile", "settings",
            "data", "order", "product", "cart", "payment",
            "post", "comment", "blog", "search", "info"
    };
    static final String[] HTTP_SUFFIXES = {
            "profile", "settings", "list", "detail", "create",
            "update", "delete", "info", "status", ""
    };
    static final String[] HTTP_TLDS = {
            ".cn", ".com.cn", ".net.cn", ".org.cn", ".edu.cn",
            ".gov.cn", ".com", ".net", ".org"
    };
    static final String[] HTTP_SUBDOMAINS = {
            "api", "data", "web", "shop", "cloud", "app",
            "baidu", "alibaba", "tencent", "jd", "sina",
            "sohu", "163", "qq", "xiaomi", "huawei"
    };
    static final String[] HTTP_QUERY_KEYS = {
            "id", "type", "page", "size", "query", "token",
            "category", "sort", "filter", "lang"
    };
    static final String[] HTTP_QUERY_VALUES = {
            "user", "admin", "json", "xml", "asc", "desc", "en", "zh"
    };

    private static final byte[][] HTTP_PREFIXES_BYTES = toAsciiArray(HTTP_PREFIXES);
    private static final byte[][] HTTP_RESOURCES_BYTES = toAsciiArray(HTTP_RESOURCES);
    private static final byte[][] HTTP_SUFFIXES_BYTES = toAsciiArray(HTTP_SUFFIXES);
    private static final byte[][] HTTP_TLDS_BYTES = toAsciiArray(HTTP_TLDS);
    private static final byte[][] HTTP_SUBDOMAINS_BYTES = toAsciiArray(HTTP_SUBDOMAINS);
    private static final byte[][] HTTP_QUERY_KEYS_BYTES = toAsciiArray(HTTP_QUERY_KEYS);
    private static final byte[][] HTTP_QUERY_VALUES_BYTES = toAsciiArray(HTTP_QUERY_VALUES);

    private static final FastDateFormat HTTP_DATE_FORMAT =
            FastDateFormat.getInstance("EEE, dd MMM yyyy HH:mm:ss 'GMT'", TimeZone.getTimeZone("GMT"), Locale.US);
    private static volatile long cachedDateEpochSecond = Long.MIN_VALUE;
    private static volatile byte[] cachedDateBytes =
            HTTP_DATE_FORMAT.format(System.currentTimeMillis()).getBytes(StandardCharsets.US_ASCII);

    private static byte[][] toAsciiArray(String[] src) {
        byte[][] out = new byte[src.length][];
        for (int i = 0; i < src.length; i++) {
            out[i] = src[i].getBytes(StandardCharsets.US_ASCII);
        }
        return out;
    }

    /** Generates a random built-in HTTP URL path (optionally with 1..3 query parameters). */
    public static String generateRandomPath() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder path = new StringBuilder(64).append('/');
        if (random.nextBoolean()) {
            path.append(HTTP_PREFIXES[random.nextInt(HTTP_PREFIXES.length)]).append('/');
        }
        path.append(HTTP_RESOURCES[random.nextInt(HTTP_RESOURCES.length)]);
        if (random.nextBoolean()) {
            String suffix = HTTP_SUFFIXES[random.nextInt(HTTP_SUFFIXES.length)];
            if (!suffix.isEmpty()) {
                path.append('/').append(suffix);
            } else if (random.nextDouble() < 0.3D) {
                path.append('/').append(random.nextInt(10000));
            }
        }
        if (random.nextBoolean()) {
            path.append('?');
            int paramCount = random.nextInt(3) + 1;
            for (int i = 0; i < paramCount; i++) {
                if (i > 0) {
                    path.append('&');
                }
                path.append(HTTP_QUERY_KEYS[random.nextInt(HTTP_QUERY_KEYS.length)]).append('=');
                if (random.nextBoolean()) {
                    path.append(random.nextInt(1000));
                } else {
                    path.append(HTTP_QUERY_VALUES[random.nextInt(HTTP_QUERY_VALUES.length)]);
                }
            }
        }
        return path.toString();
    }

    /** Generates a random built-in Host header string for private HTTP egress framing. */
    public static String generateRandomHost() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder host = new StringBuilder(32);
        if (random.nextDouble() < 0.3D) {
            host.append(HTTP_SUBDOMAINS[random.nextInt(HTTP_SUBDOMAINS.length)]).append('.');
        }
        host.append(HTTP_SUBDOMAINS[random.nextInt(HTTP_SUBDOMAINS.length)]);
        host.append(HTTP_TLDS[random.nextInt(HTTP_TLDS.length)]);
        return host.toString();
    }

    static byte[] currentUtcDateBytes() {
        long sec = System.currentTimeMillis() / 1000L;
        if (sec != cachedDateEpochSecond) {
            byte[] next = HTTP_DATE_FORMAT.format(sec * 1000L).getBytes(StandardCharsets.US_ASCII);
            cachedDateBytes = next;
            cachedDateEpochSecond = sec;
            return next;
        }
        return cachedDateBytes;
    }

    private static void writeRandomPath(ByteBuf out, ThreadLocalRandom random) {
        out.writeByte('/');
        if (random.nextBoolean()) {
            out.writeBytes(HTTP_PREFIXES_BYTES[random.nextInt(HTTP_PREFIXES_BYTES.length)]);
            out.writeByte('/');
        }
        out.writeBytes(HTTP_RESOURCES_BYTES[random.nextInt(HTTP_RESOURCES_BYTES.length)]);
        if (random.nextBoolean()) {
            byte[] suffix = HTTP_SUFFIXES_BYTES[random.nextInt(HTTP_SUFFIXES_BYTES.length)];
            if (suffix.length > 0) {
                out.writeByte('/');
                out.writeBytes(suffix);
            } else if (random.nextDouble() < 0.3D) {
                out.writeByte('/');
                writeAsciiInt(out, random.nextInt(10000));
            }
        }
        if (random.nextBoolean()) {
            out.writeByte('?');
            int paramCount = random.nextInt(3) + 1;
            for (int i = 0; i < paramCount; i++) {
                if (i > 0) {
                    out.writeByte('&');
                }
                out.writeBytes(HTTP_QUERY_KEYS_BYTES[random.nextInt(HTTP_QUERY_KEYS_BYTES.length)]);
                out.writeByte('=');
                if (random.nextBoolean()) {
                    writeAsciiInt(out, random.nextInt(1000));
                } else {
                    out.writeBytes(HTTP_QUERY_VALUES_BYTES[random.nextInt(HTTP_QUERY_VALUES_BYTES.length)]);
                }
            }
        }
    }

    private static void writeRandomHost(ByteBuf out, ThreadLocalRandom random) {
        if (random.nextDouble() < 0.3D) {
            out.writeBytes(HTTP_SUBDOMAINS_BYTES[random.nextInt(HTTP_SUBDOMAINS_BYTES.length)]);
            out.writeByte('.');
        }
        out.writeBytes(HTTP_SUBDOMAINS_BYTES[random.nextInt(HTTP_SUBDOMAINS_BYTES.length)]);
        out.writeBytes(HTTP_TLDS_BYTES[random.nextInt(HTTP_TLDS_BYTES.length)]);
    }

    /** Zero-allocation non-negative ASCII integer writer. */
    private static void writeAsciiInt(ByteBuf out, int value) {
        if (value == 0) {
            out.writeByte('0');
            return;
        }
        int div = 1;
        int tmp = value;
        while (tmp >= 10) {
            tmp /= 10;
            div *= 10;
        }
        while (div > 0) {
            out.writeByte('0' + (value / div));
            value %= div;
            div /= 10;
        }
    }

    private final String wireName;
    private final byte wireCode;

    EgressCodec(String wireName, byte wireCode) {
        this.wireName = wireName;
        this.wireCode = wireCode;
    }

    public String wireName() {
        return wireName;
    }

    public byte wireCode() {
        return wireCode;
    }

    public static EgressCodec fromWireCode(byte code) {
        switch (code) {
            case 0:
                return NONE;
            case 1:
                return ZLIB_STORED;
            case 2:
                return HTTP;
            default:
                throw new IllegalArgumentException("Unknown egress wire code: " + code);
        }
    }

    /** Parses client-allowed TCP egress codec ({@code none | zlib-stored | http}). Rejects {@code auto}. */
    public static EgressCodec parse(String name) {
        if (name == null || name.trim().isEmpty() || "none".equalsIgnoreCase(name.trim())) {
            return NONE;
        }
        String norm = name.trim().toLowerCase(Locale.ROOT);
        if ("zlib-stored".equals(norm)) {
            return ZLIB_STORED;
        }
        if ("http".equals(norm)) {
            return HTTP;
        }
        throw new IllegalArgumentException("Unknown or disallowed client TCP egress codec: " + name);
    }

    /** Parses server-allowed TCP egress codec ({@code none | zlib-stored | http | auto}). */
    public static EgressCodec parseServer(String name) {
        if (name != null && "auto".equalsIgnoreCase(name.trim())) {
            return AUTO;
        }
        return parse(name);
    }

    public static void installClient(ChannelPipeline pipeline, EgressCodec codec) {
        installClient(pipeline, EgressConfig.client(codec == null ? NONE : codec));
    }

    public static void installClient(ChannelPipeline pipeline, EgressConfig config) {
        EgressConfig cfg = config == null ? EgressConfig.none() : config;
        cfg.validateClient();
        EgressCodec codec = cfg.getTcpCodec();
        pipeline.channel().attr(ATTR_SELECTED).set(codec);
        if (codec == NONE) {
            return;
        }
        if (codec == ZLIB_STORED) {
            installZlibStored(pipeline, cfg, null);
        } else if (codec == HTTP) {
            installHttp(pipeline, cfg, false, null);
        }
    }

    public static void installServer(ChannelPipeline pipeline, EgressCodec codec, InnerProtocol innerProtocol) {
        installServer(pipeline, EgressConfig.server(codec == null ? NONE : codec), innerProtocol);
    }

    public static void installServer(ChannelPipeline pipeline, EgressConfig config, InnerProtocol innerProtocol) {
        EgressConfig cfg = config == null ? EgressConfig.none() : config;
        cfg.validateServer();
        EgressCodec codec = cfg.getTcpCodec();
        if (codec == NONE) {
            pipeline.channel().attr(ATTR_SELECTED).set(NONE);
            return;
        }
        if (codec == ZLIB_STORED) {
            pipeline.channel().attr(ATTR_SELECTED).set(ZLIB_STORED);
            installZlibStored(pipeline, cfg, null);
            return;
        }
        if (codec == HTTP) {
            pipeline.channel().attr(ATTR_SELECTED).set(HTTP);
            installHttp(pipeline, cfg, true, null);
            return;
        }
        pipeline.addLast(AUTO_DETECTOR_NAME, new EgressAutoDetector(cfg, innerProtocol));
    }

    /** Returns true if the 2-byte big-endian sequence is a valid RFC 1950 zlib stream header without preset dict. */
    public static boolean isValidRfc1950Header(int b0, int b1) {
        if ((b0 & 0x0f) != 8) {
            return false;
        }
        if ((b0 >>> 4) > 7) {
            return false;
        }
        if ((b1 & 0x20) != 0) {
            return false;
        }
        return (((b0 << 8) | b1) % 31) == 0;
    }

    private static void installZlibStored(ChannelPipeline pipeline, EgressConfig cfg, String afterAnchor) {
        boolean measure = DiagnosticMetrics.isEnabled();
        ChannelHandler decoder = newStoredDecoder(cfg.getMaxInflateBytes());
        ChannelHandler encoder = pipeline.channel().isActive() ? newStoredEncoder() : StartZlibEncoder.INSTANCE;
        if (afterAnchor == null) {
            if (measure) {
                pipeline.addLast("rxEgressWireBytes", new ByteCounter("layer=wire"));
            }
            pipeline.addLast("rxEgressDecoder", decoder);
            pipeline.addLast(measure ? "rxEgressEncoder" : INNER_ANCHOR, encoder);
            if (measure) {
                pipeline.addLast(INNER_ANCHOR, new ByteCounter("layer=input"));
            }
        } else {
            if (measure) {
                pipeline.addBefore(afterAnchor, "rxEgressWireBytes", new ByteCounter("layer=wire"));
            }
            pipeline.addAfter(afterAnchor, "rxEgressDecoder", decoder);
            String encName = measure ? "rxEgressEncoder" : INNER_ANCHOR;
            pipeline.addAfter("rxEgressDecoder", encName, newStoredEncoder());
            if (measure) {
                pipeline.addAfter(encName, INNER_ANCHOR, new ByteCounter("layer=input"));
            }
        }
    }

    private static void installHttp(ChannelPipeline pipeline, EgressConfig cfg, boolean server, String afterAnchor) {
        boolean measure = DiagnosticMetrics.isEnabled();
        ChannelHandler decoder = new EgressHttpDecoder(cfg, server);
        ChannelHandler encoder = new EgressHttpEncoder(cfg, server);
        if (afterAnchor == null) {
            if (measure) {
                pipeline.addLast("rxEgressWireBytes", new ByteCounter("layer=wire"));
            }
            pipeline.addLast("rxEgressDecoder", decoder);
            pipeline.addLast(measure ? "rxEgressEncoder" : INNER_ANCHOR, encoder);
            if (measure) {
                pipeline.addLast(INNER_ANCHOR, new ByteCounter("layer=input"));
            }
        } else {
            if (measure) {
                pipeline.addBefore(afterAnchor, "rxEgressWireBytes", new ByteCounter("layer=wire"));
            }
            pipeline.addAfter(afterAnchor, "rxEgressDecoder", decoder);
            String encName = measure ? "rxEgressEncoder" : INNER_ANCHOR;
            pipeline.addAfter("rxEgressDecoder", encName, encoder);
            if (measure) {
                pipeline.addAfter(encName, INNER_ANCHOR, new ByteCounter("layer=input"));
            }
        }
    }

    private static ChannelHandler newStoredDecoder(int maxInflateBytes) {
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

    private static ChannelHandler newStoredEncoder() {
        return ZlibCodecFactory.newZlibEncoder(ZlibWrapper.ZLIB, 0);
    }

    /** Defers Deflater allocation until channelActive so failed connects emit no trailer and leak nothing. */
    @ChannelHandler.Sharable
    private static final class StartZlibEncoder extends ChannelInboundHandlerAdapter {
        static final StartZlibEncoder INSTANCE = new StartZlibEncoder();

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            ctx.pipeline().replace(this, ctx.name(), newStoredEncoder());
            ctx.fireChannelActive();
        }
    }

    /**
     * One-shot server-side physical TCP egress selector.
     * Enforces explicit {@code allowedCodecs}, byte/time budgets, and strictly disjoint start-byte rules.
     * Removes itself immediately upon selection; fails closed with zero fallback on any mismatch or timeout.
     */
    static final class EgressAutoDetector extends ByteToMessageDecoder {
        private final EgressConfig config;
        private final InnerProtocol innerProtocol;
        private final Set<EgressCodec> allowed;
        private ScheduledFuture<?> timeoutTask;
        private boolean decided;

        EgressAutoDetector(EgressConfig config, InnerProtocol innerProtocol) {
            this.config = config;
            this.innerProtocol = innerProtocol == null ? InnerProtocol.TRANSPORT_CORE : innerProtocol;
            this.allowed = config.effectiveAllowedCodecs();
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            super.handlerAdded(ctx);
            if (ctx.channel().isActive()) {
                armTimeout(ctx);
            }
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            armTimeout(ctx);
            super.channelActive(ctx);
        }

        private void armTimeout(ChannelHandlerContext ctx) {
            if (timeoutTask != null || decided) {
                return;
            }
            timeoutTask = ctx.executor().schedule(() -> {
                if (!decided && ctx.channel().isOpen()) {
                    ctx.close();
                }
            }, config.getDetectTimeoutMillis(), TimeUnit.MILLISECONDS);
        }

        private void cancelTimeout() {
            ScheduledFuture<?> t = timeoutTask;
            if (t != null) {
                timeoutTask = null;
                t.cancel(false);
            }
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (!in.isReadable()) {
                return;
            }
            // Decide strictly from the first 1..5 disjoint protocol prefix bytes (never reject coalesced
            // valid header + large body payloads here; EgressHttpDecoder enforces header & body caps separately).
            int pos = in.readerIndex();
            int b0 = in.getUnsignedByte(pos);

            // 1. HTTP Scheme A ("POST " or optional "GET ")
            if (b0 == 'P') {
                if (in.readableBytes() < HTTP_POST_PREFIX.length) {
                    return;
                }
                for (int i = 0; i < HTTP_POST_PREFIX.length; i++) {
                    if (in.getByte(pos + i) != HTTP_POST_PREFIX[i]) {
                        reject(ctx, in);
                        return;
                    }
                }
                if (!allowed.contains(HTTP)) {
                    reject(ctx, in);
                    return;
                }
                select(ctx, HTTP);
                return;
            }
            if (b0 == 'G') {
                if (in.readableBytes() < HTTP_GET_PREFIX.length) {
                    return;
                }
                for (int i = 0; i < HTTP_GET_PREFIX.length; i++) {
                    if (in.getByte(pos + i) != HTTP_GET_PREFIX[i]) {
                        reject(ctx, in);
                        return;
                    }
                }
                if (!allowed.contains(HTTP) || !config.isHttpAllowGetMethod()) {
                    reject(ctx, in);
                    return;
                }
                select(ctx, HTTP);
                return;
            }

            // 2. Pure RFC 1950 ZLIB_STORED (low nibble 8, valid 2-byte header without preset dict)
            if ((b0 & 0x0f) == 8 && (b0 >>> 4) <= 7) {
                if (in.readableBytes() < 2) {
                    return;
                }
                int b1 = in.getUnsignedByte(pos + 1);
                if (!isValidRfc1950Header(b0, b1)) {
                    reject(ctx, in);
                    return;
                }
                if (allowed.contains(ZLIB_STORED)) {
                    select(ctx, ZLIB_STORED);
                    return;
                }
                // If RSS_SOCKS AUTO explicitly excluded ZLIB_STORED and allowed NONE, pass through to RssTcpCodecSelector.
                if (innerProtocol == InnerProtocol.RSS_SOCKS && allowed.contains(NONE)) {
                    select(ctx, NONE);
                    return;
                }
                reject(ctx, in);
                return;
            }

            // 3. Explicit NONE inner protocol boundaries
            if (innerProtocol == InnerProtocol.TRANSPORT_CORE) {
                if (b0 == RXTC_MAGIC[0]) {
                    if (in.readableBytes() < RXTC_MAGIC.length) {
                        return;
                    }
                    for (int i = 0; i < RXTC_MAGIC.length; i++) {
                        if (in.getByte(pos + i) != RXTC_MAGIC[i]) {
                            reject(ctx, in);
                            return;
                        }
                    }
                    if (!allowed.contains(NONE)) {
                        reject(ctx, in);
                        return;
                    }
                    select(ctx, NONE);
                    return;
                }
                reject(ctx, in);
                return;
            }

            // InnerProtocol.RSS_SOCKS: SOCKS5 (0x05) or RssHopAes (0xA5)
            if (b0 == 0x05 || b0 == RssHopAes.MAGIC) {
                if (!allowed.contains(NONE)) {
                    reject(ctx, in);
                    return;
                }
                select(ctx, NONE);
                return;
            }

            reject(ctx, in);
        }

        private void select(ChannelHandlerContext ctx, EgressCodec selected) {
            decided = true;
            cancelTimeout();
            ctx.channel().attr(ATTR_SELECTED).set(selected);
            ChannelPipeline p = ctx.pipeline();
            if (selected == ZLIB_STORED) {
                installZlibStored(p, config, ctx.name());
            } else if (selected == HTTP) {
                installHttp(p, config, true, ctx.name());
            }
            p.remove(this);
        }

        private void reject(ChannelHandlerContext ctx, ByteBuf in) {
            decided = true;
            cancelTimeout();
            in.skipBytes(in.readableBytes());
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            cancelTimeout();
            super.channelInactive(ctx);
        }

        @Override
        protected void handlerRemoved0(ChannelHandlerContext ctx) {
            cancelTimeout();
        }
    }

    /**
     * Full-duplex HTTP/1.1 binary-body encoder (Scheme A: fixed {@code Content-Type: application/json}
     * carrying raw binary payload with zero JSON/Base64 conversion and zero-copy body forwarding when
     * {@code httpBodyEncoding == NONE}).
     * <p>
     * Preserves legacy HTTP Pseudo header templates ({@code Connection: keep-alive}, {@code Host},
     * {@code Accept: *&#47;*}, Chrome {@code User-Agent}, server UTC {@code Date} + {@code Server: Tengine})
     * and built-in random Host/URL Path/Query &amp; 80% GET / 20% POST selection while writing headers
     * directly into pooled {@link ByteBuf}s with zero per-frame String allocation.
     */
    static final class EgressHttpEncoder extends ChannelOutboundHandlerAdapter {
        private static final byte[] REQ_MID_HTTP_VER_AND_HOST =
                " HTTP/1.1\r\nConnection: keep-alive\r\nHost: ".getBytes(StandardCharsets.US_ASCII);
        private static final byte[] REQ_TAIL_PLAIN =
                ("\r\nAccept: */*\r\n"
                        + "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: ").getBytes(StandardCharsets.US_ASCII);
        private static final byte[] REQ_TAIL_DEFLATE =
                ("\r\nAccept: */*\r\n"
                        + "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Encoding: deflate\r\n"
                        + "Content-Length: ").getBytes(StandardCharsets.US_ASCII);
        private static final byte[] CRLF_CRLF = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        private static final byte[] RESP_PREFIX = "HTTP/1.1 200 OK\r\nDate: ".getBytes(StandardCharsets.US_ASCII);
        private static final byte[] RESP_MID_PLAIN =
                "\r\nServer: Tengine\r\nContent-Type: application/json\r\nContent-Length: ".getBytes(StandardCharsets.US_ASCII);
        private static final byte[] RESP_MID_DEFLATE =
                "\r\nServer: Tengine\r\nContent-Type: application/json\r\nContent-Encoding: deflate\r\nContent-Length: ".getBytes(StandardCharsets.US_ASCII);
        private static final byte[] RESP_SUFFIX = "\r\nConnection: keep-alive\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

        private final EgressConfig config;
        private final boolean server;
        private final boolean deflateBody;
        private final boolean randomHeaders;
        private final boolean allowGetMethod;
        private final byte[] fixedRequestPrefix;
        private final byte[] reqTail;
        private final byte[] respMid;

        EgressHttpEncoder(EgressConfig config, boolean server) {
            this.config = config;
            this.server = server;
            this.deflateBody = config.getHttpBodyEncoding() == HttpBodyEncoding.ZLIB_STORED;
            this.randomHeaders = config.isHttpRandomHeaders();
            this.allowGetMethod = config.isHttpAllowGetMethod();
            this.reqTail = deflateBody ? REQ_TAIL_DEFLATE : REQ_TAIL_PLAIN;
            this.respMid = deflateBody ? RESP_MID_DEFLATE : RESP_MID_PLAIN;
            if (!server && !randomHeaders) {
                StringBuilder sb = new StringBuilder(256);
                sb.append("POST ").append(config.getHttpPath()).append(" HTTP/1.1\r\n");
                sb.append("Connection: keep-alive\r\n");
                sb.append("Host: ").append(config.getHttpHost()).append("\r\n");
                sb.append("Accept: */*\r\n");
                sb.append("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36\r\n");
                sb.append("Content-Type: application/json\r\n");
                if (deflateBody) {
                    sb.append("Content-Encoding: deflate\r\n");
                }
                sb.append("Content-Length: ");
                this.fixedRequestPrefix = sb.toString().getBytes(StandardCharsets.US_ASCII);
            } else {
                this.fixedRequestPrefix = null;
            }
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (!(msg instanceof ByteBuf)) {
                ctx.write(msg, promise);
                return;
            }
            ByteBuf body = (ByteBuf) msg;
            int total = body.readableBytes();
            if (total == 0) {
                // Preserve empty buffer promise markers (e.g. AdaptiveTcpHandler flush/close barrier) without emitting empty HTTP frames.
                ctx.write(body, promise);
                return;
            }
            int maxBody = config.getMaxBodyBytes();
            if (!deflateBody && total <= maxBody) {
                ByteBuf header = buildHeader(ctx, total);
                if (promise.isVoid()) {
                    ctx.write(header, ctx.voidPromise());
                    ctx.write(body, promise);
                } else {
                    WriteGroupAggregator agg = new WriteGroupAggregator(ctx, promise, 2);
                    ctx.write(header, agg.nextChildPromise());
                    ctx.write(body, agg.nextChildPromise());
                }
                return;
            }
            int chunks = (total + maxBody - 1) / maxBody;
            WriteGroupAggregator agg = promise.isVoid() ? null : new WriteGroupAggregator(ctx, promise, chunks * 2);
            try {
                while (body.isReadable()) {
                    int chunkLen = Math.min(maxBody, body.readableBytes());
                    if (!deflateBody) {
                        ByteBuf slice = body.readRetainedSlice(chunkLen);
                        ctx.write(buildHeader(ctx, chunkLen), agg == null ? ctx.voidPromise() : agg.nextChildPromise());
                        ctx.write(slice, agg == null ? ctx.voidPromise() : agg.nextChildPromise());
                    } else {
                        ByteBuf deflated = deflateStoredChunk(ctx, body, chunkLen);
                        try {
                            ctx.write(buildHeader(ctx, deflated.readableBytes()), agg == null ? ctx.voidPromise() : agg.nextChildPromise());
                            ctx.write(deflated, agg == null ? ctx.voidPromise() : agg.nextChildPromise());
                            deflated = null;
                        } finally {
                            if (deflated != null) {
                                deflated.release();
                            }
                        }
                    }
                }
            } catch (Throwable ex) {
                promise.tryFailure(ex);
                ctx.close();
            } finally {
                body.release();
            }
        }

        private ByteBuf buildHeader(ChannelHandlerContext ctx, int contentLength) {
            if (server) {
                byte[] dateBytes = currentUtcDateBytes();
                int cap = RESP_PREFIX.length + dateBytes.length + respMid.length + 10 + RESP_SUFFIX.length;
                ByteBuf header = ctx.alloc().ioBuffer(cap);
                header.writeBytes(RESP_PREFIX);
                header.writeBytes(dateBytes);
                header.writeBytes(respMid);
                writeAsciiInt(header, contentLength);
                header.writeBytes(RESP_SUFFIX);
                return header;
            }
            if (!randomHeaders) {
                int cap = fixedRequestPrefix.length + 10 + CRLF_CRLF.length;
                ByteBuf header = ctx.alloc().ioBuffer(cap);
                header.writeBytes(fixedRequestPrefix);
                writeAsciiInt(header, contentLength);
                header.writeBytes(CRLF_CRLF);
                return header;
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int cap = 5 + 96 + REQ_MID_HTTP_VER_AND_HOST.length + 40 + reqTail.length + 10 + CRLF_CRLF.length;
            ByteBuf header = ctx.alloc().ioBuffer(cap);
            if (allowGetMethod && random.nextInt(100) >= 20) {
                header.writeBytes(HTTP_GET_PREFIX);
            } else {
                header.writeBytes(HTTP_POST_PREFIX);
            }
            writeRandomPath(header, random);
            header.writeBytes(REQ_MID_HTTP_VER_AND_HOST);
            writeRandomHost(header, random);
            header.writeBytes(reqTail);
            writeAsciiInt(header, contentLength);
            header.writeBytes(CRLF_CRLF);
            return header;
        }

        private static final class WriteGroupAggregator implements ChannelFutureListener {
            private final ChannelHandlerContext ctx;
            private final ChannelPromise parent;
            private int remaining;
            private boolean failed;

            WriteGroupAggregator(ChannelHandlerContext ctx, ChannelPromise parent, int totalWrites) {
                this.ctx = ctx;
                this.parent = parent;
                this.remaining = totalWrites;
            }

            ChannelPromise nextChildPromise() {
                ChannelPromise p = ctx.newPromise();
                p.addListener(this);
                return p;
            }

            @Override
            public void operationComplete(ChannelFuture future) {
                if (!future.isSuccess()) {
                    if (!failed) {
                        failed = true;
                        parent.tryFailure(future.cause());
                        ctx.close();
                    }
                    return;
                }
                if (--remaining == 0 && !failed) {
                    parent.trySuccess();
                }
            }
        }

        private static ByteBuf deflateStoredChunk(ChannelHandlerContext ctx, ByteBuf src, int len) {
            byte[] raw = new byte[len];
            src.readBytes(raw);
            Deflater deflater = new Deflater(Deflater.NO_COMPRESSION, false);
            ByteBuf out = ctx.alloc().ioBuffer(len + 64);
            try {
                deflater.setInput(raw);
                deflater.finish();
                byte[] tmp = new byte[Math.min(16384, len + 64)];
                while (!deflater.finished()) {
                    int n = deflater.deflate(tmp);
                    if (n > 0) {
                        out.writeBytes(tmp, 0, n);
                    } else if (deflater.needsInput()) {
                        break;
                    }
                }
                return out;
            } catch (RuntimeException e) {
                out.release();
                throw e;
            } finally {
                deflater.end();
            }
        }
    }

    /**
     * Full-duplex HTTP/1.1 binary-body decoder enforcing strict header byte cap, start-line validation,
     * arbitrary-position {@code Content-Length} parsing, smuggling guards (rejects {@code Transfer-Encoding}
     * and duplicate {@code Content-Length}), bounded body size, and optional bounded per-body zlib0 inflation.
     */
    static final class EgressHttpDecoder extends ByteToMessageDecoder {
        private final EgressConfig config;
        private final boolean server;
        private int expectedBodyLen = -1;
        private boolean bodyDeflated;
        private ScheduledFuture<?> headerTimeoutTask;

        EgressHttpDecoder(EgressConfig config, boolean server) {
            this.config = config;
            this.server = server;
        }

        private void armHeaderTimeout(ChannelHandlerContext ctx) {
            if (headerTimeoutTask != null || !ctx.channel().isOpen()) {
                return;
            }
            headerTimeoutTask = ctx.executor().schedule(() -> {
                if (expectedBodyLen < 0 && ctx.channel().isOpen()) {
                    ctx.close();
                }
            }, config.getDetectTimeoutMillis(), TimeUnit.MILLISECONDS);
        }

        private void cancelHeaderTimeout() {
            ScheduledFuture<?> t = headerTimeoutTask;
            if (t != null) {
                headerTimeoutTask = null;
                t.cancel(false);
            }
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            try {
                while (true) {
                    if (expectedBodyLen < 0) {
                        if (!in.isReadable()) {
                            return;
                        }
                        int maxHeader = config.getMaxHeaderBytes();
                        int headerEnd = findHeaderEnd(in, maxHeader);
                        if (headerEnd < 0) {
                            if (in.readableBytes() > maxHeader) {
                                throw new TooLongFrameException("HTTP egress header exceeds cap: " + maxHeader);
                            }
                            armHeaderTimeout(ctx);
                            return;
                        }
                        cancelHeaderTimeout();
                        int headerLen = headerEnd - in.readerIndex();
                        String headerText = in.readCharSequence(headerLen, StandardCharsets.US_ASCII).toString();
                        in.skipBytes(4); // \r\n\r\n
                        parseHeaders(headerText);
                        if (expectedBodyLen == 0) {
                            expectedBodyLen = -1;
                            continue;
                        }
                    }
                    if (in.readableBytes() < expectedBodyLen) {
                        return;
                    }
                    int len = expectedBodyLen;
                    boolean deflated = bodyDeflated;
                    expectedBodyLen = -1;
                    bodyDeflated = false;
                    if (!deflated) {
                        out.add(in.readRetainedSlice(len));
                    } else {
                        out.add(inflateStoredBody(ctx, in, len, config.getMaxInflateBytes()));
                    }
                }
            } catch (RuntimeException bad) {
                cancelHeaderTimeout();
                in.skipBytes(in.readableBytes());
                ctx.close();
                throw bad;
            }
        }

        @Override
        protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            cancelHeaderTimeout();
            if (in.isReadable()) {
                decode(ctx, in, out);
            }
            if (expectedBodyLen > 0 || in.isReadable()) {
                in.skipBytes(in.readableBytes());
                expectedBodyLen = -1;
                bodyDeflated = false;
                if (ctx.channel().isOpen()) {
                    ctx.close();
                }
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            cancelHeaderTimeout();
            super.channelInactive(ctx);
        }

        @Override
        protected void handlerRemoved0(ChannelHandlerContext ctx) {
            cancelHeaderTimeout();
        }

        private void parseHeaders(String headerText) {
            String[] lines = headerText.split("\r\n", -1);
            if (lines.length < 2) {
                throw new CorruptedFrameException("Malformed HTTP egress header block");
            }
            validateStartLine(lines[0]);
            int contentLength = -1;
            boolean sawDeflate = false;
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    throw new CorruptedFrameException("Malformed HTTP egress header line");
                }
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String val = line.substring(colon + 1).trim();
                if ("transfer-encoding".equals(name)) {
                    throw new CorruptedFrameException("Transfer-Encoding is not permitted on HTTP egress");
                }
                if ("content-length".equals(name)) {
                    if (contentLength >= 0) {
                        throw new CorruptedFrameException("Duplicate Content-Length on HTTP egress");
                    }
                    long parsed;
                    try {
                        parsed = Long.parseLong(val);
                    } catch (NumberFormatException e) {
                        throw new CorruptedFrameException("Invalid Content-Length on HTTP egress");
                    }
                    if (parsed < 0L || parsed > config.getMaxBodyBytes()) {
                        throw new TooLongFrameException("HTTP egress Content-Length out of bounds: " + parsed);
                    }
                    contentLength = (int) parsed;
                } else if ("content-encoding".equals(name)) {
                    String enc = val.toLowerCase(Locale.ROOT);
                    if ("deflate".equals(enc)) {
                        sawDeflate = true;
                    } else if (!"identity".equals(enc) && !enc.isEmpty()) {
                        throw new CorruptedFrameException("Unsupported HTTP egress Content-Encoding");
                    }
                }
            }
            if (contentLength < 0) {
                throw new CorruptedFrameException("Missing Content-Length on HTTP egress");
            }
            boolean expectDeflate = config.getHttpBodyEncoding() == HttpBodyEncoding.ZLIB_STORED;
            if (sawDeflate != expectDeflate) {
                throw new CorruptedFrameException("HTTP egress Content-Encoding mismatch");
            }
            this.expectedBodyLen = contentLength;
            this.bodyDeflated = sawDeflate;
        }

        private void validateStartLine(String startLine) {
            if (server) {
                int pathStart;
                if (startLine.startsWith("POST ")) {
                    pathStart = 5;
                } else if (config.isHttpAllowGetMethod() && startLine.startsWith("GET ")) {
                    pathStart = 4;
                } else {
                    throw new CorruptedFrameException("Invalid HTTP egress request line");
                }
                if (!startLine.endsWith(" HTTP/1.1") && !startLine.endsWith(" HTTP/1.0")) {
                    throw new CorruptedFrameException("Invalid HTTP egress request line");
                }
                if (startLine.length() <= pathStart + 9) {
                    throw new CorruptedFrameException("Invalid HTTP egress request path");
                }
                String path = startLine.substring(pathStart, startLine.length() - 9);
                if (path.isEmpty() || !path.startsWith("/") || path.indexOf(' ') >= 0) {
                    throw new CorruptedFrameException("Invalid HTTP egress request path");
                }
            } else {
                if (!startLine.startsWith("HTTP/1.1 200") && !startLine.startsWith("HTTP/1.0 200")) {
                    throw new CorruptedFrameException("Invalid HTTP egress response line");
                }
            }
        }

        private static int findHeaderEnd(ByteBuf in, int maxHeaderBytes) {
            int start = in.readerIndex();
            int limit = Math.min(in.writerIndex() - 3, start + maxHeaderBytes);
            for (int i = start; i <= limit; i++) {
                if (in.getByte(i) == '\r' && in.getByte(i + 1) == '\n'
                        && in.getByte(i + 2) == '\r' && in.getByte(i + 3) == '\n') {
                    return i;
                }
            }
            return -1;
        }

        private static ByteBuf inflateStoredBody(ChannelHandlerContext ctx, ByteBuf in, int compressedLen, int maxInflateBytes) {
            byte[] compressed = new byte[compressedLen];
            in.readBytes(compressed);
            Inflater inflater = new Inflater(false);
            ByteBuf out = ctx.alloc().ioBuffer(Math.min(maxInflateBytes, Math.max(64, compressedLen)));
            try {
                inflater.setInput(compressed);
                byte[] tmp = new byte[8192];
                int total = 0;
                while (!inflater.finished()) {
                    int n = inflater.inflate(tmp);
                    if (n > 0) {
                        total += n;
                        if (total > maxInflateBytes) {
                            throw new TooLongFrameException("HTTP egress inflated body exceeds cap: " + maxInflateBytes);
                        }
                        out.writeBytes(tmp, 0, n);
                    } else if (inflater.needsDictionary() || inflater.needsInput()) {
                        break;
                    }
                }
                if (!inflater.finished()) {
                    throw new CorruptedFrameException("Truncated deflated HTTP egress body");
                }
                return out;
            } catch (DataFormatException e) {
                out.release();
                throw new CorruptedFrameException("Corrupted deflated HTTP egress body", e);
            } catch (RuntimeException e) {
                out.release();
                throw e;
            } finally {
                inflater.end();
            }
        }
    }

    /** Optional wire/input byte counter enabled only when DiagnosticMetrics is active. */
    private static final class ByteCounter extends ChannelDuplexHandler {
        private final String layer;
        private long inbound;
        private long outbound;

        ByteCounter(String layer) {
            this.layer = layer;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ByteBuf) {
                inbound += ((ByteBuf) msg).readableBytes();
            }
            ctx.fireChannelRead(msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof ByteBuf) {
                outbound += ((ByteBuf) msg).readableBytes();
            }
            ctx.write(msg, promise);
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            DiagnosticMetrics.record("rss.tcp.egress.rx.bytes", inbound, layer);
            DiagnosticMetrics.record("rss.tcp.egress.tx.bytes", outbound, layer);
        }
    }
}
