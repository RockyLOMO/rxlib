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
        DnsRecordExchange.clockNanos = System::nanoTime;
        DnsRecordExchange.maxCacheBytes = DnsRecordExchange.DEFAULT_MAX_CACHE_BYTES;
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

    @Test
    void cacheDecaysTtlEnforcesByteBudgetValidatesQuestionAndCachesSoaNegative() throws Exception {
        java.util.concurrent.atomic.AtomicLong fakeClock = new java.util.concurrent.atomic.AtomicLong(1_000_000_000_000L);
        DnsRecordExchange.clockNanos = fakeClock::get;
        DnsRecordExchange.maxCacheBytes = 2048L;

        ExecutorService executor = Executors.newCachedThreadPool();
        try (DatagramSocket responder = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            AtomicInteger hits = new AtomicInteger();
            executor.submit(() -> {
                try {
                    for (int i = 0; i < 8; i++) {
                        byte[] buf = new byte[1500];
                        DatagramPacket req = new DatagramPacket(buf, buf.length);
                        responder.receive(req);
                        int h = hits.incrementAndGet();
                        byte[] resp;
                        if (h == 1) {
                            resp = buildLargeTxtReply(req.getData(), req.getLength(), 60, 400);
                        } else if (h == 2) {
                            // Mismatched question name -> must be rejected!
                            resp = buildWrongQuestionReply(req.getData(), req.getLength());
                        } else if (h == 3) {
                            // NXDOMAIN with SOA (TTL=120, MINIMUM=25) -> effective negative cache TTL = 25s
                            resp = buildNxdomainWithSoa(req.getData(), req.getLength(), 120, 25);
                        } else {
                            resp = buildLargeTxtReply(req.getData(), req.getLength(), 60, 700);
                        }
                        responder.send(new DatagramPacket(resp, resp.length, req.getSocketAddress()));
                    }
                } catch (IOException ignored) {
                }
            });

            InetSocketAddress server = (InetSocketAddress) responder.getLocalSocketAddress();
            byte[] first = DnsRecordExchange.query(Collections.singletonList(server), "ttl-decay.example.com", 16, false, 500, 1500);
            assertEquals(60L, extractFirstAnswerTtl(first));

            // Advance 25s -> cached reply must decay TTL from 60 to 35!
            fakeClock.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(25));
            byte[] decayed = DnsRecordExchange.query(Collections.singletonList(server), "ttl-decay.example.com", 16, false, 500, 1500);
            assertEquals(35L, extractFirstAnswerTtl(decayed), "Cached RR TTL must decay by elapsed seconds");
            assertEquals(1, hits.get());

            // Mismatched question response must be rejected (not cached!)
            assertThrows(IOException.class, () ->
                    DnsRecordExchange.query(Collections.singletonList(server), "mismatch.example.com", 16, false, 500, 1500));

            // NXDOMAIN with SOA(120, min=25) capped by DEFAULT_NEGATIVE_TTL (5s): hits at +3s, expires at +6s
            byte[] neg1 = DnsRecordExchange.query(Collections.singletonList(server), "nx.example.com", 16, false, 500, 1500);
            assertEquals(3, neg1[3] & 0x0f);
            int hitsAfterNeg1 = hits.get();
            fakeClock.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(3));
            byte[] neg2 = DnsRecordExchange.query(Collections.singletonList(server), "nx.example.com", 16, false, 500, 1500);
            assertEquals(3, neg2[3] & 0x0f);
            assertEquals(hitsAfterNeg1, hits.get(), "SOA negative reply must be cached within min(SOA.TTL, SOA.MINIMUM, DEFAULT_NEGATIVE_TTL)");

            // Byte budget eviction: inserting several 700B replies under 2048B cap keeps cachedBytesUsed <= 2048
            for (int k = 0; k < 4; k++) {
                DnsRecordExchange.query(Collections.singletonList(server), "budget-" + k + ".example.com", 16, false, 500, 1500);
            }
            assertTrue(DnsRecordExchange.cachedBytesUsed() <= 2048L,
                    "Cache byte budget exceeded: " + DnsRecordExchange.cachedBytesUsed());
        } finally {
            executor.shutdownNow();
        }
    }

    private static long extractFirstAnswerTtl(byte[] resp) {
        int p = 12;
        while (p < resp.length && resp[p] != 0) {
            p += 1 + (resp[p] & 0xff);
        }
        p += 5; // end of question
        if ((resp[p] & 0xc0) == 0xc0) {
            p += 2;
        } else {
            while (p < resp.length && resp[p] != 0) {
                p += 1 + (resp[p] & 0xff);
            }
            p += 1;
        }
        return ((long) (resp[p + 4] & 0xff) << 24)
                | ((long) (resp[p + 5] & 0xff) << 16)
                | ((long) (resp[p + 6] & 0xff) << 8)
                | (resp[p + 7] & 0xff);
    }

    private static byte[] buildWrongQuestionReply(byte[] req, int reqLen) {
        io.netty.buffer.ByteBuf q = Unpooled.buffer(128);
        try {
            int id = ((req[0] & 0xff) << 8) | (req[1] & 0xff);
            DoHMessageCodec.encodeQuery(q, id, "spoofed-other.example.com", io.netty.handler.codec.dns.DnsRecordType.TXT, 0);
            byte[] out = io.netty.buffer.ByteBufUtil.getBytes(q);
            out[2] = (byte) 0x81;
            out[3] = (byte) 0x80;
            return out;
        } finally {
            q.release();
        }
    }

    private static byte[] buildNxdomainWithSoa(byte[] req, int reqLen, int soaTtl, int soaMin) {
        int qEnd = 12;
        while (qEnd < reqLen && req[qEnd] != 0) {
            qEnd += 1 + (req[qEnd] & 0xff);
        }
        qEnd += 5;
        // Minimal SOA RDATA: MNAME(root=0x00) + RNAME(root=0x00) + 5*uint32 = 22 bytes
        int rdLen = 22;
        byte[] out = new byte[qEnd + 12 + rdLen];
        System.arraycopy(req, 0, out, 0, qEnd);
        out[2] = (byte) 0x81;
        out[3] = (byte) 0x83; // NXDOMAIN
        out[6] = 0; out[7] = 0; // ANCOUNT=0
        out[8] = 0; out[9] = 1; // NSCOUNT=1
        out[10] = 0; out[11] = 0; // ARCOUNT=0
        int p = qEnd;
        out[p++] = (byte) 0xc0; out[p++] = 12; // name pointer
        out[p++] = 0; out[p++] = 6; // TYPE=SOA
        out[p++] = 0; out[p++] = 1; // CLASS=IN
        out[p++] = (byte) (soaTtl >>> 24);
        out[p++] = (byte) (soaTtl >>> 16);
        out[p++] = (byte) (soaTtl >>> 8);
        out[p++] = (byte) soaTtl;
        out[p++] = (byte) (rdLen >>> 8);
        out[p++] = (byte) rdLen;
        out[p++] = 0; // MNAME = .
        out[p++] = 0; // RNAME = .
        p += 16; // serial/refresh/retry/expire = 0
        out[p++] = (byte) (soaMin >>> 24);
        out[p++] = (byte) (soaMin >>> 16);
        out[p++] = (byte) (soaMin >>> 8);
        out[p] = (byte) soaMin;
        return out;
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
