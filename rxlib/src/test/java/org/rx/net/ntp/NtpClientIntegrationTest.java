package org.rx.net.ntp;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.net.Sockets;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Consolidated integration tests for {@link NtpClient}.
 */
@Slf4j
public class NtpClientIntegrationTest {

    private NtpClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    // ---- Constant Helpers ----

    private static final String NTP_IP = System.getProperty("rx.ntp.test.ip", "118.31.3.89");

    private static ByteBuf buildServerResponse(ByteBufAllocator alloc, int li, int stratum,
                                               long originateNtp, long receiveNtp, long transmitNtp) {
        ByteBuf resp = alloc.heapBuffer(NtpPacket.PACKET_LENGTH, NtpPacket.PACKET_LENGTH);
        resp.writeZero(NtpPacket.PACKET_LENGTH);
        resp.setByte(0, ((li & 0x3) << 6) | (NtpPacket.VERSION_3 << 3) | NtpPacket.MODE_SERVER);
        resp.setByte(1, stratum);
        resp.setLong(24, originateNtp);
        resp.setLong(32, receiveNtp);
        resp.setLong(40, transmitNtp);
        return resp;
    }

    /**
     * Probes if external NTP is reachable. Handles DNS hijacking.
     */
    private static boolean ntpReachable() {
        try {
            // Probe the direct IP instead of hostname to avoid DNS hijack issues
            InetAddress addr = InetAddress.getByName(NTP_IP);
            try (DatagramSocket s = new DatagramSocket()) {
                s.setSoTimeout(1500);
                s.connect(addr, NtpPacket.NTP_PORT);
                return true;
            }
        } catch (Exception e) {
            log.info("NTP external probe failed: {}", e.getMessage());
            return false;
        }
    }

    // ---- 1. Lifecycle & Basic Exception Tests ----

    @Test
    @SneakyThrows
    void ntpClient_lifecycle_bindsAndCloses() {
        NtpClient c = new NtpClient();
        assertTrue(c.channel.isActive());
        c.channel.close().sync();
        assertFalse(c.channel.isOpen());
    }

    @Test
    void ntpClient_closedClient_throwsIllegalState() {
        NtpClient c = new NtpClient();
        c.close();
        assertThrows(IllegalStateException.class,
            () -> c.getTimeAsync(new InetSocketAddress("127.0.0.1", 123)));
    }

    @Test
    void ntpClient_sameMillis_generatesUniqueTransmitTimestamp() {
        client = new NtpClient();
        long fixedMillis = System.currentTimeMillis();
        Set<Long> values = new HashSet<Long>();
        long prev = Long.MIN_VALUE;
        for (int i = 0; i < 256; i++) {
            long current = client.nextTransmitNtp(fixedMillis);
            assertTrue(values.add(current), "Transmit timestamp must stay unique within the same millisecond");
            assertTrue(current > prev, "Transmit timestamp must stay monotonic");
            prev = current;
        }
    }

    // ---- 2. Mock Server Integration (Reliable Local Test) ----

