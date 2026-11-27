package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import org.junit.jupiter.api.Test;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RssSocksCarrierMtuTest {
    @Test
    void outerLimitIncludesIpv4Ipv6AndDomainHeaders() throws Exception {
        try (SocksProxyServer proxy = new SocksProxyServer(new SocksConfig(0));
             DatagramSocket sink = new DatagramSocket(0)) {
            int port = ((InetSocketAddress) proxy.tcpChannels.get(0).localAddress()).getPort();
            AuthenticEndpoint outer = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", port), null, null);
            for (InetSocketAddress target : new InetSocketAddress[]{new InetSocketAddress("127.0.0.1", sink.getLocalPort()),
                    new InetSocketAddress("::1", sink.getLocalPort()), InetSocketAddress.createUnresolved("localhost", sink.getLocalPort())}) {
                verifyBudget(outer, target);
            }
        }
    }

    private static void verifyBudget(AuthenticEndpoint outer, InetSocketAddress target) throws Exception {
        int mtu = 512;
        int header = UdpManager.socks5HeaderTemplate(target).length();
        RssSocksCarrier carrier = new RssSocksCarrier(target, target, true, mtu);
        BlockingQueue<Integer> physicalPacketSizes = new LinkedBlockingQueue<>();
        Channel channel = Sockets.udpBootstrap(null, ch -> ch.pipeline()
                .addLast(new ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                        physicalPacketSizes.add(((DatagramPacket) msg).content().readableBytes());
                        ctx.write(msg, promise);
                    }
                }).addLast(carrier)).bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
        try {
            carrier.open(channel, outer).get(5, TimeUnit.SECONDS);
            for (int delta = -1; delta <= 1; delta++) {
                int length = mtu - header + delta;
                ByteBuf data = channel.alloc().buffer(length).writeZero(length);
                ChannelFuture sent = channel.writeAndFlush(new DatagramPacket(data, target)).await();
                if (delta > 0) {
                    assertFalse(sent.isSuccess());
                    assertTrue(sent.cause() instanceof IllegalArgumentException);
                    assertTrue(physicalPacketSizes.isEmpty());
                    assertEquals(0, data.refCnt());
                } else {
                    assertTrue(sent.isSuccess());
                    assertEquals(Integer.valueOf(mtu + delta), physicalPacketSizes.poll(1, TimeUnit.SECONDS));
                }
            }
        } finally {
            channel.close().sync();
            carrier.close();
        }
    }
}
