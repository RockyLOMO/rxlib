package org.rx.net.rendezvous;

import io.netty.channel.local.LocalAddress;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.core.Strings;
import org.rx.core.Sys;
import org.rx.core.Tasks;
import org.rx.net.EndpointCandidate;
import org.rx.net.EndpointCandidate.AddressFamily;
import org.rx.net.EndpointCandidate.CandidateType;
import org.rx.net.EndpointCandidate.Protocol;
import org.rx.net.Sockets;
import org.rx.net.rendezvous.PeerUdpPuncher.LocalUdpBinding;
import org.rx.net.rendezvous.PeerUdpPuncher.UdpCandidatePair;
import org.rx.net.rendezvous.PeerUdpPuncher.VerifiedUdpPath;
import org.rx.net.rendezvous.RendezvousContract.PunchInviteEvent;
import org.rx.net.rendezvous.RendezvousContract.PunchSessionResult;
import org.rx.net.rss.tcp.AdaptiveTcpHandler;
import org.rx.net.socks.RrpClient;
import org.rx.net.transport.core.TransportClient;
import org.rx.net.transport.core.TransportConfig;
import org.rx.net.transport.core.TransportServer;
import org.rx.net.transport.core.TransportSession;

import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.rx.core.Extends.tryClose;

/**
 * Direct-first, on-demand-Relay multi-candidate path selector (§4.3).
 * <p>
 * Path preference order:
 * <ol>
 *     <li>Direct LAN UDP</li>
 *     <li>Direct public IPv4/IPv6 UDP</li>
 *     <li>Rendezvous-coordinated simultaneous UDP NAT punch</li>
 *     <li>Direct TCP (LAN / public / reachable service endpoint)</li>
 *     <li>RRP TCP RAW Relay (opened strictly ON DEMAND only when no direct reliable TCP succeeds within budget)</li>
 * </ol>
 * UDP and reliable TCP are checked independently with bounded concurrency:
 * <ul>
 *     <li>When direct TCP succeeds, remaining TCP checks are cancelled immediately and RRP Relay is NEVER opened
 *     (0 Relay business streams, 0 Relay business bytes).</li>
 *     <li>UDP reachability NEVER replaces or cancels the required reliable TCP carrier; when a verified UDP path
 *     is ready, it is injected via {@link AdaptiveTcpHandler#attachVerifiedUdpPath} onto whichever reliable carrier
 *     won (direct TCP or on-demand Relay).</li>
 *     <li>If no reliable TCP path succeeds (direct failed and Relay unavailable), fails explicitly and NEVER claims
 *     a UDP-only reliable stream.</li>
 * </ul>
 */
@Slf4j
public final class PathSelector {
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum TcpPathType {
        DIRECT,
        RELAY,
        NONE
    }

    public enum UdpPathType {
        DIRECT_OR_PUNCH,
        NONE
    }

    @Getter
    @Setter
    public static final class Config {
        private int maxUdpPairsPerSession = 32;
        private int maxTcpTargetsPerSession = 16;
        private int maxConcurrentTcpChecks = 4;
        private int maxConcurrentUdpChecks = 6;
        private int maxUdpRetriesPerPair = 4;
        private long udpProbeIntervalMillis = 120L;
        private long directTcpBudgetMillis = 1200L;
        private long udpCheckBudgetMillis = 1500L;
        private long udpSettleWindowMillis = 120L;
        private long maxUdpProbeBytesPerSession = 65536L;
        private long recentCacheTtlMillis = 60_000L;
        private int maxRecentCacheEntries = 128;
        private double switchHysteresisScore = 25.0D;
    }

    /** Optional local source IP binding for real multi-NIC direct TCP checks ({@code A1/A2 x B1/B2}). */
    @Getter
    public static final class LocalTcpBinding {
        private final String bindingId;
        private final AddressFamily addressFamily;
        private final String localIp;
        private final CandidateType localType;
        private final boolean wildcard;

        public LocalTcpBinding(@NonNull String bindingId, @NonNull AddressFamily addressFamily,
                               String localIp, @NonNull CandidateType localType, boolean wildcard) {
            this.bindingId = bindingId;
            this.addressFamily = addressFamily;
            this.localIp = localIp == null ? "" : localIp;
            this.localType = localType;
            this.wildcard = wildcard;
        }

        public InetSocketAddress toBindAddress() {
            if (wildcard || Strings.isEmpty(localIp)) {
                return null;
            }
            return new InetSocketAddress(Sockets.parseIpAddress(localIp), 0);
        }
    }

    @Getter
    public static final class TcpCandidateTarget {
        private final LocalTcpBinding localBinding;
        private final EndpointCandidate remoteCandidate;
        private final InetSocketAddress remoteAddress;
        private final int priorityScore;

        TcpCandidateTarget(LocalTcpBinding localBinding, EndpointCandidate remoteCandidate,
                           InetSocketAddress remoteAddress, int priorityScore) {
            this.localBinding = localBinding;
            this.remoteCandidate = remoteCandidate;
            this.remoteAddress = remoteAddress;
            this.priorityScore = priorityScore;
        }

        public String pairKey() {
            String loc = localBinding == null ? "default" : localBinding.getBindingId();
            return loc + "->" + remoteAddress.getAddress().getHostAddress() + ":" + remoteAddress.getPort();
        }
    }

    @Getter
    public static final class CandidatePlan<T> {
        private final List<T> scheduled;
        private final List<String> uncheckedReasons;
        private final int totalConsidered;
        private final int truncatedCount;

        CandidatePlan(List<T> scheduled, List<String> uncheckedReasons, int totalConsidered, int truncatedCount) {
            this.scheduled = Collections.unmodifiableList(scheduled);
            this.uncheckedReasons = Collections.unmodifiableList(uncheckedReasons);
            this.totalConsidered = totalConsidered;
            this.truncatedCount = truncatedCount;
        }
    }

    @Getter
    public static final class RecentPathEntry {
        final String targetNodeId;
        final long localCandidateGen;
        final long remoteCandidateGen;
        volatile String preferredTcpPairKey;
        volatile String preferredUdpPairKey;
        volatile double lastUdpRttMillis;
        volatile long updatedAtMillis;

        RecentPathEntry(String targetNodeId, long localCandidateGen, long remoteCandidateGen, long now) {
            this.targetNodeId = targetNodeId;
            this.localCandidateGen = localCandidateGen;
            this.remoteCandidateGen = remoteCandidateGen;
            this.updatedAtMillis = now;
        }

        boolean isValid(long localGen, long remoteGen, long now, long ttlMillis) {
            return localCandidateGen == localGen
                    && remoteCandidateGen == remoteGen
                    && (now - updatedAtMillis) < ttlMillis;
        }
    }

    @Getter
    public static final class PathSelectionReport {
        private final String targetNodeId;
        private final String targetService;
        private final TcpPathType tcpPathType;
        private final String chosenTcpPairKey;
        private final boolean relayOpened;
        private final long tcpConnectMillis;
        private final int tcpTargetsPlanned;
        private final int tcpTargetsAttempted;
        private final int udpPairsPlanned;
        private final List<String> uncheckedReasons;
        private final boolean usedRecentPathHint;
        private volatile UdpPathType udpPathType = UdpPathType.NONE;
        private volatile String chosenUdpPairKey;
        private volatile double verifiedUdpRttMillis = -1.0D;

        PathSelectionReport(String targetNodeId, String targetService, TcpPathType tcpPathType,
                            String chosenTcpPairKey, boolean relayOpened, long tcpConnectMillis,
                            int tcpTargetsPlanned, int tcpTargetsAttempted, int udpPairsPlanned,
                            List<String> uncheckedReasons, boolean usedRecentPathHint) {
            this.targetNodeId = targetNodeId;
            this.targetService = targetService;
            this.tcpPathType = tcpPathType;
            this.chosenTcpPairKey = chosenTcpPairKey;
            this.relayOpened = relayOpened;
            this.tcpConnectMillis = tcpConnectMillis;
            this.tcpTargetsPlanned = tcpTargetsPlanned;
            this.tcpTargetsAttempted = tcpTargetsAttempted;
            this.udpPairsPlanned = udpPairsPlanned;
            this.uncheckedReasons = Collections.unmodifiableList(uncheckedReasons);
            this.usedRecentPathHint = usedRecentPathHint;
        }

