package org.rx.net.rendezvous;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.net.EndpointCandidate;
import org.rx.net.EndpointCandidate.CandidateType;
import org.rx.net.EndpointCandidate.Protocol;
import org.rx.net.rss.tcp.AdaptiveUdpPort;
import org.rx.net.transport.core.DatagramTransport;
import org.rx.net.transport.core.DatagramTransportConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

import static org.rx.core.Extends.tryClose;

/**
 * Authenticated, non-amplifying UDP external endpoint observer (STUN-like observation on the exact
 * same UDP socket lifecycle used for NAT punching and {@link AdaptiveUdpPort}).
 * <p>
 * Wire format (compact binary, max 256 bytes, zero serialization):
 * <ul>
 *     <li>{@code OBS_REQ (type=1)}: {@code [4B MAGIC "RXUO"][1B ver=1][1B type=1][8B tsMillis][16B nonce][2B tokenIdLen][tokenId UTF-8][16B hmac]}</li>
 *     <li>{@code OBS_ACK (type=2)}: {@code [4B MAGIC "RXUO"][1B ver=1][1B type=2][16B nonce][1B ipLen(4|16)][ipBytes][2B port][16B hmac]}</li>
 * </ul>
 * Server replies strictly smaller than or equal to valid authenticated requests (amplification factor &lt;= 1.0)
 * and silently drops unauthenticated, replayed/expired, oversized, or rate-limited packets.
 */
@Slf4j
public final class UdpEndpointObserver {
    public static final int MAGIC = AdaptiveUdpPort.CONTROL_MAGIC_OBS; // 0x5258554F ("RXUO")
    public static final byte VERSION = 1;
    public static final byte TYPE_REQ = 1;
    public static final byte TYPE_ACK = 2;
    public static final int NONCE_BYTES = 16;
    public static final int TAG_BYTES = 16;
    public static final int MAX_PACKET_BYTES = 256;
    public static final int MAX_TOKEN_ID_BYTES = 64;
    private static final long TIMESTAMP_SKEW_MILLIS = 30_000L;
    private static final int MAX_RATE_LIMIT_IPS = 4096;
    private static final byte[] REQ_DOMAIN = "rxlib-udp-obs-req-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ACK_DOMAIN = "rxlib-udp-obs-ack-v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private UdpEndpointObserver() {
    }

    @Getter
    public static final class ObservationToken {
        private final String tokenId;
        private final String nodeId;
        private final Object sessionRef;
        private final byte[] key;
        private final long expiresAtMillis;

        public ObservationToken(@NonNull String tokenId, @NonNull String nodeId, Object sessionRef,
                                @NonNull byte[] key, long expiresAtMillis) {
            this.tokenId = tokenId;
            this.nodeId = nodeId;
            this.sessionRef = sessionRef;
            this.key = key.clone();
            this.expiresAtMillis = expiresAtMillis;
        }

        public boolean isExpired(long nowMillis) {
            return nowMillis >= expiresAtMillis;
        }
    }

    /**
     * Public UDP observer endpoint running on {@link DatagramTransport}.
     * Zero PBKDF2 / H2 operations run on the UDP EventLoop; validates pre-issued per-session HMAC keys only.
     */
    public static final class Server extends Disposable {
        private final DatagramTransport transport;
        private final ConcurrentHashMap<String, ObservationToken> tokens = new ConcurrentHashMap<String, ObservationToken>();
        private final BiConsumer<ObservationToken, EndpointCandidate> onObserved;
        private final int maxRequestsPerIpPerSecond;
        private final long candidateTtlMillis;
        private final Object rateLock = new Object();
        private final LinkedHashMap<String, IpRateBucket> ipBuckets = new LinkedHashMap<String, IpRateBucket>(128, 0.75f, true);

        private final AtomicLong requestsReceived = new AtomicLong();
        private final AtomicLong repliesSent = new AtomicLong();
        private final AtomicLong rejectedCount = new AtomicLong();
        private final AtomicLong rxBytes = new AtomicLong();
        private final AtomicLong txBytes = new AtomicLong();

        private static final class IpRateBucket {
            double tokens;
            long lastRefillMillis;

            IpRateBucket(double tokens, long now) {
                this.tokens = tokens;
                this.lastRefillMillis = now;
            }
        }

        public Server(int bindPort, int maxRequestsPerIpPerSecond, long candidateTtlMillis,
                      BiConsumer<ObservationToken, EndpointCandidate> onObserved) {
            this.maxRequestsPerIpPerSecond = Math.max(1, maxRequestsPerIpPerSecond);
            this.candidateTtlMillis = candidateTtlMillis > 0L ? candidateTtlMillis : 60_000L;
            this.onObserved = onObserved;
            DatagramTransportConfig conf = new DatagramTransportConfig(bindPort);
            conf.setMaxReceiveBytes(2048);
            this.transport = DatagramTransport.bind(conf);
            this.transport.setReceiveHandler(this::handleDatagram);
        }

