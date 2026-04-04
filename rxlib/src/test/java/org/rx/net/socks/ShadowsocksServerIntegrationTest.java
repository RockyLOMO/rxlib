package org.rx.net.socks;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.rx.net.Sockets;
import org.rx.net.socks.encryption.CipherKind;
import org.rx.net.socks.encryption.ICrypto;
import org.rx.net.support.UnresolvedEndpoint;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class ShadowsocksServerIntegrationTest {
    static final int TCP_ECHO_PORT = 16299;
    static final int UDP_ECHO_PORT = 16300;
    static ServerBootstrap tcpEchoBootstrap;
    static Channel tcpEchoChannel;
    static Channel udpEchoChannel;
    static Bootstrap udpEchoBootstrap;

    @BeforeAll
    static void setup() throws Exception {
        tcpEchoBootstrap = Sockets.serverBootstrap(ch -> ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ctx.writeAndFlush(msg);
            }
        }));
        tcpEchoChannel = tcpEchoBootstrap.bind(Sockets.newAnyEndpoint(TCP_ECHO_PORT)).sync().channel();

        udpEchoBootstrap = Sockets.udpBootstrap(null, ob -> ob.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                // Echo back to sender
                ByteBuf content = msg.content().retain();
                ctx.writeAndFlush(new DatagramPacket(content, msg.sender()));
            }
        }));
        udpEchoChannel = udpEchoBootstrap.bind(Sockets.newAnyEndpoint(UDP_ECHO_PORT)).sync().channel();

        Thread.sleep(300);
    }

    @AfterAll
    static void teardown() {
        if (tcpEchoChannel != null) tcpEchoChannel.close();
        if (tcpEchoBootstrap != null) Sockets.closeBootstrap(tcpEchoBootstrap);
        if (udpEchoChannel != null) udpEchoChannel.close();
    }

    @Test
    @SneakyThrows
    @Timeout(value = 15)
    void shadowsocksTcpConnect_e2e() {
        int proxyPort = 16280;
        String method = CipherKind.AES_256_GCM.getCipherName();
        String password = "test-password";
        ShadowsocksConfig config = new ShadowsocksConfig(Sockets.newAnyEndpoint(proxyPort), method, password);
        config.setDebug(true); // 开启 debug
        ShadowsocksServer server = new ShadowsocksServer(config);

        try {
            Thread.sleep(300);
            try (Socket s = new Socket("127.0.0.1", proxyPort)) {
                s.setSoTimeout(4000);
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();

                ICrypto crypto = ICrypto.get(method, password);
                crypto.setForUdp(false);

                // 1) Send encrypted address + payload
                ByteBuf buf = Unpooled.buffer();
                UdpManager.encode(buf, "127.0.0.1", TCP_ECHO_PORT);
                String message = "ss-tcp-e2e";
                buf.writeBytes(message.getBytes(StandardCharsets.UTF_8));
                
                ByteBuf encrypted = crypto.encrypt(buf);
                out.write(toBytes(encrypted));
                out.flush();

                // 2) Read response (encrypted payload)
                // SS TCP response includes initial salt, then chunks.
                byte[] backEncrypted = readAtLeast(in, 1, 1024, 3000);
                ByteBuf decrypted = crypto.decrypt(Unpooled.wrappedBuffer(backEncrypted));
                assertEquals(message, decrypted.toString(StandardCharsets.UTF_8));
                decrypted.release();
            }
        } finally {
            server.close();
        }
    }

    @Test
    @SneakyThrows
    @Timeout(value = 15)
    void shadowsocksUdpRelay_e2e() {
        int proxyPort = 16281;
        String method = CipherKind.AES_256_GCM.getCipherName();
        String password = "test-password";
        ShadowsocksConfig config = new ShadowsocksConfig(Sockets.newAnyEndpoint(proxyPort), method, password);
        config.setDebug(true);
        ShadowsocksServer server = new ShadowsocksServer(config);

        try {
            Thread.sleep(300);
            DatagramSocket sock = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
            sock.setSoTimeout(4000);
            try {
                ICrypto crypto = ICrypto.get(method, password);
                crypto.setForUdp(true);

                // 1) Send encrypted address + payload
                ByteBuf buf = Unpooled.buffer();
                UdpManager.encode(buf, "127.0.0.1", UDP_ECHO_PORT);
                byte[] payload = "ss-udp-e2e".getBytes(StandardCharsets.UTF_8);
                buf.writeBytes(payload);
                
                ByteBuf encrypted = crypto.encrypt(buf);
                byte[] req = toBytes(encrypted);
                sock.send(new java.net.DatagramPacket(req, req.length, InetAddress.getByName("127.0.0.1"), proxyPort));

                // 2) Receive response (encrypted address + payload)
                byte[] resp = new byte[1024];
                java.net.DatagramPacket p = new java.net.DatagramPacket(resp, resp.length);
                sock.receive(p);

                ByteBuf backEncrypted = Unpooled.wrappedBuffer(resp, 0, p.getLength());
                ByteBuf decrypted = crypto.decrypt(backEncrypted);
                
                // SSProtocolCodec.encode for UDP adds the address
                UnresolvedEndpoint addr = UdpManager.decode(decrypted);
                assertEquals(UDP_ECHO_PORT, addr.getPort());
                
                byte[] echoed = new byte[decrypted.readableBytes()];
                decrypted.readBytes(echoed);
                assertArrayEquals(payload, echoed);
                decrypted.release();
            } finally {
                sock.close();
            }
        } finally {
            server.close();
        }
    }

    static byte[] readAtLeast(InputStream in, int minLen, int maxLen, int timeoutMs) throws Exception {
        byte[] buf = new byte[maxLen];
        int read = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (read < minLen && System.currentTimeMillis() < deadline) {
            int n = in.read(buf, read, maxLen - read);
            if (n == -1) break;
            read += n;
        }
        assertTrue(read >= minLen, "short read");
        byte[] out = new byte[read];
        System.arraycopy(buf, 0, out, 0, read);
        return out;
    }

    static byte[] toBytes(ByteBuf buf) {
        byte[] b = new byte[buf.readableBytes()];
        buf.readBytes(b);
        buf.release();
        return b;
    }
}