        void recordVerifiedUdp(VerifiedUdpPath path) {
            if (path == null) {
                return;
            }
            this.udpPathType = UdpPathType.DIRECT_OR_PUNCH;
            this.chosenUdpPairKey = path.getLocalBinding().getBindingId() + "->"
                    + path.getVerifiedPeerAddress().getAddress().getHostAddress() + ":"
                    + path.getVerifiedPeerAddress().getPort();
            this.verifiedUdpRttMillis = path.getRttMillis();
        }
    }

    @Getter
    public static final class SelectedConnection implements AutoCloseable {
        private final TransportClient transportClient;
        private final PathSelectionReport report;
        private final CompletableFuture<VerifiedUdpPath> udpFuture;
        private final AtomicLong punchSessionIdRef;
        private final Runnable cleanupAction;
        private volatile VerifiedUdpPath verifiedUdpPath;

        SelectedConnection(TransportClient transportClient, PathSelectionReport report,
                           CompletableFuture<VerifiedUdpPath> udpFuture, AtomicLong punchSessionIdRef,
                           Runnable cleanupAction) {
            this.transportClient = transportClient;
            this.report = report;
            this.udpFuture = udpFuture;
            this.punchSessionIdRef = punchSessionIdRef;
            this.cleanupAction = cleanupAction;
        }

        public long getPunchSessionId() {
            return punchSessionIdRef == null ? 0L : punchSessionIdRef.get();
        }

        public TransportSession session() {
            return transportClient.session();
        }

        @Override
        public void close() {
            if (cleanupAction != null) {
                try {
                    cleanupAction.run();
                } catch (Throwable ignored) {
                }
            }
            tryClose(transportClient);
        }
    }

    @Getter
    @Setter
    public static final class ConnectRequest {
        private String localNodeId;
        private String targetNodeId;
        private String targetService;
        private long localCandidateGen = 1L;
        private long remoteCandidateGen = 1L;
        private List<LocalTcpBinding> localTcpBindings = Collections.emptyList();
        private List<LocalUdpBinding> localUdpBindings = Collections.emptyList();
        private List<EndpointCandidate> remoteCandidates = Collections.emptyList();
        private TransportConfig e2eTransportTemplate;
        private RendezvousContract rendezvousClient;
        private RrpClient rrpRelayClient;
        private PeerUdpPuncher puncher;
        /** Optional direct punch session id/key when bypassing Rendezvous for direct LAN/public UDP tests. */
        private long directPunchSessionId;
        private byte[] directPunchKey;
    }

    private final Config config;
    private final Object cacheLock = new Object();
    private final LinkedHashMap<String, RecentPathEntry> recentPathCache;
    private final AtomicLong totalDirectTcpSelections = new AtomicLong();
    private final AtomicLong totalRelaySelections = new AtomicLong();
    private final AtomicLong totalUdpUpgrades = new AtomicLong();

    public PathSelector() {
        this(new Config());
    }

    public PathSelector(@NonNull Config config) {
        this.config = config;
        this.recentPathCache = new LinkedHashMap<String, RecentPathEntry>(32, 0.75f, true);
    }

    public Config getConfig() {
        return config;
    }

    public long totalDirectTcpSelections() {
        return totalDirectTcpSelections.get();
    }

    public long totalRelaySelections() {
        return totalRelaySelections.get();
    }

    public long totalUdpUpgrades() {
        return totalUdpUpgrades.get();
    }

    public int recentCacheSize() {
        synchronized (cacheLock) {
            return recentPathCache.size();
        }
    }

    public void invalidateTarget(String targetNodeId) {
        if (targetNodeId == null) {
            return;
        }
        synchronized (cacheLock) {
            recentPathCache.remove(targetNodeId.trim());
        }
    }

    public void invalidateAll() {
        synchronized (cacheLock) {
            recentPathCache.clear();
        }
    }

    /** Quality hysteresis check: only switch an already-healthy UDP path when candidate improves score beyond threshold. */
    public boolean shouldSwitchUdpPath(double currentQualityScore, double candidateQualityScore) {
        return candidateQualityScore - currentQualityScore >= config.getSwitchHysteresisScore();
    }

    public RecentPathEntry getValidRecentHint(String targetNodeId, long localGen, long remoteGen, long nowMillis) {
        if (Strings.isEmpty(targetNodeId)) {
            return null;
        }
        String key = targetNodeId.trim();
        synchronized (cacheLock) {
            RecentPathEntry entry = recentPathCache.get(key);
            if (entry == null) {
                return null;
            }
            if (!entry.isValid(localGen, remoteGen, nowMillis, config.getRecentCacheTtlMillis())) {
                recentPathCache.remove(key);
                return null;
            }
            return entry;
        }
    }

    private void recordRecentTcp(String targetNodeId, long localGen, long remoteGen, String pairKey, long now) {
        if (Strings.isEmpty(targetNodeId) || Strings.isEmpty(pairKey)) {
            return;
        }
        String key = targetNodeId.trim();
        synchronized (cacheLock) {
            RecentPathEntry entry = getOrCreateCacheEntryLocked(key, localGen, remoteGen, now);
            entry.preferredTcpPairKey = pairKey;
            entry.updatedAtMillis = now;
        }
    }

    private void recordRecentUdp(String targetNodeId, long localGen, long remoteGen, String pairKey, double rttMs, long now) {
        if (Strings.isEmpty(targetNodeId) || Strings.isEmpty(pairKey)) {
            return;
        }
        String key = targetNodeId.trim();
        synchronized (cacheLock) {
            RecentPathEntry entry = getOrCreateCacheEntryLocked(key, localGen, remoteGen, now);
            entry.preferredUdpPairKey = pairKey;
            entry.lastUdpRttMillis = rttMs;
            entry.updatedAtMillis = now;
        }
    }

    private RecentPathEntry getOrCreateCacheEntryLocked(String key, long localGen, long remoteGen, long now) {
        RecentPathEntry existing = recentPathCache.get(key);
        if (existing != null && existing.localCandidateGen == localGen && existing.remoteCandidateGen == remoteGen) {
            return existing;
        }
        int max = Math.max(1, config.getMaxRecentCacheEntries());
        while (recentPathCache.size() >= max) {
            Iterator<Map.Entry<String, RecentPathEntry>> it = recentPathCache.entrySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            it.next();
            it.remove();
        }
        RecentPathEntry created = new RecentPathEntry(key, localGen, remoteGen, now);
        recentPathCache.put(key, created);
        return created;
    }

    // region Bounded Multi-Candidate Pairing & Filtering (A1/A2 x B1/B2)

