package org.rx.net.rss.udp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.EncoderException;
import io.netty.util.AttributeKey;
import io.netty.util.internal.ThrowableUtil;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.net.Sockets;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** One RPC-negotiated UDP association. Both server ports run on the primary relay's event loop. */
public final class DailyUdpWire extends ChannelDuplexHandler {
    public static final int HEADER_BYTES = UdpDailyFec.WIRE_HEADROOM;
    public static final int LZ4 = 1, AES = 2, FEC = 4, GAME = 8, WEAK = 16;
    static final long HOP_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final AttributeKey<RecvByteBufAllocator> ORIGINAL_ALLOCATOR = AttributeKey.valueOf("rssDailyOriginalAllocator");
    private static final EncoderException MTU_EXCEEDED_ERROR = ThrowableUtil.unknownStackTrace(
            new EncoderException("UDP datagram exceeds negotiated RSS MTU"), DailyUdpWire.class, "write");
    private static final EncoderException FINAL_MTU_EXCEEDED_ERROR = ThrowableUtil.unknownStackTrace(
            new EncoderException("Encoded RSS UDP frame exceeds wire MTU"), DailyUdpWire.class, "writePacket");

    private final long tokenHigh, tokenLow;
    private final InetSocketAddress primary, alias;
    private final boolean hopping;
    private final Predicate<InetSocketAddress> knownTarget;
    private final LongSupplier clock;
    private final DailyUdpCompression compression;
    private final DailyUdpCipher cipher;
    private final DailyUdpMac mac;
    private final DailyUdpFragmenter fragmenter;
    private final UdpGameRedundancy game;
    private final boolean useFec, weak;
    private long latestBindingSeq;
    private final Consumer<ByteBuf> emit = this::sendFrame;
    private final Consumer<ByteBuf> deliver = this::deliver;
    private ChannelHandlerContext main, returnPath;
    private InetSocketAddress logicalPeer, physicalPeer;
    private UdpDailyFec fec;
    private ScheduledFuture<?> timer;
    private ChannelPromise dataPromise;
    private long started, lastMetrics, lastProbe, payloadBytes, encodedBytes, compressionSavedBytes;
    private long mtuDrops, finalMtuDrops, unnegotiatedFragRxDrops;
    private static final AtomicLong LAST_LOOP_SAMPLE = new AtomicLong(System.nanoTime() - TimeUnit.SECONDS.toNanos(1));
    private int epoch, wireMtu, authOperations;
    private final ChannelFutureListener originalWrite = f -> {
        if (!f.isSuccess()) DiagnosticMetrics.record("rss.udp.game.tx.originalWriteFailure", 1);
    };

    private static final class AuthCpu {
        static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

        static long now() {
            return THREADS.isCurrentThreadCpuTimeSupported() && THREADS.isThreadCpuTimeEnabled()
                    ? THREADS.getCurrentThreadCpuTime() : -1;
        }
    }

    private long sampleAuth() {
        return game != null && (++authOperations & 255) == 0 && DiagnosticMetrics.isEnabled() ? AuthCpu.now() : -1;
    }

    private static void recordAuth(long start) {
        if (start >= 0) DiagnosticMetrics.record("rss.udp.game.authSampleCpuNanos", AuthCpu.now() - start);
    }

    private static final class Key {
        static final byte[] PSK = DailyUdpCipher.configuredKey();
    }

    public static boolean hasCipherKey() {
        return Key.PSK != null;
    }

    public static DailyUdpWire client(String token, InetSocketAddress primary, InetSocketAddress alias,
                                      int mtu, int features, UUID salt) {
        return client(token, primary, alias, mtu, features, salt, null, false);
    }

    public static DailyUdpWire client(String token, InetSocketAddress primary, InetSocketAddress alias,
                                      int mtu, int features, UUID salt, byte[] authSecret) {
        return client(token, primary, alias, mtu, features, salt, authSecret, false);
    }

