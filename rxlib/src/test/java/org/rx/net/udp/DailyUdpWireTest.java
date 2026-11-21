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
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class DailyUdpWireTest {
    @Test
    void ipv4ChangesReturnPortAndKeepsTargetTuple() throws Exception {
        verifyHops(InetAddress.getByName("127.0.0.1"));
    }

    @Test
    void ipv6ChangesReturnPortAndKeepsTargetTuple() throws Exception {
        verifyHops(InetAddress.getByName("::1"));
    }

    private void verifyHops(InetAddress ip) throws Exception {
        SocksConfig config = new SocksConfig(new LocalAddress(UUID.randomUUID().toString()));
        config.setDailyUdp(true);
        try (DatagramSocket target = new DatagramSocket(new InetSocketAddress(ip, 0));
             SocksProxyServer server = new SocksProxyServer(config)) {
            target.setSoTimeout(5000);
            UdpRelayGroupOpenRequest req = new UdpRelayGroupOpenRequest();
            req.setMaxRelayCount(2);
            req.setAttributes(Collections.singletonMap("profile", SocksRpcCapabilities.DAILY_PROFILE));
            UdpRelayGroupOpenResult result = server.openUdpRelayGroup(req, SocksRpcContract.rpcToken());
            assertTrue(result.isSuccess(), result.getErrorCode());
            InetSocketAddress primary = new InetSocketAddress(ip, result.getRelays().get(0).getRelayAddress().getPort());
            InetSocketAddress alias = new InetSocketAddress(ip, result.getRelays().get(1).getRelayAddress().getPort());
            AtomicLong clock = new AtomicLong();
            BlockingQueue<Integer> wirePorts = new LinkedBlockingQueue<>(), delivered = new LinkedBlockingQueue<>();
            Channel client = Sockets.udpBootstrap(null, ch -> {
                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        wirePorts.add(((DatagramPacket) msg).sender().getPort());
                        ctx.fireChannelRead(msg);
                    }
                });
                ch.pipeline().addLast(new DailyUdpWire(result.getToken(), primary, alias, null, clock::get));
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
                    ByteBuf b = UdpManager.socks5Encode(client.alloc().buffer(1200).writeInt(i).writeZero(1196),
                            (InetSocketAddress) target.getLocalSocketAddress());
                    client.writeAndFlush(new DatagramPacket(b, primary)).sync();
                    java.net.DatagramPacket received = new java.net.DatagramPacket(new byte[1200], 1200);
                    target.receive(received);
                    assertEquals(primary.getPort(), received.getPort());
                    target.send(received);
                    assertEquals(Integer.valueOf(i), delivered.poll(5, TimeUnit.SECONDS));
                    assertEquals(Integer.valueOf((i & 1) == 0 ? primary.getPort() : alias.getPort()),
                            wirePorts.poll(1, TimeUnit.SECONDS));
                }
            } finally {
                client.close().sync();
            }
        }
    }
}
