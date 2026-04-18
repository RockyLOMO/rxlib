package org.rx.net.ntp;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
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

    private static final String NTP_HOST = "ntp1.aliyun.com";
    private static final String NTP_IP = "118.31.3.89"; // Known working Aliyun NTP IP

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
                ByteBuf resp = ctx.alloc().heapBuffer(NtpPacket.PACKET_LENGTH).writeZero(NtpPacket.PACKET_LENGTH);
                resp.setByte(0, (NtpPacket.VERSION_3 << 3) | NtpPacket.MODE_SERVER);
                resp.setByte(1, 1); // Stratum 1
                resp.setLong(24, xmitNtp); // Echo originate
                resp.setLong(32, nowNtp);  // Receive
                resp.setLong(40, nowNtp);  // Transmit
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
