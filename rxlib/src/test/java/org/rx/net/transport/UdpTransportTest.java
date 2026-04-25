package org.rx.net.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.rx.exception.InvalidException;

import java.io.Serializable;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UdpTransportTest {
    static int freeUdpPort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    @Timeout(10)
    void fullSyncResendsUntilHandlerCompletes() throws Exception {
        int serverPort = freeUdpPort();
        int clientPort = freeUdpPort();
        InetSocketAddress serverEndpoint = new InetSocketAddress("127.0.0.1", serverPort);

        try (UdpClient server = new UdpClient(serverPort);
             UdpClient client = new UdpClient(clientPort)) {
            server.setWaitAckTimeoutMillis(1200);
            client.setWaitAckTimeoutMillis(1200);
            client.setMaxResend(3);

            AtomicInteger attempts = new AtomicInteger();
            CountDownLatch handled = new CountDownLatch(1);
            server.onReceive.combine((s, e) -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new InvalidException("first attempt fail");
                }
                handled.countDown();
            });

            client.sendAsync(serverEndpoint, "retry-check", 1200, true);

            assertTrue(handled.await(3, TimeUnit.SECONDS), "服务端应在重试后处理成功");
            assertEquals(2, attempts.get(), "首个 FULL ACK 失败后应触发一次重发");
        }
    }

    @Test
    @Timeout(10)
    void requestTransfersSerializableObjectAcrossFragments() throws Exception {
        int serverPort = freeUdpPort();
        int clientPort = freeUdpPort();
        InetSocketAddress serverEndpoint = new InetSocketAddress("127.0.0.1", serverPort);

        try (UdpClient server = new UdpClient(serverPort);
             UdpClient client = new UdpClient(clientPort)) {
            server.setWaitAckTimeoutMillis(1500);
            client.setWaitAckTimeoutMillis(1500);
            server.setMaxFragmentPayloadBytes(128);
            client.setMaxFragmentPayloadBytes(128);

            byte[] payload = new byte[4096];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i % 251);
            }
            EchoRequest request = new EchoRequest("frag-rpc", payload);

            server.onReceive.combine((s, e) -> {
                EchoRequest packet = e.getValue().packet();
                s.reply(e.getValue(), new EchoResponse(packet.name, Arrays.copyOf(packet.payload, packet.payload.length)));
            });

            EchoResponse response = client.request(serverEndpoint, request, EchoResponse.class, 3000);
            assertEquals(request.name, response.name);
            assertArrayEquals(request.payload, response.payload);
        }
    }

    static final class EchoRequest implements Serializable {
        private static final long serialVersionUID = -5005224312237928424L;
        final String name;
        final byte[] payload;

        EchoRequest(String name, byte[] payload) {
            this.name = name;
            this.payload = payload;
        }
    }

    static final class EchoResponse implements Serializable {
        private static final long serialVersionUID = 3123942842540140752L;
        final String name;
        final byte[] payload;

        EchoResponse(String name, byte[] payload) {
            this.name = name;
            this.payload = payload;
        }
    }
}