        public int port() {
            return transport.localEndpoint().getPort();
        }

        public InetSocketAddress localEndpoint() {
            return transport.localEndpoint();
        }

        public long requestsReceived() {
            return requestsReceived.get();
        }

        public long repliesSent() {
            return repliesSent.get();
        }

        public long rejectedCount() {
            return rejectedCount.get();
        }

        public long rxBytes() {
            return rxBytes.get();
        }

        public long txBytes() {
            return txBytes.get();
        }

        public void registerToken(@NonNull ObservationToken token) {
            tokens.put(token.getTokenId(), token);
        }

        public void revokeToken(String tokenId) {
            if (tokenId != null) {
                tokens.remove(tokenId);
            }
        }

        public void revokeTokensForNode(String nodeId) {
            if (nodeId == null) {
                return;
            }
            for (Map.Entry<String, ObservationToken> entry : tokens.entrySet()) {
                if (nodeId.equals(entry.getValue().getNodeId())) {
                    tokens.remove(entry.getKey(), entry.getValue());
                }
            }
        }

        private void handleDatagram(InetSocketAddress sender, ByteBuf in) {
            int totalBytes = in.readableBytes();
            rxBytes.addAndGet(totalBytes);
            requestsReceived.incrementAndGet();
            if (sender == null || sender.getAddress() == null || sender.getPort() <= 0) {
                rejectedCount.incrementAndGet();
                return;
            }
            if (totalBytes < 4 + 1 + 1 + 8 + NONCE_BYTES + 2 + 1 + TAG_BYTES || totalBytes > MAX_PACKET_BYTES) {
                rejectedCount.incrementAndGet();
                return;
            }
            long now = System.currentTimeMillis();
            if (!tryAcquireIpRate(sender.getAddress().getHostAddress(), now)) {
                rejectedCount.incrementAndGet();
                return;
            }
            int base = in.readerIndex();
            int magic = in.getInt(base);
            byte ver = in.getByte(base + 4);
            byte type = in.getByte(base + 5);
            if (magic != MAGIC || ver != VERSION || type != TYPE_REQ) {
                rejectedCount.incrementAndGet();
                return;
            }
            long tsMillis = in.getLong(base + 6);
            if (Math.abs(now - tsMillis) > TIMESTAMP_SKEW_MILLIS) {
                rejectedCount.incrementAndGet();
                return;
            }
            int tokenIdLen = in.getUnsignedShort(base + 30);
            int expectedBytes = 32 + tokenIdLen + TAG_BYTES;
            if (tokenIdLen <= 0 || tokenIdLen > MAX_TOKEN_ID_BYTES || totalBytes != expectedBytes) {
                rejectedCount.incrementAndGet();
                return;
            }
            byte[] nonce = new byte[NONCE_BYTES];
            in.getBytes(base + 14, nonce);
            byte[] tokenIdBytes = new byte[tokenIdLen];
            in.getBytes(base + 32, tokenIdBytes);
            String tokenId = new String(tokenIdBytes, StandardCharsets.UTF_8);

            ObservationToken token = tokens.get(tokenId);
            if (token == null || token.isExpired(now)) {
                if (token != null) {
                    tokens.remove(tokenId, token);
                }
                rejectedCount.incrementAndGet();
                return;
            }

            byte[] rxTag = new byte[TAG_BYTES];
            in.getBytes(base + 32 + tokenIdLen, rxTag);
            byte[] body = new byte[32 + tokenIdLen];
            in.getBytes(base, body);
            byte[] expectedTag = computeTag(token.key, REQ_DOMAIN, body);
            if (!MessageDigest.isEqual(expectedTag, rxTag)) {
                rejectedCount.incrementAndGet();
                return;
            }

            byte[] ipBytes = sender.getAddress().getAddress();
            int ackBodyLen = 4 + 1 + 1 + NONCE_BYTES + 1 + ipBytes.length + 2;
            int ackTotal = ackBodyLen + TAG_BYTES;
            // Strict non-amplification guard
            if (ackTotal > totalBytes + 16) {
                rejectedCount.incrementAndGet();
                return;
            }
            ByteBuf out = transport.channel().alloc().ioBuffer(ackTotal, ackTotal);
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            out.writeByte(TYPE_ACK);
            out.writeBytes(nonce);
            out.writeByte(ipBytes.length);
            out.writeBytes(ipBytes);
            out.writeShort(sender.getPort() & 0xFFFF);
            byte[] ackBody = new byte[ackBodyLen];
            out.getBytes(out.readerIndex(), ackBody);
            out.writeBytes(computeTag(token.key, ACK_DOMAIN, ackBody));

            EndpointCandidate observed = new EndpointCandidate(
                    EndpointCandidate.familyOf(sender.getAddress()),
                    sender.getAddress().getHostAddress(),
                    sender.getPort(),
                    Protocol.UDP,
                    CandidateType.UDP_MAPPED,
                    "udp-observer",
                    now,
                    candidateTtlMillis,
                    true);
            if (onObserved != null) {
                try {
                    onObserved.accept(token, observed);
                } catch (Throwable ex) {
                    log.debug("Observer callback error nodeId={}", token.getNodeId(), ex);
                }
            }
            txBytes.addAndGet(ackTotal);
            repliesSent.incrementAndGet();
            transport.send(sender, out);
        }

