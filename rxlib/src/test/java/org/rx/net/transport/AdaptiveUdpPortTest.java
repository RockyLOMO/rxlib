package org.rx.net.transport;

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
            byte[] corrupt = packet(key, 1, 10);
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
            server.send(7, Unpooled.buffer(4).writeInt(99));
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
