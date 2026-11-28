package org.rx.net.rendezvous;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.core.Tasks;
import org.rx.net.EndpointCandidate;
import org.rx.net.EndpointCandidate.AddressFamily;
import org.rx.net.EndpointCandidate.CandidateType;
import org.rx.net.rss.tcp.AdaptiveUdpPort;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Authenticated, bounded bidirectional UDP reachability verifier and simultaneous NAT hole puncher
 * sharing the exact {@link AdaptiveUdpPort} socket(s) used by {@link org.rx.net.rss.tcp.AdaptiveTcpHandler}.
 * <p>
 * Wire format (fixed 51 bytes, zero serialization, zero reflection):
 * {@code [4B MAGIC "RXPP"][1B ver=1][1B type(1=PROBE,2=ACK)][1B flags][8B punchSessionId][4B seq][8B sentNanos][8B nonce][16B hmac-sha256]}.
 * <p>
 * Security &amp; correctness invariants:
 * <ul>
 *     <li>Every PROBE and ACK carries a 16-byte HMAC-SHA256 bound to {@code (punchKey, punchSessionId, type, flags, seq, sentNanos, nonce)}.</li>
 *     <li>Unauthenticated, expired, or wrong-session datagrams are dropped silently (zero reflection/amplification).</li>
 *     <li>A candidate pair enters {@code UDP_READY} ONLY after bidirectional proof (our authenticated PROBE was ACKed by
 *     the peer AND peer saw our PROBE or we received an authenticated PROBE from that exact peer endpoint).</li>
 *     <li>Multi-NIC local bindings ({@link LocalUdpBinding}) emit probes from the actual bound local socket so every
 *     candidate pair {@code A1/A2 x B1/B2} tests its true source interface rather than the OS default route.</li>
 * </ul>
 */
@Slf4j
public final class PeerUdpPuncher extends Disposable {
    public static final int MAGIC = AdaptiveUdpPort.CONTROL_MAGIC_PUNCH; // 0x52585050 ("RXPP")
    public static final byte VERSION = 1;
    public static final byte TYPE_PROBE = 1;
    public static final byte TYPE_ACK = 2;
    public static final byte FLAG_SAW_PEER_PROBE = 1;
    public static final int PACKET_BYTES = 4 + 1 + 1 + 1 + 8 + 4 + 8 + 8 + 16; // 51 bytes
    private static final int BODY_BYTES = PACKET_BYTES - 16;
    private static final byte[] PUNCH_DOMAIN = "rxlib-udp-punch-v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Associates a real bound {@link AdaptiveUdpPort} socket with its local interface/address metadata. */
    @Getter
    public static final class LocalUdpBinding {
        private final String bindingId;
        private final AdaptiveUdpPort port;
        private final AddressFamily addressFamily;
        private final String localIp;
        private final CandidateType localType;
        private final boolean wildcard;

        public LocalUdpBinding(@NonNull String bindingId, @NonNull AdaptiveUdpPort port,
                               @NonNull AddressFamily addressFamily, String localIp,
                               @NonNull CandidateType localType, boolean wildcard) {
            this.bindingId = bindingId;
            this.port = port;
            this.addressFamily = addressFamily;
            this.localIp = localIp == null ? "" : localIp;
            this.localType = localType;
            this.wildcard = wildcard;
        }
    }

    /** One candidate pair to probe: specific local bound socket -> specific remote UDP target. */
    @Getter
    public static final class UdpCandidatePair {
        private final LocalUdpBinding local;
        private final EndpointCandidate remote;
        private final InetSocketAddress remoteAddress;
        private final int priorityScore;

        public UdpCandidatePair(@NonNull LocalUdpBinding local, @NonNull EndpointCandidate remote,
                                @NonNull InetSocketAddress remoteAddress, int priorityScore) {
            this.local = local;
            this.remote = remote;
            this.remoteAddress = remoteAddress;
            this.priorityScore = priorityScore;
        }

