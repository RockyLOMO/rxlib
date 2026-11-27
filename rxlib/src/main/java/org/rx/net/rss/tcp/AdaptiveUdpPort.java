package org.rx.net.rss.tcp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import org.rx.net.Sockets;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.net.udp.UdpSequenceWindow;

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
import java.util.concurrent.TimeUnit;

/** Authenticated, best-effort UDP path for streams negotiated over an existing TCP connection. */
public final class AdaptiveUdpPort implements AutoCloseable {
    public static final int MAX_FRAME_BYTES = 1400;
    private static final int PREFIX = 16, TAG = 16, MAX_PACKET = PREFIX + MAX_FRAME_BYTES + TAG;
    static int frameLimit(int wireMtu) {
        int limit = org.rx.net.rss.RssUdpMtuBudget.negotiate(wireMtu, PREFIX + AdaptiveTcpStream.MAX_UDP_FRAME + TAG);
        return limit - PREFIX - TAG;
    }

    private static final int QUEUE_LIMIT = 256;
    private static final byte CLIENT = 1, SERVER = 2;

    private final ConcurrentMap<Long, Peer> peers = new ConcurrentHashMap<>();
    private final AtomicInteger pendingSends = new AtomicInteger(), pendingReads = new AtomicInteger();
    private final ChannelFutureListener sendComplete = f -> pendingSends.decrementAndGet();
    private final boolean server;
    private final ChannelFuture bind, aliasBind;
    private final int virtualAlias;
    private volatile boolean closed;
    // Both sockets share one UDP EventLoop. Includes authenticated port-hop probes and framing.
    private long txPackets, txBytes, rxPackets, rxBytes;

    private AdaptiveUdpPort(boolean server, InetSocketAddress local, int alias, boolean redirect, boolean hopping) {
        this(server, local, alias, redirect, hopping, null, null, null, 0);
    }

