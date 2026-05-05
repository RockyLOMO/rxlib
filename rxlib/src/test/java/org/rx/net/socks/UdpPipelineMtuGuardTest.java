package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import org.junit.jupiter.api.Test;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UdpPipelineMtuGuardTest {
    private static final InetSocketAddress REMOTE = new InetSocketAddress("127.0.0.1", 22345);

    @Test
    void finalGuardIsBeforeOutboundOptimizationEncoders() {
        SocksConfig config = new SocksConfig();
        config.setUdpMtu(1300);
        config.setUdpCompressEnabled(true);
        config.setUdpRedundantMultiplier(2);

        EmbeddedChannel channel = newPipeline(config);
        try {
            List<String> names = channel.pipeline().names();
            int guard = names.indexOf(Sockets.UDP_FINAL_EGRESS_GUARD);
            int redundant = names.indexOf(UdpRedundantEncoder.class.getSimpleName());
            int compress = names.indexOf(UdpCompressEncoder.class.getSimpleName());

            assertTrue(guard >= 0, "final guard must be installed");
            assertTrue(redundant >= 0, "redundant encoder must be installed");
            assertTrue(compress >= 0, "compress encoder must be installed");
            assertTrue(guard < redundant, "outbound redundant output must pass final guard");
            assertTrue(redundant < compress, "outbound order must be compress -> redundant -> final guard");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void finalGuardDropsRedundantPacketAfterHeaderExceedsMtu() {
        SocksConfig config = new SocksConfig();
        config.setUdpMtu(1300);
        config.setUdpRedundantMultiplier(2);

        EmbeddedChannel channel = newPipeline(config);
        try {
            UdpRelayAttributes.addRedundantPeer(channel, REMOTE);
            ByteBuf payload = Unpooled.buffer(1293);
            payload.writeZero(1293);

            channel.writeOutbound(new DatagramPacket(payload, REMOTE));

            assertEquals(0, payload.refCnt(), "original payload must be released after redundant encode");
            assertNull(channel.readOutbound(), "final 1301-byte datagram must be dropped");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void finalGuardAllowsEachRedundantPacketAtMtuBoundary() {
        SocksConfig config = new SocksConfig();
        config.setUdpMtu(1300);
        config.setUdpRedundantMultiplier(3);

        EmbeddedChannel channel = newPipeline(config);
        try {
            UdpRelayAttributes.addRedundantPeer(channel, REMOTE);
            ByteBuf payload = Unpooled.buffer(1292);
            payload.writeZero(1292);

            channel.writeOutbound(new DatagramPacket(payload, REMOTE));

            for (int i = 0; i < 3; i++) {
                DatagramPacket out = channel.readOutbound();
                assertNotNull(out, "redundant copy " + i + " must pass");
                try {
                    assertEquals(1300, out.content().readableBytes());
                    assertEquals(UdpRedundantEncoder.HEADER_MAGIC, out.content().getInt(out.content().readerIndex()));
                } finally {
                    out.release();
                }
            }
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void writeUdpAllowsRawPayloadAboveMtuWhenCompressionShrinksFinalDatagram() {
        SocksConfig config = compressConfig();
        config.setUdpMtu(1300);

        EmbeddedChannel channel = newPipeline(config);
        try {
            UdpRelayAttributes.addRedundantPeer(channel, REMOTE);
            ByteBuf payload = Unpooled.wrappedBuffer(repeatedPayload(2000));

            Sockets.UdpWriteResult result = Sockets.writeUdp(channel,
                    new DatagramPacket(payload, REMOTE), config, "test.udp", "case=compress-final-mtu");

            assertEquals(Sockets.UdpWriteResult.ACCEPTED, result);
            assertEquals(0, payload.refCnt(), "compressed write must release original payload");
            DatagramPacket out = channel.readOutbound();
            assertNotNull(out);
            try {
                assertTrue(out.content().readableBytes() <= 1300);
                assertEquals(UdpCompressSupport.HEADER_MAGIC, out.content().getInt(out.content().readerIndex()));
            } finally {
                out.release();
            }
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel newPipeline(SocksConfig config) {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(SocketConfig.ATTR_CONF).set(config);
        Sockets.addUdpOptimizationHandlers(channel.pipeline(), config);
        return channel;
    }

    private static SocksConfig compressConfig() {
        SocksConfig config = new SocksConfig();
        config.setUdpCompressEnabled(true);
        config.setUdpCompressMinPayloadBytes(1);
        config.setUdpCompressMinSavingsBytes(1);
        config.setUdpCompressMinSavingsRatio(0.01D);
        return config;
    }

    private static byte[] repeatedPayload(int len) {
        byte[] payload = new byte[len];
        Arrays.fill(payload, (byte) 'A');
        return payload;
    }
}