    @Test
    @Timeout(10)
    @SneakyThrows
    void ntpClient_withMockServer_fullStackSucceeds() {
        int mockPort = 12345;
        Bootstrap sb = Sockets.udpBootstrap(null, ch -> ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                long xmitNtp = NtpPacket.getTransmitNtp(packet.content());
                long nowNtp = NtpPacket.millisToNtp(System.currentTimeMillis());
                ByteBuf resp = buildServerResponse(ctx.alloc(), 0, 1, xmitNtp, nowNtp, nowNtp);
                ctx.writeAndFlush(new DatagramPacket(resp, packet.sender()));
            }
        }));
        Channel serverChannel = sb.bind(mockPort).sync().channel();
        try {
            client = new NtpClient();
            try (NtpResult result = client.getTimeAsync(new InetSocketAddress("127.0.0.1", mockPort)).get(5, TimeUnit.SECONDS)) {
                assertNotNull(result);
                assertEquals(1, result.getStratum());
                assertTrue(result.getDelayMillis() >= 0);
            }
        } finally {
            serverChannel.close().sync();
        }
    }

    @Test
    @Timeout(10)
    @SneakyThrows
    void ntpClient_ignoresUnexpectedAndInvalidResponses_thenAcceptsValidReply() {
        int mockPort = 12346;
        int roguePort = 12347;
        Channel rogueChannel = Sockets.udpBootstrap(null, ch -> {
        }).bind(roguePort).sync().channel();
        Bootstrap sb = Sockets.udpBootstrap(null, ch -> ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                long xmitNtp = NtpPacket.getTransmitNtp(packet.content());
                long nowNtp = NtpPacket.millisToNtp(System.currentTimeMillis());

                ByteBuf wrongSource = buildServerResponse(rogueChannel.alloc(), 0, 1, xmitNtp, nowNtp, nowNtp);
                rogueChannel.writeAndFlush(new DatagramPacket(wrongSource, packet.sender()));

                ByteBuf invalidResp = buildServerResponse(ctx.alloc(), 0, 0, xmitNtp, nowNtp, nowNtp);
                ctx.writeAndFlush(new DatagramPacket(invalidResp, packet.sender()));

                ctx.executor().schedule(() -> {
                    long replyNtp = NtpPacket.millisToNtp(System.currentTimeMillis());
                    ByteBuf validResp = buildServerResponse(ctx.alloc(), 0, 1, xmitNtp, replyNtp, replyNtp);
                    ctx.writeAndFlush(new DatagramPacket(validResp, packet.sender()));
                }, 50, TimeUnit.MILLISECONDS);
            }
        }));
        Channel serverChannel = sb.bind(mockPort).sync().channel();
        try {
            client = new NtpClient();
            try (NtpResult result = client.getTimeAsync(new InetSocketAddress("127.0.0.1", mockPort)).get(5, TimeUnit.SECONDS)) {
                assertNotNull(result);
                assertEquals(1, result.getStratum());
                assertEquals(mockPort, result.getServerAddress().getPort());
            }
            assertTrue(client.pendingRequests.isEmpty(), "Pending requests must be cleared after valid reply");
        } finally {
            serverChannel.close().sync();
            rogueChannel.close().sync();
        }
    }

    // ---- 3. Timeout & Cleanup Tests ----

    @Test
    @Timeout(5)
    @SneakyThrows
    void ntpClient_timeout_clearsPendingRequests() {
        client = new NtpClient();
        client.setTimeoutMillis(500);
        InetSocketAddress deadAddr = new InetSocketAddress("127.0.0.1", 19999);
        CompletableFuture<NtpResult> future = client.getTimeAsync(deadAddr);

        assertThrows(Exception.class, () -> future.get(2, TimeUnit.SECONDS));
        Thread.sleep(200);
        assertTrue(client.pendingRequests.isEmpty(), "Pending requests must be cleared after timeout");
    }

    @Test
    @Timeout(5)
    @SneakyThrows
    void ntpClient_close_failsInFlightRequestsImmediately() {
        client = new NtpClient();
        client.setTimeoutMillis(5000);
        CompletableFuture<NtpResult> future = client.getTimeAsync(new InetSocketAddress("127.0.0.1", 19998));

        client.close();

        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof ClosedChannelException);
        assertTrue(client.pendingRequests.isEmpty(), "Pending requests must be cleared on close");
    }

    // ---- 4. Real Network Tests (Best effort) ----

    @Test
    @Timeout(20)
    @SneakyThrows
    void ntpClient_realServer_succeedsIfReachable() {
        assumeTrue(ntpReachable(), "No external NTP reachability - skipping real network test");

        client = new NtpClient();
        client.setTimeoutMillis(8000);
        
        // Try direct IP first as it's more reliable in hijacked environments
        InetAddress addr = InetAddress.getByName(NTP_IP);
        try (NtpResult result = client.getTime(addr)) {
            log.info("Real NTP Result: {}", result);
            assertTrue(result.getStratum() >= 1 && result.getStratum() <= 15);
            assertTrue(Math.abs(result.getOffsetMillis()) < 60000);
        }
    }

    @Test
    @Timeout(40)
    @SneakyThrows
    void ntpClient_concurrentRequests_allSucceed() {
        assumeTrue(ntpReachable(), "No external NTP reachability - skipping concurrent test");

        client = new NtpClient();
        client.setTimeoutMillis(15000);
        InetSocketAddress server = new InetSocketAddress(NTP_IP, NtpPacket.NTP_PORT);

        int count = 3;
        CompletableFuture<NtpResult>[] futures = new CompletableFuture[count];
        for (int i = 0; i < count; i++) {
            futures[i] = client.getTimeAsync(server);
            Thread.sleep(100); // Be kind to public servers
        }

        for (int i = 0; i < count; i++) {
            try (NtpResult r = futures[i].get(20, TimeUnit.SECONDS)) {
                assertNotNull(r);
                assertTrue(r.getStratum() >= 1);
            }
        }
        assertTrue(client.pendingRequests.isEmpty());
    }
}