    private AdaptiveUdpPort(boolean server, InetSocketAddress local, int alias, boolean redirect, boolean hopping,
                            org.rx.net.AuthenticEndpoint proxy, InetSocketAddress firstPeer, InetSocketAddress secondPeer, int outerMtu) {
        this.server = server;
        virtualAlias = redirect ? alias : 0;
        Bootstrap bootstrap = Sockets.udpBootstrap(null, ch -> {
            org.rx.net.socks.RssSocksCarrier carrier = proxy == null ? null
                    : new org.rx.net.socks.RssSocksCarrier(firstPeer, secondPeer, true, outerMtu);
            if (carrier != null) ch.pipeline().addLast(carrier);
            ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
            @Override public void channelActive(ChannelHandlerContext ctx) throws Exception {
                if (carrier != null) {
                    carrier.open(ctx.channel(), proxy).whenComplete((v, error) -> { if (error != null) ctx.close(); });
                }
                super.channelActive(ctx);
            }
            @Override protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                receive(ctx.channel(), packet);
            }
        }); }).option(ChannelOption.SO_REUSEADDR, false)
                .option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(MAX_PACKET + (proxy == null ? 1 : 23)));
        bind = bootstrap.bind(local);
        bind.channel().closeFuture().addListener(f -> recordTraffic());
        try {
            aliasBind = hopping && !redirect ? bootstrap.clone(bind.channel().eventLoop())
                    .bind(new InetSocketAddress(local.getAddress(), alias)) : null;
            if (aliasBind != null) aliasBind.channel().closeFuture().addListener(f -> recordTraffic());
        } catch (RuntimeException e) {
            bind.channel().close();
            throw e;
        }
    }

    /** Server startup only. Both real sockets share the EventLoop, MAC and replay window. */
    public static AdaptiveUdpPort listen(InetSocketAddress address) {
        return listen(address, 0, false, true);
    }

    public static AdaptiveUdpPort listen(InetSocketAddress address, int alias, boolean redirect) {
        return listen(address, alias, redirect, true);
    }

    public static AdaptiveUdpPort listen(InetSocketAddress address, int alias, boolean redirect, boolean hopping) {
        AdaptiveUdpPort port = new AdaptiveUdpPort(true, address, alias, redirect && hopping, hopping);
        try {
            port.bind.syncUninterruptibly();
            if (port.aliasBind != null) port.aliasBind.syncUninterruptibly();
            return port;
        } catch (RuntimeException e) {
            port.close();
            throw e;
        } catch (Throwable e) {
            // Netty can rethrow a checked BindException from syncUninterruptibly.
            port.close();
            throw new IllegalStateException("Cannot bind adaptive UDP port", e);
        }
    }

    public static AdaptiveUdpPort client(long id, byte[] key, Channel tcp, int primary, int alias, Consumer<ByteBuf> recv) {
        return client(id, key, tcp, primary, alias, null, recv);
    }

    public static AdaptiveUdpPort client(long id, byte[] key, Channel tcp, int primary, int alias,
                                         InetSocketAddress udpTarget, Consumer<ByteBuf> recv) {
        return client(id, key, tcp, primary, alias, udpTarget, MAX_FRAME_BYTES, recv);
    }

    static AdaptiveUdpPort client(long id, byte[] key, Channel tcp, int primary, int alias,
                                  InetSocketAddress udpTarget, int frameLimit, Consumer<ByteBuf> recv) {
        InetSocketAddress local = tcp.localAddress() instanceof InetSocketAddress
                ? (InetSocketAddress) tcp.localAddress() : null;
        boolean hopping = primary != alias;
        InetAddress targetIp;
        InetSocketAddress primaryAddr;
        InetSocketAddress aliasAddr;
        InetSocketAddress bindAddr;
        if (!hopping && udpTarget != null) {
            if (udpTarget.isUnresolved() || udpTarget.getPort() <= 0) {
                throw new IllegalArgumentException("Invalid adaptive UDP target: " + udpTarget);
            }
            targetIp = udpTarget.getAddress();
            primaryAddr = aliasAddr = udpTarget;
            if (targetIp.isLoopbackAddress()) {
                bindAddr = new InetSocketAddress(targetIp, 0);
            } else if (local != null && local.getAddress() != null
                    && local.getAddress().getClass() == targetIp.getClass()) {
                bindAddr = new InetSocketAddress(local.getAddress(), 0);
            } else {
                bindAddr = new InetSocketAddress(0);
            }
        } else {
            InetSocketAddress carrierPeer = tcp.attr(org.rx.net.socks.RssSocksCarrier.INNER_PEER).get();
            targetIp = carrierPeer != null ? carrierPeer.getAddress() : remoteIp(tcp);
            primaryAddr = new InetSocketAddress(targetIp, primary);
            aliasAddr = new InetSocketAddress(targetIp, alias);
            bindAddr = local != null && local.getAddress() != null
                    ? new InetSocketAddress(local.getAddress(), 0)
                    : new InetSocketAddress(0);
        }
        Integer outerMtu = tcp.attr(org.rx.net.socks.RssSocksCarrier.OUTER_MTU).get();
        AdaptiveUdpPort port = new AdaptiveUdpPort(false, bindAddr, 0, false, hopping,
                tcp.attr(org.rx.net.socks.RssSocksCarrier.OUTER_PROXY).get(), primaryAddr, aliasAddr, outerMtu == null ? 0 : outerMtu);
        try {
            Peer peer = new Peer(key, tcp, recv, targetIp, primaryAddr, false, frameLimit);
            peer.alias = aliasAddr;
            port.add(id, peer);
            ChannelFutureListener closeOnFailure = f -> { if (!f.isSuccess()) port.close(); };
            port.bind.addListener(closeOnFailure);
            if (port.aliasBind != null) port.aliasBind.addListener(closeOnFailure);
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

    public int aliasPort() {
        return aliasBind != null ? ((InetSocketAddress) aliasBind.channel().localAddress()).getPort()
                : virtualAlias != 0 ? virtualAlias : port();
    }

    public boolean isHopping() {
        return aliasBind != null || virtualAlias != 0;
    }

    public InetSocketAddress peerAddress(long id) {
        Peer peer = peers.get(id);
        return peer == null ? null : peer.address;
    }

    public void register(long id, byte[] key, Channel tcp, Consumer<ByteBuf> recv) {
        register(id, key, tcp, true, recv);
    }

    public void register(long id, byte[] key, Channel tcp, boolean hopping, Consumer<ByteBuf> recv) {
        register(id, key, tcp, hopping, MAX_FRAME_BYTES, recv);
    }

    void register(long id, byte[] key, Channel tcp, boolean hopping, int frameLimit, Consumer<ByteBuf> recv) {
        // No-hop streams may enter through a local single-port UDP tunnel (e.g. udp2raw -> 127.0.0.1:primary)
        // while TCP remains direct; still gated by stream id, replay window and per-stream HMAC-SHA256.
        Peer peer = new Peer(key, tcp, recv, remoteIp(tcp), null, !hopping, frameLimit);
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
        if (peer != null && frame.readableBytes() > peer.frameLimit) {
            DiagnosticMetrics.record("rss.tcp.adaptive.finalMtuDrop", 1);
            frame.release();
            return;
        }
        if (peer == null || peer.address == null || !bind.isSuccess() || !udp.isWritable() || !reserve(pendingSends)) {
            frame.release();
            return;
        }
        Runnable task = () -> {
            boolean submitted = false;
            try {
                if (!udp.isActive() || !udp.isWritable() || peers.get(id) != peer) {
                    return;
                }
                boolean alternate = !server && aliasBind != null && peer.hop(System.nanoTime()) != 0;
                Channel output = alternate ? aliasBind.channel() : (server && peer.returnPath != null ? peer.returnPath : udp);
                InetSocketAddress recipient = alternate ? peer.alias : peer.address;
                if (!output.isActive() || !output.isWritable()) return;
                int length = frame.readableBytes();
                ByteBuf wire = null;
                try {
                    if (frame.refCnt() == 1 && !frame.isReadOnly()
                            && frame.readerIndex() >= PREFIX && frame.maxWritableBytes() >= TAG) {
                        int start = frame.readerIndex() - PREFIX;
                        wire = frame.retain();
                        wire.setLong(start, id).setLong(start + 8, ++peer.sent).readerIndex(start);
                        peer.sign(server ? SERVER : CLIENT, wire, start, PREFIX + length);
                        wire.ensureWritable(TAG).writeBytes(peer.digest, 0, TAG);
                    } else {
                        wire = udp.alloc().ioBuffer(PREFIX + length + TAG, PREFIX + length + TAG);
                        wire.writeLong(id).writeLong(++peer.sent).writeBytes(frame, frame.readerIndex(), length);
                        peer.sign(server ? SERVER : CLIENT, wire, 0, PREFIX + length);
                        wire.writeBytes(peer.digest, 0, TAG);
                    }
                    if (wire.readableBytes() > PREFIX + peer.frameLimit + TAG) {
                        DiagnosticMetrics.record("rss.tcp.adaptive.finalMtuDrop", 1);
                        return;
                    }
                    ChannelFuture write = output.writeAndFlush(new DatagramPacket(wire, recipient));
                    wire = null;
                    submitted = true;
                    write.addListener(sendComplete);
                    txBytes += PREFIX + length + TAG;
                    txPackets++;
                    if (txPackets + rxPackets >= 256) recordTraffic();
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

    private void receive(Channel path, DatagramPacket packet) {
        ByteBuf wire = packet.content();
        int bytes = wire.readableBytes(), i = wire.readerIndex();
        if (bytes < PREFIX + TAG || bytes > MAX_PACKET) return;
        long id = wire.getLong(i), seq = wire.getLong(i + 8);
        Peer peer = peers.get(id);
        if (peer == null || bytes - PREFIX - TAG > peer.frameLimit) return;
        InetAddress senderIp = packet.sender().getAddress();
        boolean senderAllowed = peer.ip.equals(senderIp)
                || (server && peer.allowLoopback && senderIp.isLoopbackAddress());
        if (!senderAllowed
                || !server && !packet.sender().equals(peer.address) && !packet.sender().equals(peer.alias)
                || seq <= 0 || peer.replay.isDuplicate(seq)) return;

        peer.sign(server ? CLIENT : SERVER, wire, i, bytes - TAG);
        int difference = 0;
        for (int n = 0; n < TAG; n++) {
            difference |= wire.getByte(i + bytes - TAG + n) ^ peer.digest[n];
        }
        if (difference != 0) return;
        boolean newest = seq > peer.replay.highest();
        peer.replay.mark(seq);
        rxBytes += bytes;
        rxPackets++;
        if (txPackets + rxPackets >= 256) recordTraffic();
        if (server && newest) {
            peer.address = packet.sender();
            peer.returnPath = path;
        } else if (!server && aliasBind != null && peer.hop(System.nanoTime()) != peer.lastProbeHop) {
            // Keep downloads hopping even when there is no application uplink.
            peer.lastProbeHop = peer.hop(System.nanoTime());
            send(id, Unpooled.EMPTY_BUFFER);
        }
        if (bytes == PREFIX + TAG) return;
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

    private void recordTraffic() {
        if ((txPackets | rxPackets) != 0 && DiagnosticMetrics.isEnabled()) {
            // TX is submitted to Netty. RX passed authentication and replay checks. Excludes IP/UDP headers.
            DiagnosticMetrics.record("rss.tcp.adaptive.tx.transportBytes", txBytes, "path=udp");
            DiagnosticMetrics.record("rss.tcp.adaptive.tx.datagrams", txPackets, "path=udp");
            DiagnosticMetrics.record("rss.tcp.adaptive.rx.transportBytes", rxBytes, "path=udp");
            DiagnosticMetrics.record("rss.tcp.adaptive.rx.datagrams", rxPackets, "path=udp");
        }
        txPackets = txBytes = rxPackets = rxBytes = 0;
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
        if (aliasBind != null) aliasBind.channel().close();
    }

    private static final class Peer {
        final InetAddress ip;
        final Channel tcp;
        final Consumer<ByteBuf> recv;
        final Mac mac;
        final boolean allowLoopback;
        final int frameLimit;
        final byte[] digest = new byte[32];
        volatile InetSocketAddress address;
        InetSocketAddress alias;
        Channel returnPath;
        final long started = System.nanoTime();
        int lastProbeHop;
        final UdpSequenceWindow replay = new UdpSequenceWindow(UdpSequenceWindow.FEC_SIZE);
        long sent;

        int hop(long now) {
            return (int) ((now - started) / TimeUnit.SECONDS.toNanos(30)) & 1;
        }

        Peer(byte[] key, Channel tcp, Consumer<ByteBuf> recv, InetAddress ip, InetSocketAddress address,
             boolean allowLoopback, int frameLimit) {
            this.frameLimit = frameLimit;
            if (key.length < 16) throw new IllegalArgumentException("short UDP session key");
            this.ip = ip;
            this.tcp = tcp;
            this.recv = recv;
            this.address = address;
            this.allowLoopback = allowLoopback;
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
    }
}
