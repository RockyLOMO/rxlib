package org.rx.net.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class TcpTransportTest {
    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    @Timeout(10)
    void connectFailureCompletesSynchronouslyAndAsynchronously() throws Exception {
        InetSocketAddress endpoint = new InetSocketAddress("127.0.0.1", freePort());

        TcpClientConfig syncConfig = new TcpClientConfig();
        syncConfig.setServerEndpoint(endpoint);
        syncConfig.setEnableReconnect(false);
        syncConfig.setConnectTimeoutMillis(5000);
        DefaultTcpClient syncClient = new DefaultTcpClient(syncConfig);
        try {
            long start = System.nanoTime();
            TimeoutException ex = assertThrows(TimeoutException.class, () -> syncClient.connect(endpoint));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsed < syncConfig.getConnectTimeoutMillis() - 500L,
                    "连接失败应早于 connectTimeoutMillis 返回，而不是等满超时");
            assertNotNull(ex.getCause(), "失败原因应透传给调用方");
        } finally {
            syncClient.close();
        }

        TcpClientConfig asyncConfig = new TcpClientConfig();
        asyncConfig.setServerEndpoint(endpoint);
        asyncConfig.setEnableReconnect(false);
        asyncConfig.setConnectTimeoutMillis(5000);
        DefaultTcpClient asyncClient = new DefaultTcpClient(asyncConfig);
        try {
            Future<Void> future = asyncClient.connectAsync(endpoint);
            awaitTrue(future::isDone, asyncConfig.getConnectTimeoutMillis(), "异步连接失败后 future 必须结束");
            ExecutionException ex = assertThrows(ExecutionException.class, future::get);
            assertNotNull(ex.getCause(), "异步失败原因应透传给调用方");
        } finally {
            asyncClient.close();
        }
    }

    @Test
    @Timeout(10)
    void serverCapacityLimitRejectsOverflowConnections() throws Exception {
        int port = freePort();
        TcpServerConfig config = new TcpServerConfig(port);
        config.setCapacity(1);

        TcpServer server = new TcpServer(config);
        AtomicInteger connectedCount = new AtomicInteger();
        server.onConnected.add((s, e) -> connectedCount.incrementAndGet());
        server.start();

        Socket first = new Socket();
        Socket second = new Socket();
        try {
            first.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            awaitTrue(() -> server.getClients().size() == 1, 2000, "首个连接应成功注册");

            second.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            awaitTrue(() -> server.getClients().size() == 1, 2000, "超出容量的连接不应进入 clients");

            assertEquals(1, connectedCount.get(), "容量满时不应再触发 onConnected");
            assertTrue(server.getClients().containsKey((InetSocketAddress) first.getLocalSocketAddress()),
                    "已接入连接应保持在 clients 中");
        } finally {
            closeQuietly(second);
            closeQuietly(first);
            server.close();
        }
    }

    static void awaitTrue(BooleanSupplier condition, long timeoutMillis, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }
}
