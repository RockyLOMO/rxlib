package org.rx.core;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.net.Sockets;
import org.rx.net.ntp.NtpPacket;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Slf4j
public class NtpClockTest {

    /** 与 {@link org.rx.net.ntp.NtpClientIntegrationTest} 一致的公网 NTP（阿里云 ntp1 常见解析地址）。 */
    private static final String NTP_PUBLIC_IP = "118.31.3.89";

    private static boolean ntpReachable(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            try (DatagramSocket s = new DatagramSocket()) {
                s.setSoTimeout(1500);
                s.connect(addr, NtpPacket.NTP_PORT);
                return true;
            }
        } catch (Exception e) {
            log.info("NTP probe failed for {}: {}", ip, e.getMessage());
            return false;
        }
    }

    @Test
    public void testSync() throws Exception {
        int mockPort = 12346;
        long mockOffset = 10000; // 10 seconds offset

        List<String> serversBackup = new ArrayList<>(RxConfig.INSTANCE.net.ntp.getServers());
        long timeoutBackup = RxConfig.INSTANCE.net.ntp.timeoutMillis;
        long offsetBackup = NtpClock.offset;
        boolean injectedBackup = NtpClock.injected;

        // 1. Setup Mock NTP Server
        Bootstrap sb = Sockets.udpBootstrap(null, ch -> ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                long xmitNtp = NtpPacket.getTransmitNtp(packet.content());
                // Simulate a server that is 10 seconds ahead
                long serverMillis = System.currentTimeMillis() + mockOffset;
                long nowNtp = NtpPacket.millisToNtp(serverMillis);
                
                ByteBuf resp = ctx.alloc().heapBuffer(NtpPacket.PACKET_LENGTH).writeZero(NtpPacket.PACKET_LENGTH);
                resp.setByte(0, (NtpPacket.VERSION_3 << 3) | NtpPacket.MODE_SERVER);
                resp.setByte(1, 1);
                resp.setLong(24, xmitNtp);
                resp.setLong(32, nowNtp);
                resp.setLong(40, nowNtp);
                ctx.writeAndFlush(new DatagramPacket(resp, packet.sender()));
            }
        }));
        Channel serverChannel = sb.bind(mockPort).sync().channel();

        try {
            // 2. Configure RxConfig to use our mock server
            RxConfig.INSTANCE.net.ntp.getServers().clear();
            RxConfig.INSTANCE.net.ntp.getServers().add("127.0.0.1:" + mockPort);
            RxConfig.INSTANCE.net.ntp.timeoutMillis = 2000;

            // 3. Trigger Sync
            NtpClock.sync();

            // 4. Verify Offset
            // Offset calculation in NTP is ( (T2-T1) + (T3-T4) ) / 2
            // If server is 10s ahead:
            // T1=0, T2=10, T3=10, T4=0.01 (delay)
            // Offset = (10 + (10 - 0.01)) / 2 = 9.995s
            System.out.println("NtpClock offset: " + NtpClock.offset + "ms");
            assertTrue(Math.abs(NtpClock.offset - mockOffset) < 500, "Offset should be close to " + mockOffset + "ms");

            // 5. Verify millis()
            long now = System.currentTimeMillis();
            long ntpMillis = NtpClock.UTC.millis();
            System.out.println("System millis: " + now);
            System.out.println("NtpClock millis: " + ntpMillis);
            assertTrue(ntpMillis >= now + mockOffset - 500);

            // 6. Instant check
            Instant instant = NtpClock.UTC.instant();
            assertTrue(instant.toEpochMilli() >= now + mockOffset - 500);

        } finally {
            serverChannel.close().sync();
            RxConfig.INSTANCE.net.ntp.getServers().clear();
            RxConfig.INSTANCE.net.ntp.getServers().addAll(serversBackup);
            RxConfig.INSTANCE.net.ntp.timeoutMillis = timeoutBackup;
            NtpClock.offset = offsetBackup;
            NtpClock.injected = injectedBackup;
        }
    }

    /**
     * 使用公网 NTP 118.31.3.89 验证 {@link NtpClock#sync()} 与 {@link org.rx.net.ntp.NtpClient} 联调（无 mock）。
     */
    @Test
    @Timeout(25)
    public void ntpClock_sync_withPublicNtp118_succeedsIfReachable() throws Exception {
        assumeTrue(ntpReachable(NTP_PUBLIC_IP), "公网 NTP 不可达，跳过");

        List<String> serversBackup = new ArrayList<>(RxConfig.INSTANCE.net.ntp.getServers());
        long timeoutBackup = RxConfig.INSTANCE.net.ntp.timeoutMillis;
        long offsetBackup = NtpClock.offset;
        boolean injectedBackup = NtpClock.injected;

        try {
            RxConfig.INSTANCE.net.ntp.getServers().clear();
            RxConfig.INSTANCE.net.ntp.getServers().add(NTP_PUBLIC_IP);
            RxConfig.INSTANCE.net.ntp.timeoutMillis = 8000;
            NtpClock.offset = 0;
            NtpClock.injected = false;

            NtpClock.sync();

            assertTrue(Math.abs(NtpClock.offset) < 120_000, "offset 应在合理范围内: " + NtpClock.offset);
        } finally {
            RxConfig.INSTANCE.net.ntp.getServers().clear();
            RxConfig.INSTANCE.net.ntp.getServers().addAll(serversBackup);
            RxConfig.INSTANCE.net.ntp.timeoutMillis = timeoutBackup;
            NtpClock.offset = offsetBackup;
            NtpClock.injected = injectedBackup;
        }
    }
}
