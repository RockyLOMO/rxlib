package org.rx.net.transport.hybrid;

import io.netty.channel.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.core.Delegate;
import org.rx.core.NEventArgs;
import org.rx.net.transport.TcpClient;
import org.rx.net.transport.TcpServerConfig;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridServerSessionConflictTest {
    static class FakeTcpClient implements TcpClient {
        final InetSocketAddress remoteEndpoint;
        final Delegate<TcpClient, NEventArgs<Object>> onReceive = Delegate.create();
        final List<Object> sent = new ArrayList<Object>();
        boolean connected = true;
        boolean closed;

        FakeTcpClient(InetSocketAddress remoteEndpoint) {
            this.remoteEndpoint = remoteEndpoint;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public InetSocketAddress getRemoteEndpoint() {
            return remoteEndpoint;
        }

        @Override
        public void connect(InetSocketAddress remoteEp) throws TimeoutException {
        }

        @Override
        public Future<Void> connectAsync(InetSocketAddress remoteEp) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void send(Object pack) {
            sent.add(pack);
        }

        @Override
        public Delegate<TcpClient, NEventArgs<Object>> onReceive() {
            return onReceive;
        }

        @Override
        public Channel getChannel() {
            return null;
        }

        @Override
        public void close() {
            connected = false;
            closed = true;
        }
    }

    @Test
    @Timeout(10)
    void duplicateSessionIdIsRejected() throws Exception {
        HybridConfig serverConfig = new HybridConfig();
        serverConfig.setTcpServerConfig(new TcpServerConfig(freePort()));
        serverConfig.setEnableUdpDirect(false);

        try (HybridServer server = new HybridServer(serverConfig)) {
            FakeTcpClient first = new FakeTcpClient(new InetSocketAddress("127.0.0.1", 10001));
            server.handleHello(first, hello(7L, 11L, "first"));

            assertNotNull(server.getSession(7L));
            assertFalse(first.closed);
            assertEquals(1, first.sent.size());

            FakeTcpClient second = new FakeTcpClient(new InetSocketAddress("127.0.0.1", 10002));
            server.handleHello(second, hello(7L, 22L, "second"));

            assertTrue(second.closed);
            assertFalse(first.closed);
            assertEquals(first.remoteEndpoint, server.getSession(7L).tcpRemoteEndpoint());
            assertEquals(1, server.getMetrics().tcpSessionConflicts());
        }
    }

    static HybridHello hello(long sessionId, long token, String peerId) {
        HybridHello hello = new HybridHello();
        hello.version = 1;
        hello.sessionId = sessionId;
        hello.udpToken = token;
        hello.peerId = peerId;
        hello.udpLocalHost = "127.0.0.1";
        hello.udpLocalPort = 0;
        return hello;
    }

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