    public CandidatePlan<UdpCandidatePair> planUdpPairs(List<LocalUdpBinding> localBindings,
                                                        List<EndpointCandidate> remoteCandidates,
                                                        RecentPathEntry cachedHint,
                                                        long nowMillis) {
        List<String> unchecked = new ArrayList<String>();
        if (localBindings == null || localBindings.isEmpty() || remoteCandidates == null || remoteCandidates.isEmpty()) {
            return new CandidatePlan<UdpCandidatePair>(Collections.<UdpCandidatePair>emptyList(), unchecked, 0, 0);
        }
        List<EndpointCandidate> validRemotes = filterRemotes(remoteCandidates, Protocol.UDP, nowMillis, unchecked);
        List<UdpCandidatePair> allPairs = new ArrayList<UdpCandidatePair>();
        Set<String> seenPairs = new HashSet<String>();
        for (LocalUdpBinding local : localBindings) {
            if (local == null || local.getPort() == null) {
                continue;
            }
            for (EndpointCandidate remote : validRemotes) {
                if (local.getAddressFamily() != remote.getAddressFamily()) {
                    unchecked.add("udp:family_mismatch:" + local.getBindingId() + "->" + remote.getIp() + ":" + remote.getPort());
                    continue;
                }
                InetSocketAddress remoteAddr = safeSocketAddress(remote);
                if (remoteAddr == null) {
                    unchecked.add("udp:invalid_ip:" + remote.getIp());
                    continue;
                }
                int prio = scoreUdpPair(local, remote, cachedHint);
                UdpCandidatePair pair = new UdpCandidatePair(local, remote, remoteAddr, prio);
                if (seenPairs.add(pair.pairKey())) {
                    allPairs.add(pair);
                } else {
                    unchecked.add("udp:duplicate_pair:" + pair.pairKey());
                }
            }
        }
        int totalConsidered = allPairs.size();
        int cap = Math.max(1, config.getMaxUdpPairsPerSession());
        int truncated = 0;
        List<UdpCandidatePair> scheduled = selectFairBounded(allPairs, cap,
                p -> p.getLocal().getBindingId(),
                UdpCandidatePair::getPriorityScore);
        if (totalConsidered > scheduled.size()) {
            truncated = totalConsidered - scheduled.size();
            unchecked.add("udp:pair_budget_truncated:" + truncated);
        }
        return new CandidatePlan<UdpCandidatePair>(scheduled, unchecked, totalConsidered, truncated);
    }

    public CandidatePlan<TcpCandidateTarget> planDirectTcpTargets(List<LocalTcpBinding> localBindings,
                                                                  List<EndpointCandidate> remoteCandidates,
                                                                  RecentPathEntry cachedHint,
                                                                  long nowMillis) {
        List<String> unchecked = new ArrayList<String>();
        if (remoteCandidates == null || remoteCandidates.isEmpty()) {
            return new CandidatePlan<TcpCandidateTarget>(Collections.<TcpCandidateTarget>emptyList(), unchecked, 0, 0);
        }
        List<EndpointCandidate> validRemotes = filterRemotes(remoteCandidates, Protocol.TCP, nowMillis, unchecked);
        List<LocalTcpBinding> effectiveLocals = (localBindings == null || localBindings.isEmpty())
                ? Collections.<LocalTcpBinding>singletonList(null)
                : localBindings;

        List<TcpCandidateTarget> allTargets = new ArrayList<TcpCandidateTarget>();
        Set<String> seenPairs = new HashSet<String>();
        for (LocalTcpBinding local : effectiveLocals) {
            for (EndpointCandidate remote : validRemotes) {
                if (local != null && local.getAddressFamily() != remote.getAddressFamily()) {
                    unchecked.add("tcp:family_mismatch:" + local.getBindingId() + "->" + remote.getIp() + ":" + remote.getPort());
                    continue;
                }
                InetSocketAddress remoteAddr = safeSocketAddress(remote);
                if (remoteAddr == null) {
                    unchecked.add("tcp:invalid_ip:" + remote.getIp());
                    continue;
                }
                int prio = scoreTcpPair(local, remote, cachedHint);
                TcpCandidateTarget target = new TcpCandidateTarget(local, remote, remoteAddr, prio);
                if (seenPairs.add(target.pairKey())) {
                    allTargets.add(target);
                } else {
                    unchecked.add("tcp:duplicate_pair:" + target.pairKey());
                }
            }
        }
        int totalConsidered = allTargets.size();
        int cap = Math.max(1, config.getMaxTcpTargetsPerSession());
        int truncated = 0;
        List<TcpCandidateTarget> scheduled = selectFairBounded(allTargets, cap,
                t -> t.getLocalBinding() == null ? "default" : t.getLocalBinding().getBindingId(),
                TcpCandidateTarget::getPriorityScore);
        if (totalConsidered > scheduled.size()) {
            truncated = totalConsidered - scheduled.size();
            unchecked.add("tcp:target_budget_truncated:" + truncated);
        }
        return new CandidatePlan<TcpCandidateTarget>(scheduled, unchecked, totalConsidered, truncated);
    }

    /**
     * Interleaves candidates round-robin across local NIC bindings within each descending score tier
     * so budget truncation never starves non-default interfaces.
     */
    private static <T> List<T> selectFairBounded(List<T> items, int cap,
                                                 java.util.function.Function<T, String> nicKeyFn,
                                                 java.util.function.ToIntFunction<T> scoreFn) {
        if (items.isEmpty()) {
            return Collections.emptyList();
        }
        Collections.sort(items, new Comparator<T>() {
            @Override
            public int compare(T a, T b) {
                return Integer.compare(scoreFn.applyAsInt(b), scoreFn.applyAsInt(a));
            }
        });
        List<T> out = new ArrayList<T>(Math.min(cap, items.size()));
        int i = 0;
        while (i < items.size() && out.size() < cap) {
            int tierScore = scoreFn.applyAsInt(items.get(i));
            LinkedHashMap<String, List<T>> byNic = new LinkedHashMap<String, List<T>>();
            while (i < items.size() && scoreFn.applyAsInt(items.get(i)) == tierScore) {
                T item = items.get(i++);
                String nic = nicKeyFn.apply(item);
                List<T> bucket = byNic.get(nic);
                if (bucket == null) {
                    bucket = new ArrayList<T>();
                    byNic.put(nic, bucket);
                }
                bucket.add(item);
            }
            int round = 0;
            boolean addedAny;
            do {
                addedAny = false;
                for (List<T> bucket : byNic.values()) {
                    if (round < bucket.size() && out.size() < cap) {
                        out.add(bucket.get(round));
                        addedAny = true;
                    }
                }
                round++;
            } while (addedAny && out.size() < cap);
        }
        return out;
    }

    private List<EndpointCandidate> filterRemotes(List<EndpointCandidate> raw, Protocol wantProtocol,
                                                  long nowMillis, List<String> unchecked) {
        List<EndpointCandidate> out = new ArrayList<EndpointCandidate>();
        Set<String> seen = new HashSet<String>();
        String prefix = wantProtocol == Protocol.UDP ? "udp:" : "tcp:";
        for (EndpointCandidate c : raw) {
            if (c == null) {
                continue;
            }
            if (c.isExpired(nowMillis)) {
                unchecked.add(prefix + "expired:" + c.getIp() + ":" + c.getPort());
                continue;
            }
            if (c.getCandidateType() == CandidateType.HTTPS_PUBLIC || c.getCandidateType() == CandidateType.TCP_OBSERVED) {
                unchecked.add(prefix + "unsupported_type:" + c.getCandidateType() + ":" + c.getIp());
                continue;
            }
            if (wantProtocol == Protocol.TCP && c.getCandidateType() == CandidateType.UDP_MAPPED) {
                unchecked.add(prefix + "unsupported_type:UDP_MAPPED:" + c.getIp() + ":" + c.getPort());
                continue;
            }
            if (c.getPort() <= 0 || c.getPort() > 65535) {
                unchecked.add(prefix + "invalid_port:" + c.getIp() + ":" + c.getPort());
                continue;
            }
            if (c.getProtocol() != Protocol.ANY && c.getProtocol() != wantProtocol) {
                unchecked.add(prefix + "protocol_mismatch:" + c.getProtocol() + ":" + c.getIp() + ":" + c.getPort());
                continue;
            }
            if (!seen.add(c.dedupeKey())) {
                unchecked.add(prefix + "duplicate:" + c.getIp() + ":" + c.getPort());
                continue;
            }
            out.add(c);
        }
        return out;
    }

