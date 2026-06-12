package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.traffic.GlobalChannelTrafficShapingHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.rx.core.RxConfig;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class NetworkFlowControlTest {
    private final NetworkTrafficConfig original = new NetworkTrafficConfig(RxConfig.INSTANCE.getNet().getGlobalTraffic());

    @AfterEach
    void restoreConfig() {
        RxConfig.INSTANCE.getNet().setGlobalTraffic(new NetworkTrafficConfig(original));
        NetworkFlowControl.DEFAULT.refresh(original);
    }

    @Test
    public void testDisabledGlobalTrafficDoesNotInstallHandler() {
        NetworkTrafficConfig config = new NetworkTrafficConfig();
        config.setEnabled(false);
        config.setUploadKilobytesPerSecond(1L);
        config.setDownloadKilobytesPerSecond(1L);
        NetworkFlowControl.DEFAULT.refresh(config);

        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            assertFalse(NetworkFlowControl.DEFAULT.install(channel));
            assertNull(channel.pipeline().get(NetworkFlowControl.GLOBAL_TRAFFIC_HANDLER));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void testInstallGlobalTrafficUsesUploadAsWriteLimitAndDownloadAsReadLimit() {
        NetworkTrafficConfig config = new NetworkTrafficConfig();
        config.setEnabled(true);
        config.setUploadKilobytesPerSecond(4L);
        config.setDownloadKilobytesPerSecond(8L);
        config.setCheckIntervalMillis(50L);
        NetworkFlowControl.DEFAULT.refresh(config);

        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            assertTrue(NetworkFlowControl.DEFAULT.install(channel));
            GlobalChannelTrafficShapingHandler handler = (GlobalChannelTrafficShapingHandler) channel.pipeline()
                    .get(NetworkFlowControl.GLOBAL_TRAFFIC_HANDLER);
            assertNotNull(handler);
            assertEquals(4096L, handler.getWriteLimit());
            assertEquals(8192L, handler.getReadLimit());
            assertEquals(50L, handler.getCheckInterval());
            assertEquals(200L, handler.getMaxTimeWait());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void testRefreshUpdatesExistingGlobalTrafficHandler() {
        NetworkTrafficConfig config = new NetworkTrafficConfig();
        config.setEnabled(true);
        config.setUploadKilobytesPerSecond(1L);
        config.setDownloadKilobytesPerSecond(2L);
        config.setCheckIntervalMillis(100L);
        NetworkFlowControl.DEFAULT.refresh(config);

        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            assertTrue(NetworkFlowControl.DEFAULT.install(channel));
            GlobalChannelTrafficShapingHandler handler = (GlobalChannelTrafficShapingHandler) channel.pipeline()
                    .get(NetworkFlowControl.GLOBAL_TRAFFIC_HANDLER);

            NetworkTrafficConfig next = new NetworkTrafficConfig();
            next.setEnabled(true);
            next.setUploadKilobytesPerSecond(16L);
            next.setDownloadKilobytesPerSecond(32L);
            next.setCheckIntervalMillis(25L);
            next.setMaxDelayMillis(75L);
            NetworkFlowControl.DEFAULT.refresh(next);

            assertEquals(16384L, handler.getWriteLimit());
            assertEquals(32768L, handler.getReadLimit());
            assertEquals(25L, handler.getCheckInterval());
            assertEquals(75L, handler.getMaxTimeWait());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void testRefreshDisabledZeroesExistingGlobalTrafficHandler() {
        NetworkTrafficConfig config = new NetworkTrafficConfig();
        config.setEnabled(true);
        config.setUploadKilobytesPerSecond(4L);
        config.setDownloadKilobytesPerSecond(8L);
        config.setCheckIntervalMillis(50L);
        NetworkFlowControl.DEFAULT.refresh(config);

        EmbeddedChannel channel = new EmbeddedChannel();
        EmbeddedChannel nextChannel = new EmbeddedChannel();
        try {
            assertTrue(NetworkFlowControl.DEFAULT.install(channel));
            GlobalChannelTrafficShapingHandler handler = (GlobalChannelTrafficShapingHandler) channel.pipeline()
                    .get(NetworkFlowControl.GLOBAL_TRAFFIC_HANDLER);
            assertNotNull(handler);
            assertEquals(4096L, handler.getWriteLimit());
            assertEquals(8192L, handler.getReadLimit());

            NetworkTrafficConfig next = new NetworkTrafficConfig();
            next.setEnabled(false);
            next.setUploadKilobytesPerSecond(4L);
            next.setDownloadKilobytesPerSecond(8L);
            next.setCheckIntervalMillis(50L);
            NetworkFlowControl.DEFAULT.refresh(next);

            assertEquals(0L, handler.getWriteLimit());
            assertEquals(0L, handler.getReadLimit());
            assertFalse(NetworkFlowControl.DEFAULT.install(nextChannel));
            assertNull(nextChannel.pipeline().get(NetworkFlowControl.GLOBAL_TRAFFIC_HANDLER));
        } finally {
            nextChannel.finishAndReleaseAll();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void testGlobalUdpPendingBytesCapsSocketDefault() {
        NetworkTrafficConfig config = new NetworkTrafficConfig();
        config.setUdpMaxPendingBytes(4);
        NetworkFlowControl.DEFAULT.refresh(config);

        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            ByteBuf payload = Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4, 5});
            DatagramPacket packet = new DatagramPacket(payload, new InetSocketAddress("127.0.0.1", 53));

            assertEquals(Sockets.UdpWriteResult.PENDING_OVERLIMIT,
                    Sockets.writeUdp(channel, packet, "test.udp", "case=global-limit"));
            assertEquals(0, payload.refCnt());
            assertEquals(0, Sockets.udpPendingWriteBytes(channel));
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void testUdpPendingPacketLimitDropsAndKeepsCountersBalanced() {
        NetworkFlowControl.DEFAULT.refresh(new NetworkTrafficConfig());

        EmbeddedChannel channel = new EmbeddedChannel();
        channel.config().setOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WriteBufferWaterMark.DEFAULT);
        channel.attr(Sockets.ATTR_UDP_WRITE_LIMIT_BYTES).set(128);
        channel.attr(Sockets.ATTR_UDP_WRITE_LIMIT_PACKETS).set(1);
        channel.attr(Sockets.ATTR_UDP_PENDING_WRITE_PACKETS).set(new AtomicInteger(1));
        try {
            ByteBuf payload = Unpooled.copiedBuffer("udp-packet-overlimit", StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(payload, new InetSocketAddress("127.0.0.1", 53));

            assertEquals(Sockets.UdpWriteResult.PENDING_PACKETS_OVERLIMIT,
                    Sockets.writeUdp(channel, packet, "test.udp", "case=packet-limit"));
            assertEquals(0, payload.refCnt());
            assertEquals(0, Sockets.udpPendingWriteBytes(channel));
            assertEquals(1, Sockets.udpPendingWritePackets(channel));
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void testUdpBackpressureDisabledBypassesPendingLimits() {
        NetworkTrafficConfig config = new NetworkTrafficConfig();
        config.setUdpBackpressureEnabled(false);
        config.setUdpMaxPendingBytes(4);
        NetworkFlowControl.DEFAULT.refresh(config);

        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            ByteBuf payload = Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4, 5});
            DatagramPacket packet = new DatagramPacket(payload, new InetSocketAddress("127.0.0.1", 53));

            assertEquals(Sockets.UdpWriteResult.ACCEPTED,
                    Sockets.writeUdp(channel, packet, "test.udp", "case=udp-disabled"));

            DatagramPacket outbound = channel.readOutbound();
            assertNotNull(outbound);
            assertEquals(0, Sockets.udpPendingWriteBytes(channel));
            assertEquals(0, Sockets.udpPendingWritePackets(channel));
            outbound.release();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void testFinalGuardDropsWhenGlobalPendingBytesExceeded() {
        NetworkTrafficConfig config = new NetworkTrafficConfig();
        config.setUdpMaxPendingBytes(4);
        NetworkFlowControl.DEFAULT.refresh(config);

        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            Sockets.addUdpFinalEgressGuard(channel.pipeline(), new SocketConfig());
            assertNotNull(channel.pipeline().get(Sockets.UDP_FINAL_EGRESS_GUARD));

            ByteBuf payload = Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4, 5});
            DatagramPacket packet = new DatagramPacket(payload, new InetSocketAddress("127.0.0.1", 53));

            assertFalse(channel.writeOutbound(packet));
            assertEquals(0, payload.refCnt());
            assertEquals(0, Sockets.udpPendingWriteBytes(channel));
            assertEquals(0, Sockets.udpPendingWritePackets(channel));
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void testFinalGuardStillEnforcesMtuWhenUdpBackpressureDisabled() {
        NetworkTrafficConfig config = new NetworkTrafficConfig();
        config.setUdpBackpressureEnabled(false);
        config.setUdpMaxPendingBytes(4);
        NetworkFlowControl.DEFAULT.refresh(config);

        SocketConfig socketConfig = new SocketConfig();
        socketConfig.setUdpMtu(4);
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            Sockets.addUdpFinalEgressGuard(channel.pipeline(), socketConfig);
            assertNotNull(channel.pipeline().get(Sockets.UDP_FINAL_EGRESS_GUARD));

            ByteBuf payload = Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4, 5});
            DatagramPacket packet = new DatagramPacket(payload, new InetSocketAddress("127.0.0.1", 53));

            assertFalse(channel.writeOutbound(packet));
            assertEquals(0, payload.refCnt());
            assertEquals(0, Sockets.udpPendingWriteBytes(channel));
            assertEquals(0, Sockets.udpPendingWritePackets(channel));
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void testTcpBackpressureHandlerRespectsDisabledConfig() {
        NetworkTrafficConfig config = new NetworkTrafficConfig(original);
        config.setTcpBackpressureEnabled(false);
        RxConfig.INSTANCE.getNet().setGlobalTraffic(config);
        NetworkFlowControl.DEFAULT.refresh(config);

        EmbeddedChannel inbound = new EmbeddedChannel();
        EmbeddedChannel outbound = new EmbeddedChannel();
        outbound.config().setOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WriteBufferWaterMark.DEFAULT);
        try {
            TcpBackpressureHandler.install(inbound, outbound);
            assertNull(outbound.pipeline().get(TcpBackpressureHandler.class));
        } finally {
            outbound.finishAndReleaseAll();
            inbound.finishAndReleaseAll();
        }
    }
}
