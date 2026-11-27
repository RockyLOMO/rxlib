package org.rx.net.dns;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.DnsRecordType;
import org.rx.core.Cache;
import org.rx.core.CachePolicy;
import org.rx.core.RxConfig;
import org.rx.core.cache.MemoryCache;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Preserves compressed RDATA pointers for non-address records with bounded TTL caching,
 * EDNS0 (1232-byte UDP payload with legacy fallback and TC->TCP retry), and bounded upstream socket reuse.
 * Called on DNS/RPC workers only.
 */
public final class DnsRecordExchange {
    public static final int EDNS_UDP_PAYLOAD_SIZE = 1232;
    private static final int LEGACY_UDP_PAYLOAD_SIZE = 512;
    private static final int MAX_CACHE_ENTRIES = 4096;
    private static final int MIN_POSITIVE_TTL_SECONDS = 1;
    private static final int MAX_POSITIVE_TTL_SECONDS = 300;
    private static final int MAX_IDLE_PER_SERVER = 4;
    private static final int MAX_POOLED_SERVERS = 16;
    private static final long TCP_IDLE_TTL_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final long UDP_IDLE_TTL_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final Semaphore SLOTS = new Semaphore(32);
    private static final int TIMEOUT = 2000;
    private static final int TOTAL_DEADLINE_MILLIS = 3500;

    private static final Cache<String, byte[]> RECORD_CACHE = new MemoryCache<String, byte[]>(b -> b.maximumSize(MAX_CACHE_ENTRIES));
    private static final ConcurrentHashMap<InetSocketAddress, ConcurrentLinkedDeque<PooledTcp>> TCP_POOL =
            new ConcurrentHashMap<InetSocketAddress, ConcurrentLinkedDeque<PooledTcp>>();
    private static final ConcurrentHashMap<InetSocketAddress, ConcurrentLinkedDeque<PooledUdp>> UDP_POOL =
            new ConcurrentHashMap<InetSocketAddress, ConcurrentLinkedDeque<PooledUdp>>();
    private static final AtomicLong CACHE_HITS = new AtomicLong();
    private static final AtomicLong CACHE_MISSES = new AtomicLong();
    private static final AtomicLong TCP_REUSES = new AtomicLong();
    private static final AtomicLong UDP_REUSES = new AtomicLong();

    private static final class PooledTcp {
        final Socket socket;
        final long returnedAtNanos;

        PooledTcp(Socket socket, long returnedAtNanos) {
            this.socket = socket;
            this.returnedAtNanos = returnedAtNanos;
        }
    }

    private static final class PooledUdp {
        final DatagramSocket socket;
        final long returnedAtNanos;

        PooledUdp(DatagramSocket socket, long returnedAtNanos) {
            this.socket = socket;
            this.returnedAtNanos = returnedAtNanos;
        }
    }

    private DnsRecordExchange() {
    }

    public static List<InetSocketAddress> directResolvers() {
        return DnsClient.parseNameServers(RxConfig.INSTANCE.getNet().getDns().getDirectServers());
    }

    public static void clearCache() {
        RECORD_CACHE.clear();
    }

    public static void closeIdleSockets() {
        for (ConcurrentLinkedDeque<PooledTcp> deque : TCP_POOL.values()) {
            PooledTcp item;
            while ((item = deque.pollFirst()) != null) {
                closeQuietly(item.socket);
            }
        }
        TCP_POOL.clear();
        for (ConcurrentLinkedDeque<PooledUdp> deque : UDP_POOL.values()) {
            PooledUdp item;
            while ((item = deque.pollFirst()) != null) {
                closeQuietly(item.socket);
            }
        }
        UDP_POOL.clear();
    }

    static long cacheHits() {
        return CACHE_HITS.get();
    }

    static long cacheMisses() {
        return CACHE_MISSES.get();
    }

    static long tcpReuses() {
        return TCP_REUSES.get();
    }

    static long udpReuses() {
        return UDP_REUSES.get();
    }

    public static byte[] query(List<InetSocketAddress> servers, String domain, int type, boolean tcp) throws IOException {
        return query(servers, domain, type, tcp, TIMEOUT, TOTAL_DEADLINE_MILLIS);
    }