        private boolean tryAcquireIpRate(String ip, long now) {
            synchronized (rateLock) {
                IpRateBucket bucket = ipBuckets.get(ip);
                if (bucket == null) {
                    while (ipBuckets.size() >= MAX_RATE_LIMIT_IPS) {
                        Iterator<Map.Entry<String, IpRateBucket>> it = ipBuckets.entrySet().iterator();
                        if (!it.hasNext()) {
                            break;
                        }
                        it.next();
                        it.remove();
                    }
                    bucket = new IpRateBucket(maxRequestsPerIpPerSecond, now);
                    ipBuckets.put(ip, bucket);
                } else {
                    long elapsed = Math.max(0L, now - bucket.lastRefillMillis);
                    if (elapsed > 0L) {
                        double refill = (elapsed / 1000.0D) * maxRequestsPerIpPerSecond;
                        bucket.tokens = Math.min(maxRequestsPerIpPerSecond, bucket.tokens + refill);
                        bucket.lastRefillMillis = now;
                    }
                }
                if (bucket.tokens < 1.0D) {
                    return false;
                }
                bucket.tokens -= 1.0D;
                return true;
            }
        }

        @Override
        protected void dispose() {
            tokens.clear();
            tryClose(transport);
        }
    }

    /**
     * Client-side observation request sent directly from the caller's existing {@link AdaptiveUdpPort},
     * ensuring the observed NAT mapping belongs to the exact same bound UDP socket used for punching
     * and {@link org.rx.net.rss.tcp.AdaptiveTcpHandler}.
     */
    public static final class Client {
        private final AdaptiveUdpPort sharedPort;
        private final ConcurrentHashMap<String, PendingObs> pendingByNonce = new ConcurrentHashMap<String, PendingObs>();
        private final AtomicLong probeTxBytes = new AtomicLong();
        private final AtomicLong probeRxBytes = new AtomicLong();

        private static final class PendingObs {
            final byte[] key;
            final byte[] nonce;
            final InetSocketAddress observerEp;
            final CompletableFuture<InetSocketAddress> future = new CompletableFuture<InetSocketAddress>();
            volatile ScheduledFuture<?> retryTask;

            PendingObs(byte[] key, byte[] nonce, InetSocketAddress observerEp) {
                this.key = key;
                this.nonce = nonce;
                this.observerEp = observerEp;
            }

            void cancelRetry() {
                ScheduledFuture<?> f = retryTask;
                if (f != null) {
                    retryTask = null;
                    f.cancel(false);
                }
            }
        }

        public Client(@NonNull AdaptiveUdpPort sharedPort) {
            this.sharedPort = sharedPort;
        }

        public long probeTxBytes() {
            return probeTxBytes.get();
        }

        public long probeRxBytes() {
            return probeRxBytes.get();
        }

        /** Control packet handler hook; chain with {@link PeerUdpPuncher} on the same {@link AdaptiveUdpPort}. */
        public boolean handleControlPacket(InetSocketAddress sender, ByteBuf in) {
            int bytes = in.readableBytes();
            if (bytes < 4) {
                return false;
            }
            int base = in.readerIndex();
            if (in.getInt(base) != MAGIC) {
                return false;
            }
            probeRxBytes.addAndGet(bytes);
            if (bytes < 4 + 1 + 1 + NONCE_BYTES + 1 + 4 + 2 + TAG_BYTES || bytes > MAX_PACKET_BYTES) {
                return true;
            }
            byte ver = in.getByte(base + 4);
            byte type = in.getByte(base + 5);
            if (ver != VERSION || type != TYPE_ACK) {
                return true;
            }
            byte[] nonce = new byte[NONCE_BYTES];
            in.getBytes(base + 6, nonce);
            String nonceKey = hex(nonce);
            PendingObs pending = pendingByNonce.get(nonceKey);
            if (pending == null) {
                return true;
            }
            int ipLen = in.getUnsignedByte(base + 6 + NONCE_BYTES);
            if ((ipLen != 4 && ipLen != 16) || bytes != 6 + NONCE_BYTES + 1 + ipLen + 2 + TAG_BYTES) {
                return true;
            }
            int bodyLen = 6 + NONCE_BYTES + 1 + ipLen + 2;
            byte[] body = new byte[bodyLen];
            in.getBytes(base, body);
            byte[] rxTag = new byte[TAG_BYTES];
            in.getBytes(base + bodyLen, rxTag);
            byte[] expected = computeTag(pending.key, ACK_DOMAIN, body);
            if (!MessageDigest.isEqual(expected, rxTag)) {
                return true;
            }
            byte[] ipBytes = new byte[ipLen];
            in.getBytes(base + 6 + NONCE_BYTES + 1, ipBytes);
            int observedPort = in.getUnsignedShort(base + 6 + NONCE_BYTES + 1 + ipLen);
            if (observedPort <= 0) {
                return true;
            }
            try {
                InetAddress addr = InetAddress.getByAddress(ipBytes);
                if (pendingByNonce.remove(nonceKey, pending)) {
                    pending.cancelRetry();
                    pending.future.complete(new InetSocketAddress(addr, observedPort));
                }
            } catch (UnknownHostException ignored) {
            }
            return true;
        }

