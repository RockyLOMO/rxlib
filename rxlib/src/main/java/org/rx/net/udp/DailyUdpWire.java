package org.rx.net.udp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AttributeKey;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** One RPC-negotiated UDP association. Both server ports run on the primary relay's event loop. */
public final class DailyUdpWire extends ChannelDuplexHandler {
    public static final int HEADER_BYTES = UdpDailyFec.WIRE_HEADROOM;
    public static final int LZ4 = 1, AES = 2, FEC = 4, GAME = 8, WEAK = 16;
    static final long HOP_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final AttributeKey<RecvByteBufAllocator> ORIGINAL_ALLOCATOR = AttributeKey.valueOf("rssDailyOriginalAllocator");

    private final long tokenHigh, tokenLow;
    private final InetSocketAddress primary, alias;
    private final Predicate<InetSocketAddress> knownTarget;
    private final LongSupplier clock;
    private final DailyUdpCompression compression;
    private final DailyUdpCipher cipher;
    private final DailyUdpMac mac;
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
    private int epoch, wireMtu;

    private static final class Key {
        static final byte[] PSK = DailyUdpCipher.configuredKey();
    }

    public static boolean hasCipherKey() {
        return Key.PSK != null;
    }

    public static DailyUdpWire client(String token, InetSocketAddress primary, InetSocketAddress alias,
                                      int mtu, int features, UUID salt) {
        return client(token, primary, alias, mtu, features, salt, null);
    }

    public static DailyUdpWire client(String token, InetSocketAddress primary, InetSocketAddress alias,
                                      int mtu, int features, UUID salt, byte[] authSecret) {
        DailyUdpWire wire = new DailyUdpWire(token, primary, alias, null, System::nanoTime, features, salt,
                (features & AES) != 0 ? Key.PSK : authSecret);
        wire.wireMtu = mtu;
        return wire;
    }

    public static DailyUdpWire server(String token, Predicate<InetSocketAddress> knownTarget, int features, UUID salt) {
        return server(token, knownTarget, features, salt, null);
    }

    public static DailyUdpWire server(String token, Predicate<InetSocketAddress> knownTarget, int features,
                                      UUID salt, byte[] authSecret) {
        return new DailyUdpWire(token, null, null, knownTarget, System::nanoTime, features, salt,
                (features & AES) != 0 ? Key.PSK : authSecret);
    }

    DailyUdpWire(String token, InetSocketAddress primary, InetSocketAddress alias, Predicate<InetSocketAddress> knownTarget,
                 LongSupplier clock, int features, UUID salt, byte[] key) {
        if (features <= 0 || (features & ~(LZ4 | AES | FEC | GAME | WEAK)) != 0
                || (features & GAME) != 0 && (features & FEC) != 0
                || (features & WEAK) != 0 && (features & FEC) == 0) {
            throw new IllegalArgumentException("invalid UDP extension mask");
        }
        UUID id = UUID.fromString(token);
        useFec = (features & FEC) != 0;
        weak = (features & WEAK) != 0;
        game = (features & GAME) != 0 ? new UdpGameRedundancy() : null;
        mac = (game != null || weak) && (features & AES) == 0 ? new DailyUdpMac(key, id, features, primary == null) : null;
        cipher = (features & AES) != 0 ? new DailyUdpCipher(key, id, salt, primary == null, features) : null;
        tokenHigh = id.getMostSignificantBits();
        tokenLow = id.getLeastSignificantBits();
        this.primary = logicalPeer = physicalPeer = primary;
        this.alias = alias;
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
        int channelMtu = sizeReceiveBuffer(ctx);
        if (wireMtu <= 0 || channelMtu > 0 && channelMtu < wireMtu) {
            wireMtu = channelMtu;
        }
        fec = useFec ? new UdpDailyFec(ctx.alloc(), clock, weak, wireOverhead()) : null;
        started = lastMetrics = clock.getAsLong();
    }

