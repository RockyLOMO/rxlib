package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.concurrent.Future;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;
import java.net.InetSocketAddress;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class Socks5ClientIntegrationTest {
    private SocksProxyServer proxyServer;
    private Channel echoServer;
    private Channel udpEchoServer;
    private int proxyPort;
    private int echoPort;
    private int udpEchoPort;

    @BeforeEach
    public void setup() throws Exception {
        final InetSocketAddress[] proxyAddress = new InetSocketAddress[1];
        SocksConfig config = new SocksConfig(0);
        config.setDebug(true);
        proxyServer = new SocksProxyServer(config, null, ch -> proxyAddress[0] = (InetSocketAddress) ch.localAddress());
        proxyPort = proxyAddress[0].getPort();

        echoServer = Sockets.serverBootstrap(ch -> {
            ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                    ctx.writeAndFlush(msg.retain());
                }
            });
        }).bind(0).sync().channel();
        echoPort = ((InetSocketAddress) echoServer.localAddress()).getPort();

        udpEchoServer = Sockets.udpBootstrap(new SocketConfig(), ch -> {
            ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                    ctx.writeAndFlush(new DatagramPacket(msg.content().retain(), msg.sender()));
                }
            });
        }).bind(0).sync().channel();
        udpEchoPort = ((InetSocketAddress) udpEchoServer.localAddress()).getPort();
    }

    @AfterEach
    public void tearDown() {
        if (proxyServer != null) proxyServer.close();
        if (echoServer != null) echoServer.close().syncUninterruptibly();
        if (udpEchoServer != null) udpEchoServer.close().syncUninterruptibly();
    }

    @Test
    public void testConnect() throws Exception {
        try (Socks5Client client = new Socks5Client(AuthenticEndpoint.valueOf("127.0.0.1:" + proxyPort))) {
            String message = "hello-socks5-tcp";
            CompletableFuture<String> result = new CompletableFuture<>();

            client.connect(org.rx.net.Sockets.newUnresolvedEndpoint("127.0.0.1", echoPort)).addListener(f -> {
                if (!f.isSuccess()) {
                    result.completeExceptionally(f.cause());
                    return;
                }
                Channel ch = (Channel) ((Future<?>) f).getNow();
                ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                        result.complete(msg.toString(StandardCharsets.UTF_8));
                    }
                });
                ch.writeAndFlush(Unpooled.copiedBuffer(message, StandardCharsets.UTF_8));
            });

            assertEquals(message, result.get(15, TimeUnit.SECONDS));
        }
    }

    @Test
    @SneakyThrows
    void testUdpAssociate() {
        try (Socks5Client client = new Socks5Client(AuthenticEndpoint.valueOf("127.0.0.1:" + proxyPort))) {
            String message = "hello-socks5-udp";
            CompletableFuture<String> result = new CompletableFuture<>();

            System.out.println("DEBUG: Starting UDP associate...");
            Socks5Client.Socks5UdpSession session = client.udpAssociateAsync().get(30, TimeUnit.SECONDS);
            System.out.println("DEBUG: UDP session started, relay addr: " + session.getRelayAddress());
            assertNotNull(session.getUdpRelay());

            session.onReceive.add((s, e) -> {
                DatagramPacket pkt = e.getValue();
                try {
                    String received = pkt.content().toString(StandardCharsets.UTF_8);
                    System.out.println("DEBUG: UDP session received from " + pkt.sender() + ": " + received);
                    result.complete(received);
                } finally {
                    pkt.content().release();
                }
            });

            System.out.println("DEBUG: Sending UDP packet to " + udpEchoPort);
            session.send(org.rx.net.Sockets.newUnresolvedEndpoint("127.0.0.1", udpEchoPort),
                    Unpooled.copiedBuffer(message, StandardCharsets.UTF_8)).addListener(f -> {
                if (!f.isSuccess()) {
                    result.completeExceptionally(f.cause());
                }
            });

            assertEquals(message, result.get(15, TimeUnit.SECONDS));
        }
    }

    @Test
    @SneakyThrows
    void testUdpAssociateWithProvidedChannel() {
        try (Socks5Client client = new Socks5Client(AuthenticEndpoint.valueOf("127.0.0.1:" + proxyPort))) {
            String message = "hello-socks5-udp-provided";
            CompletableFuture<String> result = new CompletableFuture<>();

            // 1. Manually create a UDP channel
            Channel udpChannel = Sockets.udpBootstrap(new SocketConfig(), ch -> {
                ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                        // This handler is part of the manual pipeline
                        ByteBuf content = msg.content();
                        try {
                             InetSocketAddress src = UdpManager.socks5Decode(content);
                             String received = content.toString(StandardCharsets.UTF_8);
                             System.out.println("DEBUG: Provided channel received from " + src + ": " + received);
                             result.complete(received);
                        } finally {
                            // No need to release here if we use SimpleChannelInboundHandler's auto-release,
                            // but usually it's better to be explicit if we are not sure.
                        }
                    }
                });
            }).bind(0).sync().channel();

            try {
                System.out.println("DEBUG: Starting UDP associate with provided channel...");
                // 2. Associate using the provided channel
                Socks5Client.Socks5UdpSession session = client.udpAssociateAsync(udpChannel).get(30, TimeUnit.SECONDS);
                System.out.println("DEBUG: UDP session started with provided channel, relay addr: " + session.getRelayAddress());
                assertEquals(udpChannel, session.getUdpRelay());

                // 3. Send using session
                System.out.println("DEBUG: Sending UDP packet via session to " + udpEchoPort);
                session.send(org.rx.net.Sockets.newUnresolvedEndpoint("127.0.0.1", udpEchoPort),
                        Unpooled.copiedBuffer(message, StandardCharsets.UTF_8));

                assertEquals(message, result.get(15, TimeUnit.SECONDS));
            } finally {
                udpChannel.close();
            }
        }
    }
}
