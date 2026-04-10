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
import org.rx.net.support.UnresolvedEndpoint;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class Socks5ClientIntegrationTest {
    private static final int PROXY_PORT = 16800;
    private static final int ECHO_PORT = 16801;
    private static final int UDP_ECHO_PORT = 16802;

    private SocksProxyServer proxyServer;
    private Channel echoServer;
    private Channel udpEchoServer;

    @BeforeEach
    public void setup() throws Exception {
        SocksConfig config = new SocksConfig(PROXY_PORT);
        config.setDebug(true);
        proxyServer = new SocksProxyServer(config);

        echoServer = Sockets.serverBootstrap(ch -> {
            ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                    ctx.writeAndFlush(msg.retain());
                }
            });
        }).bind(ECHO_PORT).sync().channel();

        udpEchoServer = Sockets.udpBootstrap(new SocketConfig(), ch -> {
            ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                    ctx.writeAndFlush(new DatagramPacket(msg.content().retain(), msg.sender()));
                }
            });
        }).bind(UDP_ECHO_PORT).sync().channel();
    }

    @AfterEach
    public void tearDown() {
        if (proxyServer != null) proxyServer.close();
        if (echoServer != null) echoServer.close();
        if (udpEchoServer != null) udpEchoServer.close();
    }

    @Test
    public void testConnect() throws Exception {
        try (Socks5Client client = new Socks5Client(AuthenticEndpoint.valueOf("127.0.0.1:" + PROXY_PORT))) {
            String message = "hello-socks5-tcp";
            CompletableFuture<String> result = new CompletableFuture<>();

            client.connect(new UnresolvedEndpoint("127.0.0.1", ECHO_PORT)).addListener(f -> {
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
        try (Socks5Client client = new Socks5Client(AuthenticEndpoint.valueOf("127.0.0.1:" + PROXY_PORT))) {
            String message = "hello-socks5-udp";
            CompletableFuture<String> result = new CompletableFuture<>();

            System.out.println("DEBUG: Starting UDP associate...");
            Socks5Client.Socks5UdpSession session = client.udpAssociateAsync().get(30, TimeUnit.SECONDS);
            System.out.println("DEBUG: UDP session started, relay addr: " + session.getRelayAddress());
            assertNotNull(session.getUdpRelay());

            session.onReceive.combine((s, e) -> {
                DatagramPacket pkt = e.getValue();
                try {
                    String received = pkt.content().toString(StandardCharsets.UTF_8);
                    System.out.println("DEBUG: UDP session received from " + pkt.sender() + ": " + received);
                    result.complete(received);
                } finally {
                    pkt.content().release();
                }
            });

            System.out.println("DEBUG: Sending UDP packet to " + UDP_ECHO_PORT);
            session.send(new UnresolvedEndpoint("127.0.0.1", UDP_ECHO_PORT),
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
        try (Socks5Client client = new Socks5Client(AuthenticEndpoint.valueOf("127.0.0.1:" + PROXY_PORT))) {
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
                             UnresolvedEndpoint src = UdpManager.socks5Decode(content);
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
                System.out.println("DEBUG: Sending UDP packet via session to " + UDP_ECHO_PORT);
                session.send(new UnresolvedEndpoint("127.0.0.1", UDP_ECHO_PORT),
                        Unpooled.copiedBuffer(message, StandardCharsets.UTF_8));

                assertEquals(message, result.get(15, TimeUnit.SECONDS));
            } finally {
                udpChannel.close();
            }
        }
    }
}
