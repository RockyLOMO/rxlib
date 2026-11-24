package org.rx.net.udp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.socket.DatagramPacket;
import org.junit.jupiter.api.Test;
import org.rx.net.Sockets;
import org.rx.net.socks.*;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class DailyUdpWireTest {
    @Test
    void ipv4ChangesReturnPortAndKeepsTargetTuple() throws Exception {
        verifyHops(InetAddress.getByName("127.0.0.1"), DailyUdpWire.FEC);
    }

    @Test
    void ipv6ChangesReturnPortAndKeepsTargetTuple() throws Exception {
        verifyHops(InetAddress.getByName("::1"), DailyUdpWire.FEC);
    }

    @Test
    void negotiatedCompressionShrinksBothHopRepliesAndPreservesRawTarget() throws Exception {
        verifyHops(InetAddress.getByName("127.0.0.1"), DailyUdpWire.LZ4);
        verifyHops(InetAddress.getByName("::1"), DailyUdpWire.LZ4 | DailyUdpWire.FEC);
    }

    @Test
    void authenticatedUdpCombinationsHopAndEcho() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(DailyUdpWire.hasCipherKey(), "test deployment key required");
        for (int flags : new int[]{2, 3, 6, 7}) {
            verifyHops(InetAddress.getByName("127.0.0.1"), flags);
        }
    }

    @Test
    void gameAndWeakHopWithAuthenticatedFeedback() throws Exception {
        verifyHops(InetAddress.getByName("127.0.0.1"), DailyUdpWire.GAME);
        verifyHops(InetAddress.getByName("127.0.0.1"), DailyUdpWire.GAME | DailyUdpWire.FEC);
        verifyHops(InetAddress.getByName("::1"), DailyUdpWire.WEAK | DailyUdpWire.FEC | DailyUdpWire.LZ4);
        if (DailyUdpWire.hasCipherKey()) {
            verifyHops(InetAddress.getByName("::1"), DailyUdpWire.GAME | DailyUdpWire.AES);
        }
    }

    private void verifyHops(InetAddress ip, int flags) throws Exception {
        boolean lz4 = (flags & DailyUdpWire.LZ4) != 0;
        SocksConfig config = new SocksConfig(new LocalAddress(UUID.randomUUID().toString()));
        config.setDailyUdpFeatures(31);
        String redirectedRange = System.getenv("RSS_TEST_REDIRECTED_RANGE");
        if (redirectedRange != null) {
            config.setDailyPortRange(redirectedRange);
            config.setRedirectUdpHops(true);
        }
        try (DatagramSocket target = new DatagramSocket(new InetSocketAddress(ip, 0));
             SocksProxyServer server = new SocksProxyServer(config)) {
            target.setSoTimeout(5000);
            UdpRelayGroupOpenRequest req = new UdpRelayGroupOpenRequest();
            req.setMaxRelayCount(2);
            req.setDailyFeatures(flags);
            if ((flags & (DailyUdpWire.GAME | DailyUdpWire.WEAK)) != 0) req.setProfileVersion(1);
            req.setClientSalt(UUID.randomUUID());
            UdpRelayGroupOpenResult result = server.openUdpRelayGroup(req, SocksRpcContract.rpcToken());
            assertTrue(result.isSuccess(), result.getErrorCode());
            InetSocketAddress primary = new InetSocketAddress(ip, result.getRelays().get(0).getRelayAddress().getPort());
            InetSocketAddress alias = new InetSocketAddress(ip, result.getRelays().get(1).getRelayAddress().getPort());
            AtomicLong clock = new AtomicLong();
            BlockingQueue<Integer> wirePorts = new LinkedBlockingQueue<>(), delivered = new LinkedBlockingQueue<>();
            BlockingQueue<Integer> wireSizes = new LinkedBlockingQueue<>();
            Channel client = Sockets.udpBootstrap(null, ch -> {
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    private int generation = -1;
                    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        DatagramPacket pkt = (DatagramPacket) msg;
                        int hop = pkt.content().getInt(pkt.content().readerIndex() + 16);
                        if (hop > generation) {
                            generation = hop;
                            wirePorts.add(pkt.sender().getPort());
                            wireSizes.add(pkt.content().readableBytes());
                        }
                        ctx.fireChannelRead(msg);
                    }
                });
                ch.pipeline().addLast(new DailyUdpWire(result.getToken(), primary, alias, null, clock::get, flags, req.getClientSalt(),
                        (flags & DailyUdpWire.AES) != 0 ? DailyUdpCipher.configuredKey() : result.getAuthSecret()));
                ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                        ByteBuf b = packet.content();
                        UdpManager.socks5Decode(b);
                        delivered.add(b.readableBytes() == 1200 ? b.readInt() : -1);
                    }
                });
            }).bind(new InetSocketAddress(ip, 0)).sync().channel();
            try {
                for (int i = 0; i < 3; i++) {
                    clock.set(i * DailyUdpWire.HOP_NANOS);
                    int value = 1200 << 16 | i; // A raw target payload beginning with a plausible LZ4 length.
                    ByteBuf b = UdpManager.socks5Encode(client.alloc().buffer(1200).writeInt(value).writeZero(1196),
                            (InetSocketAddress) target.getLocalSocketAddress());
                    client.writeAndFlush(new DatagramPacket(b, primary)).sync();
                    java.net.DatagramPacket received = new java.net.DatagramPacket(new byte[1200], 1200);
                    target.receive(received);
                    assertEquals(primary.getPort(), received.getPort());
                    assertEquals(1200, received.getLength());
                    target.send(received);
                    assertEquals(Integer.valueOf(value), delivered.poll(5, TimeUnit.SECONDS));
                    assertEquals(Integer.valueOf((i & 1) == 0 ? primary.getPort() : alias.getPort()),
                            wirePorts.poll(1, TimeUnit.SECONDS));
                    int wireSize = wireSizes.remove();
                    assertTrue(lz4 ? wireSize < 100 : wireSize > 1200, "wire bytes=" + wireSize);
                }
            } finally {
                client.close().sync();
            }
        }
    }
}