    private static int scoreUdpPair(LocalUdpBinding local, EndpointCandidate remote, RecentPathEntry hint) {
        int score;
        switch (remote.getCandidateType()) {
            case LAN:
                score = sameIpv4Slash24(local.getLocalIp(), remote.getIp()) ? 300 : 240;
                break;
            case HOST:
            case GLOBAL_V6:
                score = 180;
                break;
            case UDP_MAPPED:
                // Prefer the NAT-observed wildcard socket for external UDP_MAPPED peers so outbound probes reuse the observed mapping.
                score = local.isWildcard() ? 155 : 120;
                break;
            default:
                score = 50;
                break;
        }
        if (hint != null && hint.preferredUdpPairKey != null) {
            String key = local.getBindingId() + "->" + remote.getIp() + ":" + remote.getPort();
            if (hint.preferredUdpPairKey.equals(key)) {
                score += 40;
            }
        }
        return score;
    }

    private static int scoreTcpPair(LocalTcpBinding local, EndpointCandidate remote, RecentPathEntry hint) {
        int score;
        String localIp = local == null ? "" : local.getLocalIp();
        switch (remote.getCandidateType()) {
            case LAN:
                score = sameIpv4Slash24(localIp, remote.getIp()) ? 260 : 210;
                break;
            case HOST:
            case GLOBAL_V6:
                score = 170;
                break;
            default:
                score = 80;
                break;
        }
        if (hint != null && hint.preferredTcpPairKey != null) {
            String loc = local == null ? "default" : local.getBindingId();
            String key = loc + "->" + remote.getIp() + ":" + remote.getPort();
            if (hint.preferredTcpPairKey.equals(key)) {
                score += 40;
            }
        }
        return score;
    }

    static boolean sameIpv4Slash24(String ipA, String ipB) {
        if (Strings.isEmpty(ipA) || Strings.isEmpty(ipB)) {
            return false;
        }
        try {
            InetAddress a = Sockets.parseIpAddress(ipA);
            InetAddress b = Sockets.parseIpAddress(ipB);
            if (!(a instanceof Inet4Address) || !(b instanceof Inet4Address)) {
                return false;
            }
            byte[] ba = a.getAddress();
            byte[] bb = b.getAddress();
            return ba[0] == bb[0] && ba[1] == bb[1] && ba[2] == bb[2];
        } catch (RuntimeException ignore) {
            return false;
        }
    }

