package org.rx.net.udp;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class UdpProtectTest {
    private static final InetSocketAddress LOCAL = Sockets.newAnyEndpoint(0);
    private static final InetSocketAddress REMOTE_A = new InetSocketAddress("127.0.0.1", 10001);
    private static final InetSocketAddress REMOTE_B = new InetSocketAddress("127.0.0.1", 10002);

    @Test
    public void testEncoderEmitsXorParityOnFullGroup() {
        UdpProtectConfig config = config(3);
        EmbeddedChannel channel = new EmbeddedChannel(new UdpProtectEncoder(config));

        channel.writeOutbound(datagram("a", REMOTE_A));
        channel.writeOutbound(datagram("bb", REMOTE_A));
        channel.writeOutbound(datagram("ccc", REMOTE_A));
        channel.flush();

        List<DatagramPacket> out = drainOutbound(channel);
        try {
            assertEquals(4, out.size());
            for (int i = 0; i < out.size(); i++) {
                ByteBuf content = out.get(i).content();
                assertFalse(content instanceof CompositeByteBuf);
                assertEquals(UdpProtectHeader.MAGIC, content.getUnsignedShort(content.readerIndex()));
            }
            ByteBuf parity = out.get(3).content();
            int readerIndex = parity.readerIndex();
            assertEquals(UdpProtectHeader.FLAG_PARITY, UdpProtectHeader.flags(parity, readerIndex));
            assertEquals(3, UdpProtectHeader.shardK(parity, readerIndex));
            assertEquals(3, UdpProtectHeader.shardIdx(parity, readerIndex));
            assertEquals(1, config.stats().parityPackets());
        } finally {
            release(out);
        }
    }

    @Test
    public void testDecoderRecoversVariableLengthPayload() {
        UdpProtectConfig config = config(3);
        List<DatagramPacket> encoded = encode(config, REMOTE_A, "aa", "bbbb", "ccc");
        EmbeddedChannel decoder = new EmbeddedChannel(new UdpProtectDecoder(config));
        try {
            writeInboundCopy(decoder, encoded.get(0), REMOTE_A);
            writeInboundCopy(decoder, encoded.get(2), REMOTE_A);
            writeInboundCopy(decoder, encoded.get(3), REMOTE_A);

            List<String> received = drainInboundStrings(decoder);
            assertEquals(3, received.size());
            assertEquals("aa", received.get(0));
            assertEquals("ccc", received.get(1));
            assertEquals("bbbb", received.get(2));
            assertEquals(1, config.stats().recoveredPackets());
        } finally {
            release(encoded);
            decoder.finishAndReleaseAll();
        }
    }

    @Test
    public void testParityFirstThenDataStillRecovers() {
        UdpProtectConfig config = config(3);
        List<DatagramPacket> encoded = encode(config, REMOTE_A, "aa", "bbbb", "ccc");
        EmbeddedChannel decoder = new EmbeddedChannel(new UdpProtectDecoder(config));
        try {
            writeInboundCopy(decoder, encoded.get(3), REMOTE_A);
            assertNull(decoder.readInbound());

            writeInboundCopy(decoder, encoded.get(0), REMOTE_A);
            writeInboundCopy(decoder, encoded.get(2), REMOTE_A);

            List<String> received = drainInboundStrings(decoder);
            assertEquals(3, received.size());
            assertEquals("aa", received.get(0));
            assertEquals("ccc", received.get(1));
            assertEquals("bbbb", received.get(2));
        } finally {
            release(encoded);
            decoder.finishAndReleaseAll();
        }
    }

    @Test
    public void testEncoderKeepsPeerGroupsIsolated() {
        UdpProtectConfig config = config(2);
        EmbeddedChannel channel = new EmbeddedChannel(new UdpProtectEncoder(config));

        channel.writeOutbound(datagram("a0", REMOTE_A));
        channel.writeOutbound(datagram("b0", REMOTE_B));
        channel.writeOutbound(datagram("a1", REMOTE_A));
        channel.writeOutbound(datagram("b1", REMOTE_B));
        channel.flush();

        List<DatagramPacket> out = drainOutbound(channel);
        try {
            assertEquals(6, out.size());
            int parityA = 0;
            int parityB = 0;
            for (DatagramPacket packet : out) {
                ByteBuf content = packet.content();
                int flags = UdpProtectHeader.flags(content, content.readerIndex());
                if ((flags & UdpProtectHeader.FLAG_PARITY) != 0) {
                    if (REMOTE_A.equals(packet.recipient())) {
                        parityA++;
                    } else if (REMOTE_B.equals(packet.recipient())) {
                        parityB++;
                    }
                }
            }
            assertEquals(1, parityA);
            assertEquals(1, parityB);
        } finally {
            release(out);
        }
    }

    @Test
    public void testRedundantCopiesDeliveredOnce() {
        UdpProtectConfig config = config(3);
        config.setFecEnabled(false);
        config.setRedundantEnabled(true);
        config.setRedundantMultiplier(2);
        config.setRedundantIntervalMicros(0);
        EmbeddedChannel encoder = new EmbeddedChannel(new UdpProtectEncoder(config));
        EmbeddedChannel decoder = new EmbeddedChannel(new UdpProtectDecoder(config));
        try {
            encoder.writeOutbound(datagram("ping", REMOTE_A));
            List<DatagramPacket> out = drainOutbound(encoder);
            assertEquals(2, out.size());

            writeInboundCopy(decoder, out.get(0), REMOTE_A);
            writeInboundCopy(decoder, out.get(1), REMOTE_A);

            DatagramPacket decoded = decoder.readInbound();
            assertNotNull(decoded);
            assertEquals("ping", decoded.content().toString(StandardCharsets.UTF_8));
            decoded.release();
            assertNull(decoder.readInbound());
            assertEquals(1, config.stats().duplicateDrops());
            release(out);
        } finally {
            encoder.finishAndReleaseAll();
            decoder.finishAndReleaseAll();
        }
    }

    @Test
    public void testProtectedPeerAttributeRestrictsEncoder() {
        UdpProtectConfig config = config(3);
        config.setProtectAll(false);
        EmbeddedChannel channel = new EmbeddedChannel(new UdpProtectEncoder(config));

        channel.writeOutbound(datagram("plain", REMOTE_A));
        DatagramPacket plain = channel.readOutbound();
        assertNotNull(plain);
        assertFalse(UdpProtectHeader.isProtected(plain.content()));
        plain.release();

        UdpProtectAttributes.addProtectedPeer(channel, REMOTE_A);
        channel.writeOutbound(datagram("secure", REMOTE_A));
        DatagramPacket protectedPacket = channel.readOutbound();
        assertNotNull(protectedPacket);
        assertTrue(UdpProtectHeader.isProtected(protectedPacket.content()));
        protectedPacket.release();
    }

    @Test
    public void testRealDatagramPipelineRecoversDroppedShard() throws Exception {
        UdpProtectConfig senderConfig = config(3);
        UdpProtectConfig receiverConfig = config(3);
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        CountDownLatch latch = new CountDownLatch(3);
        List<String> received = new CopyOnWriteArrayList<>();
        Channel receiver = null;
        Channel sender = null;
        try {
            Bootstrap rb = new Bootstrap();
            rb.group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(new ChannelInitializer<DatagramChannel>() {
                        @Override
                        protected void initChannel(DatagramChannel ch) {
                            ch.pipeline().addLast(new DropDataShardHandler(1));
                            ch.pipeline().addLast(new UdpProtectDecoder(receiverConfig));
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                                    received.add(msg.content().toString(StandardCharsets.UTF_8));
                                    latch.countDown();
                                }
                            });
                        }
                    });
            receiver = rb.bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();

            Bootstrap sb = new Bootstrap();
            sb.group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(new ChannelInitializer<DatagramChannel>() {
                        @Override
                        protected void initChannel(DatagramChannel ch) {
                            ch.pipeline().addLast(new UdpProtectEncoder(senderConfig));
                        }
                    });
            sender = sb.bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();

            InetSocketAddress receiverAddress = (InetSocketAddress) receiver.localAddress();
            InetSocketAddress remote = new InetSocketAddress("127.0.0.1", receiverAddress.getPort());
            sender.writeAndFlush(datagram("r0", remote)).sync();
            sender.writeAndFlush(datagram("r1", remote)).sync();
            sender.writeAndFlush(datagram("r2", remote)).sync();

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals(3, received.size());
            assertEquals("r0", received.get(0));
            assertEquals("r2", received.get(1));
            assertEquals("r1", received.get(2));
            assertEquals(1, receiverConfig.stats().recoveredPackets());
        } finally {
            if (sender != null) {
                sender.close().syncUninterruptibly();
            }
            if (receiver != null) {
                receiver.close().syncUninterruptibly();
            }
            group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }

    private static UdpProtectConfig config(int dataShards) {
        UdpProtectConfig config = UdpProtectConfig.gameLowLatency();
        config.setFecDataShards(dataShards);
        config.setFecFlushTimeoutMs(0);
        config.setRedundantEnabled(false);
        return config;
    }

    private static List<DatagramPacket> encode(UdpProtectConfig config, InetSocketAddress remote, String a, String b, String c) {
        EmbeddedChannel encoder = new EmbeddedChannel(new UdpProtectEncoder(config));
        encoder.writeOutbound(datagram(a, remote));
        encoder.writeOutbound(datagram(b, remote));
        encoder.writeOutbound(datagram(c, remote));
        encoder.flush();
        return drainOutbound(encoder);
    }

    private static DatagramPacket datagram(String value, InetSocketAddress recipient) {
        return new DatagramPacket(Unpooled.copiedBuffer(value, StandardCharsets.UTF_8), recipient);
    }

    private static void writeInboundCopy(EmbeddedChannel decoder, DatagramPacket encoded, InetSocketAddress sender) {
        decoder.writeInbound(new DatagramPacket(encoded.content().retainedDuplicate(), LOCAL, sender));
    }

    private static List<DatagramPacket> drainOutbound(EmbeddedChannel channel) {
        List<DatagramPacket> packets = new ArrayList<>();
        DatagramPacket packet;
        while ((packet = channel.readOutbound()) != null) {
            packets.add(packet);
        }
        return packets;
    }

    private static List<String> drainInboundStrings(EmbeddedChannel channel) {
        List<String> values = new ArrayList<>();
        DatagramPacket packet;
        while ((packet = channel.readInbound()) != null) {
            values.add(packet.content().toString(StandardCharsets.UTF_8));
            packet.release();
        }
        return values;
    }

    private static void release(List<DatagramPacket> packets) {
        for (DatagramPacket packet : packets) {
            packet.release();
        }
    }

    private static final class DropDataShardHandler extends ChannelInboundHandlerAdapter {
        private final int shardIdx;

        DropDataShardHandler(int shardIdx) {
            this.shardIdx = shardIdx;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof DatagramPacket) {
                DatagramPacket packet = (DatagramPacket) msg;
                ByteBuf content = packet.content();
                int readerIndex = content.readerIndex();
                if (UdpProtectHeader.isProtected(content)
                        && (UdpProtectHeader.flags(content, readerIndex) & UdpProtectHeader.FLAG_DATA) != 0
                        && UdpProtectHeader.shardIdx(content, readerIndex) == shardIdx) {
                    ReferenceCountUtil.release(msg);
                    return;
                }
            }
            super.channelRead(ctx, msg);
        }
    }
}