        public String pairKey() {
            return local.getBindingId() + "->" + remoteAddress.getAddress().getHostAddress() + ":" + remoteAddress.getPort();
        }
    }

    /** Bidirectionally verified UDP path ready for injection into {@link org.rx.net.rss.tcp.AdaptiveTcpHandler}. */
    @Getter
    public static final class VerifiedUdpPath {
        private final long punchSessionId;
        private final LocalUdpBinding localBinding;
        private final InetSocketAddress verifiedPeerAddress;
        private final CandidateType remoteCandidateType;
        private final double rttMillis;
        private final double lossRate;
        private final double qualityScore;
        private final long verifiedAtMillis;

        VerifiedUdpPath(long punchSessionId, LocalUdpBinding localBinding, InetSocketAddress verifiedPeerAddress,
                        CandidateType remoteCandidateType, double rttMillis, double lossRate,
                        double qualityScore, long verifiedAtMillis) {
            this.punchSessionId = punchSessionId;
            this.localBinding = localBinding;
            this.verifiedPeerAddress = verifiedPeerAddress;
            this.remoteCandidateType = remoteCandidateType;
            this.rttMillis = rttMillis;
            this.lossRate = lossRate;
            this.qualityScore = qualityScore;
            this.verifiedAtMillis = verifiedAtMillis;
        }
    }

    private static final class PairProgress {
        final UdpCandidatePair pair;
        final ConcurrentHashMap<Long, Long> sentNonceNanos = new ConcurrentHashMap<Long, Long>();
        final AtomicInteger probesSent = new AtomicInteger();
        final AtomicInteger acksReceived = new AtomicInteger();
        final AtomicBoolean receivedPeerProbe = new AtomicBoolean();
        final AtomicBoolean receivedPeerSawUs = new AtomicBoolean();
        final AtomicBoolean verified = new AtomicBoolean();
        volatile long lastTriggeredProbeNanos;
        volatile double minRttMillis = Double.MAX_VALUE;
        volatile double avgRttMillis = Double.MAX_VALUE;

        PairProgress(UdpCandidatePair pair) {
            this.pair = pair;
        }

        void recordRtt(double rttMs) {
            if (rttMs < 0.0D) {
                rttMs = 0.0D;
            }
            if (rttMs < minRttMillis) {
                minRttMillis = rttMs;
            }
            int count = acksReceived.get();
            avgRttMillis = count <= 1 || avgRttMillis == Double.MAX_VALUE
                    ? rttMs
                    : (avgRttMillis * 0.7D + rttMs * 0.3D);
        }

        double lossRate() {
            int sent = Math.max(1, probesSent.get());
            int acked = Math.min(sent, acksReceived.get());
            return 1.0D - ((double) acked / (double) sent);
        }
    }

    private final class PunchSessionState {
        final long punchSessionId;
        final byte[] punchKey;
        final long expiresAtMillis;
        final int maxRetriesPerPair;
        final int maxConcurrentPairs;
        final long maxSessionProbeBytes;
        final long settleWindowMillis;
        final List<UdpCandidatePair> pairs;
        final ConcurrentHashMap<String, PairProgress> progressByPairKey = new ConcurrentHashMap<String, PairProgress>();
        final ConcurrentHashMap<String, PairProgress> progressBySocketAndSender = new ConcurrentHashMap<String, PairProgress>();
        final ConcurrentHashMap<String, Boolean> inboundPeerProbesBySocketAndSender = new ConcurrentHashMap<String, Boolean>();
        final AtomicInteger dynamicCandidateCount = new AtomicInteger();
        final AtomicInteger trackedPairCount = new AtomicInteger();
        final AtomicBoolean pairsReleased = new AtomicBoolean();
        final AtomicInteger nextSeq = new AtomicInteger();
        final AtomicLong sessionProbeBytes = new AtomicLong();
        final CompletableFuture<VerifiedUdpPath> bestPathFuture = new CompletableFuture<VerifiedUdpPath>();
        final AtomicBoolean settled = new AtomicBoolean();
        final List<VerifiedUdpPath> verifiedPaths = Collections.synchronizedList(new ArrayList<VerifiedUdpPath>());
        final Consumer<VerifiedUdpPath> onVerifiedCallback;
        volatile ScheduledFuture<?> pumpTask;
        volatile ScheduledFuture<?> deadlineTask;
        volatile ScheduledFuture<?> settleTask;
        volatile ScheduledFuture<?> lingerTask;