    private static InetSocketAddress safeSocketAddress(EndpointCandidate c) {
        try {
            return c.toSocketAddress();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // endregion

    // region Direct-First Concurrent TCP & UDP Execution

    private static final class PathAttempt {
        final ConnectRequest req;
        final CandidatePlan<TcpCandidateTarget> tcpPlan;
        final CandidatePlan<UdpCandidatePair> udpPlan;
        final boolean usedHint;
        final long startNanos;
        final String attemptId;
        final String correlationId;
        final String peerIdTag;
        final CompletableFuture<VerifiedUdpPath> udpFuture = new CompletableFuture<VerifiedUdpPath>();
        final CompletableFuture<SelectedConnection> resultFuture = new CompletableFuture<SelectedConnection>();
        final AtomicLong activePunchSessionId = new AtomicLong();
        final AtomicInteger nextTcpIndex = new AtomicInteger();
        final AtomicInteger tcpAttempted = new AtomicInteger();
        final AtomicInteger tcpFinished = new AtomicInteger();

        final Object lock = new Object();
        final List<String> uncheckedReasons;
        final List<TransportClient> inFlightClients = new ArrayList<TransportClient>();
        ScheduledFuture<?> budgetTimer;
        CompletableFuture<LocalAddress> pendingRelayCarrierFuture;
        LocalAddress openedRelayCarrier;
        boolean carrierClaimed;
        boolean tcpDecided;
        boolean relayOpened;
        boolean cancelled;

        PathAttempt(ConnectRequest req, CandidatePlan<TcpCandidateTarget> tcpPlan, CandidatePlan<UdpCandidatePair> udpPlan,
                    boolean usedHint, long startNanos) {
            this.req = req;
            this.tcpPlan = tcpPlan;
            this.udpPlan = udpPlan;
            this.usedHint = usedHint;
            this.startNanos = startNanos;
            this.attemptId = generateAttemptId();
            long directPunchId = req.getDirectPunchSessionId();
            this.activePunchSessionId.set(directPunchId);
            this.correlationId = directPunchId != 0L ? ("p" + directPunchId) : this.attemptId;
            this.peerIdTag = buildPeerIdTag(req.getLocalNodeId(), this.correlationId);
            this.uncheckedReasons = new ArrayList<String>(tcpPlan.getUncheckedReasons().size() + udpPlan.getUncheckedReasons().size() + 4);
            this.uncheckedReasons.addAll(tcpPlan.getUncheckedReasons());
            this.uncheckedReasons.addAll(udpPlan.getUncheckedReasons());
        }

        void addUncheckedReason(String reason) {
            if (Strings.isEmpty(reason)) {
                return;
            }
            synchronized (lock) {
                uncheckedReasons.add(reason);
            }
        }

        List<String> snapshotUncheckedReasons() {
            synchronized (lock) {
                return new ArrayList<String>(uncheckedReasons);
            }
        }

        boolean registerInFlight(TransportClient cli) {
            synchronized (lock) {
                if (cancelled || tcpDecided) {
                    return false;
                }
                inFlightClients.add(cli);
                return true;
            }
        }

        void unregisterInFlight(TransportClient cli) {
            synchronized (lock) {
                inFlightClients.remove(cli);
            }
        }

        boolean tryWinDirect(TransportClient winner) {
            ScheduledFuture<?> timer;
            List<TransportClient> losers;
            synchronized (lock) {
                if (cancelled || tcpDecided) {
                    inFlightClients.remove(winner);
                    return false;
                }
                tcpDecided = true;
                inFlightClients.remove(winner);
                losers = new ArrayList<TransportClient>(inFlightClients);
                inFlightClients.clear();
                timer = budgetTimer;
                budgetTimer = null;
            }
            if (timer != null) {
                timer.cancel(false);
            }
            for (TransportClient c : losers) {
                tryClose(c);
            }
            return true;
        }

        boolean beginRelayFallback() {
            ScheduledFuture<?> timer;
            List<TransportClient> losers;
            synchronized (lock) {
                if (cancelled || tcpDecided) {
                    return false;
                }
                tcpDecided = true;
                losers = new ArrayList<TransportClient>(inFlightClients);
                inFlightClients.clear();
                timer = budgetTimer;
                budgetTimer = null;
            }
            if (timer != null) {
                timer.cancel(false);
            }
            for (TransportClient c : losers) {
                tryClose(c);
            }
            return true;
        }

        boolean isStopped() {
            synchronized (lock) {
                return cancelled || tcpDecided;
            }
        }

        boolean isCancelled() {
            synchronized (lock) {
                return cancelled;
            }
        }

        void cancelUdpDimension() {
            long pid = activePunchSessionId.getAndSet(0L);
            if (pid != 0L && req.getPuncher() != null) {
                req.getPuncher().cancelPunchSession(pid);
            }
            if (!udpFuture.isDone()) {
                udpFuture.complete(null);
            }
        }

        void cancelAll() {
            ScheduledFuture<?> timer;
            List<TransportClient> clients;
            CompletableFuture<LocalAddress> carrierF;
            LocalAddress unconsumedCarrier = null;
            synchronized (lock) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                tcpDecided = true;
                timer = budgetTimer;
                budgetTimer = null;
                clients = new ArrayList<TransportClient>(inFlightClients);
                inFlightClients.clear();
                carrierF = pendingRelayCarrierFuture;
                pendingRelayCarrierFuture = null;
                if (openedRelayCarrier != null && !carrierClaimed) {
                    unconsumedCarrier = openedRelayCarrier;
                    openedRelayCarrier = null;
                }
            }
            if (timer != null) {
                timer.cancel(false);
            }
            for (TransportClient c : clients) {
                tryClose(c);
            }
            if (carrierF != null && !carrierF.isDone()) {
                carrierF.cancel(false);
            }
            if (unconsumedCarrier != null && req.getRrpRelayClient() != null) {
                req.getRrpRelayClient().cancelRawCarrier(unconsumedCarrier);
            }
            cancelUdpDimension();
        }
    }

    public CompletableFuture<SelectedConnection> connectPeerAsync(@NonNull ConnectRequest req) {
        if (Strings.isEmpty(req.getLocalNodeId()) || Strings.isEmpty(req.getTargetNodeId())
                || Strings.isEmpty(req.getTargetService()) || req.getE2eTransportTemplate() == null) {
            CompletableFuture<SelectedConnection> bad = new CompletableFuture<SelectedConnection>();
            bad.completeExceptionally(new IllegalArgumentException("localNodeId, targetNodeId, targetService, and e2eTransportTemplate are required"));
            return bad;
        }
        long startNanos = System.nanoTime();
        long now = System.currentTimeMillis();
        RecentPathEntry hint = getValidRecentHint(req.getTargetNodeId(), req.getLocalCandidateGen(), req.getRemoteCandidateGen(), now);
        boolean usedHint = hint != null;

        CandidatePlan<TcpCandidateTarget> tcpPlan = planDirectTcpTargets(req.getLocalTcpBindings(), req.getRemoteCandidates(), hint, now);
        CandidatePlan<UdpCandidatePair> udpPlan = planUdpPairs(req.getLocalUdpBindings(), req.getRemoteCandidates(), hint, now);

        PathAttempt attempt = new PathAttempt(req, tcpPlan, udpPlan, usedHint, startNanos);
        attempt.resultFuture.whenComplete((conn, ex) -> {
            if (ex != null) {
                attempt.cancelAll();
            }
        });

        // Start UDP dimension asynchronously (independent of TCP; NEVER blocks direct TCP or cancels reliable carrier).
        if (req.getPuncher() != null && !udpPlan.getScheduled().isEmpty()) {
            Tasks.run(() -> startUdpDimensionAsync(attempt));
        } else {
            attempt.udpFuture.complete(null);
        }

        // Start reliable TCP dimension (direct-first with bounded concurrency; Relay ONLY on demand).
        tryDirectTcpThenOptionalRelay(attempt);
        return attempt.resultFuture;
    }

    private void startUdpDimensionAsync(PathAttempt attempt) {
        ConnectRequest req = attempt.req;
        try {
            if (attempt.isCancelled()) {
                attempt.udpFuture.complete(null);
                return;
            }
            long punchSessionId = req.getDirectPunchSessionId();
            byte[] punchKey = req.getDirectPunchKey();
            long expiresAt = System.currentTimeMillis() + config.getUdpCheckBudgetMillis();
            List<UdpCandidatePair> effectivePairs = attempt.udpPlan.getScheduled();

            if (req.getRendezvousClient() != null && (punchSessionId == 0L || punchKey == null)) {
                PunchSessionResult coord = req.getRendezvousClient().requestPunchSessionForAttempt(
                        req.getTargetNodeId(), req.getTargetService(), attempt.attemptId);
                if (attempt.isCancelled()) {
                    attempt.udpFuture.complete(null);
                    return;
                }
                punchSessionId = coord.getPunchSessionId();
                punchKey = coord.getPunchKey();
                expiresAt = coord.getExpiresAtMillis();
                if (coord.getTargetCandidates() != null && !coord.getTargetCandidates().isEmpty()) {
                    RecentPathEntry hint = getValidRecentHint(req.getTargetNodeId(),
                            req.getLocalCandidateGen(), coord.getTargetCandidateGen(), System.currentTimeMillis());
                    CandidatePlan<UdpCandidatePair> refreshed = planUdpPairs(
                            req.getLocalUdpBindings(), coord.getTargetCandidates(), hint, System.currentTimeMillis());
                    if (!refreshed.getScheduled().isEmpty()) {
                        effectivePairs = refreshed.getScheduled();
                    }
                }
            }
            if (attempt.isCancelled() || punchSessionId == 0L || punchKey == null || effectivePairs.isEmpty()) {
                attempt.udpFuture.complete(null);
                return;
            }
            attempt.activePunchSessionId.set(punchSessionId);
            if (attempt.isCancelled()) {
                attempt.cancelUdpDimension();
                return;
            }
            final long finalPunchId = punchSessionId;
            req.getPuncher().startPunchSession(
                    finalPunchId,
                    punchKey,
                    expiresAt,
                    effectivePairs,
                    config.getMaxUdpRetriesPerPair(),
                    config.getMaxConcurrentUdpChecks(),
                    config.getUdpProbeIntervalMillis(),
                    config.getUdpCheckBudgetMillis(),
                    config.getMaxUdpProbeBytesPerSession(),
                    config.getUdpSettleWindowMillis(),
                    null
            ).whenComplete((verified, err) -> {
                if (err != null || attempt.isCancelled()) {
                    attempt.udpFuture.complete(null);
                } else {
                    if (verified != null) {
                        String pairKey = verified.getLocalBinding().getBindingId() + "->"
                                + verified.getVerifiedPeerAddress().getAddress().getHostAddress() + ":"
                                + verified.getVerifiedPeerAddress().getPort();
                        recordRecentUdp(req.getTargetNodeId(), req.getLocalCandidateGen(), req.getRemoteCandidateGen(),
                                pairKey, verified.getRttMillis(), System.currentTimeMillis());
                    }
                    attempt.udpFuture.complete(verified);
                }
            });
            if (attempt.isCancelled()) {
                attempt.cancelUdpDimension();
            }
        } catch (Throwable ex) {
            log.debug("PathSelector UDP dimension unavailable target={}: {}", req.getTargetNodeId(), ex.getMessage());
            attempt.udpFuture.complete(null);
        }
    }

    private void tryDirectTcpThenOptionalRelay(PathAttempt attempt) {
        List<TcpCandidateTarget> targets = attempt.tcpPlan.getScheduled();
        if (targets.isEmpty()) {
            if (attempt.beginRelayFallback()) {
                openOnDemandRelay(attempt);
            }
            return;
        }

        Runnable fallbackToRelay = () -> {
            if (!attempt.beginRelayFallback()) {
                return;
            }
            openOnDemandRelay(attempt);
        };

        ScheduledFuture<?> timer = Tasks.setTimeout(() -> {
            if (!attempt.isStopped()) {
                attempt.addUncheckedReason("tcp:direct_budget_expired");
                fallbackToRelay.run();
            }
        }, Math.max(150L, config.getDirectTcpBudgetMillis()));
        synchronized (attempt.lock) {
            if (attempt.cancelled || attempt.tcpDecided) {
                timer.cancel(false);
                return;
            }
            attempt.budgetTimer = timer;
        }

        final Runnable[] launchNext = new Runnable[1];
        launchNext[0] = () -> {
            if (attempt.isStopped()) {
                return;
            }
            int idx = attempt.nextTcpIndex.getAndIncrement();
            if (idx >= targets.size()) {
                return;
            }
            TcpCandidateTarget target = targets.get(idx);
            attempt.tcpAttempted.incrementAndGet();
            ConnectRequest req = attempt.req;
            TransportConfig tc = Sys.deepClone(req.getE2eTransportTemplate());
            tc.setPeerId(attempt.peerIdTag);
            tc.getTcpClientConfig().setEnableReconnect(false);
            tc.getTcpClientConfig().setConnectTimeoutMillis((int) Math.max(300L, config.getDirectTcpBudgetMillis()));
            if (target.getLocalBinding() != null) {
                tc.getTcpClientConfig().setLocalAddress(target.getLocalBinding().toBindAddress());
            }
            boolean usePuncherUdp = req.getPuncher() != null && !attempt.udpPlan.getScheduled().isEmpty();
            if (usePuncherUdp) {
                tc.setEnableAdaptiveUdp(req.getE2eTransportTemplate().isEnableAdaptiveUdp());
            }

            TransportClient cli = new TransportClient(tc);
            if (!attempt.registerInFlight(cli)) {
                tryClose(cli);
                return;
            }
            Tasks.run(() -> {
                try {
                    if (attempt.isStopped()) {
                        attempt.unregisterInFlight(cli);
                        tryClose(cli);
                        return;
                    }
                    cli.connect(target.getRemoteAddress());
                    if (attempt.tryWinDirect(cli)) {
                        int remaining = targets.size() - attempt.tcpAttempted.get();
                        if (remaining > 0) {
                            attempt.addUncheckedReason("tcp:cancelled_after_direct_win:" + remaining);
                        }
                        long elapsedMs = Math.max(1L, (System.nanoTime() - attempt.startNanos) / 1_000_000L);
                        totalDirectTcpSelections.incrementAndGet();
                        recordRecentTcp(req.getTargetNodeId(), req.getLocalCandidateGen(), req.getRemoteCandidateGen(),
                                target.pairKey(), System.currentTimeMillis());
                        PathSelectionReport report = new PathSelectionReport(
                                req.getTargetNodeId(), req.getTargetService(), TcpPathType.DIRECT,
                                target.pairKey(), false, elapsedMs,
                                targets.size(), attempt.tcpAttempted.get(), attempt.udpPlan.getScheduled().size(),
                                attempt.snapshotUncheckedReasons(), attempt.usedHint);
                        completeWithUdpAttachment(attempt, cli, report);
                    } else {
                        tryClose(cli);
                    }
                } catch (Throwable fail) {
                    attempt.unregisterInFlight(cli);
                    tryClose(cli);
                    int done = attempt.tcpFinished.incrementAndGet();
                    if (!attempt.isStopped()) {
                        if (done >= targets.size()) {
                            fallbackToRelay.run();
                        } else {
                            launchNext[0].run();
                        }
                    }
                }
            });
        };

        int initialConcurrency = Math.min(Math.max(1, config.getMaxConcurrentTcpChecks()), targets.size());
        for (int i = 0; i < initialConcurrency; i++) {
            launchNext[0].run();
        }
    }

    private void openOnDemandRelay(PathAttempt attempt) {
        if (attempt.isCancelled()) {
            return;
        }
        ConnectRequest req = attempt.req;
        RrpClient relay = req.getRrpRelayClient();
        if (relay == null || !relay.isRawAuthenticated()) {
            // Never claim UDP-only reliable stream when all reliable paths are unavailable.
            attempt.resultFuture.completeExceptionally(new ConnectException(
                    "No reliable TCP path reachable for " + req.getTargetNodeId() + "/" + req.getTargetService()
                            + " (direct TCP unavailable and RRP Relay unavailable)"));
            return;
        }
        CompletableFuture<LocalAddress> carrierF = relay.openRawCarrierAsync(req.getTargetNodeId(), req.getTargetService());
        synchronized (attempt.lock) {
            if (attempt.cancelled) {
                carrierF.cancel(false);
                return;
            }
            attempt.pendingRelayCarrierFuture = carrierF;
        }
        carrierF.whenComplete((carrierAddr, err) -> {
            if (err != null) {
                attempt.resultFuture.completeExceptionally(err);
                return;
            }
            synchronized (attempt.lock) {
                attempt.pendingRelayCarrierFuture = null;
                if (attempt.cancelled) {
                    relay.cancelRawCarrier(carrierAddr);
                    return;
                }
                attempt.openedRelayCarrier = carrierAddr;
                attempt.relayOpened = true;
            }
            Tasks.run(() -> {
                if (attempt.isCancelled()) {
                    relay.cancelRawCarrier(carrierAddr);
                    return;
                }
                TransportConfig tc = Sys.deepClone(req.getE2eTransportTemplate());
                tc.setPeerId(attempt.peerIdTag);
                tc.getTcpClientConfig().setEnableReconnect(false);
                TransportClient cli = new TransportClient(tc);
                synchronized (attempt.lock) {
                    if (attempt.cancelled) {
                        tryClose(cli);
                        relay.cancelRawCarrier(carrierAddr);
                        return;
                    }
                    attempt.inFlightClients.add(cli);
                }
                try {
                    cli.connectLocal(carrierAddr);
                    synchronized (attempt.lock) {
                        attempt.inFlightClients.remove(cli);
                        if (attempt.cancelled) {
                            tryClose(cli);
                            relay.cancelRawCarrier(carrierAddr);
                            return;
                        }
                        attempt.carrierClaimed = true;
                    }
                    long elapsedMs = Math.max(1L, (System.nanoTime() - attempt.startNanos) / 1_000_000L);
                    totalRelaySelections.incrementAndGet();
                    PathSelectionReport report = new PathSelectionReport(
                            req.getTargetNodeId(), req.getTargetService(), TcpPathType.RELAY,
                            "rrp-relay:" + req.getTargetNodeId() + "/" + req.getTargetService(),
                            true, elapsedMs,
                            attempt.tcpPlan.getScheduled().size(), attempt.tcpAttempted.get(),
                            attempt.udpPlan.getScheduled().size(),
                            attempt.snapshotUncheckedReasons(), attempt.usedHint);
                    completeWithUdpAttachment(attempt, cli, report);
                } catch (Throwable connectErr) {
                    synchronized (attempt.lock) {
                        attempt.inFlightClients.remove(cli);
                    }
                    tryClose(cli);
                    relay.cancelRawCarrier(carrierAddr);
                    attempt.resultFuture.completeExceptionally(connectErr);
                }
            });
        });
    }

    private void completeWithUdpAttachment(PathAttempt attempt, TransportClient cli, PathSelectionReport report) {
        SelectedConnection conn = new SelectedConnection(cli, report, attempt.udpFuture,
                attempt.activePunchSessionId, attempt::cancelAll);
        if (!attempt.resultFuture.complete(conn)) {
            conn.close();
            return;
        }
        attempt.udpFuture.whenComplete((verified, err) -> {
            if (verified != null && err == null && !attempt.isCancelled()) {
                conn.verifiedUdpPath = verified;
                report.recordVerifiedUdp(verified);
                TransportSession s = cli.session();
                if (s != null && s.isConnected() && s.adaptiveHandler() != null) {
                    s.adaptiveHandler().attachVerifiedUdpPath(
                            verified.getLocalBinding().getPort(), verified.getVerifiedPeerAddress());
                    totalUdpUpgrades.incrementAndGet();
                }
            }
        });
    }

    private static String generateAttemptId() {
        long v = RANDOM.nextLong();
        return Long.toHexString(v);
    }

    public static String buildPeerIdTag(String localNodeId, long punchSessionId) {
        if (punchSessionId != 0L) {
            return buildPeerIdTag(localNodeId, "p" + punchSessionId);
        }
        return localNodeId == null ? "" : localNodeId;
    }

    public static String buildPeerIdTag(String localNodeId, String correlationId) {
        String base = localNodeId == null ? "" : localNodeId.trim();
        if (Strings.isEmpty(correlationId)) {
            return base;
        }
        String suffix = "#" + correlationId.trim();
        int maxBase = Math.max(1, 64 - suffix.length());
        if (base.length() > maxBase) {
            base = base.substring(0, maxBase);
        }
        return base + suffix;
    }

    public static String extractNodeIdFromPeerTag(String peerIdTag) {
        if (Strings.isEmpty(peerIdTag)) {
            return "";
        }
        int hash = peerIdTag.indexOf('#');
        return hash > 0 ? peerIdTag.substring(0, hash) : peerIdTag;
    }

    public static String extractCorrelationIdFromPeerTag(String peerIdTag) {
        if (Strings.isEmpty(peerIdTag)) {
            return "";
        }
        int hash = peerIdTag.indexOf('#');
        return hash > 0 && hash + 1 < peerIdTag.length() ? peerIdTag.substring(hash + 1) : "";
    }

    // endregion

    // region Target Peer Helper (B-side automatic PunchInvite handling & verified UDP attachment)

    /**
     * Target-side coordinator wiring {@link RendezvousContract#EVENT_PUNCH_INVITE} + {@link PeerUdpPuncher}
     * to target {@link TransportServer}(s) (direct TCP and/or RRP RAW Relay {@link LocalAddress}) so verified
     * punched UDP paths bind ONLY to the exact matching authenticated {@link TransportSession} (by
     * {@code (initiatorNodeId, correlationId)} + target service + expiry), regardless of whether TCP/Relay
     * or UDP punch finishes first.
     */
    public static final class PeerEndpointAcceptor extends Disposable {
        public static final int DEFAULT_MAX_PENDING_BINDINGS = 256;

        private static final class BindingKey {
            final String initiatorNodeId;
            final String correlationId;

            BindingKey(String initiatorNodeId, String correlationId) {
                this.initiatorNodeId = initiatorNodeId;
                this.correlationId = correlationId;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof BindingKey)) return false;
                BindingKey that = (BindingKey) o;
                return initiatorNodeId.equals(that.initiatorNodeId) && correlationId.equals(that.correlationId);
            }

            @Override
            public int hashCode() {
                return 31 * initiatorNodeId.hashCode() + correlationId.hashCode();
            }
        }