    static byte[] query(List<InetSocketAddress> servers, String domain, int type, boolean tcp,
                        int perAttemptTimeoutMillis, int totalDeadlineMillis) throws IOException {
        if (domain == null || domain.length() > 254 || type < 1 || type >= 249 && type <= 255 || type > 65535) {
            throw new IOException("Unsupported DNS question");
        }
        if (servers == null || servers.isEmpty()) {
            throw new IOException("No configured DNS resolver");
        }
        String cacheKey = buildCacheKey(servers, domain, type);
        byte[] cached = RECORD_CACHE.get(cacheKey);
        if (cached != null) {
            CACHE_HITS.incrementAndGet();
            return Arrays.copyOf(cached, cached.length);
        }
        CACHE_MISSES.incrementAndGet();

        if (!SLOTS.tryAcquire()) {
            throw new IOException("DNS query capacity reached");
        }
        try {
            int attemptCap = Math.max(1, perAttemptTimeoutMillis);
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(attemptCap, totalDeadlineMillis));
            IOException lastFailure = null;
            boolean attempted = false;
            for (InetSocketAddress server : servers) {
                if (server == null || server.isUnresolved()) {
                    continue;
                }
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                if (remainingMillis <= 0L) {
                    if (lastFailure == null) {
                        lastFailure = new SocketTimeoutException("DNS query deadline exceeded");
                    }
                    break;
                }
                int timeoutMillis = (int) Math.min(attemptCap, remainingMillis);
                attempted = true;
                try {
                    byte[] response = tcp
                            ? querySingleTcp(server, domain, type, timeoutMillis, deadlineNanos)
                            : querySingleUdpWithFallback(server, domain, type, timeoutMillis, deadlineNanos);
                    int ttlSeconds = parseCacheTtlSeconds(response);
                    if (ttlSeconds > 0) {
                        RECORD_CACHE.put(cacheKey, Arrays.copyOf(response, response.length), CachePolicy.absolute(ttlSeconds));
                    }
                    return response;
                } catch (IOException failure) {
                    lastFailure = failure;
                }
            }
            if (lastFailure != null) {
                throw lastFailure;
            }
            throw new IOException(attempted ? "DNS query timed out" : "No configured DNS resolver");
        } finally {
            SLOTS.release();
        }
    }

    private static byte[] querySingleTcp(InetSocketAddress server, String domain, int type,
                                         int timeoutMillis, long deadlineNanos) throws IOException {
        int id = ThreadLocalRandom.current().nextInt(65536);
        byte[] request = encodeRequest(id, domain, type, EDNS_UDP_PAYLOAD_SIZE);
        byte[] response = exchangeTcp(server, request, timeoutMillis, deadlineNanos);
        validateResponse(server, id, response);
        return response;
    }

    private static byte[] querySingleUdpWithFallback(InetSocketAddress server, String domain, int type,
                                                     int timeoutMillis, long deadlineNanos) throws IOException {
        int id = ThreadLocalRandom.current().nextInt(65536);
        byte[] ednsRequest = encodeRequest(id, domain, type, EDNS_UDP_PAYLOAD_SIZE);
        byte[] response = exchangeUdp(server, id, ednsRequest, timeoutMillis, EDNS_UDP_PAYLOAD_SIZE);
        validateResponse(server, id, response);

        int rcode = response[3] & 0x0f;
        // Legacy resolvers that reject EDNS0 OPT with FORMERR or NOTIMP: retry once without EDNS0
        if (rcode == 1 || rcode == 4) {
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remaining > 20L) {
                int retryId = ThreadLocalRandom.current().nextInt(65536);
                byte[] legacyRequest = encodeRequest(retryId, domain, type, 0);
                byte[] legacyResponse = exchangeUdp(server, retryId, legacyRequest,
                        (int) Math.min(timeoutMillis, remaining), LEGACY_UDP_PAYLOAD_SIZE);
                validateResponse(server, retryId, legacyResponse);
                response = legacyResponse;
            }
        }
        // Truncated UDP reply (TC=1): automatically upgrade to TCP within remaining deadline
        if ((response[2] & 0x02) != 0) {
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remaining > 20L) {
                try {
                    return querySingleTcp(server, domain, type, (int) Math.min(timeoutMillis, remaining), deadlineNanos);
                } catch (IOException ignored) {
                    // Fall back to returning the valid truncated reply if TCP upgrade fails
                }
            }
        }
        return response;
    }

    private static byte[] encodeRequest(int id, String domain, int type, int ednsUdpPayloadSize) {
        ByteBuf query = Unpooled.buffer(256, 512);
        try {
            DoHMessageCodec.encodeQuery(query, id, domain, DnsRecordType.valueOf(type), ednsUdpPayloadSize);
            return ByteBufUtil.getBytes(query);
        } finally {
            query.release();
        }
    }

    private static void validateResponse(InetSocketAddress server, int expectedId, byte[] response) throws IOException {
        if (response.length < 12 || (response[2] & 0xf8) != 0x80
                || (((response[0] & 255) << 8) | (response[1] & 255)) != expectedId) {
            throw new IOException("Invalid recursive DNS response from " + server);
        }
    }

    private static byte[] exchangeTcp(InetSocketAddress server, byte[] request, int timeoutMillis, long deadlineNanos) throws IOException {
        Socket pooled = borrowTcp(server);
        if (pooled != null) {
            try {
                byte[] resp = doTcpRoundTrip(pooled, request, timeoutMillis, deadlineNanos);
                TCP_REUSES.incrementAndGet();
                returnTcp(server, pooled);
                return resp;
            } catch (IOException stale) {
                closeQuietly(pooled);
            }
        }
        Socket fresh = new Socket();
        boolean reusable = false;
        try {
            fresh.connect(server, timeoutMillis);
            byte[] resp = doTcpRoundTrip(fresh, request, timeoutMillis, deadlineNanos);
            reusable = true;
            returnTcp(server, fresh);
            return resp;
        } finally {
            if (!reusable) {
                closeQuietly(fresh);
            }
        }
    }

    private static byte[] doTcpRoundTrip(Socket socket, byte[] request, int timeoutMillis, long deadlineNanos) throws IOException {
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (remainingMillis <= 0L) {
            throw new SocketTimeoutException("DNS TCP query deadline exceeded");
        }
        socket.setSoTimeout((int) Math.max(1L, Math.min(timeoutMillis, remainingMillis)));
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        out.writeShort(request.length);
        out.write(request);
        out.flush();
        DataInputStream in = new DataInputStream(socket.getInputStream());
        int respLen = in.readUnsignedShort();
        byte[] response = new byte[respLen];
        in.readFully(response);
        return response;
    }

    private static byte[] exchangeUdp(InetSocketAddress server, int expectedId, byte[] request,
                                      int timeoutMillis, int maxPayloadBytes) throws IOException {
        DatagramSocket socket = borrowUdp(server);
        boolean reused = socket != null;
        if (socket == null) {
            socket = new DatagramSocket();
            socket.connect(server);
        }
        boolean healthy = false;
        try {
            socket.setSoTimeout(Math.max(1, timeoutMillis));
            socket.send(new DatagramPacket(request, request.length, server));
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMillis));
            byte[] buf = new byte[maxPayloadBytes + 1];
            while (true) {
                long remainMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                if (remainMs <= 0L) {
                    throw new SocketTimeoutException("DNS UDP query timed out");
                }
                socket.setSoTimeout((int) Math.max(1L, remainMs));
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                if (packet.getLength() > maxPayloadBytes) {
                    throw new IOException("Oversized DNS reply: " + packet.getLength());
                }
                if (packet.getLength() >= 2) {
                    int rxId = ((buf[0] & 0xff) << 8) | (buf[1] & 0xff);
                    if (rxId != expectedId) {
                        // Ignore stale late reply from a prior query on reused socket
                        continue;
                    }
                }
                byte[] reply = Arrays.copyOf(packet.getData(), packet.getLength());
                healthy = true;
                if (reused) {
                    UDP_REUSES.incrementAndGet();
                }
                returnUdp(server, socket);
                return reply;
            }
        } finally {
            if (!healthy) {
                closeQuietly(socket);
            }
        }
    }

    private static Socket borrowTcp(InetSocketAddress server) {
        ConcurrentLinkedDeque<PooledTcp> deque = TCP_POOL.get(server);
        if (deque == null) {
            return null;
        }
        long now = System.nanoTime();
        PooledTcp item;
        while ((item = deque.pollFirst()) != null) {
            if (now - item.returnedAtNanos <= TCP_IDLE_TTL_NANOS
                    && !item.socket.isClosed() && item.socket.isConnected() && !item.socket.isInputShutdown()) {
                return item.socket;
            }
            closeQuietly(item.socket);
        }
        return null;
    }

    private static void returnTcp(InetSocketAddress server, Socket socket) {
        if (socket == null || socket.isClosed() || !socket.isConnected()) {
            closeQuietly(socket);
            return;
        }
        if (TCP_POOL.size() >= MAX_POOLED_SERVERS && !TCP_POOL.containsKey(server)) {
            closeQuietly(socket);
            return;
        }
        ConcurrentLinkedDeque<PooledTcp> deque = TCP_POOL.computeIfAbsent(server, k -> new ConcurrentLinkedDeque<PooledTcp>());
        if (deque.size() >= MAX_IDLE_PER_SERVER) {
            closeQuietly(socket);
            return;
        }
        deque.offerFirst(new PooledTcp(socket, System.nanoTime()));
    }

    private static DatagramSocket borrowUdp(InetSocketAddress server) {
        ConcurrentLinkedDeque<PooledUdp> deque = UDP_POOL.get(server);
        if (deque == null) {
            return null;
        }
        long now = System.nanoTime();
        PooledUdp item;
        while ((item = deque.pollFirst()) != null) {
            if (now - item.returnedAtNanos <= UDP_IDLE_TTL_NANOS && !item.socket.isClosed()) {
                return item.socket;
            }
            closeQuietly(item.socket);
        }
        return null;
    }

    private static void returnUdp(InetSocketAddress server, DatagramSocket socket) {
        if (socket == null || socket.isClosed()) {
            closeQuietly(socket);
            return;
        }
        if (UDP_POOL.size() >= MAX_POOLED_SERVERS && !UDP_POOL.containsKey(server)) {
            closeQuietly(socket);
            return;
        }
        ConcurrentLinkedDeque<PooledUdp> deque = UDP_POOL.computeIfAbsent(server, k -> new ConcurrentLinkedDeque<PooledUdp>());
        if (deque.size() >= MAX_IDLE_PER_SERVER) {
            closeQuietly(socket);
            return;
        }
        deque.offerFirst(new PooledUdp(socket, System.nanoTime()));
    }

    static int parseCacheTtlSeconds(byte[] response) {
        if (response == null || response.length < 12) {
            return 0;
        }
        int flags = ((response[2] & 0xff) << 8) | (response[3] & 0xff);
        boolean truncated = (flags & 0x0200) != 0;
        if (truncated) {
            return 0;
        }
        int rcode = flags & 0x0f;
        if (rcode != 0 && rcode != 3) { // Only cache NOERROR and NXDOMAIN
            return 0;
        }
        int qdCount = ((response[4] & 0xff) << 8) | (response[5] & 0xff);
        int anCount = ((response[6] & 0xff) << 8) | (response[7] & 0xff);
        int nsCount = ((response[8] & 0xff) << 8) | (response[9] & 0xff);
        int pos = 12;
        try {
            for (int i = 0; i < qdCount; i++) {
                pos = skipWireName(response, pos);
                pos += 4;
                if (pos > response.length) {
                    return 0;
                }
            }
            long minAnswerTtl = Long.MAX_VALUE;
            for (int i = 0; i < anCount; i++) {
                pos = skipWireName(response, pos);
                if (pos + 10 > response.length) {
                    return 0;
                }
                long ttl = readUnsignedInt(response, pos + 4);
                int rdLen = ((response[pos + 8] & 0xff) << 8) | (response[pos + 9] & 0xff);
                pos += 10 + rdLen;
                if (pos > response.length) {
                    return 0;
                }
                if (ttl < minAnswerTtl) {
                    minAnswerTtl = ttl;
                }
            }
            if (anCount > 0 && rcode == 0) {
                if (minAnswerTtl <= 0L) {
                    return 0;
                }
                return (int) Math.max(MIN_POSITIVE_TTL_SECONDS, Math.min(MAX_POSITIVE_TTL_SECONDS, minAnswerTtl));
            }
            // Negative / NODATA response: check authority SOA TTL capped at negative TTL
            long minAuthTtl = DnsServer.DEFAULT_NEGATIVE_TTL;
            for (int i = 0; i < nsCount; i++) {
                pos = skipWireName(response, pos);
                if (pos + 10 > response.length) {
                    break;
                }
                long ttl = readUnsignedInt(response, pos + 4);
                int rdLen = ((response[pos + 8] & 0xff) << 8) | (response[pos + 9] & 0xff);
                pos += 10 + rdLen;
                if (pos > response.length) {
                    break;
                }
                if (ttl > 0 && ttl < minAuthTtl) {
                    minAuthTtl = ttl;
                }
            }
            return (int) Math.max(1L, Math.min(DnsServer.DEFAULT_NEGATIVE_TTL, minAuthTtl));
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static int skipWireName(byte[] buf, int pos) {
        int steps = 0;
        while (pos < buf.length && steps++ < 128) {
            int len = buf[pos] & 0xff;
            if (len == 0) {
                return pos + 1;
            }
            if ((len & 0xc0) == 0xc0) {
                return pos + 2;
            }
            if ((len & 0xc0) != 0) {
                throw new IllegalArgumentException("Invalid DNS label");
            }
            pos += 1 + len;
        }
        throw new IllegalArgumentException("Malformed DNS wire name");
    }

    private static long readUnsignedInt(byte[] buf, int offset) {
        return ((long) (buf[offset] & 0xff) << 24)
                | ((long) (buf[offset + 1] & 0xff) << 16)
                | ((long) (buf[offset + 2] & 0xff) << 8)
                | (long) (buf[offset + 3] & 0xff);
    }

    private static String buildCacheKey(List<InetSocketAddress> servers, String domain, int type) {
        String normDomain = domain.trim().toLowerCase(Locale.ROOT);
        while (normDomain.endsWith(".")) {
            normDomain = normDomain.substring(0, normDomain.length() - 1);
        }
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < servers.size(); i++) {
            InetSocketAddress s = servers.get(i);
            if (s == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(s.getHostString()).append(':').append(s.getPort());
        }
        sb.append('|').append(normDomain).append('|').append(type);
        return sb.toString();
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(DatagramSocket socket) {
        if (socket != null) {
            socket.close();
        }
    }
}
