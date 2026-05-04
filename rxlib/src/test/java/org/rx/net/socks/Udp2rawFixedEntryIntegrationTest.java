package org.rx.net.socks;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.core.RxConfig;
import org.rx.net.Sockets;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class Udp2rawFixedEntryIntegrationTest {
    @Test
    @Timeout(20)
    void fixedEntryCreatesIndependentNatChannelsAndRepliesFromEntryPort() throws Exception {
        String oldToken = RxConfig.INSTANCE.getRtoken();
        RxConfig.INSTANCE.setRtoken("udp2raw-test-token");
        LinkedBlockingQueue<InetSocketAddress> echoSenders = new LinkedBlockingQueue<>();
        Channel echo = null;
        SocksProxyServer proxy = null;
        DatagramSocket client = null;
        try {
            Bootstrap udpEcho = Sockets.udpBootstrap(null, ch -> ch.pipeline().addLast(
                    new SimpleChannelInboundHandler<DatagramPacket>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                            echoSenders.add(msg.sender());
                            ctx.writeAndFlush(new DatagramPacket(msg.content().retain(), msg.sender()));
                        }
                    }));
            echo = udpEcho.bind(Sockets.newLoopbackEndpoint(0)).sync().channel();
            InetSocketAddress echoAddress = (InetSocketAddress) echo.localAddress();

            SocksConfig config = new SocksConfig(Sockets.newLoopbackEndpoint(0));
            config.setEnableUdp2raw(true);
            config.setUdp2rawAuthMode(Udp2rawAuthMode.FIRST_PACKET_MAC);
            config.setUdp2rawMaxSessions(8);
            proxy = new SocksProxyServer(config, null);

            Udp2rawOpenRequest request = new Udp2rawOpenRequest();
            request.setClientId("junit");
            request.setMaxSessions(8);
            Udp2rawOpenResult open = proxy.openUdp2rawTunnel(request, SocksRpcContract.rpcToken());
            assertTrue(open.isSupported());
            assertTrue(open.isSuccess());
            assertNotNull(open.getSessionSecret());
            InetSocketAddress entryAddress = new InetSocketAddress("127.0.0.1", open.getUdpEntryAddress().getPort());

            client = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
            client.setSoTimeout(5000);
            sendUdp2raw(client, entryAddress, open, 1001L,
                    new InetSocketAddress("127.0.0.1", 30001), echoAddress, "one");
            sendUdp2raw(client, entryAddress, open, 1002L,
                    new InetSocketAddress("127.0.0.1", 30002), echoAddress, "two");

            Set<String> responses = new HashSet<>();
            for (int i = 0; i < 2; i++) {
                java.net.DatagramPacket packet = receive(client);
                assertEquals(entryAddress.getPort(), packet.getPort(), "response source must be fixed entry port");
                ByteBuf buf = Unpooled.wrappedBuffer(packet.getData(), 0, packet.getLength());
                try {
                    Udp2rawFrame frame = Udp2rawCodec.decode(buf);
                    assertEquals(Udp2rawFrameType.DATA, frame.getType());
                    assertTrue(frame.hasFlag(Udp2rawCodec.FLAG_HAS_SRC));
                    assertEquals(echoAddress.getPort(), frame.getSourceAddress().getPort());
                    responses.add(buf.toString(StandardCharsets.UTF_8));
                } finally {
                    buf.release();
                }
            }
            assertTrue(responses.contains("one"));
            assertTrue(responses.contains("two"));

            InetSocketAddress firstSender = echoSenders.poll(3, TimeUnit.SECONDS);
            InetSocketAddress secondSender = echoSenders.poll(3, TimeUnit.SECONDS);
            assertNotNull(firstSender);
            assertNotNull(secondSender);
            assertNotEquals(firstSender.getPort(), secondSender.getPort(),
                    "different client sourceEndpoint/connId must use different NAT source ports");
        } finally {
            if (client != null) {
                client.close();
            }
            if (proxy != null) {
                proxy.close();
            }
            if (echo != null) {
                echo.close();
            }
            RxConfig.INSTANCE.setRtoken(oldToken);
        }
    }

    private static void sendUdp2raw(DatagramSocket socket, InetSocketAddress entryAddress,
            Udp2rawOpenResult open, long connId, InetSocketAddress clientSource,
            InetSocketAddress destination, String text) throws Exception {
        ByteBuf payload = Unpooled.copiedBuffer(text, StandardCharsets.UTF_8);
        Udp2rawFrame frame = Udp2rawFrame.data(open.getSessionHi(), open.getSessionLo(), connId, 1L);
        frame.setFlags(Udp2rawCodec.FLAG_NEW_CONN | Udp2rawCodec.FLAG_HAS_CLIENT
                | Udp2rawCodec.FLAG_HAS_DST | Udp2rawCodec.FLAG_AUTH_TAG);
        frame.setClientSource(clientSource);
        frame.setDestination(new UnresolvedEndpoint(destination.getHostString(), destination.getPort()));
        ByteBuf authTag = Udp2rawAuthenticator.sign(UnpooledByteBufAllocator.DEFAULT,
                open.getSessionSecret(), frame, payload);
        frame.setAuthTag(authTag);
        ByteBuf encoded = Udp2rawCodec.encode(UnpooledByteBufAllocator.DEFAULT, frame, payload);
        try {
            byte[] bytes = new byte[encoded.readableBytes()];
            encoded.getBytes(encoded.readerIndex(), bytes);
            socket.send(new java.net.DatagramPacket(bytes, bytes.length,
                    InetAddress.getByName(entryAddress.getHostString()), entryAddress.getPort()));
        } finally {
            authTag.release();
            encoded.release();
        }
    }

    private static java.net.DatagramPacket receive(DatagramSocket socket) throws Exception {
        byte[] bytes = new byte[1024];
        java.net.DatagramPacket packet = new java.net.DatagramPacket(bytes, bytes.length);
        socket.receive(packet);
        return packet;
    }
}