        public CompletableFuture<InetSocketAddress> observeAsync(@NonNull InetSocketAddress observerEp,
                                                                 @NonNull String tokenId,
                                                                 @NonNull byte[] obsKey,
                                                                 int maxAttempts,
                                                                 long timeoutMillis) {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            String nonceKey = hex(nonce);
            PendingObs pending = new PendingObs(obsKey.clone(), nonce, observerEp);
            pendingByNonce.put(nonceKey, pending);

            int attempts = Math.max(1, Math.min(5, maxAttempts));
            long budget = Math.max(300L, timeoutMillis);
            long interval = Math.max(100L, budget / attempts);
            final int[] sent = {0};

            Runnable sendOne = () -> {
                if (pending.future.isDone()) {
                    return;
                }
                if (sent[0] >= attempts) {
                    if (pendingByNonce.remove(nonceKey, pending)) {
                        pending.cancelRetry();
                        pending.future.completeExceptionally(new TimeoutException("UDP endpoint observation timeout"));
                    }
                    return;
                }
                sent[0]++;
                ByteBuf req = encodeRequest(tokenId, obsKey, nonce, System.currentTimeMillis());
                probeTxBytes.addAndGet(req.readableBytes());
                sharedPort.sendRawDatagram(observerEp, req);
            };

            sendOne.run();
            pending.retryTask = org.rx.core.Tasks.schedulePeriod(() -> {
                if (pending.future.isDone()) {
                    pending.cancelRetry();
                    return;
                }
                sendOne.run();
            }, interval);
            pending.future.whenComplete((r, err) -> {
                pendingByNonce.remove(nonceKey, pending);
                pending.cancelRetry();
            });
            return pending.future;
        }
    }

    public static ByteBuf encodeRequest(@NonNull String tokenId, @NonNull byte[] key,
                                        @NonNull byte[] nonce, long timestampMillis) {
        byte[] idBytes = tokenId.getBytes(StandardCharsets.UTF_8);
        if (idBytes.length == 0 || idBytes.length > MAX_TOKEN_ID_BYTES) {
            throw new IllegalArgumentException("Invalid observer tokenId length: " + idBytes.length);
        }
        int bodyLen = 4 + 1 + 1 + 8 + NONCE_BYTES + 2 + idBytes.length;
        int total = bodyLen + TAG_BYTES;
        ByteBuf buf = Unpooled.buffer(total, total);
        buf.writeInt(MAGIC);
        buf.writeByte(VERSION);
        buf.writeByte(TYPE_REQ);
        buf.writeLong(timestampMillis);
        buf.writeBytes(nonce, 0, NONCE_BYTES);
        buf.writeShort(idBytes.length);
        buf.writeBytes(idBytes);
        byte[] body = new byte[bodyLen];
        buf.getBytes(0, body);
        buf.writeBytes(computeTag(key, REQ_DOMAIN, body));
        return buf;
    }

    static byte[] computeTag(byte[] key, byte[] domain, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(domain);
            mac.update(data);
            return Arrays.copyOf(mac.doFinal(), TAG_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            chars[i * 2] = digits[v >>> 4];
            chars[i * 2 + 1] = digits[v & 0x0F];
        }
        return new String(chars);
    }

    /** Convenience helper for chaining multiple control packet handlers on one shared {@link AdaptiveUdpPort}. */
    public static BiPredicate<InetSocketAddress, ByteBuf> chain(BiPredicate<InetSocketAddress, ByteBuf> first,
                                                                BiPredicate<InetSocketAddress, ByteBuf> second) {
        return (sender, buf) -> (first != null && first.test(sender, buf))
                || (second != null && second.test(sender, buf));
    }
}