        private static final class VerifiedBinding {
            final String initiatorNodeId;
            final String targetService;
            final long punchSessionId;
            final VerifiedUdpPath verified;
            final long expiresAtMillis;

            VerifiedBinding(String initiatorNodeId, String targetService, long punchSessionId,
                            VerifiedUdpPath verified, long expiresAtMillis) {
                this.initiatorNodeId = initiatorNodeId;
                this.targetService = targetService;
                this.punchSessionId = punchSessionId;
                this.verified = verified;
                this.expiresAtMillis = expiresAtMillis;
            }

            boolean isExpired(long now) {
                return now >= expiresAtMillis;
            }
        }

        private static final class PendingSessionEntry {
            final String serviceName;
            final TransportSession session;
            final long expiresAtMillis;

            PendingSessionEntry(String serviceName, TransportSession session, long expiresAtMillis) {
                this.serviceName = serviceName;
                this.session = session;
                this.expiresAtMillis = expiresAtMillis;
            }
        }

        private final PathSelector selector;
        private final PeerUdpPuncher puncher;
        private final Supplier<List<LocalUdpBinding>> localBindingsSupplier;
        private final Object bindingLock = new Object();
        private final LinkedHashMap<BindingKey, VerifiedBinding> pendingVerifiedByKey = new LinkedHashMap<BindingKey, VerifiedBinding>(32, 0.75f, false);
        private final LinkedHashMap<BindingKey, PendingSessionEntry> pendingSessionsByKey = new LinkedHashMap<BindingKey, PendingSessionEntry>(32, 0.75f, false);
        private final ConcurrentHashMap<String, Set<Long>> activePunchIdsByInitiator = new ConcurrentHashMap<String, Set<Long>>();
        private volatile int maxPendingBindings = DEFAULT_MAX_PENDING_BINDINGS;

