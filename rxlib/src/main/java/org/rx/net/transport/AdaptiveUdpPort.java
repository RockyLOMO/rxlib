package org.rx.net.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import org.rx.net.Sockets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Authenticated, best-effort UDP path for streams negotiated over an existing TCP connection. */
public final class AdaptiveUdpPort implements AutoCloseable {
    public static final int MAX_FRAME_BYTES = 1400;
    private static final int PREFIX = 16, TAG = 16, MAX_PACKET = PREFIX + MAX_FRAME_BYTES + TAG;
    private static final int QUEUE_LIMIT = 256;
    private static final byte CLIENT = 1, SERVER = 2;

    private final ConcurrentMap<Long, Peer> peers = new ConcurrentHashMap<>();
    private final AtomicInteger pendingSends = new AtomicInteger(), pendingReads = new AtomicInteger();
    private final ChannelFutureListener sendComplete = f -> pendingSends.decrementAndGet();
    private final boolean server;
    private final ChannelFuture bind;
    private volatile boolean closed;

    private AdaptiveUdpPort(boolean server, InetSocketAddress local) {
        this.server = server;
        bind = Sockets.udpBootstrap(null, ch -> ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
            @Override protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                receive(packet);
            }
        })).option(ChannelOption.SO_REUSEADDR, false)
                .option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(MAX_PACKET + 1)).bind(local);
    }

    /** Only invoked at server startup, outside any event loop. */
    public static AdaptiveUdpPort listen(InetSocketAddress address) {
        AdaptiveUdpPort port = new AdaptiveUdpPort(true, address);
        port.bind.syncUninterruptibly();
        return port;
    }

    public static AdaptiveUdpPort client(long id, byte[] key, Channel tcp, InetSocketAddress target, Consumer<ByteBuf> recv) {
        InetAddress ip = remoteIp(tcp);
        InetSocketAddress local = (InetSocketAddress) tcp.localAddress();
        AdaptiveUdpPort port = new AdaptiveUdpPort(false, new InetSocketAddress(local.getAddress(), 0));
        try {
            port.add(id, new Peer(key, tcp, recv, ip, new InetSocketAddress(ip, target.getPort())));
            port.bind.addListener(f -> { if (!f.isSuccess()) port.close(); });
            tcp.closeFuture().addListener(f -> port.close());
            return port;
        } catch (RuntimeException e) {
            port.close();
            throw e;
        }
    }

    private static InetAddress remoteIp(Channel tcp) {
        if (!(tcp.remoteAddress() instanceof InetSocketAddress)) {
            throw new IllegalArgumentException("UDP acceleration requires a connected IP transport");
        }
        return ((InetSocketAddress) tcp.remoteAddress()).getAddress();
    }

    public int port() {
        InetSocketAddress address = (InetSocketAddress) bind.channel().localAddress();
        return address == null ? 0 : address.getPort();
    }

    public void register(long id, byte[] key, Channel tcp, Consumer<ByteBuf> recv) {
        Peer peer = new Peer(key, tcp, recv, remoteIp(tcp), null);
        add(id, peer);
        tcp.closeFuture().addListener(f -> {
            Peer current = peers.get(id);
            if (current != null && current.tcp == tcp) peers.remove(id, current);
        });
    }

    private void add(long id, Peer peer) {
        if (closed || peers.putIfAbsent(id, peer) != null) {
            throw new IllegalStateException("UDP port closed or duplicate stream id");
        }
        if (closed) {
            peers.remove(id, peer);
            throw new IllegalStateException("UDP port closed");
        }
    }

    public void unregister(long id) {
        peers.remove(id);
    }

    /** Always consumes frame. Packets lost to backpressure are recovered by the stream protocol. */
    public void send(long id, ByteBuf frame) {
        Channel udp = bind.channel();
        Peer peer = peers.get(id);
        if (peer == null || peer.address == null || !bind.isSuccess() || !udp.isWritable()
                || frame.readableBytes() > MAX_FRAME_BYTES || !reserve(pendingSends)) {
            frame.release();
            return;
        }
        Runnable task = () -> {
            boolean submitted = false;
            try {
                if (!udp.isActive() || !udp.isWritable() || peers.get(id) != peer) {
                    return;
                }
                int length = frame.readableBytes();
                ByteBuf wire = udp.alloc().ioBuffer(PREFIX + length + TAG, PREFIX + length + TAG);
                try {
                    wire.writeLong(id).writeLong(++peer.sent).writeBytes(frame, frame.readerIndex(), length);
                    peer.sign(server ? SERVER : CLIENT, wire, 0, PREFIX + length);
                    wire.writeBytes(peer.digest, 0, TAG);
                    ChannelFuture write = udp.writeAndFlush(new DatagramPacket(wire, peer.address));
                    wire = null;
                    submitted = true;
                    write.addListener(sendComplete);
                } finally {
                    if (wire != null) wire.release();
                }
            } finally {
                frame.release();
                if (!submitted) pendingSends.decrementAndGet();
            }
        };
        execute(udp.eventLoop(), task, frame, pendingSends);
    }

    private void receive(DatagramPacket packet) {
        ByteBuf wire = packet.content();
        int bytes = wire.readableBytes(), i = wire.readerIndex();
        if (bytes < PREFIX + TAG || bytes > MAX_PACKET) return;
        long id = wire.getLong(i), seq = wire.getLong(i + 8);
        Peer peer = peers.get(id);
        if (peer == null || !peer.ip.equals(packet.sender().getAddress())
                || !server && !packet.sender().equals(peer.address) || seq <= 0) return;

        peer.sign(server ? CLIENT : SERVER, wire, i, bytes - TAG);
        int difference = 0;
        for (int n = 0; n < TAG; n++) {
            difference |= wire.getByte(i + bytes - TAG + n) ^ peer.digest[n];
        }
        if (difference != 0) return;
        boolean newest = seq > peer.highest;
        if (!peer.accept(seq)) return;
        if (server && newest) peer.address = packet.sender();
        if (!reserve(pendingReads)) return;
        ByteBuf frame = wire.retainedSlice(i + PREFIX, bytes - PREFIX - TAG);
        execute(peer.tcp.eventLoop(), () -> {
            try {
                if (peers.get(id) == peer && peer.tcp.isActive()) {
                    peer.recv.accept(frame);
                } else {
                    frame.release();
                }
            } finally {
                pendingReads.decrementAndGet();
            }
        }, frame, pendingReads);
    }

    private static void execute(EventLoop loop, Runnable task, ByteBuf frame, AtomicInteger budget) {
        if (loop.inEventLoop()) {
            task.run();
            return;
        }
        try {
            loop.execute(task);
        } catch (RejectedExecutionException e) {
            frame.release();
            budget.decrementAndGet();
        }
    }

    private static boolean reserve(AtomicInteger count) {
        if (count.incrementAndGet() <= QUEUE_LIMIT) return true;
        count.decrementAndGet();
        return false;
    }

    @Override public void close() {
        closed = true;
        peers.clear();
        bind.channel().close();
    }

    private static final class Peer {
        final InetAddress ip;
        final Channel tcp;
        final Consumer<ByteBuf> recv;
        final Mac mac;
        final byte[] digest = new byte[32];
        volatile InetSocketAddress address;
        long sent, highest, seen;

        Peer(byte[] key, Channel tcp, Consumer<ByteBuf> recv, InetAddress ip, InetSocketAddress address) {
            if (key.length < 16) throw new IllegalArgumentException("short UDP session key");
            this.ip = ip;
            this.tcp = tcp;
            this.recv = recv;
            this.address = address;
            try {
                mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(key, "HmacSHA256"));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        // Both the MAC and replay window are confined to the UDP socket's event loop.
        void sign(byte direction, ByteBuf bytes, int offset, int length) {
            mac.update(direction);
            mac.update(bytes.nioBuffer(offset, length));
            try {
                mac.doFinal(digest, 0);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        boolean accept(long seq) {
            if (seq > highest) {
                long advance = seq - highest;
                seen = advance >= 64 ? 1L : (seen << advance) | 1L;
                highest = seq;
                return true;
            }
            long age = highest - seq;
            if (age >= 64 || (seen & (1L << age)) != 0) return false;
            seen |= 1L << age;
            return true;
        }
    }
}
