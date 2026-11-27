package org.rx.net.dns;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

    @BeforeEach
    @AfterEach
    void resetState() {
        DnsRecordExchange.clearCache();
        DnsRecordExchange.closeIdleSockets();
    }

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

    @Test
    void queryUdp_SendsEdns0AcceptsLargeTxtReplyAndCachesByTtl() throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        try (DatagramSocket responder = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            AtomicInteger hits = new AtomicInteger();
            AtomicInteger observedEdnsSize = new AtomicInteger();
            executor.submit(() -> {
                try {
                    byte[] buf = new byte[1500];
                    DatagramPacket req = new DatagramPacket(buf, buf.length);
                    responder.receive(req);
                    hits.incrementAndGet();
                    observedEdnsSize.set(DoHMessageCodec.extractEdnsUdpPayloadSize(
                            Unpooled.wrappedBuffer(req.getData(), 0, req.getLength())));
                    // Build a > 700 byte valid TXT reply with TTL=60
                    byte[] resp = buildLargeTxtReply(req.getData(), req.getLength(), 60, 700);
                    responder.send(new DatagramPacket(resp, resp.length, req.getSocketAddress()));
                } catch (IOException ignored) {
                }
            });

            InetSocketAddress server = (InetSocketAddress) responder.getLocalSocketAddress();
            byte[] first = DnsRecordExchange.query(Collections.singletonList(server), "edns-txt.example.com", 16, false, 500, 1500);
            assertTrue(first.length > 600, "EDNS0 UDP reply > 512 bytes must succeed, len=" + first.length);
            assertEquals(DnsRecordExchange.EDNS_UDP_PAYLOAD_SIZE, observedEdnsSize.get(), "Query must carry EDNS0 1232 OPT");
            assertEquals(1, hits.get());

            long hitsBefore = DnsRecordExchange.cacheHits();
            byte[] second = DnsRecordExchange.query(Collections.singletonList(server), "EDNS-TXT.example.com.", 16, false, 500, 1500);
            assertArrayEquals(first, second);
            assertEquals(1, hits.get(), "Second query within TTL must hit non-address RR cache");
            assertTrue(DnsRecordExchange.cacheHits() > hitsBefore);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void queryUdp_TruncatedReplyUpgradesToTcpAndEdnsFormerrFallsBack() throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        try (DatagramSocket udpSrv = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
             ServerSocket tcpSrv = new ServerSocket(udpSrv.getLocalPort(), 4, InetAddress.getByName("127.0.0.1"))) {
            AtomicInteger udpHits = new AtomicInteger();
            AtomicInteger tcpHits = new AtomicInteger();
            executor.submit(() -> {
                try {
                    for (int i = 0; i < 3; i++) {
                        byte[] buf = new byte[1500];
                        DatagramPacket req = new DatagramPacket(buf, buf.length);
                        udpSrv.receive(req);
                        int hit = udpHits.incrementAndGet();
                        int edns = DoHMessageCodec.extractEdnsUdpPayloadSize(Unpooled.wrappedBuffer(req.getData(), 0, req.getLength()));
                        byte[] resp;
                        if (hit == 1 && edns > 512) {
                            // First query: simulate legacy resolver returning FORMERR to EDNS0
                            resp = minimalReply(req.getData(), 12);
                            resp[3] = (byte) 0x81; // FORMERR
                        } else {
                            // Second query (or TC test): return TC=1 truncated reply
                            resp = minimalReply(req.getData(), req.getLength());
                            resp[2] |= 0x02; // TC=1
                        }
                        udpSrv.send(new DatagramPacket(resp, resp.length, req.getSocketAddress()));
                    }
                } catch (IOException ignored) {
                }
            });
            executor.submit(() -> {
                try (Socket client = tcpSrv.accept()) {
                    DataInputStream in = new DataInputStream(client.getInputStream());
                    DataOutputStream out = new DataOutputStream(client.getOutputStream());
                    int len = in.readUnsignedShort();
                    byte[] req = new byte[len];
                    in.readFully(req);
                    tcpHits.incrementAndGet();
                    byte[] resp = buildLargeTxtReply(req, req.length, 30, 800);
                    out.writeShort(resp.length);
                    out.write(resp);
                    out.flush();
                } catch (IOException ignored) {
                }
            });

            InetSocketAddress server = new InetSocketAddress("127.0.0.1", udpSrv.getLocalPort());
            byte[] reply = DnsRecordExchange.query(Collections.singletonList(server), "tc-upgrade.example.com", 16, false, 500, 2000);
            assertTrue(reply.length > 700, "FORMERR->legacy UDP->TC=1 should transparently upgrade to TCP");
            assertEquals(2, udpHits.get());
            assertEquals(1, tcpHits.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void queryTcpAndUdp_ReuseUpstreamConnectionsAcrossDistinctDomains() throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        try (ServerSocket tcpSrv = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
             DatagramSocket udpSrv = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            AtomicInteger tcpAccepted = new AtomicInteger();
            executor.submit(() -> {
                try (Socket client = tcpSrv.accept()) {
                    tcpAccepted.incrementAndGet();
                    DataInputStream in = new DataInputStream(client.getInputStream());
                    DataOutputStream out = new DataOutputStream(client.getOutputStream());
                    for (int i = 0; i < 2; i++) {
                        int len = in.readUnsignedShort();
                        byte[] req = new byte[len];
                        in.readFully(req);
                        byte[] resp = minimalReply(req, req.length);
                        out.writeShort(resp.length);
                        out.write(resp);
                        out.flush();
                    }
                } catch (IOException ignored) {
                }
            });
            executor.submit(() -> {
                try {
                    for (int i = 0; i < 2; i++) {
                        byte[] buf = new byte[1500];
                        DatagramPacket req = new DatagramPacket(buf, buf.length);
                        udpSrv.receive(req);
                        byte[] resp = minimalReply(req.getData(), req.getLength());
                        udpSrv.send(new DatagramPacket(resp, resp.length, req.getSocketAddress()));
                    }
                } catch (IOException ignored) {
                }
            });

            long tcpReusesBefore = DnsRecordExchange.tcpReuses();
            long udpReusesBefore = DnsRecordExchange.udpReuses();
            InetSocketAddress tcpEp = new InetSocketAddress("127.0.0.1", tcpSrv.getLocalPort());
            InetSocketAddress udpEp = (InetSocketAddress) udpSrv.getLocalSocketAddress();

            DnsRecordExchange.query(Collections.singletonList(tcpEp), "reuse-a.example.com", 16, true, 500, 1500);
            DnsRecordExchange.query(Collections.singletonList(tcpEp), "reuse-b.example.com", 16, true, 500, 1500);
            assertEquals(1, tcpAccepted.get(), "Second TCP query should reuse pooled connection");
            assertTrue(DnsRecordExchange.tcpReuses() > tcpReusesBefore);

            DnsRecordExchange.query(Collections.singletonList(udpEp), "reuse-c.example.com", 16, false, 500, 1500);
            DnsRecordExchange.query(Collections.singletonList(udpEp), "reuse-d.example.com", 16, false, 500, 1500);
            assertTrue(DnsRecordExchange.udpReuses() > udpReusesBefore);
        } finally {
            executor.shutdownNow();
        }
    }

    private static byte[] buildLargeTxtReply(byte[] req, int reqLen, int ttlSeconds, int txtBytes) {
        // Strip OPT from question section copy by finding end of question
        int qEnd = 12;
        while (qEnd < reqLen && req[qEnd] != 0) {
            qEnd += 1 + (req[qEnd] & 0xff);
        }
        qEnd += 5; // null label + qtype(2) + qclass(2)
        int chunks = (txtBytes + 254) / 255;
        int rdLen = txtBytes + chunks;
        byte[] out = new byte[qEnd + 12 + rdLen];
        System.arraycopy(req, 0, out, 0, qEnd);
        out[2] = (byte) 0x81; // QR=1, RD=1
        out[3] = (byte) 0x80; // RA=1, NOERROR
        out[6] = 0; out[7] = 1; // ANCOUNT=1
        out[8] = 0; out[9] = 0; // NSCOUNT=0
        out[10] = 0; out[11] = 0; // ARCOUNT=0
        int p = qEnd;
        out[p++] = (byte) 0xc0; out[p++] = 12; // name pointer to offset 12
        out[p++] = 0; out[p++] = 16; // TYPE=TXT
        out[p++] = 0; out[p++] = 1;  // CLASS=IN
        out[p++] = (byte) (ttlSeconds >>> 24);
        out[p++] = (byte) (ttlSeconds >>> 16);
        out[p++] = (byte) (ttlSeconds >>> 8);
        out[p++] = (byte) ttlSeconds;
        out[p++] = (byte) (rdLen >>> 8);
        out[p++] = (byte) rdLen;
        int remaining = txtBytes;
        while (remaining > 0) {
            int seg = Math.min(255, remaining);
            out[p++] = (byte) seg;
            for (int i = 0; i < seg; i++) {
                out[p++] = 'a';
            }
            remaining -= seg;
        }
        return out;
    }

    private static byte[] minimalReply(byte[] req, int len) {
        byte[] resp = Arrays.copyOf(req, Math.max(12, len));
        resp[2] = (byte) 0x81; // QR=1, RD=1
        resp[3] = (byte) 0x80; // RA=1, NOERROR
        return resp;
    }
}
