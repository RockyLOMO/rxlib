package org.rx.net.rss.tcp;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveUdpPortTest {
    @Test
    @Timeout(12)
    void authenticatesDirectionRejectsReplayAndKeepsNewestNatBinding() throws Exception {
        verifyPath("127.0.0.1");
    }

    @Test
    @Timeout(12)
    void supportsIpv6WithAuthenticatedReturnPath() throws Exception {
        verifyPath("::1");
    }

    @Test
    @Timeout(12)
    void supportsSinglePortNoHopOnServerAndClient() throws Exception {
        InetSocketAddress loopback = new InetSocketAddress("127.0.0.1", 0);
        EventLoopGroup group = new NioEventLoopGroup(1);
        BlockingQueue<Channel> accepted = new LinkedBlockingQueue<>();
        Channel listener = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInboundHandlerAdapter() {
                    @Override public void channelActive(ChannelHandlerContext ctx) {
                        accepted.add(ctx.channel());
                    }
                }).bind(loopback).sync().channel();
        Channel tcp = new Bootstrap().group(group).channel(NioSocketChannel.class)
                .handler(new ChannelInboundHandlerAdapter()).connect(listener.localAddress()).sync().channel();
        Channel remote = accepted.poll(2, TimeUnit.SECONDS);
        assertNotNull(remote);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        BlockingQueue<Integer> inbound = new LinkedBlockingQueue<>();
        try (AdaptiveUdpPort server = AdaptiveUdpPort.listen(loopback, 0, false, false)) {
            assertFalse(server.isHopping());
            assertEquals(server.port(), server.aliasPort());
            server.register(19, key, remote, frame -> server.send(19, frame));
            try (AdaptiveUdpPort client = AdaptiveUdpPort.client(19, key, tcp, server.port(), server.aliasPort(), frame -> {
                try { inbound.add(frame.readInt()); } finally { frame.release(); }
            })) {
                assertFalse(client.isHopping());
                assertEquals(client.port(), client.aliasPort());
                for (int i = 0; i < 30 && inbound.isEmpty(); i++) {
                    client.send(19, Unpooled.buffer(4).writeInt(42));
                    Thread.sleep(20);
                }
                assertEquals(Integer.valueOf(42), inbound.poll(1, TimeUnit.SECONDS));
            }
        } finally {
            tcp.close().sync();
            remote.close().sync();
            listener.close().sync();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    @Test
    @Timeout(12)
    void noHopClientRoutesThroughExternalUdp2rawForwarderAndServerAcceptsLoopback() throws Exception {
        InetSocketAddress loopback = new InetSocketAddress("127.0.0.1", 0);
        EventLoopGroup group = new NioEventLoopGroup(1);
        BlockingQueue<Channel> accepted = new LinkedBlockingQueue<>();
        Channel listener = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInboundHandlerAdapter() {
                    @Override public void channelActive(ChannelHandlerContext ctx) {
                        accepted.add(ctx.channel());
                    }
                }).bind(loopback).sync().channel();
        Channel tcp = new Bootstrap().group(group).channel(NioSocketChannel.class)
                .handler(new ChannelInboundHandlerAdapter()).connect(listener.localAddress()).sync().channel();
        Channel remote = accepted.poll(2, TimeUnit.SECONDS);
        assertNotNull(remote);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        BlockingQueue<Integer> inbound = new LinkedBlockingQueue<>();
        java.util.concurrent.atomic.AtomicInteger c2s = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger s2c = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);

        // Server stays globally hopping (like rssServer 20000-20127), while this single stream is no-hop via udp2raw.
        try (AdaptiveUdpPort server = AdaptiveUdpPort.listen(loopback, 0, false, true);
             DatagramSocket rawClientSide = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             DatagramSocket rawServerSide = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            assertTrue(server.isHopping());
            rawClientSide.setSoTimeout(200);
            rawServerSide.setSoTimeout(200);
            InetSocketAddress rawEntry = (InetSocketAddress) rawClientSide.getLocalSocketAddress();
            InetSocketAddress serverUdp = new InetSocketAddress("127.0.0.1", server.port());
            java.util.concurrent.atomic.AtomicReference<InetSocketAddress> lastClientPeer = new java.util.concurrent.atomic.AtomicReference<>();
            Thread c2sThread = new Thread(() -> {
                byte[] buf = new byte[2048];
                while (running.get()) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        rawClientSide.receive(p);
                        lastClientPeer.set((InetSocketAddress) p.getSocketAddress());
                        c2s.incrementAndGet();
                        rawServerSide.send(new DatagramPacket(p.getData(), p.getLength(), serverUdp));
                    } catch (java.net.SocketTimeoutException ignored) {
                    } catch (Exception e) {
                        if (running.get()) break;
                    }
                }
            });
            Thread s2cThread = new Thread(() -> {
                byte[] buf = new byte[2048];
                while (running.get()) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        rawServerSide.receive(p);
                        InetSocketAddress cli = lastClientPeer.get();
                        if (cli != null) {
                            s2c.incrementAndGet();
                            rawClientSide.send(new DatagramPacket(p.getData(), p.getLength(), cli));
                        }
                    } catch (java.net.SocketTimeoutException ignored) {
                    } catch (Exception e) {
                        if (running.get()) break;
                    }
                }
            });
            c2sThread.setDaemon(true);
            s2cThread.setDaemon(true);
            c2sThread.start();
            s2cThread.start();

            server.register(27, key, remote, false, frame -> server.send(27, frame));
            try (AdaptiveUdpPort client = AdaptiveUdpPort.client(27, key, tcp, server.port(), server.port(), rawEntry, frame -> {
                try { inbound.add(frame.readInt()); } finally { frame.release(); }
            })) {
                assertFalse(client.isHopping());
                assertEquals(rawEntry, client.peerAddress(27));
                // Tampered packet from loopback must still be rejected by HMAC.
                byte[] bad = packet(key, 1, 1);
                bad[17] ^= 1;
                rawServerSide.send(new DatagramPacket(bad, bad.length, serverUdp));

                for (int i = 0; i < 30 && inbound.isEmpty(); i++) {
                    client.send(27, Unpooled.buffer(4).writeInt(777));
                    Thread.sleep(20);
                }
                assertEquals(Integer.valueOf(777), inbound.poll(2, TimeUnit.SECONDS));
                assertTrue(c2s.get() > 0 && s2c.get() > 0,
                        "traffic must traverse external udp2raw forwarder c2s=" + c2s.get() + " s2c=" + s2c.get());
                assertEquals(rawServerSide.getLocalSocketAddress(), server.peerAddress(27));
            } finally {
                running.set(false);
            }
        } finally {
            tcp.close().sync();
            remote.close().sync();
            listener.close().sync();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    private void verifyPath(String host) throws Exception {
        InetSocketAddress loopback = new InetSocketAddress(host, 0);
        EventLoopGroup group = new NioEventLoopGroup(1);
        BlockingQueue<Channel> accepted = new LinkedBlockingQueue<>();
        Channel listener = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInboundHandlerAdapter() {
                    @Override public void channelActive(ChannelHandlerContext ctx) {
                        accepted.add(ctx.channel());
                    }
                }).bind(loopback).sync().channel();
        Channel tcp = new Bootstrap().group(group).channel(NioSocketChannel.class)
                .handler(new ChannelInboundHandlerAdapter()).connect(listener.localAddress()).sync().channel();
        Channel remote = accepted.poll(2, TimeUnit.SECONDS);
        assertNotNull(remote);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        BlockingQueue<Integer> frames = new LinkedBlockingQueue<>();
        String range = System.getenv("RSS_TEST_REDIRECTED_RANGE");
        int firstPort = range == null ? 0 : Integer.parseInt(range.split("-")[0]);
        try (AdaptiveUdpPort server = firstPort == 0 ? AdaptiveUdpPort.listen(loopback)
                : AdaptiveUdpPort.listen(new InetSocketAddress(loopback.getAddress(), firstPort), firstPort + 1, true);
             DatagramSocket first = new DatagramSocket(loopback);
             DatagramSocket next = new DatagramSocket(loopback)) {
            InetSocketAddress target = new InetSocketAddress(loopback.getAddress(), server.port());
            server.register(7, key, remote, frame -> {
                try {
                    frames.add(remote.eventLoop().inEventLoop() ? frame.readInt() : -1);
                } finally {
                    frame.release();
                }
            });
            byte[] corrupt = packet(key, 1, 2);
            corrupt[17] ^= 1;
            first.send(new DatagramPacket(corrupt, corrupt.length, target));
            send(first, target, packet(key, 2, 11));
            byte[] seen = packet(key, 1, 3);
            send(first, target, seen);
            send(first, target, seen);
            assertEquals(Integer.valueOf(3), frames.poll(2, TimeUnit.SECONDS));
            send(next, new InetSocketAddress(target.getAddress(), server.aliasPort()), packet(key, 1, 260));
            assertEquals(Integer.valueOf(260), frames.poll(2, TimeUnit.SECONDS));
            send(first, target, packet(key, 1, 4));
            assertEquals(Integer.valueOf(4), frames.poll(2, TimeUnit.SECONDS));
            send(first, target, packet(key, 1, 2));
            assertEquals(Integer.valueOf(2), frames.poll(2, TimeUnit.SECONDS));
            ByteBuf headroomFrame = Unpooled.buffer(24, 40).setIndex(20, 20).writeInt(99);
            server.send(7, headroomFrame);
            next.setSoTimeout(2000);
            DatagramPacket reply = new DatagramPacket(new byte[100], 100);
            next.receive(reply);
            assertEquals(server.aliasPort(), reply.getPort());
            assertEquals(99, ByteBuffer.wrap(reply.getData()).getInt(16));

            BlockingQueue<Integer> inbound = new LinkedBlockingQueue<>();
            try (AdaptiveUdpPort client = AdaptiveUdpPort.client(8, key, tcp, server.port(), server.aliasPort(), frame -> {
                try { inbound.add(frame.readInt()); } finally { frame.release(); }
            })) {
                server.register(8, key, remote, frame -> server.send(8, frame));
                for (int i = 0; i < 30 && inbound.isEmpty(); i++) {
                    client.send(8, Unpooled.buffer(4).writeInt(77));
                    Thread.sleep(20);
                }
                assertEquals(Integer.valueOf(77), inbound.poll(1, TimeUnit.SECONDS));
                server.unregister(8);
                ByteBuf dropped = Unpooled.buffer(4).writeInt(77);
                server.send(8, dropped);
                assertEquals(0, dropped.refCnt());
                assertTrue(tcp.isActive());
            }
        } finally {
            tcp.close().sync();
            remote.close().sync();
            listener.close().sync();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    private static void send(DatagramSocket socket, InetSocketAddress target, byte[] data) throws Exception {
        socket.send(new DatagramPacket(data, data.length, target));
    }

    private static byte[] packet(byte[] key, int direction, int seq) throws Exception {
        ByteBuffer wire = ByteBuffer.allocate(36).putLong(7).putLong(seq).putInt(seq);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update((byte) direction);
        mac.update(wire.array(), 0, wire.position());
        wire.put(mac.doFinal(), 0, 16);
        return wire.array();
    }
}