        public PeerEndpointAcceptor(@NonNull PathSelector selector,
                                    @NonNull PeerUdpPuncher puncher,
                                    @NonNull Supplier<List<LocalUdpBinding>> localBindingsSupplier) {
            this.selector = selector;
            this.puncher = puncher;
            this.localBindingsSupplier = localBindingsSupplier;
        }

        public void setMaxPendingBindings(int maxPendingBindings) {
            this.maxPendingBindings = Math.max(8, maxPendingBindings);
        }

        public int pendingVerifiedCount() {
            synchronized (bindingLock) {
                sweepExpiredLocked(System.currentTimeMillis());
                return pendingVerifiedByKey.size();
            }
        }

        public int pendingSessionCount() {
            synchronized (bindingLock) {
                sweepExpiredLocked(System.currentTimeMillis());
                return pendingSessionsByKey.size();
            }
        }

        /** Binds a target {@link TransportServer} (wildcard service match) so incoming sessions receive their exact verified UDP path. */
        public void bindTransportServer(@NonNull TransportServer server) {
            bindTransportServer(null, server);
        }

        /** Binds a service-specific {@link TransportServer} so incoming sessions only receive verified UDP paths authorized for {@code serviceName}. */
        public void bindTransportServer(String serviceName, @NonNull TransportServer server) {
            final String normSvc = Strings.isEmpty(serviceName) ? null : serviceName.trim();
            server.onConnected.add((s, e) -> onSessionConnected(normSvc, e.getValue()));
            server.onDisconnected.add((s, e) -> onSessionDisconnected(e.getValue()));
        }

        /** Subscribes to {@link RendezvousContract#EVENT_PUNCH_INVITE} so B punches simultaneously with A. */
        public void attachToRendezvous(@NonNull RendezvousContract rendezvousFacade) {
            rendezvousFacade.attachEvent(RendezvousContract.EVENT_PUNCH_INVITE, (sender, args) -> {
                if (args instanceof PunchInviteEvent) {
                    handlePunchInvite((PunchInviteEvent) args);
                }
            });
        }

        /** Immediately revokes cached bindings and cancels active punches for {@code initiatorNodeId}. */
        public void revokeNode(String initiatorNodeId) {
            if (Strings.isEmpty(initiatorNodeId)) {
                return;
            }
            String normId = initiatorNodeId.trim();
            Set<Long> activeIds = activePunchIdsByInitiator.remove(normId);
            if (activeIds != null) {
                for (Long pid : activeIds) {
                    puncher.cancelPunchSession(pid);
                }
            }
            synchronized (bindingLock) {
                Iterator<Map.Entry<BindingKey, VerifiedBinding>> vit = pendingVerifiedByKey.entrySet().iterator();
                while (vit.hasNext()) {
                    if (normId.equals(vit.next().getKey().initiatorNodeId)) {
                        vit.remove();
                    }
                }
                Iterator<Map.Entry<BindingKey, PendingSessionEntry>> sit = pendingSessionsByKey.entrySet().iterator();
                while (sit.hasNext()) {
                    if (normId.equals(sit.next().getKey().initiatorNodeId)) {
                        sit.remove();
                    }
                }
            }
        }

        public void handlePunchInvite(@NonNull PunchInviteEvent invite) {
            if (isClosed() || Strings.isEmpty(invite.getInitiatorNodeId())) {
                return;
            }
            List<LocalUdpBinding> locals = localBindingsSupplier.get();
            CandidatePlan<UdpCandidatePair> plan = selector.planUdpPairs(
                    locals, invite.getInitiatorCandidates(), null, System.currentTimeMillis());
            if (plan.getScheduled().isEmpty()) {
                return;
            }
            String initiatorId = invite.getInitiatorNodeId().trim();
            String corrId = !Strings.isEmpty(invite.getAttemptId())
                    ? invite.getAttemptId().trim()
                    : ("p" + invite.getPunchSessionId());
            long punchId = invite.getPunchSessionId();
            long expiresAt = invite.getExpiresAtMillis();
            trackActivePunch(initiatorId, punchId);
            Config cfg = selector.getConfig();
            puncher.startPunchSession(
                    punchId,
                    invite.getPunchKey(),
                    expiresAt,
                    plan.getScheduled(),
                    cfg.getMaxUdpRetriesPerPair(),
                    cfg.getMaxConcurrentUdpChecks(),
                    cfg.getUdpProbeIntervalMillis(),
                    cfg.getUdpCheckBudgetMillis(),
                    cfg.getMaxUdpProbeBytesPerSession(),
                    cfg.getUdpSettleWindowMillis(),
                    verified -> {
                        untrackActivePunch(initiatorId, punchId);
                        onPunchVerified(initiatorId, corrId, invite.getTargetService(), punchId, verified, expiresAt);
                    }).whenComplete((v, err) -> untrackActivePunch(initiatorId, punchId));
        }

