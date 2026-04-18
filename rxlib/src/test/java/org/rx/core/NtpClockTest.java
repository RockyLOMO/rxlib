package org.rx.core;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import org.junit.jupiter.api.Test;
import org.rx.net.Sockets;
import org.rx.net.ntp.NtpPacket;

import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NtpClockTest {

    @Test
    public void testSync() throws Exception {
        int mockPort = 12346;
        long mockOffset = 10000; // 10 seconds offset

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
        }
    }
}