        PunchSessionState(long punchSessionId, byte[] punchKey, long expiresAtMillis,
                          List<UdpCandidatePair> pairs, int maxRetriesPerPair, int maxConcurrentPairs,
                          long maxSessionProbeBytes, long settleWindowMillis,
                          Consumer<VerifiedUdpPath> onVerifiedCallback) {
            this.punchSessionId = punchSessionId;
            this.punchKey = punchKey.clone();
            this.expiresAtMillis = expiresAtMillis;
            this.pairs = pairs;
            this.maxRetriesPerPair = Math.max(1, maxRetriesPerPair);
            this.maxConcurrentPairs = Math.max(1, maxConcurrentPairs);
            this.maxSessionProbeBytes = Math.max(PACKET_BYTES * 2L, maxSessionProbeBytes);
            this.settleWindowMillis = Math.max(0L, settleWindowMillis);
            this.onVerifiedCallback = onVerifiedCallback;
            for (UdpCandidatePair p : pairs) {
                PairProgress prog = new PairProgress(p);
                progressByPairKey.put(p.pairKey(), prog);
                if (progressBySocketAndSender.putIfAbsent(socketSenderKey(p.local.getBindingId(), p.remoteAddress), prog) == null) {
                    trackedPairCount.incrementAndGet();
                }
            }
            totalTrackedPairs.addAndGet(trackedPairCount.get());
        }

        void releaseTrackedPairs() {
            if (pairsReleased.compareAndSet(false, true)) {
                totalTrackedPairs.addAndGet(-trackedPairCount.get());
                progressByPairKey.clear();
                progressBySocketAndSender.clear();
                inboundPeerProbesBySocketAndSender.clear();
            }
        }

        void cancelTimers() {
            ScheduledFuture<?> p = pumpTask;
            if (p != null) {
                pumpTask = null;
                p.cancel(false);
            }
            ScheduledFuture<?> d = deadlineTask;
            if (d != null) {
                deadlineTask = null;
                d.cancel(false);
            }
            ScheduledFuture<?> s = settleTask;
            if (s != null) {
                settleTask = null;
                s.cancel(false);
            }
            ScheduledFuture<?> l = lingerTask;
            if (l != null) {
                lingerTask = null;
                l.cancel(false);
            }
        }

        void completeBest() {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            cancelTimers();
            VerifiedUdpPath best = selectBestVerified();
            bestPathFuture.complete(best);
            if (best != null && onVerifiedCallback != null) {
                try {
                    onVerifiedCallback.accept(best);
                } catch (Throwable ex) {
                    log.debug("Punch verified callback error session={}", punchSessionId, ex);
                }
            }
            // Keep session briefly in passive ACK-responder mode so the peer's in-flight probes
            // still receive authenticated PUNCH_ACK even if we settled slightly earlier.
            long lingerMs = Math.max(50L, Math.min(2000L, expiresAtMillis - System.currentTimeMillis()));
            lingerTask = Tasks.setTimeout(() -> {
                if (activeSessions.remove(punchSessionId, this)) {
                    releaseTrackedPairs();
                }
            }, lingerMs);
        }

        VerifiedUdpPath selectBestVerified() {
            synchronized (verifiedPaths) {
                VerifiedUdpPath best = null;
                for (VerifiedUdpPath p : verifiedPaths) {
                    if (best == null || p.qualityScore > best.qualityScore) {
                        best = p;
                    }
                }
                return best;
            }
        }
    }