        /** Handles a direct pre-arranged punch without Rendezvous (e.g. direct LAN UDP testing). */
        public CompletableFuture<VerifiedUdpPath> startDirectPunchResponder(String initiatorNodeId,
                                                                            long punchSessionId,
                                                                            byte[] punchKey,
                                                                            List<EndpointCandidate> initiatorCandidates) {
            return startDirectPunchResponder(initiatorNodeId, null, punchSessionId, punchKey, initiatorCandidates);
        }

        public CompletableFuture<VerifiedUdpPath> startDirectPunchResponder(String initiatorNodeId,
                                                                            String targetService,
                                                                            long punchSessionId,
                                                                            byte[] punchKey,
                                                                            List<EndpointCandidate> initiatorCandidates) {
            List<LocalUdpBinding> locals = localBindingsSupplier.get();
            CandidatePlan<UdpCandidatePair> plan = selector.planUdpPairs(
                    locals, initiatorCandidates, null, System.currentTimeMillis());
            Config cfg = selector.getConfig();
            long expiresAt = System.currentTimeMillis() + Math.max(5000L, cfg.getUdpCheckBudgetMillis() * 2L);
            String corrId = "p" + punchSessionId;
            String normInit = initiatorNodeId == null ? "" : initiatorNodeId.trim();
            trackActivePunch(normInit, punchSessionId);
            CompletableFuture<VerifiedUdpPath> f = puncher.startPunchSession(
                    punchSessionId,
                    punchKey,
                    expiresAt,
                    plan.getScheduled(),
                    cfg.getMaxUdpRetriesPerPair(),
                    cfg.getMaxConcurrentUdpChecks(),
                    cfg.getUdpProbeIntervalMillis(),
                    cfg.getUdpCheckBudgetMillis(),
                    cfg.getMaxUdpProbeBytesPerSession(),
                    cfg.getUdpSettleWindowMillis(),
                    verified -> {
                        untrackActivePunch(normInit, punchSessionId);
                        onPunchVerified(normInit, corrId, targetService, punchSessionId, verified, expiresAt);
                    });
            f.whenComplete((v, err) -> untrackActivePunch(normInit, punchSessionId));
            return f;
        }

        private void trackActivePunch(String initiatorNodeId, long punchSessionId) {
            if (Strings.isEmpty(initiatorNodeId) || punchSessionId == 0L) {
                return;
            }
            activePunchIdsByInitiator.computeIfAbsent(initiatorNodeId, k -> ConcurrentHashMap.newKeySet()).add(punchSessionId);
        }

        private void untrackActivePunch(String initiatorNodeId, long punchSessionId) {
            Set<Long> set = activePunchIdsByInitiator.get(initiatorNodeId);
            if (set != null) {
                set.remove(punchSessionId);
                if (set.isEmpty()) {
                    activePunchIdsByInitiator.remove(initiatorNodeId, set);
                }
            }
        }

        private void onPunchVerified(String initiatorNodeId, String correlationId, String targetService,
                                     long punchSessionId, VerifiedUdpPath verified, long expiresAtMillis) {
            if (isClosed() || verified == null || Strings.isEmpty(initiatorNodeId) || Strings.isEmpty(correlationId)) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now >= expiresAtMillis) {
                return;
            }
            BindingKey key = new BindingKey(initiatorNodeId, correlationId);
            PendingSessionEntry matchedSession = null;
            synchronized (bindingLock) {
                sweepExpiredLocked(now);
                PendingSessionEntry existing = pendingSessionsByKey.get(key);
                if (existing != null && existing.session.isConnected()
                        && serviceMatches(existing.serviceName, targetService)) {
                    pendingSessionsByKey.remove(key);
                    matchedSession = existing;
                } else {
                    evictOldestIfNeeded(pendingVerifiedByKey, maxPendingBindings);
                    pendingVerifiedByKey.put(key, new VerifiedBinding(
                            initiatorNodeId, targetService, punchSessionId, verified, expiresAtMillis));
                }
            }
            if (matchedSession != null && matchedSession.session.adaptiveHandler() != null) {
                matchedSession.session.adaptiveHandler().attachVerifiedUdpPath(
                        verified.getLocalBinding().getPort(), verified.getVerifiedPeerAddress());
            }
        }

        private void onSessionConnected(String boundServiceName, TransportSession session) {
            if (isClosed() || session == null || !session.isConnected()) {
                return;
            }
            String initiatorNodeId = extractNodeIdFromPeerTag(session.remotePeerId());
            String correlationId = extractCorrelationIdFromPeerTag(session.remotePeerId());
            if (Strings.isEmpty(initiatorNodeId) || Strings.isEmpty(correlationId)) {
                return;
            }
            long now = System.currentTimeMillis();
            BindingKey key = new BindingKey(initiatorNodeId, correlationId);
            VerifiedBinding matchedBinding = null;
            synchronized (bindingLock) {
                sweepExpiredLocked(now);
                VerifiedBinding existing = pendingVerifiedByKey.get(key);
                if (existing != null && !existing.isExpired(now)
                        && serviceMatches(boundServiceName, existing.targetService)) {
                    pendingVerifiedByKey.remove(key);
                    matchedBinding = existing;
                } else {
                    long sessionBindTtl = Math.max(5000L, selector.getConfig().getUdpCheckBudgetMillis() * 3L);
                    evictOldestIfNeeded(pendingSessionsByKey, maxPendingBindings);
                    pendingSessionsByKey.put(key, new PendingSessionEntry(boundServiceName, session, now + sessionBindTtl));
                }
            }
            if (matchedBinding != null && session.adaptiveHandler() != null) {
                session.adaptiveHandler().attachVerifiedUdpPath(
                        matchedBinding.verified.getLocalBinding().getPort(),
                        matchedBinding.verified.getVerifiedPeerAddress());
            }
        }

        private void onSessionDisconnected(TransportSession closed) {
            if (closed == null) {
                return;
            }
            String initiatorNodeId = extractNodeIdFromPeerTag(closed.remotePeerId());
            String correlationId = extractCorrelationIdFromPeerTag(closed.remotePeerId());
            if (Strings.isEmpty(initiatorNodeId) || Strings.isEmpty(correlationId)) {
                return;
            }
            BindingKey key = new BindingKey(initiatorNodeId, correlationId);
            synchronized (bindingLock) {
                PendingSessionEntry entry = pendingSessionsByKey.get(key);
                if (entry != null && entry.session == closed) {
                    pendingSessionsByKey.remove(key);
                }
                pendingVerifiedByKey.remove(key);
            }
        }

        private static boolean serviceMatches(String serverService, String inviteService) {
            return Strings.isEmpty(serverService) || Strings.isEmpty(inviteService) || serverService.equals(inviteService);
        }

        private void sweepExpiredLocked(long now) {
            Iterator<Map.Entry<BindingKey, VerifiedBinding>> vit = pendingVerifiedByKey.entrySet().iterator();
            while (vit.hasNext()) {
                if (vit.next().getValue().isExpired(now)) {
                    vit.remove();
                }
            }
            Iterator<Map.Entry<BindingKey, PendingSessionEntry>> sit = pendingSessionsByKey.entrySet().iterator();
            while (sit.hasNext()) {
                PendingSessionEntry entry = sit.next().getValue();
                if (now >= entry.expiresAtMillis || !entry.session.isConnected()) {
                    sit.remove();
                }
            }
        }

        private static <K, V> void evictOldestIfNeeded(LinkedHashMap<K, V> map, int cap) {
            while (map.size() >= cap) {
                Iterator<Map.Entry<K, V>> it = map.entrySet().iterator();
                if (!it.hasNext()) {
                    break;
                }
                it.next();
                it.remove();
            }
        }

        @Override
        protected void dispose() {
            for (Set<Long> set : activePunchIdsByInitiator.values()) {
                for (Long pid : set) {
                    puncher.cancelPunchSession(pid);
                }
            }
            activePunchIdsByInitiator.clear();
            synchronized (bindingLock) {
                pendingVerifiedByKey.clear();
                pendingSessionsByKey.clear();
            }
        }
    }

    // endregion
}