    private static int sizeReceiveBuffer(ChannelHandlerContext ctx) {
        // Datagram reads truncate silently if an adaptive allocator guessed too small for the first packet.
        SocketConfig config = ctx.channel().attr(SocketConfig.ATTR_CONF).get();
        int mtu = config == null ? 0 : config.getUdpMtu();
        int capacity = mtu <= 0 ? 65536 : Math.min(65535, mtu) + 1;
        ctx.channel().config().setRecvByteBufAllocator(new FixedRecvByteBufAllocator(capacity));
        return mtu;
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
        if (fec != null) fec.close();
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
                decoded = cipher != null ? cipher.open(b) : mac.open(b);
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
            } else if (rotate() || generation != epoch && clock.getAsLong() - lastProbe >= TimeUnit.SECONDS.toNanos(1)) {
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
            if (packet.content().readableBytes() > maxPayload()) {
                DiagnosticMetrics.record("rss.udp.daily.mtuDrop", 1);
                promise.trySuccess();
                return;
            }
            if (primary != null) {
                rotate();
            }
            payloadBytes += packet.content().readableBytes();
            dataPromise = promise;
            block = compression == null ? null : compression.tryEncode(packet.content(), clock.getAsLong());
            if (block != null) {
                compressionSavedBytes += packet.content().readableBytes() - block.readableBytes();
            }
            ByteBuf data = block == null ? packet.content() : block;
            if (game != null) {
                sendGame(data);
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

    private void sendGame(ByteBuf data) {
        long now = clock.getAsLong();
        int seq = game.begin(now), len = data.readableBytes();
        int body = UdpGameRedundancy.DATA_HEADER + len;
        ByteBuf frame = main.alloc().ioBuffer(HEADER_BYTES + body, HEADER_BYTES + body).setIndex(HEADER_BYTES, HEADER_BYTES);
        try {
            frame.writeByte(0).writeInt(seq).writeBytes(data, data.readerIndex(), len);
            // seal() consumes the borrowed bytes synchronously and creates an independent authenticated datagram.
            sendFrame(frame.retainedDuplicate());
            for (int copy = 1; copy < game.copies; copy++) {
                if (!game.extra(seq, wireOverhead() + body, returnPath.channel().isWritable(), now)) break;
                frame.setByte(HEADER_BYTES, copy);
                sendFrame(frame.retainedDuplicate());
            }
        } finally {
            frame.release();
        }
    }

    private boolean rotate() {
        int next = (int) ((clock.getAsLong() - started) / HOP_NANOS);
        physicalPeer = (next & 1) == 0 ? primary : alias;
        boolean changed = epoch != next;
        epoch = next;
        return changed;
    }

    private void sendFrame(ByteBuf frame) {
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
                frame = cipher != null ? cipher.seal(plain) : mac.seal(plain);
            } catch (RuntimeException failure) {
                // A fresh association will negotiate new salts / directional keys.
                main.close();
                throw failure;
            } finally {
                plain.release();
            }
        }
        encodedBytes += frame.readableBytes();
        DatagramPacket packet = new DatagramPacket(frame, physicalPeer);
        if (pending == null || pending.isVoid()) {
            returnPath.writeAndFlush(packet, returnPath.voidPromise());
        } else if (pending.channel() == returnPath.channel()) {
            returnPath.writeAndFlush(packet, pending);
        } else {
            ChannelPromise original = pending;
            returnPath.writeAndFlush(packet).addListener(f -> {
                if (f.isSuccess()) {
                    original.trySuccess();
                } else {
                    original.tryFailure(f.cause());
                }
            });
        }
    }

    private void deliver(ByteBuf payload) {
        if (compression != null) {
            payload = DailyUdpCompression.decode(payload, maxPayload());
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

    private void recordMetrics() {
        lastMetrics = clock.getAsLong();
        if (DiagnosticMetrics.isEnabled()) {
            String side = primary == null ? "side=server" : "side=client";
            if (game != null) {
                DiagnosticMetrics.record("rss.udp.game.tx.multiplier", game.copies, side);
                DiagnosticMetrics.record("rss.udp.game.tx.extraPackets", game.sentCopies - game.sentUnique, side);
                DiagnosticMetrics.record("rss.udp.game.tx.suppressed", game.suppressed, side);
                DiagnosticMetrics.record("rss.udp.game.tx.changes", game.changes, side);
                DiagnosticMetrics.record("rss.udp.game.rx.physical", game.receivedCopies, side);
                DiagnosticMetrics.record("rss.udp.game.rx.unique", game.receivedUnique, side);
                DiagnosticMetrics.record("rss.udp.game.rx.sampled", game.sampled, side);
                DiagnosticMetrics.record("rss.udp.game.rx.missing", game.lost, side);
            }
            if (fec != null) {
                DiagnosticMetrics.record("rss.udp.fec.tx.dataBytes", fec.dataBytes, side);
                DiagnosticMetrics.record("rss.udp.fec.tx.parityBytes", fec.parityBytes, side);
                DiagnosticMetrics.record("rss.udp.daily.rx.sampled", fec.sampledData, side);
                DiagnosticMetrics.record("rss.udp.daily.rx.missing", fec.missingData, side);
                DiagnosticMetrics.record("rss.udp.daily.rx.recovered", fec.recoveredData, side);
                DiagnosticMetrics.record("rss.udp.daily.tx.data", fec.sentData, side);
                DiagnosticMetrics.record("rss.udp.daily.tx.protectedData", fec.protectedData, side);
                DiagnosticMetrics.record("rss.udp.daily.tx.parity", fec.paritySent, side);
                DiagnosticMetrics.record("rss.udp.daily.tx.skippedSmallGroups", fec.skippedSmallGroups, side);
                DiagnosticMetrics.record("rss.udp.daily.rx.evictedGroups", fec.evictedGroups, side);
            }
            DiagnosticMetrics.record("rss.udp.daily.tx.payloadBytes", payloadBytes, side);
            DiagnosticMetrics.record("rss.udp.daily.tx.encodedBytes", encodedBytes, side);
            if (compression != null) {
                DiagnosticMetrics.record("rss.udp.daily.tx.compressionSavedBytes", compressionSavedBytes, side);
            }
        }
        if (game != null) {
            game.sentCopies = game.sentUnique = game.suppressed = game.changes = 0;
            game.receivedCopies = game.receivedUnique = game.sampled = game.lost = 0;
        }
        payloadBytes = encodedBytes = compressionSavedBytes = 0;
        if (fec != null) {
            fec.sampledData = fec.missingData = fec.recoveredData = fec.paritySent = 0;
            fec.sentData = fec.protectedData = fec.dataBytes = fec.parityBytes = 0;
            fec.skippedSmallGroups = fec.evictedGroups = 0;
        }
    }

    private void scheduleTick() {
        long delay = fec.nextDelayMillis();
        if (delay < 0 || timer != null && timer.getDelay(TimeUnit.MILLISECONDS) <= delay) {
            return;
        }
        if (timer != null) {
            timer.cancel(false);
        }
        timer = main.executor().schedule(() -> {
            timer = null;
            if (weak && !returnPath.channel().isWritable()) fec.cancelPendingParity();
            fec.tick(emit);
            scheduleTick();
        }, delay, TimeUnit.MILLISECONDS);
    }
}