    public static final int DEFAULT_MAX_ACTIVE_SESSIONS = 128;
    public static final int DEFAULT_MAX_DYNAMIC_CANDIDATES_PER_SESSION = 32;
    public static final int DEFAULT_MAX_NONCES_PER_PAIR = 64;
    public static final int DEFAULT_MAX_TOTAL_TRACKED_PAIRS = 2048;

    private final ConcurrentHashMap<Long, PunchSessionState> activeSessions = new ConcurrentHashMap<Long, PunchSessionState>();
    private final ConcurrentHashMap<String, LocalUdpBinding> bindingsById = new ConcurrentHashMap<String, LocalUdpBinding>();
    private final AtomicInteger totalTrackedPairs = new AtomicInteger();
    private final AtomicLong totalProbeTxBytes = new AtomicLong();
    private final AtomicLong totalProbeRxBytes = new AtomicLong();
    private final AtomicLong totalRejectedPackets = new AtomicLong();
    private volatile int maxActiveSessions = DEFAULT_MAX_ACTIVE_SESSIONS;
    private volatile int maxDynamicCandidatesPerSession = DEFAULT_MAX_DYNAMIC_CANDIDATES_PER_SESSION;
    private volatile int maxNoncesPerPair = DEFAULT_MAX_NONCES_PER_PAIR;
    private volatile int maxTotalTrackedPairs = DEFAULT_MAX_TOTAL_TRACKED_PAIRS;

    public PeerUdpPuncher() {
    }

    public void setMaxActiveSessions(int maxActiveSessions) {
        this.maxActiveSessions = Math.max(1, maxActiveSessions);
    }

    public void setMaxDynamicCandidatesPerSession(int maxDynamicCandidatesPerSession) {
        this.maxDynamicCandidatesPerSession = Math.max(0, maxDynamicCandidatesPerSession);
    }

    public void setMaxNoncesPerPair(int maxNoncesPerPair) {
        this.maxNoncesPerPair = Math.max(4, maxNoncesPerPair);
    }

    public void setMaxTotalTrackedPairs(int maxTotalTrackedPairs) {
        this.maxTotalTrackedPairs = Math.max(16, maxTotalTrackedPairs);
    }

    public int activeSessionCount() {
        return activeSessions.size();
    }

    public int trackedPairCount() {
        return Math.max(0, totalTrackedPairs.get());
    }

    public long totalProbeTxBytes() {
        return totalProbeTxBytes.get();
    }

    public long totalProbeRxBytes() {
        return totalProbeRxBytes.get();
    }

    public long totalRejectedPackets() {
        return totalRejectedPackets.get();
    }

    /** Registers a local bound {@link AdaptiveUdpPort} so incoming punch packets are matched against its exact binding. */
    public void registerLocalBinding(@NonNull LocalUdpBinding binding) {
        bindingsById.put(binding.getBindingId(), binding);
    }

    public void unregisterLocalBinding(String bindingId) {
        if (bindingId != null) {
            bindingsById.remove(bindingId);
        }
    }

    /**
     * Installs control packet demux on {@code binding.getPort()}, optionally chaining an existing
     * {@link UdpEndpointObserver.Client} handler on the exact same UDP socket.
     */
    public void attachToBinding(@NonNull LocalUdpBinding binding, UdpEndpointObserver.Client observerClient) {
        registerLocalBinding(binding);
        binding.getPort().setControlPacketHandler(UdpEndpointObserver.chain(
                observerClient == null ? null : observerClient::handleControlPacket,
                (sender, buf) -> handleControlPacket(binding, sender, buf)));
    }