    public static DailyUdpWire client(String token, InetSocketAddress primary, InetSocketAddress alias,
                                      int mtu, int features, UUID salt, byte[] authSecret, boolean fragEnabled) {
        DailyUdpWire wire = new DailyUdpWire(token, primary, alias, null, System::nanoTime, features, salt,
                (features & AES) != 0 ? Key.PSK : authSecret, UdpGameRedundancy.GLOBAL_EXTRAS, fragEnabled);
        wire.wireMtu = Math.max(0, mtu);
        return wire;
    }

    public static DailyUdpWire server(String token, Predicate<InetSocketAddress> knownTarget, int features, UUID salt) {
        return server(token, knownTarget, features, salt, null, 0, false);
    }

    public static DailyUdpWire server(String token, Predicate<InetSocketAddress> knownTarget, int features,
                                      UUID salt, byte[] authSecret) {
        return server(token, knownTarget, features, salt, authSecret, 0, false);
    }

    public static DailyUdpWire server(String token, Predicate<InetSocketAddress> knownTarget, int features,
                                      UUID salt, byte[] authSecret, int mtu, boolean fragEnabled) {
        DailyUdpWire wire = new DailyUdpWire(token, null, null, knownTarget, System::nanoTime, features, salt,
                (features & AES) != 0 ? Key.PSK : authSecret, UdpGameRedundancy.GLOBAL_EXTRAS, fragEnabled);
        wire.wireMtu = Math.max(0, mtu);
        return wire;
    }

    DailyUdpWire(String token, InetSocketAddress primary, InetSocketAddress alias, Predicate<InetSocketAddress> knownTarget,
                 LongSupplier clock, int features, UUID salt, byte[] key) {
        this(token, primary, alias, knownTarget, clock, features, salt, key, new AtomicLong(clock.getAsLong()), false);
    }

    DailyUdpWire(String token, InetSocketAddress primary, InetSocketAddress alias, Predicate<InetSocketAddress> knownTarget,
                 LongSupplier clock, int features, UUID salt, byte[] key, int mtu, boolean fragEnabled) {
        this(token, primary, alias, knownTarget, clock, features, salt, key, new AtomicLong(clock.getAsLong()), fragEnabled);
        this.wireMtu = Math.max(0, mtu);
    }

    private DailyUdpWire(String token, InetSocketAddress primary, InetSocketAddress alias, Predicate<InetSocketAddress> knownTarget,
                         LongSupplier clock, int features, UUID salt, byte[] key, AtomicLong processBudget, boolean fragEnabled) {
        if (features <= 0 || (features & ~(LZ4 | AES | FEC | GAME | WEAK)) != 0
                || (features & GAME) != 0 && (features & WEAK) != 0
                || (features & WEAK) != 0 && (features & FEC) == 0) {
            throw new IllegalArgumentException("invalid UDP extension mask");
        }
        UUID id = UUID.fromString(token);
        useFec = (features & FEC) != 0;
        weak = (features & WEAK) != 0;
        game = !useFec && (features & GAME) != 0 ? new UdpGameRedundancy(processBudget) : null;
        mac = (features & (GAME | WEAK)) != 0 && (features & AES) == 0 ? new DailyUdpMac(key, id, features, primary == null) : null;
        cipher = (features & AES) != 0 ? new DailyUdpCipher(key, id, salt, primary == null, features) : null;
        // Never allow unauthenticated fragment reassembly: outer cipher/mac or per-group RPC authSecret is required.
        boolean canAuthFragments = cipher != null || mac != null || (key != null && key.length == 32);
        fragmenter = fragEnabled && canAuthFragments
                ? new DailyUdpFragmenter(id, features, primary == null, cipher == null && mac == null ? key : null)
                : null;
        tokenHigh = id.getMostSignificantBits();
        tokenLow = id.getLeastSignificantBits();
        this.primary = logicalPeer = physicalPeer = primary;
        this.alias = alias != null ? alias : primary;
        this.hopping = primary != null && !primary.equals(this.alias);
        this.knownTarget = knownTarget;
        this.clock = clock;
        compression = (features & LZ4) != 0 ? new DailyUdpCompression() : null;
    }

