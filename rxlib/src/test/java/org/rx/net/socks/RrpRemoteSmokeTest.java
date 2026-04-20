package org.rx.net.socks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.net.Sockets;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RrpRemoteSmokeTest {
    private static final String PROP_SERVER_ENDPOINT = "rrp.smoke.serverEndpoint";
    private static final String PROP_TOKEN = "rrp.smoke.token";
    private static final String PROP_REMOTE_PORT = "rrp.smoke.remotePort";
    private static final String PROP_AUTH = "rrp.smoke.auth";

    @Test
    @SneakyThrows
    @Timeout(value = 35)
    void remoteServer_socks5PasswordAuth_echoRoundTrip() {
        // 远端 smoke 默认跳过，避免常规单测依赖真实部署。
        String serverEndpoint = System.getProperty(PROP_SERVER_ENDPOINT);
        Assumptions.assumeTrue(!isBlank(serverEndpoint), "set -D" + PROP_SERVER_ENDPOINT + " to run remote RRP smoke test");

        String token = requireProperty(PROP_TOKEN);
        int remotePort = Integer.parseInt(System.getProperty(PROP_REMOTE_PORT, "6014"));
        String auth = requireProperty(PROP_AUTH);
        int split = auth.indexOf(':');
        assertTrue(split > 0 && split < auth.length() - 1, PROP_AUTH + " must be user:password");
        String user = auth.substring(0, split);
        String password = auth.substring(split + 1);

        ServerBootstrap echoBootstrap = Sockets.serverBootstrap(ch -> ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ctx.writeAndFlush(msg);
            }
        }));
        Channel echoChannel = echoBootstrap.bind(Sockets.newAnyEndpoint(0)).sync().channel();
        int echoPort = ((InetSocketAddress) echoChannel.localAddress()).getPort();

        RrpConfig.Proxy proxy = new RrpConfig.Proxy();
        proxy.setName("smoke-" + remotePort);
        proxy.setRemotePort(remotePort);
        proxy.setAuth(auth);

        RrpConfig config = new RrpConfig();
        config.setToken(token);
        config.setServerEndpoint(serverEndpoint);
        config.setProxies(Collections.singletonList(proxy));

        RrpClient client = new RrpClient(config);
        InetSocketAddress server = Sockets.parseEndpoint(serverEndpoint);
        try {
            client.connectAsync().get(10, TimeUnit.SECONDS);
            waitForPortOpen(server.getHostString(), remotePort, 10000);

            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(server.getHostString(), remotePort), 5000);
                socket.setSoTimeout(5000);

                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                out.write(new byte[]{0x05, 0x01, 0x02});
                out.flush();
                assertArrayEquals(new byte[]{0x05, 0x02}, readExact(in, 2, 3000));

                writePasswordAuth(out, user, password);
                assertArrayEquals(new byte[]{0x01, 0x00}, readExact(in, 2, 3000));

                out.write(buildSocks5ConnectReqLoopback(echoPort));
                out.flush();
                byte[] conn = readAtLeast(in, 4, 10, 3000);
                assertEquals(0x05, conn[0] & 0xFF);
                assertEquals(0x00, conn[1] & 0xFF);

                byte[] payload = ("rrp-remote-smoke-" + remotePort).getBytes(StandardCharsets.UTF_8);
                out.write(payload);
                out.flush();
                assertArrayEquals(payload, readExact(in, payload.length, 3000));
            }
        } finally {
            client.close();
            echoChannel.close().syncUninterruptibly();
            Sockets.closeBootstrap(echoBootstrap);
        }
    }

    static String requireProperty(String name) {
        String value = System.getProperty(name);
        Assumptions.assumeTrue(!isBlank(value), "set -D" + name + " to run remote RRP smoke test");
        return value;
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    static void waitForPortOpen(String host, int port, int timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 300);
                return;
            } catch (Exception ignore) {
                Thread.sleep(100);
            }
        }
        throw new AssertionError("port not open: " + host + ":" + port);
    }

    static void writePasswordAuth(OutputStream out, String user, String password) throws Exception {
        byte[] u = user.getBytes(StandardCharsets.US_ASCII);
        byte[] p = password.getBytes(StandardCharsets.US_ASCII);
        out.write(0x01);
        out.write(u.length);
        out.write(u);
        out.write(p.length);
        out.write(p);
        out.flush();
    }

    static byte[] buildSocks5ConnectReqLoopback(int port) {
        byte[] req = new byte[10];
        req[0] = 0x05;
        req[1] = 0x01;
        req[2] = 0x00;
        req[3] = 0x01;
        req[4] = 127;
        req[5] = 0;
        req[6] = 0;
        req[7] = 1;
        req[8] = (byte) ((port >> 8) & 0xFF);
        req[9] = (byte) (port & 0xFF);
        return req;
    }

    static byte[] readExact(InputStream in, int len, int timeoutMs) throws Exception {
        byte[] buf = new byte[len];
        int read = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (read < len && System.currentTimeMillis() < deadline) {
            int n = in.read(buf, read, len - read);
            if (n == -1) {
                break;
            }
            read += n;
        }
        assertEquals(len, read, "short read");
        return buf;
    }

    static byte[] readAtLeast(InputStream in, int minLen, int maxLen, int timeoutMs) throws Exception {
        byte[] buf = new byte[maxLen];
        int read = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (read < minLen && System.currentTimeMillis() < deadline) {
            int n = in.read(buf, read, maxLen - read);
            if (n == -1) {
                break;
            }
            read += n;
        }
        assertTrue(read >= minLen, "short read");
        byte[] out = new byte[read];
        System.arraycopy(buf, 0, out, 0, read);
        return out;
    }
}