    /**
     * Starts a bounded, authenticated UDP reachability / simultaneous NAT punch session across {@code pairs}.
     * Cancels remaining probe tasks once the quality settle window finishes or budget expires.
     */
    public CompletableFuture<VerifiedUdpPath> startPunchSession(long punchSessionId,
                                                                @NonNull byte[] punchKey,
                                                                long expiresAtMillis,
                                                                @NonNull List<UdpCandidatePair> pairs,
                                                                int maxRetriesPerPair,
                                                                int maxConcurrentPairs,
                                                                long probeIntervalMillis,
                                                                long overallBudgetMillis,
                                                                long maxSessionProbeBytes,
                                                                long settleWindowMillis,
                                                                Consumer<VerifiedUdpPath> onVerifiedCallback) {
        checkNotClosed();
        if (pairs.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        long now = System.currentTimeMillis();
        long effectiveDeadline = Math.min(expiresAtMillis, now + Math.max(200L, overallBudgetMillis));
        if (effectiveDeadline <= now) {
            return CompletableFuture.completedFuture(null);
        }
        sweepExpiredSessions(now);
        if (!activeSessions.containsKey(punchSessionId)) {
            if (activeSessions.size() >= maxActiveSessions
                    || totalTrackedPairs.get() + pairs.size() > maxTotalTrackedPairs) {
                log.debug("PeerUdpPuncher global budget exceeded sessions={} pairs={}", activeSessions.size(), totalTrackedPairs.get());
                return CompletableFuture.completedFuture(null);
            }
        }
        for (UdpCandidatePair pair : pairs) {
            bindingsById.putIfAbsent(pair.local.getBindingId(), pair.local);
        }
        PunchSessionState state = new PunchSessionState(
                punchSessionId, punchKey, effectiveDeadline, new ArrayList<UdpCandidatePair>(pairs),
                maxRetriesPerPair, maxConcurrentPairs, maxSessionProbeBytes, settleWindowMillis, onVerifiedCallback);
        PunchSessionState prev = activeSessions.put(punchSessionId, state);
        if (prev != null) {
            prev.cancelTimers();
            prev.releaseTrackedPairs();
            prev.bestPathFuture.complete(null);
        }

        final AtomicInteger roundIndex = new AtomicInteger();
        final long interval = Math.max(40L, probeIntervalMillis);
        Runnable pump = () -> {
            if (isClosed() || state.settled.get() || System.currentTimeMillis() >= state.expiresAtMillis) {
                state.completeBest();
                return;
            }
            int sentThisTick = 0;
            boolean anyRemaining = false;
            int totalPairs = state.pairs.size();
            int startIdx = (roundIndex.getAndIncrement() * state.maxConcurrentPairs) % totalPairs;
            for (int offset = 0; offset < totalPairs && sentThisTick < state.maxConcurrentPairs; offset++) {
                UdpCandidatePair pair = state.pairs.get((startIdx + offset) % totalPairs);
                PairProgress prog = state.progressByPairKey.get(pair.pairKey());
                if (prog == null || prog.verified.get()) {
                    continue;
                }
                if (prog.probesSent.get() >= state.maxRetriesPerPair) {
                    continue;
                }
                anyRemaining = true;
                long nextBytes = state.sessionProbeBytes.addAndGet(PACKET_BYTES);
                if (nextBytes > state.maxSessionProbeBytes) {
                    state.sessionProbeBytes.addAndGet(-PACKET_BYTES);
                    anyRemaining = false;
                    break;
                }
                int seq = state.nextSeq.incrementAndGet();
                long nonce = RANDOM.nextLong();
                long sentNanos = System.nanoTime();
                recordSentNonce(prog, nonce, sentNanos);
                prog.probesSent.incrementAndGet();
                byte flags = prog.receivedPeerProbe.get() ? FLAG_SAW_PEER_PROBE : 0;
                ByteBuf pkt = encodePacket(TYPE_PROBE, flags, punchSessionId, seq, sentNanos, nonce, state.punchKey);
                totalProbeTxBytes.addAndGet(PACKET_BYTES);
                pair.local.getPort().sendRawDatagram(pair.remoteAddress, pkt);
                sentThisTick++;
            }
            if (!anyRemaining && state.verifiedPaths.size() > 0) {
                state.completeBest();
            }
        };

        pump.run();
        if (!state.settled.get()) {
            state.pumpTask = Tasks.schedulePeriod(pump::run, interval);
            long remainingMs = Math.max(50L, effectiveDeadline - System.currentTimeMillis());
            state.deadlineTask = Tasks.setTimeout(() -> state.completeBest(), remainingMs);
        }
        return state.bestPathFuture;
    }

    public void cancelPunchSession(long punchSessionId) {
        PunchSessionState state = activeSessions.remove(punchSessionId);
        if (state != null) {
            state.settled.set(true);
            state.cancelTimers();
            VerifiedUdpPath best = state.selectBestVerified();
            state.releaseTrackedPairs();
            state.bestPathFuture.complete(best);
        }
    }

    private void sweepExpiredSessions(long now) {
        for (Map.Entry<Long, PunchSessionState> e : activeSessions.entrySet()) {
            PunchSessionState s = e.getValue();
            if (now >= s.expiresAtMillis && activeSessions.remove(e.getKey(), s)) {
                s.cancelTimers();
                s.releaseTrackedPairs();
                s.bestPathFuture.complete(s.selectBestVerified());
            }
        }
    }

    private void recordSentNonce(PairProgress prog, long nonce, long sentNanos) {
        int cap = maxNoncesPerPair;
        if (prog.sentNonceNanos.size() >= cap) {
            Long oldestKey = null;
            long oldestVal = Long.MAX_VALUE;
            for (Map.Entry<Long, Long> e : prog.sentNonceNanos.entrySet()) {
                if (e.getValue() < oldestVal) {
                    oldestVal = e.getValue();
                    oldestKey = e.getKey();
                }
            }
            if (oldestKey != null) {
                prog.sentNonceNanos.remove(oldestKey);
            }
        }
        prog.sentNonceNanos.put(nonce, sentNanos);
    }

    /** Handles an inbound datagram on {@code localBinding}'s {@link AdaptiveUdpPort}. */
    public boolean handleControlPacket(@NonNull LocalUdpBinding localBinding, InetSocketAddress sender, ByteBuf in) {
        if (in == null || in.readableBytes() < 4) {
            return false;
        }
        int base = in.readerIndex();
        if (in.getInt(base) != MAGIC) {
            return false;
        }
        int len = in.readableBytes();
        totalProbeRxBytes.addAndGet(len);
        if (sender == null || sender.getAddress() == null || sender.getPort() <= 0 || len != PACKET_BYTES) {
            totalRejectedPackets.incrementAndGet();
            return true;
        }
        byte ver = in.getByte(base + 4);
        byte type = in.getByte(base + 5);
        byte flags = in.getByte(base + 6);
        if (ver != VERSION || (type != TYPE_PROBE && type != TYPE_ACK)) {
            totalRejectedPackets.incrementAndGet();
            return true;
        }
        long punchSessionId = in.getLong(base + 7);
        PunchSessionState state = activeSessions.get(punchSessionId);
        if (state == null || System.currentTimeMillis() >= state.expiresAtMillis) {
            totalRejectedPackets.incrementAndGet();
            return true;
        }
        byte[] body = new byte[BODY_BYTES];
        in.getBytes(base, body);
        byte[] rxTag = new byte[16];
        in.getBytes(base + BODY_BYTES, rxTag);
        byte[] expected = computeTag(state.punchKey, body);
        if (!MessageDigest.isEqual(expected, rxTag)) {
            totalRejectedPackets.incrementAndGet();
            return true;
        }

        int seq = in.getInt(base + 15);
        long sentNanos = in.getLong(base + 19);
        long nonce = in.getLong(base + 27);
        String key = socketSenderKey(localBinding.getBindingId(), sender);

        if (type == TYPE_PROBE) {
            // Peer probed us from `sender` (possibly NAT-mapped dynamic port even if not pre-listed!).
            PairProgress prog = state.progressBySocketAndSender.get(key);
            if (prog == null) {
                if (state.dynamicCandidateCount.get() >= maxDynamicCandidatesPerSession
                        || totalTrackedPairs.get() >= maxTotalTrackedPairs) {
                    totalRejectedPackets.incrementAndGet();
                    return true;
                }
                // Dynamically register peer-reflexive candidate (ICE peer-reflexive behavior) on the SAME authenticated session!
                EndpointCandidate prflx = new EndpointCandidate(
                        EndpointCandidate.familyOf(sender.getAddress()),
                        sender.getAddress().getHostAddress(),
                        sender.getPort(),
                        EndpointCandidate.Protocol.UDP,
                        CandidateType.UDP_MAPPED,
                        "peer-reflexive",
                        System.currentTimeMillis(),
                        Math.max(5000L, state.expiresAtMillis - System.currentTimeMillis()),
                        false);
                UdpCandidatePair dynamicPair = new UdpCandidatePair(localBinding, prflx, sender, 70);
                PairProgress candidateProg = new PairProgress(dynamicPair);
                PairProgress existing = state.progressBySocketAndSender.putIfAbsent(key, candidateProg);
                if (existing != null) {
                    prog = existing;
                } else {
                    if (state.dynamicCandidateCount.incrementAndGet() > maxDynamicCandidatesPerSession
                            || totalTrackedPairs.incrementAndGet() > maxTotalTrackedPairs) {
                        state.progressBySocketAndSender.remove(key, candidateProg);
                        state.dynamicCandidateCount.decrementAndGet();
                        totalTrackedPairs.decrementAndGet();
                        totalRejectedPackets.incrementAndGet();
                        return true;
                    }
                    state.trackedPairCount.incrementAndGet();
                    state.progressByPairKey.putIfAbsent(dynamicPair.pairKey(), candidateProg);
                    prog = candidateProg;
                }
            }
            if (state.inboundPeerProbesBySocketAndSender.size() < state.trackedPairCount.get() + maxDynamicCandidatesPerSession) {
                state.inboundPeerProbesBySocketAndSender.put(key, Boolean.TRUE);
            }
            prog.receivedPeerProbe.set(true);

            // Immediately reply with authenticated PUNCH_ACK echoing peer's seq/sentNanos/nonce and FLAG_SAW_PEER_PROBE.
            if (state.sessionProbeBytes.addAndGet(PACKET_BYTES) <= state.maxSessionProbeBytes) {
                ByteBuf ack = encodePacket(TYPE_ACK, FLAG_SAW_PEER_PROBE, punchSessionId, seq, sentNanos, nonce, state.punchKey);
                totalProbeTxBytes.addAndGet(PACKET_BYTES);
                localBinding.getPort().sendRawDatagram(sender, ack);
            } else {
                state.sessionProbeBytes.addAndGet(-PACKET_BYTES);
            }

            // Triggered check (RFC 8445 §7.2.5.1): if we haven't received a peer ACK yet, immediately
            // send a reciprocal PROBE (rate-limited to 1 per 50ms) so both peers converge in 1 RTT.
            long nowNanos = System.nanoTime();
            if (prog.acksReceived.get() == 0 && !state.settled.get()
                    && (nowNanos - prog.lastTriggeredProbeNanos) >= TimeUnit.MILLISECONDS.toNanos(50L)
                    && state.sessionProbeBytes.addAndGet(PACKET_BYTES) <= state.maxSessionProbeBytes) {
                prog.lastTriggeredProbeNanos = nowNanos;
                int ourSeq = state.nextSeq.incrementAndGet();
                long ourNonce = RANDOM.nextLong();
                recordSentNonce(prog, ourNonce, nowNanos);
                prog.probesSent.incrementAndGet();
                ByteBuf backProbe = encodePacket(TYPE_PROBE, FLAG_SAW_PEER_PROBE, punchSessionId, ourSeq, nowNanos, ourNonce, state.punchKey);
                totalProbeTxBytes.addAndGet(PACKET_BYTES);
                localBinding.getPort().sendRawDatagram(sender, backProbe);
            }
            maybeMarkPairVerified(state, prog);
            return true;
        }

        // TYPE_ACK: verify this echoes one of OUR sent nonces on this exact localBinding & sender!
        PairProgress prog = state.progressBySocketAndSender.get(key);
        if (prog == null) {
            return true;
        }
        Long origSentNanos = prog.sentNonceNanos.remove(nonce);
        if (origSentNanos == null || origSentNanos.longValue() != sentNanos) {
            totalRejectedPackets.incrementAndGet();
            return true;
        }
        double rttMs = Math.max(0.05D, (System.nanoTime() - origSentNanos.longValue()) / 1_000_000.0D);
        prog.acksReceived.incrementAndGet();
        prog.recordRtt(rttMs);
        if ((flags & FLAG_SAW_PEER_PROBE) != 0) {
            prog.receivedPeerSawUs.set(true);
        }
        maybeMarkPairVerified(state, prog);
        return true;
    }

    private void maybeMarkPairVerified(PunchSessionState state, PairProgress prog) {
        // Strict bidirectional requirement:
        // 1) At least one of our authenticated probes was ACKed by this exact sender, AND
        // 2) Either we received an authenticated probe from them OR their ACK confirms they saw our probe.
        if (prog.acksReceived.get() <= 0) {
            return;
        }
        if (!prog.receivedPeerProbe.get() && !prog.receivedPeerSawUs.get()) {
            return;
        }
        if (!prog.verified.compareAndSet(false, true)) {
            return;
        }
        double rtt = prog.avgRttMillis == Double.MAX_VALUE ? 1.0D : prog.avgRttMillis;
        double loss = prog.lossRate();
        double quality = computeUdpQualityScore(prog.pair.priorityScore, rtt, loss);
        VerifiedUdpPath verified = new VerifiedUdpPath(
                state.punchSessionId,
                prog.pair.local,
                prog.pair.remoteAddress,
                prog.pair.remote.getCandidateType(),
                rtt,
                loss,
                quality,
                System.currentTimeMillis());
        state.verifiedPaths.add(verified);

        if (state.settleWindowMillis <= 0L) {
            state.completeBest();
        } else if (state.settleTask == null && !state.settled.get()) {
            state.settleTask = Tasks.setTimeout(() -> state.completeBest(), state.settleWindowMillis);
        }
    }

    /** Quality score preferring LAN > direct public > punched, lower RTT, lower loss. */
    public static double computeUdpQualityScore(int candidatePriorityBonus, double rttMillis, double lossRate) {
        return candidatePriorityBonus - (rttMillis * 2.0D) - (lossRate * 200.0D);
    }

    public static ByteBuf encodePacket(byte type, byte flags, long punchSessionId, int seq,
                                       long sentNanos, long nonce, byte[] punchKey) {
        ByteBuf buf = Unpooled.buffer(PACKET_BYTES, PACKET_BYTES);
        buf.writeInt(MAGIC);
        buf.writeByte(VERSION);
        buf.writeByte(type);
        buf.writeByte(flags);
        buf.writeLong(punchSessionId);
        buf.writeInt(seq);
        buf.writeLong(sentNanos);
        buf.writeLong(nonce);
        byte[] body = new byte[BODY_BYTES];
        buf.getBytes(0, body);
        buf.writeBytes(computeTag(punchKey, body));
        return buf;
    }

    private static byte[] computeTag(byte[] key, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(PUNCH_DOMAIN);
            mac.update(body);
            return Arrays.copyOf(mac.doFinal(), 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static String socketSenderKey(String bindingId, InetSocketAddress sender) {
        return bindingId + "|" + sender.getAddress().getHostAddress() + ":" + sender.getPort();
    }

    @Override
    protected void dispose() {
        for (PunchSessionState state : activeSessions.values()) {
            state.settled.set(true);
            state.cancelTimers();
            state.releaseTrackedPairs();
            state.bestPathFuture.complete(null);
        }
        activeSessions.clear();
        bindingsById.clear();
        totalTrackedPairs.set(0);
    }
}
