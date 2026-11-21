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
    static final long HOP_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final long tokenHigh, tokenLow;
    private final InetSocketAddress primary, alias;
    private final Predicate<InetSocketAddress> knownTarget;
    private final LongSupplier clock;
    private final Consumer<ByteBuf> emit = this::sendFrame;
    private final Consumer<ByteBuf> deliver = this::deliver;
    private ChannelHandlerContext main, returnPath;
    private InetSocketAddress logicalPeer, physicalPeer;
    private RecvByteBufAllocator originalAllocator;
    private UdpDailyFec fec;
    private ScheduledFuture<?> timer;
    private ChannelPromise dataPromise;
    private long started, lastMetrics, lastProbe, payloadBytes, encodedBytes;
    private int epoch, wireMtu;

    public static DailyUdpWire client(String token, InetSocketAddress primary, InetSocketAddress alias, int mtu) {
        DailyUdpWire wire = new DailyUdpWire(token, primary, alias, null, System::nanoTime);
        wire.wireMtu = mtu;
        return wire;
    }

    public static DailyUdpWire server(String token, Predicate<InetSocketAddress> knownTarget) {
        return new DailyUdpWire(token, null, null, knownTarget, System::nanoTime);
    }

    DailyUdpWire(String token, InetSocketAddress primary, InetSocketAddress alias,
                 Predicate<InetSocketAddress> knownTarget, LongSupplier clock) {
        UUID id = UUID.fromString(token);
        tokenHigh = id.getMostSignificantBits();
        tokenLow = id.getLeastSignificantBits();
        this.primary = logicalPeer = physicalPeer = primary;
        this.alias = alias;
        this.knownTarget = knownTarget;
        this.clock = clock;
    }

    public static void install(ChannelPipeline pipeline, DailyUdpWire wire) {
        if (pipeline.get(Sockets.UDP_FINAL_EGRESS_GUARD) == null) {
            pipeline.addFirst("dailyUdp", wire);
        } else {
            pipeline.addAfter(Sockets.UDP_FINAL_EGRESS_GUARD, "dailyUdp", wire);
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
        originalAllocator = ctx.channel().config().getRecvByteBufAllocator();
        int channelMtu = sizeReceiveBuffer(ctx);
        if (wireMtu <= 0 || channelMtu > 0 && channelMtu < wireMtu) {
            wireMtu = channelMtu;
        }
        fec = new UdpDailyFec(ctx.alloc());
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
        ctx.channel().config().setRecvByteBufAllocator(originalAllocator);
        recordMetrics();
        fec.close();
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
        try {
            ByteBuf b = packet.content();
            int pos = b.readerIndex();
            if (main == null || b.readableBytes() < HEADER_BYTES || wireMtu > 0 && b.readableBytes() > wireMtu
                    || b.getLong(pos) != tokenHigh
                    || b.getLong(pos + 8) != tokenLow) {
                return;
            }
            int generation = b.getInt(pos + 16);
            if (primary == null) {
                if (logicalPeer == null) {
                    logicalPeer = sender;
                    epoch = generation;
                }
                // A reordered frame from the previous hop can still repair data, but cannot move the return path.
                if (!logicalPeer.getAddress().equals(sender.getAddress())) {
                    return;
                }
                if (generation - epoch >= 0) {
                    epoch = generation;
                    physicalPeer = sender;
                    returnPath = path;
                }
            } else if (rotate() || generation != epoch && clock.getAsLong() - lastProbe >= TimeUnit.SECONDS.toNanos(1)) {
                lastProbe = clock.getAsLong();
                // Open the new NAT binding even during a download with no application uplink.
                sendFrame(main.alloc().ioBuffer(HEADER_BYTES, HEADER_BYTES).setIndex(HEADER_BYTES, HEADER_BYTES));
            }
            if (b.readableBytes() > HEADER_BYTES) {
                b.skipBytes(HEADER_BYTES);
                fec.read(b, deliver, emit);
                if (clock.getAsLong() - lastMetrics >= TimeUnit.SECONDS.toNanos(30)) {
                    recordMetrics();
                }
                scheduleTick();
            }
        } finally {
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
        try {
            if (wireMtu > 0 && packet.content().readableBytes() > wireMtu - HEADER_BYTES - UdpDailyFec.MAX_OVERHEAD) {
                DiagnosticMetrics.record("rss.udp.daily.mtuDrop", 1);
                promise.trySuccess();
                return;
            }
            if (primary != null) {
                rotate();
            }
            payloadBytes += packet.content().readableBytes();
            dataPromise = promise;
            fec.write(packet.content(), emit);
            scheduleTick();
        } catch (RuntimeException e) {
            promise.tryFailure(e);
        } finally {
            dataPromise = null;
            packet.release();
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
        if (dataPromise != null && frame.isReadable() && frame.getUnsignedByte(pos) <= UdpDailyFec.SHARD) {
            pending = dataPromise;
            dataPromise = null;
        }
        frame.setLong(pos - HEADER_BYTES, tokenHigh).setLong(pos - HEADER_BYTES + 8, tokenLow)
                .setInt(pos - 4, epoch).readerIndex(pos - HEADER_BYTES);
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
        main.fireChannelRead(new DatagramPacket(payload, (InetSocketAddress) main.channel().localAddress(), logicalPeer));
    }

    private void recordMetrics() {
        lastMetrics = clock.getAsLong();
        if (DiagnosticMetrics.isEnabled()) {
            String side = primary == null ? "side=server" : "side=client";
            DiagnosticMetrics.record("rss.udp.daily.rx.sampled", fec.sampledData, side);
            DiagnosticMetrics.record("rss.udp.daily.rx.missing", fec.missingData, side);
            DiagnosticMetrics.record("rss.udp.daily.rx.recovered", fec.recoveredData, side);
            DiagnosticMetrics.record("rss.udp.daily.tx.parity", fec.paritySent, side);
            DiagnosticMetrics.record("rss.udp.daily.tx.payloadBytes", payloadBytes, side);
            DiagnosticMetrics.record("rss.udp.daily.tx.encodedBytes", encodedBytes, side);
        }
        payloadBytes = encodedBytes = 0;
        fec.sampledData = fec.missingData = fec.recoveredData = fec.paritySent = 0;
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
            fec.tick(emit);
            scheduleTick();
        }, delay, TimeUnit.MILLISECONDS);
    }
}
