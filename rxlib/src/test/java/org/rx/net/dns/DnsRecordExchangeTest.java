package org.rx.net.dns;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DnsRecordExchangeTest {

    @Test
    void queryUdp_FailsOverFromBlackholeToHealthyResolver() throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        try (DatagramSocket blackhole = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
             DatagramSocket responder = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            AtomicInteger responderHits = new AtomicInteger();
            executor.submit(() -> {
                try {
                    byte[] buf = new byte[512];
                    DatagramPacket req = new DatagramPacket(buf, buf.length);
                    responder.receive(req);
                    responderHits.incrementAndGet();
                    byte[] resp = minimalReply(req.getData(), req.getLength());
                    responder.send(new DatagramPacket(resp, resp.length, req.getSocketAddress()));
                } catch (IOException ignored) {
                }
            });

            InetSocketAddress first = (InetSocketAddress) blackhole.getLocalSocketAddress();
            InetSocketAddress second = (InetSocketAddress) responder.getLocalSocketAddress();
            byte[] reply = DnsRecordExchange.query(Arrays.asList(first, second), "example.com", 16, false, 200, 1500);
            assertNotNull(reply);
            assertTrue(reply.length >= 12);
            assertEquals(0x80, reply[2] & 0xf8);
            assertEquals(1, responderHits.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void queryTcp_FailsOverFromClosedPortToHealthyResolver() throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        int closedPort;
        try (ServerSocket tmp = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            closedPort = tmp.getLocalPort();
        }
        try (ServerSocket tcpResponder = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            AtomicInteger hits = new AtomicInteger();
            executor.submit(() -> {
                try (Socket client = tcpResponder.accept()) {
                    DataInputStream in = new DataInputStream(client.getInputStream());
                    int len = in.readUnsignedShort();
                    byte[] req = new byte[len];
                    in.readFully(req);
                    hits.incrementAndGet();
                    byte[] resp = minimalReply(req, req.length);
                    DataOutputStream out = new DataOutputStream(client.getOutputStream());
                    out.writeShort(resp.length);
                    out.write(resp);
                    out.flush();
                } catch (IOException ignored) {
                }
            });

            InetSocketAddress first = new InetSocketAddress("127.0.0.1", closedPort);
            InetSocketAddress second = new InetSocketAddress("127.0.0.1", tcpResponder.getLocalPort());
            byte[] reply = DnsRecordExchange.query(Arrays.asList(first, second), "example.com", 16, true, 300, 2000);
            assertNotNull(reply);
            assertTrue(reply.length >= 12);
            assertEquals(0x80, reply[2] & 0xf8);
            assertEquals(1, hits.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void query_RespectsTotalDeadlineAcrossMultipleBlackholes() throws Exception {
        try (DatagramSocket bh1 = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
             DatagramSocket bh2 = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
             DatagramSocket bh3 = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            long start = System.nanoTime();
            assertThrows(IOException.class, () -> DnsRecordExchange.query(
                    Arrays.asList(
                            (InetSocketAddress) bh1.getLocalSocketAddress(),
                            (InetSocketAddress) bh2.getLocalSocketAddress(),
                            (InetSocketAddress) bh3.getLocalSocketAddress()),
                    "example.com", 16, false, 200, 350));
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
            assertTrue(elapsedMillis < 1200L, "elapsedMillis=" + elapsedMillis);
        }
    }

    @Test
    void query_EmptyResolversThrows() {
        assertThrows(IOException.class, () -> DnsRecordExchange.query(Collections.emptyList(), "example.com", 16, false));
    }

    private static byte[] minimalReply(byte[] req, int len) {
        byte[] resp = Arrays.copyOf(req, Math.max(12, len));
        resp[2] = (byte) 0x81; // QR=1, RD=1
        resp[3] = (byte) 0x80; // RA=1, NOERROR
        return resp;
    }
}