    public static void install(ChannelPipeline pipeline, DailyUdpWire wire) {
        if (pipeline.get(Sockets.UDP_FINAL_EGRESS_GUARD) == null) {
            pipeline.addFirst(wire);
        } else {
            pipeline.addAfter(Sockets.UDP_FINAL_EGRESS_GUARD, null, wire);
        }
    }

    public ChannelHandler aliasHandler() {
        return new ChannelInboundHandlerAdapter() {
            @Override public void handlerAdded(ChannelHandlerContext ctx) {
                sizeReceiveBuffer(ctx);
            }
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                receive(ctx, msg);
            }
        };
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        main = returnPath = ctx;
        ctx.channel().attr(ORIGINAL_ALLOCATOR).setIfAbsent(ctx.channel().config().getRecvByteBufAllocator());
        sizeReceiveBuffer(ctx);
        fec = useFec ? new UdpDailyFec(ctx.alloc(), clock, weak, wireOverhead()) : null;
        started = lastMetrics = clock.getAsLong();
    }

    private static void sizeReceiveBuffer(ChannelHandlerContext ctx) {
        // Shared relay sockets also read unfragmented datagrams from local apps or Internet UDP targets up to 64 KiB.
        ctx.channel().config().setRecvByteBufAllocator(new FixedRecvByteBufAllocator(65536));
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (timer != null) {
            timer.cancel(false);
        }
        if (ctx.pipeline().get(DailyUdpWire.class) == null) {
            RecvByteBufAllocator original = ctx.channel().attr(ORIGINAL_ALLOCATOR).getAndSet(null);
            if (original != null) ctx.channel().config().setRecvByteBufAllocator(original);
        }
        recordMetrics();
        if (fragmenter != null) fragmenter.close();
        if (fec != null) fec.close();
        if (game != null) game.close();
        main = returnPath = null;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        receive(ctx, msg);
    }

    private void receive(ChannelHandlerContext path, Object msg) {
        if (!(msg instanceof DatagramPacket)) {
            path.fireChannelRead(msg);
            return;
        }
        DatagramPacket packet = (DatagramPacket) msg;
        InetSocketAddress sender = packet.sender();
        // The same primary socket also exchanges ordinary UDP with Internet destinations.
        if (main != null && path == main && (primary == null ? knownTarget.test(sender)
                : !primary.equals(sender) && !alias.equals(sender))) {
            path.fireChannelRead(msg);
            return;
        }
        ByteBuf decoded = null;
        try {
            ByteBuf b = packet.content();
            int pos = b.readerIndex();
            if (main == null || b.readableBytes() < HEADER_BYTES || wireMtu > 0 && b.readableBytes() > wireMtu
                    || b.getLong(pos) != tokenHigh
                    || b.getLong(pos + 8) != tokenLow) {
                return;
            }
            int generation = b.getInt(pos + 16);
            long authSeq = 0;
            if (cipher != null || mac != null) {
                long sample = sampleAuth();
                decoded = cipher != null ? cipher.open(b) : mac.open(b);
                recordAuth(sample);
                if (decoded == null) return;
                authSeq = b.getLong(pos + HEADER_BYTES);
                b = decoded;
            } else {
                b.skipBytes(HEADER_BYTES);
            }
            if (primary == null) {
                if (logicalPeer == null) {
                    logicalPeer = sender;
                    epoch = generation;
                }
                // A reordered frame from the previous hop can still repair data, but cannot move the return path.
                if (!logicalPeer.getAddress().equals(sender.getAddress())) {
                    return;
                }
                if (generation - epoch >= 0 && (authSeq == 0 || authSeq > latestBindingSeq)) {
                    latestBindingSeq = authSeq;
                    epoch = generation;
                    physicalPeer = sender;
                    returnPath = path;
                }
            } else if (hopping && (rotate() || generation != epoch && clock.getAsLong() - lastProbe >= TimeUnit.SECONDS.toNanos(1))) {
                lastProbe = clock.getAsLong();
                // Open the new NAT binding even during a download with no application uplink.
                sendFrame(main.alloc().ioBuffer(HEADER_BYTES, HEADER_BYTES).setIndex(HEADER_BYTES, HEADER_BYTES));
            }
            if (b.isReadable()) {
                if (fec != null) {
                    fec.read(b, deliver, emit);
                    scheduleTick();
                } else if (game != null) {
                    game.read(b, main.alloc(), clock.getAsLong(), returnPath.channel().isWritable(), deliver, emit);
                } else if (b.readableBytes() <= maxPayload()) {
                    deliver(b.retainedSlice());
                }
                if (clock.getAsLong() - lastMetrics >= TimeUnit.SECONDS.toNanos(30)) {
                    if (game != null && DiagnosticMetrics.isEnabled()) sampleLoopDelay(path);
                    recordMetrics();
                }
            }
        } finally {
            if (decoded != null) decoded.release();
            packet.release();
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (!(msg instanceof DatagramPacket) || logicalPeer == null
                || !logicalPeer.equals(((DatagramPacket) msg).recipient())) {
            ctx.write(msg, promise);
            return;
        }
        DatagramPacket packet = (DatagramPacket) msg;
        ByteBuf block = null;
        try {
            if (packet.content().readableBytes() <= 0 || packet.content().readableBytes() > DailyUdpFragmenter.MAX_DATAGRAM_BYTES) {
                mtuDrops++;
                DiagnosticMetrics.record("rss.udp.daily.mtuDrop", 1, "reason=oversized");
                if (game != null) DiagnosticMetrics.record("rss.udp.game.tx.originalMtuDrop", 1);
                promise.tryFailure(MTU_EXCEEDED_ERROR);
                return;
            }
            long now = clock.getAsLong();
            block = compression == null ? null : compression.tryEncode(packet.content(), now);
            ByteBuf data = block == null ? packet.content() : block;
            int maxSingle = maxPayload();
            boolean collidesWithFragMagic = fragmenter != null && block == null && DailyUdpFragmenter.isFragment(data);
            if (data.readableBytes() > maxSingle || collidesWithFragMagic) {
                int unitLimit = fragmentUnitLimit(maxSingle);
                if (fragmenter == null || !fragmenter.canFragment(data.readableBytes(), unitLimit)) {
                    mtuDrops++;
                    DiagnosticMetrics.record("rss.udp.daily.mtuDrop", 1,
                            fragmenter == null ? "reason=unnegotiated" : "reason=fragment-limit");
                    if (game != null) DiagnosticMetrics.record("rss.udp.game.tx.originalMtuDrop", 1);
                    promise.tryFailure(MTU_EXCEEDED_ERROR);
                    return;
                }
                if (primary != null) {
                    rotate();
                }
                payloadBytes += packet.content().readableBytes();
                if (block != null) {
                    compressionSavedBytes += packet.content().readableBytes() - block.readableBytes();
                }
                fragmenter.fragment(ctx.alloc(), data, unitLimit, (fragFrame, last) -> {
                    try {
                        dataPromise = last ? promise : null;
                        emitUnitFromHeadroomFrame(fragFrame, last);
                    } finally {
                        dataPromise = null;
                    }
                });
                return;
            }
            if (primary != null) {
                rotate();
            }
            payloadBytes += packet.content().readableBytes();
            if (block != null) {
                compressionSavedBytes += packet.content().readableBytes() - block.readableBytes();
            }
            dataPromise = promise;
            if (game != null) {
                sendGame(data, true);
            } else if (fec != null) {
                if (weak && !returnPath.channel().isWritable()) fec.cancelPendingParity();
                fec.write(data, emit);
                scheduleTick();
            } else {
                int len = data.readableBytes();
                ByteBuf frame = ctx.alloc().ioBuffer(HEADER_BYTES + len, HEADER_BYTES + len);
                frame.setIndex(HEADER_BYTES, HEADER_BYTES).writeBytes(data, data.readerIndex(), len);
                sendFrame(frame);
            }
        } catch (RuntimeException e) {
            promise.tryFailure(e);
        } finally {
            dataPromise = null;
            if (block != null) {
                block.release();
            }
            packet.release();
        }
    }

    private int fragmentUnitLimit(int maxSingle) {
        return useFec ? Math.min(maxSingle, UdpDailyFec.MAX_SHARD_BYTES) : maxSingle;
    }

    private void emitUnitFromHeadroomFrame(ByteBuf fragFrame, boolean flush) {
        if (game != null) {
            try {
                sendGame(fragFrame, flush);
            } finally {
                fragFrame.release();
            }
        } else if (fec != null) {
            try {
                if (weak && !returnPath.channel().isWritable()) fec.cancelPendingParity();
                fec.write(fragFrame, f -> sendFrame(f, false));
            } finally {
                fragFrame.release();
            }
            if (flush) {
                returnPath.flush();
                scheduleTick();
            }
        } else {
            sendFrame(fragFrame, flush);
        }
    }

    private void sendGame(ByteBuf data, boolean flush) {
        long now = clock.getAsLong();
        int seq = game.begin(now);
        int body = UdpGameRedundancy.DATA_HEADER + data.readableBytes();
        ChannelHandlerContext path = returnPath;
        ByteBuf plain = null;
        try {
            if (mac == null) {
                plain = main.alloc().ioBuffer(HEADER_BYTES + body, HEADER_BYTES + body);
                plain.setIndex(HEADER_BYTES, HEADER_BYTES).writeByte(0).writeInt(seq)
                        .writeBytes(data, data.readerIndex(), data.readableBytes());
            }
            for (int copy = 0; copy < game.copies; copy++) {
                if (copy != 0 && !game.extra(seq, wireOverhead() + body, path.channel().isWritable(), now)) break;
                if (mac != null) {
                    ByteBuf wire;
                    try {
                        long sample = sampleAuth();
                        wire = mac.sealGame(data, tokenHigh, tokenLow, epoch, copy, seq);
                        recordAuth(sample);
                    } catch (RuntimeException failure) {
                        main.close();
                        throw failure;
                    }
                    ChannelPromise pending = dataPromise;
                    dataPromise = null;
                    writePacket(wire, pending);
                } else {
                    plain.setByte(HEADER_BYTES, copy);
                    sendFrame(plain.retainedDuplicate(), false);
                }
            }
        } finally {
            if (plain != null) plain.release();
            if (flush) path.flush();
        }
    }

    private boolean rotate() {
        if (!hopping) {
            return false;
        }
        int next = (int) ((clock.getAsLong() - started) / HOP_NANOS);
        physicalPeer = (next & 1) == 0 ? primary : alias;
        boolean changed = epoch != next;
        epoch = next;
        return changed;
    }

    private void sendFrame(ByteBuf frame) {
        sendFrame(frame, true);
    }

    private void sendFrame(ByteBuf frame, boolean flush) {
        int pos = frame.readerIndex();
        ChannelPromise pending = null;
        if (dataPromise != null && frame.isReadable() && (fec == null || frame.getUnsignedByte(pos) <= UdpDailyFec.SHARD)) {
            pending = dataPromise;
            dataPromise = null;
        }
        frame.setLong(pos - HEADER_BYTES, tokenHigh).setLong(pos - HEADER_BYTES + 8, tokenLow)
                .setInt(pos - 4, epoch).readerIndex(pos - HEADER_BYTES);
        if (cipher != null || mac != null) {
            ByteBuf plain = frame;
            try {
                long sample = sampleAuth();
                frame = cipher != null ? cipher.seal(plain) : mac.seal(plain);
                recordAuth(sample);
            } catch (RuntimeException failure) {
                // A fresh association will negotiate new salts / directional keys.
                main.close();
                throw failure;
            } finally {
                plain.release();
            }
        }
        writePacket(frame, pending);
        if (flush) returnPath.flush();
    }

    private void writePacket(ByteBuf frame, ChannelPromise pending) {
        if (wireMtu > 0 && frame.readableBytes() > wireMtu) {
            int droppedBytes = frame.readableBytes();
            frame.release();
            finalMtuDrops++;
            DiagnosticMetrics.record("rss.udp.daily.finalMtuDrop", 1, "mtu=" + wireMtu + ",bytes=" + droppedBytes);
            if (pending != null && !pending.isVoid()) {
                pending.tryFailure(FINAL_MTU_EXCEEDED_ERROR);
            }
            return;
        }
        encodedBytes += frame.readableBytes();
        DatagramPacket packet = new DatagramPacket(frame, physicalPeer);
        // Caller-observed original writes only. Kernel drops are measured separately at the socket.
        if (game != null && pending != null && !pending.isVoid() && DiagnosticMetrics.isEnabled()) {
            pending.addListener(originalWrite);
        }
        if (pending == null || pending.isVoid()) {
            returnPath.write(packet, returnPath.voidPromise());
        } else if (pending.channel() == returnPath.channel()) {
            returnPath.write(packet, pending);
        } else {
            ChannelPromise original = pending;
            returnPath.write(packet).addListener(f -> {
                if (f.isSuccess()) {
                    original.trySuccess();
                } else {
                    original.tryFailure(f.cause());
                }
            });
        }
    }

    private void deliver(ByteBuf payload) {
        if (DailyUdpFragmenter.isFragment(payload)) {
            if (fragmenter == null) {
                payload.release();
                unnegotiatedFragRxDrops++;
                DiagnosticMetrics.record("rss.udp.daily.frag.rx.unnegotiatedDrop", 1);
                return;
            }
            payload = fragmenter.accept(payload, main.alloc(), clock.getAsLong());
            scheduleTick();
            if (payload == null) {
                return;
            }
        }
        if (compression != null) {
            payload = DailyUdpCompression.decode(payload, DailyUdpFragmenter.MAX_DATAGRAM_BYTES);
            if (payload == null) {
                return;
            }
        }
        main.fireChannelRead(new DatagramPacket(payload, (InetSocketAddress) main.channel().localAddress(), logicalPeer));
    }

    private int maxPayload() {
        int mtu = wireMtu > 0 ? Math.min(65507, wireMtu) : 65507;
        int overhead = wireOverhead() + (useFec ? UdpDailyFec.MAX_OVERHEAD : game != null ? UdpGameRedundancy.DATA_HEADER : 0);
        return mtu - overhead;
    }

    private int wireOverhead() {
        return HEADER_BYTES + (cipher != null ? DailyUdpCipher.OVERHEAD : mac != null ? DailyUdpMac.OVERHEAD : 0);
    }

    // At most one extra task per process per second, from already-active metric intervals.
    private static void sampleLoopDelay(ChannelHandlerContext path) {
        long start = System.nanoTime();
        long last = LAST_LOOP_SAMPLE.get();
        if (start - last < TimeUnit.SECONDS.toNanos(1) || !LAST_LOOP_SAMPLE.compareAndSet(last, start)) return;
        try {
            path.executor().execute(() -> DiagnosticMetrics.record("rss.udp.game.eventLoopDelayMillis",
                    (System.nanoTime() - start) / 1_000_000.0));
        } catch (RejectedExecutionException closing) {
            // Closing the event loop supersedes its pending latency measurement.
        }
    }

    private void recordMetrics() {
        lastMetrics = clock.getAsLong();
        if (DiagnosticMetrics.isEnabled()) {
            String side = primary == null ? "side=server" : "side=client";
            if (game != null) {
                DiagnosticMetrics.record("rss.udp.game.tx.multiplier", game.copies, side);
                DiagnosticMetrics.record("rss.udp.game.tx.extraPackets", game.sentCopies - game.sentUnique, side);
                DiagnosticMetrics.record("rss.udp.game.tx.suppressed", game.suppressed, side);
                DiagnosticMetrics.record("rss.udp.game.report.expired", game.expiredReports, side);
                DiagnosticMetrics.record("rss.udp.game.report.invalid", game.invalidReports, side);
                DiagnosticMetrics.record("rss.udp.game.tx.queueSuppressed", game.queueSuppressed, side);
                DiagnosticMetrics.record("rss.udp.game.tx.rateSuppressed", game.rateSuppressed, side);
                DiagnosticMetrics.record("rss.udp.game.tx.globalSuppressed", game.globalSuppressed, side);
                DiagnosticMetrics.record("rss.udp.game.tx.changes", game.changes, side);
                DiagnosticMetrics.record("rss.udp.game.rx.physical", game.receivedCopies, side);
                DiagnosticMetrics.record("rss.udp.game.rx.unique", game.receivedUnique, side);
                DiagnosticMetrics.record("rss.udp.game.rx.sampled", game.sampled, side);
                DiagnosticMetrics.record("rss.udp.game.rx.missing", game.lost, side);
            }
            if (fec != null) {
                DiagnosticMetrics.record("rss.udp.fec.tx.dataBytes", fec.dataBytes, side);
                DiagnosticMetrics.record("rss.udp.fec.tx.parityBytes", fec.parityBytes, side);
                DiagnosticMetrics.record("rss.udp.fec.sessions.total", UdpDailyFec.totalSessions(), side);
                DiagnosticMetrics.record("rss.udp.fec.sessions.active", UdpDailyFec.activeSessions(), side);
                DiagnosticMetrics.record("rss.udp.fec.cache.bytes", UdpDailyFec.cachedXorBytes(), side);
                DiagnosticMetrics.record("rss.udp.fec.cache.peakBytes", UdpDailyFec.peakCachedXorBytes(), side);
                DiagnosticMetrics.record("rss.udp.daily.rx.sampled", fec.sampledData, side);
                DiagnosticMetrics.record("rss.udp.daily.rx.missing", fec.missingData, side);
                DiagnosticMetrics.record("rss.udp.daily.rx.recovered", fec.recoveredData, side);
                DiagnosticMetrics.record("rss.udp.daily.tx.data", fec.sentData, side);
                DiagnosticMetrics.record("rss.udp.daily.tx.protectedData", fec.protectedData, side);
                DiagnosticMetrics.record("rss.udp.daily.tx.parity", fec.paritySent, side);
                DiagnosticMetrics.record("rss.udp.daily.tx.skippedSmallGroups", fec.skippedSmallGroups, side);
                DiagnosticMetrics.record("rss.udp.daily.tx.unprotectedGroups", fec.unprotectedTxGroups, side);
                DiagnosticMetrics.record("rss.udp.daily.rx.evictedGroups", fec.evictedGroups, side);
                DiagnosticMetrics.record("rss.udp.daily.rx.unprotectedGroups", fec.unprotectedRxGroups, side);
            }
            DiagnosticMetrics.record("rss.udp.daily.tx.payloadBytes", payloadBytes, side);
            DiagnosticMetrics.record("rss.udp.daily.tx.encodedBytes", encodedBytes, side);
            DiagnosticMetrics.record("rss.udp.daily.tx.mtuDrops", mtuDrops, side);
            DiagnosticMetrics.record("rss.udp.daily.tx.finalMtuDrops", finalMtuDrops, side);
            if (fragmenter != null) {
                DiagnosticMetrics.record("rss.udp.daily.frag.tx.datagrams", fragmenter.txDatagrams, side);
                DiagnosticMetrics.record("rss.udp.daily.frag.tx.fragments", fragmenter.txFragments, side);
                DiagnosticMetrics.record("rss.udp.daily.frag.rx.reassembled", fragmenter.rxReassembled, side);
                DiagnosticMetrics.record("rss.udp.daily.frag.rx.timeout", fragmenter.rxTimeouts, side);
                DiagnosticMetrics.record("rss.udp.daily.frag.rx.duplicate", fragmenter.rxDuplicates, side);
                DiagnosticMetrics.record("rss.udp.daily.frag.rx.invalid", fragmenter.rxInvalid, side);
                DiagnosticMetrics.record("rss.udp.daily.frag.rx.budgetRejected", fragmenter.rxBudgetRejected, side);
            }
            if (unnegotiatedFragRxDrops != 0) {
                DiagnosticMetrics.record("rss.udp.daily.frag.rx.unnegotiated", unnegotiatedFragRxDrops, side);
            }
            if (compression != null) {
                DiagnosticMetrics.record("rss.udp.daily.tx.compressionSavedBytes", compressionSavedBytes, side);
            }
        }
        if (game != null) {
            game.expiredReports = game.invalidReports = 0;
            game.queueSuppressed = game.rateSuppressed = game.globalSuppressed = 0;
            game.sentCopies = game.sentUnique = game.suppressed = game.changes = 0;
            game.receivedCopies = game.receivedUnique = game.sampled = game.lost = 0;
        }
        payloadBytes = encodedBytes = compressionSavedBytes = 0;
        mtuDrops = finalMtuDrops = unnegotiatedFragRxDrops = 0;
        if (fragmenter != null) {
            fragmenter.txDatagrams = fragmenter.txFragments = 0;
            fragmenter.rxReassembled = fragmenter.rxTimeouts = fragmenter.rxDuplicates = 0;
            fragmenter.rxInvalid = fragmenter.rxBudgetRejected = 0;
        }
        if (fec != null) {
            fec.sampledData = fec.missingData = fec.recoveredData = fec.paritySent = 0;
            fec.sentData = fec.protectedData = fec.dataBytes = fec.parityBytes = 0;
            fec.skippedSmallGroups = fec.unprotectedTxGroups = fec.evictedGroups = fec.unprotectedRxGroups = 0;
        }
    }

    private long nextTimerDelayMillis() {
        long delay = -1L;
        if (fec != null) {
            delay = fec.nextDelayMillis();
            if (delay < 0) {
                delay = fec.nextIdleCleanupDelayMillis();
            }
        }
        if (fragmenter != null) {
            long fragDelay = fragmenter.nextDelayMillis(clock.getAsLong());
            if (fragDelay >= 0 && (delay < 0 || fragDelay < delay)) {
                delay = fragDelay;
            }
        }
        return delay;
    }

    private void scheduleTick() {
        long delay = nextTimerDelayMillis();
        if (delay < 0 || timer != null && timer.getDelay(TimeUnit.MILLISECONDS) <= delay) {
            return;
        }
        if (timer != null) {
            timer.cancel(false);
        }
        timer = main.executor().schedule(() -> {
            timer = null;
            if (main == null) {
                return;
            }
            if (fragmenter != null) {
                fragmenter.expire(clock.getAsLong());
            }
            if (fec != null) {
                if (weak && !returnPath.channel().isWritable()) fec.cancelPendingParity();
                fec.tick(emit);
            }
            scheduleTick();
        }, delay, TimeUnit.MILLISECONDS);
    }
}
