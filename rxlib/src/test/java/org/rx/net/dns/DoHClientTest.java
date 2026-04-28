package org.rx.net.dns;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DoHClientTest {
    @Test
    void endpointPoolSizeTracksMaxInFlight() throws Exception {
        DoHEndpoint endpoint = new DoHEndpoint(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 9),
                null, "/dns-query");
        try (DoHClient client = new DoHClient(Collections.singletonList(endpoint), null, 5)) {
            assertEquals(5, client.endpointStates.get(0).channels.length);
        }
    }

    @Test
    void channelInactiveCompletesPendingQuery() throws Exception {
        DoHEndpoint endpoint = new DoHEndpoint(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 9),
                null, "/dns-query");
        try (DoHClient client = new DoHClient(Collections.singletonList(endpoint), null, 1)) {
            AtomicReference<List<InetAddress>> result = new AtomicReference<>();
            AtomicReference<Throwable> error = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            DoHClient.EndpointState.EndpointChannel slot = client.endpointStates.get(0).nextChannel();
            DoHClient.ClientHandler handler = client.new ClientHandler(1, result, error, latch, slot);
            EmbeddedChannel channel = new EmbeddedChannel(handler);

            channel.close().syncUninterruptibly();

            assertTrue(latch.await(1, TimeUnit.SECONDS));
            assertTrue(error.get() instanceof IOException);
            assertNull(result.get());
        }
    }

    @Test
    void resolveAfterCloseDoesNotUnderflowInFlight() throws Exception {
        DoHEndpoint endpoint = new DoHEndpoint(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 9),
                null, "/dns-query");
        DoHClient client = new DoHClient(Collections.singletonList(endpoint), null, 1);
        client.close();

        assertNull(client.resolveHost(null, "closed.example"));
        assertEquals(0, client.inFlight.get());
        assertEquals(1, client.rejectedCount.get());
    }
}
