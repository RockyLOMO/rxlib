package org.rx.net.socks;

import org.junit.jupiter.api.Test;
import org.rx.net.Sockets;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SocksTcpIdleIntegrationTest {
    @Test
    void tcpRelayKeepsOneWayDownloadAliveAndClosesBothSidesAfterSilence() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket source = new ServerSocket()) {
            source.bind(new InetSocketAddress("127.0.0.1", 0));
            source.setSoTimeout(6000);
            Future<Integer> upstreamClose = worker.submit(() -> {
                try (Socket upstream = source.accept()) {
                    upstream.setSoTimeout(6000);
                    OutputStream out = upstream.getOutputStream();
                    for (int i = 0; i < 15; i++) {
                        out.write(i);
                        out.flush();
                        Thread.sleep(200);
                    }
                    // The source stays open: only relay idle can produce EOF here.
                    return upstream.getInputStream().read();
                }
            });
            SocksConfig config = new SocksConfig(Sockets.newLoopbackEndpoint(0));
            config.setReadTimeoutSeconds(0);
            config.setWriteTimeoutSeconds(0);
            config.setTcpIdleTimeoutSeconds(1);
            AtomicReference<InetSocketAddress> proxyAddress = new AtomicReference<>();
            SocksProxyServer proxy = new SocksProxyServer(config, null,
                    ch -> proxyAddress.set((InetSocketAddress) ch.localAddress()));
            try (Socket client = new Socket()) {
                client.connect(proxyAddress.get(), 3000);
                client.setSoTimeout(4000);
                DataInputStream in = new DataInputStream(client.getInputStream());
                OutputStream out = client.getOutputStream();
                out.write(new byte[]{5, 1, 0});
                out.flush();
                assertEquals(5, in.readUnsignedByte());
                assertEquals(0, in.readUnsignedByte());
                int port = source.getLocalPort();
                out.write(new byte[]{5, 1, 0, 1, 127, 0, 0, 1, (byte) (port >>> 8), (byte) port});
                out.flush();
                byte[] reply = new byte[10];
                in.readFully(reply);
                assertEquals(0, reply[1]);
                for (int i = 0; i < 15; i++) {
                    assertEquals(i, in.read(), "Download must survive beyond the one-second idle window");
                }
                assertEquals(-1, in.read(), "Both directions silent must close the frontend");
                assertEquals(-1, upstreamClose.get(4, TimeUnit.SECONDS).intValue(), "Frontend close must close the backend");
            } finally {
                proxy.close();
            }
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
