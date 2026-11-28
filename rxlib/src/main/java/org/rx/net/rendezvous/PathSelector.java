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
        private final long punchSessionId;
        private volatile VerifiedUdpPath verifiedUdpPath;

        SelectedConnection(TransportClient transportClient, PathSelectionReport report,
                           CompletableFuture<VerifiedUdpPath> udpFuture, long punchSessionId) {
            this.transportClient = transportClient;
            this.report = report;
            this.udpFuture = udpFuture;
            this.punchSessionId = punchSessionId;
        }

        public TransportSession session() {
            return transportClient.session();
        }

        @Override
        public void close() {
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
        Collections.sort(allPairs, new Comparator<UdpCandidatePair>() {
            @Override
            public int compare(UdpCandidatePair a, UdpCandidatePair b) {
                return Integer.compare(b.getPriorityScore(), a.getPriorityScore());
            }
        });
        int totalConsidered = allPairs.size();
        int cap = Math.max(1, config.getMaxUdpPairsPerSession());
        int truncated = 0;
        List<UdpCandidatePair> scheduled = allPairs;
        if (allPairs.size() > cap) {
            truncated = allPairs.size() - cap;
            unchecked.add("udp:pair_budget_truncated:" + truncated);
            scheduled = new ArrayList<UdpCandidatePair>(allPairs.subList(0, cap));
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
        Collections.sort(allTargets, new Comparator<TcpCandidateTarget>() {
            @Override
            public int compare(TcpCandidateTarget a, TcpCandidateTarget b) {
                return Integer.compare(b.getPriorityScore(), a.getPriorityScore());
            }
        });
        int totalConsidered = allTargets.size();
        int cap = Math.max(1, config.getMaxTcpTargetsPerSession());
        int truncated = 0;
        List<TcpCandidateTarget> scheduled = allTargets;
        if (allTargets.size() > cap) {
            truncated = allTargets.size() - cap;
            unchecked.add("tcp:target_budget_truncated:" + truncated);
            scheduled = new ArrayList<TcpCandidateTarget>(allTargets.subList(0, cap));
        }
        return new CandidatePlan<TcpCandidateTarget>(scheduled, unchecked, totalConsidered, truncated);
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
                score = 120;
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
        List<String> combinedUnchecked = new ArrayList<String>(tcpPlan.getUncheckedReasons().size() + udpPlan.getUncheckedReasons().size() + 2);
        combinedUnchecked.addAll(tcpPlan.getUncheckedReasons());
        combinedUnchecked.addAll(udpPlan.getUncheckedReasons());

        // Start UDP dimension asynchronously (independent of TCP; NEVER blocks direct TCP or cancels reliable carrier).
        CompletableFuture<VerifiedUdpPath> udpFuture = new CompletableFuture<VerifiedUdpPath>();
        long initialPunchSessionId = req.getDirectPunchSessionId();
        final AtomicLong activePunchSessionId = new AtomicLong(initialPunchSessionId);

        if (req.getPuncher() != null && !udpPlan.getScheduled().isEmpty()) {
            Tasks.run(() -> startUdpDimensionAsync(req, udpPlan, activePunchSessionId, udpFuture));
        } else {
            udpFuture.complete(null);
        }

        // Start reliable TCP dimension (direct-first with bounded concurrency; Relay ONLY on demand).
        CompletableFuture<SelectedConnection> resultFuture = new CompletableFuture<SelectedConnection>();
        String peerIdTag = buildPeerIdTag(req.getLocalNodeId(), initialPunchSessionId);

        tryDirectTcpThenOptionalRelay(req, tcpPlan, udpPlan, combinedUnchecked, usedHint,
                peerIdTag, startNanos, udpFuture, activePunchSessionId, resultFuture);
        return resultFuture;
    }

    private void startUdpDimensionAsync(ConnectRequest req,
                                        CandidatePlan<UdpCandidatePair> udpPlan,
                                        AtomicLong activePunchSessionId,
                                        CompletableFuture<VerifiedUdpPath> udpFuture) {
        try {
            long punchSessionId = req.getDirectPunchSessionId();
            byte[] punchKey = req.getDirectPunchKey();
            long expiresAt = System.currentTimeMillis() + config.getUdpCheckBudgetMillis();
            List<UdpCandidatePair> effectivePairs = udpPlan.getScheduled();

            if (req.getRendezvousClient() != null && (punchSessionId == 0L || punchKey == null)) {
                PunchSessionResult coord = req.getRendezvousClient().requestPunchSession(
                        req.getTargetNodeId(), req.getTargetService());
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
            if (punchSessionId == 0L || punchKey == null || effectivePairs.isEmpty()) {
                udpFuture.complete(null);
                return;
            }
            activePunchSessionId.set(punchSessionId);
            req.getPuncher().startPunchSession(
                    punchSessionId,
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
                if (err != null) {
                    udpFuture.complete(null);
                } else {
                    if (verified != null) {
                        String pairKey = verified.getLocalBinding().getBindingId() + "->"
                                + verified.getVerifiedPeerAddress().getAddress().getHostAddress() + ":"
                                + verified.getVerifiedPeerAddress().getPort();
                        recordRecentUdp(req.getTargetNodeId(), req.getLocalCandidateGen(), req.getRemoteCandidateGen(),
                                pairKey, verified.getRttMillis(), System.currentTimeMillis());
                    }
                    udpFuture.complete(verified);
                }
            });
        } catch (Throwable ex) {
            log.debug("PathSelector UDP dimension unavailable target={}: {}", req.getTargetNodeId(), ex.getMessage());
            udpFuture.complete(null);
        }
    }

    private void tryDirectTcpThenOptionalRelay(ConnectRequest req,
                                               CandidatePlan<TcpCandidateTarget> tcpPlan,
                                               CandidatePlan<UdpCandidatePair> udpPlan,
                                               List<String> uncheckedReasons,
                                               boolean usedHint,
                                               String peerIdTag,
                                               long startNanos,
                                               CompletableFuture<VerifiedUdpPath> udpFuture,
                                               AtomicLong activePunchSessionId,
                                               CompletableFuture<SelectedConnection> resultFuture) {
        List<TcpCandidateTarget> targets = tcpPlan.getScheduled();
        if (targets.isEmpty()) {
            openOnDemandRelay(req, tcpPlan, udpPlan, uncheckedReasons, usedHint, 0,
                    peerIdTag, startNanos, udpFuture, activePunchSessionId, resultFuture);
            return;
        }

        final AtomicBoolean won = new AtomicBoolean();
        final AtomicInteger nextIndex = new AtomicInteger();
        final AtomicInteger attempted = new AtomicInteger();
        final AtomicInteger finished = new AtomicInteger();
        final List<TransportClient> inFlightClients = Collections.synchronizedList(new ArrayList<TransportClient>());
        final ScheduledFuture<?>[] budgetTimer = new ScheduledFuture<?>[1];

        Runnable cancelInFlight = () -> {
            ScheduledFuture<?> t = budgetTimer[0];
            if (t != null) {
                t.cancel(false);
            }
            synchronized (inFlightClients) {
                for (TransportClient c : inFlightClients) {
                    tryClose(c);
                }
                inFlightClients.clear();
            }
        };

        Runnable fallbackToRelay = () -> {
            if (!won.compareAndSet(false, true)) {
                return;
            }
            cancelInFlight.run();
            openOnDemandRelay(req, tcpPlan, udpPlan, uncheckedReasons, usedHint, attempted.get(),
                    peerIdTag, startNanos, udpFuture, activePunchSessionId, resultFuture);
        };

        budgetTimer[0] = Tasks.setTimeout(() -> {
            if (!won.get()) {
                uncheckedReasons.add("tcp:direct_budget_expired");
                fallbackToRelay.run();
            }
        }, Math.max(150L, config.getDirectTcpBudgetMillis()));

        final Runnable[] launchNext = new Runnable[1];
        launchNext[0] = () -> {
            if (won.get()) {
                return;
            }
            int idx = nextIndex.getAndIncrement();
            if (idx >= targets.size()) {
                return;
            }
            TcpCandidateTarget target = targets.get(idx);
            attempted.incrementAndGet();
            TransportConfig tc = Sys.deepClone(req.getE2eTransportTemplate());
            tc.setPeerId(peerIdTag);
            tc.getTcpClientConfig().setEnableReconnect(false);
            tc.getTcpClientConfig().setConnectTimeoutMillis((int) Math.max(300L, config.getDirectTcpBudgetMillis()));
            if (target.getLocalBinding() != null) {
                tc.getTcpClientConfig().setLocalAddress(target.getLocalBinding().toBindAddress());
            }
            // Direct TCP: let PathSelector attach verified UDP path if puncher is active (avoiding duplicate UDP bind)
            boolean usePuncherUdp = req.getPuncher() != null && !udpPlan.getScheduled().isEmpty();
            if (usePuncherUdp) {
                // Keep adaptive UDP negotiated via deferred or direct handler; direct TCP negotiates with server's UDP or deferred.
                tc.setEnableAdaptiveUdp(req.getE2eTransportTemplate().isEnableAdaptiveUdp());
            }

            TransportClient cli = new TransportClient(tc);
            inFlightClients.add(cli);
            Tasks.run(() -> {
                try {
                    cli.connect(target.getRemoteAddress());
                    if (won.compareAndSet(false, true)) {
                        inFlightClients.remove(cli);
                        cancelInFlight.run();
                        int remaining = targets.size() - attempted.get();
                        if (remaining > 0) {
                            uncheckedReasons.add("tcp:cancelled_after_direct_win:" + remaining);
                        }
                        long elapsedMs = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);
                        totalDirectTcpSelections.incrementAndGet();
                        recordRecentTcp(req.getTargetNodeId(), req.getLocalCandidateGen(), req.getRemoteCandidateGen(),
                                target.pairKey(), System.currentTimeMillis());
                        PathSelectionReport report = new PathSelectionReport(
                                req.getTargetNodeId(), req.getTargetService(), TcpPathType.DIRECT,
                                target.pairKey(), false, elapsedMs,
                                targets.size(), attempted.get(), udpPlan.getScheduled().size(),
                                uncheckedReasons, usedHint);
                        completeWithUdpAttachment(cli, report, udpFuture, activePunchSessionId, resultFuture);
                    } else {
                        inFlightClients.remove(cli);
                        tryClose(cli);
                    }
                } catch (Throwable fail) {
                    inFlightClients.remove(cli);
                    tryClose(cli);
                    int done = finished.incrementAndGet();
                    if (!won.get()) {
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

    private void openOnDemandRelay(ConnectRequest req,
                                   CandidatePlan<TcpCandidateTarget> tcpPlan,
                                   CandidatePlan<UdpCandidatePair> udpPlan,
                                   List<String> uncheckedReasons,
                                   boolean usedHint,
                                   int tcpAttempted,
                                   String peerIdTag,
                                   long startNanos,
                                   CompletableFuture<VerifiedUdpPath> udpFuture,
                                   AtomicLong activePunchSessionId,
                                   CompletableFuture<SelectedConnection> resultFuture) {
        RrpClient relay = req.getRrpRelayClient();
        if (relay == null || !relay.isRawAuthenticated()) {
            // Never claim UDP-only reliable stream when all reliable paths are unavailable.
            resultFuture.completeExceptionally(new ConnectException(
                    "No reliable TCP path reachable for " + req.getTargetNodeId() + "/" + req.getTargetService()
                            + " (direct TCP unavailable and RRP Relay unavailable)"));
            return;
        }
        relay.openRawCarrierAsync(req.getTargetNodeId(), req.getTargetService()).whenComplete((carrierAddr, err) -> {
            if (err != null) {
                resultFuture.completeExceptionally(err);
                return;
            }
            Tasks.run(() -> {
                TransportConfig tc = Sys.deepClone(req.getE2eTransportTemplate());
                tc.setPeerId(peerIdTag);
                tc.getTcpClientConfig().setEnableReconnect(false);
                TransportClient cli = new TransportClient(tc);
                try {
                    cli.connectLocal(carrierAddr);
                    long elapsedMs = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);
                    totalRelaySelections.incrementAndGet();
                    PathSelectionReport report = new PathSelectionReport(
                            req.getTargetNodeId(), req.getTargetService(), TcpPathType.RELAY,
                            "rrp-relay:" + req.getTargetNodeId() + "/" + req.getTargetService(),
                            true, elapsedMs,
                            tcpPlan.getScheduled().size(), tcpAttempted, udpPlan.getScheduled().size(),
                            uncheckedReasons, usedHint);
                    completeWithUdpAttachment(cli, report, udpFuture, activePunchSessionId, resultFuture);
                } catch (Throwable connectErr) {
                    tryClose(cli);
                    resultFuture.completeExceptionally(connectErr);
                }
            });
        });
    }

    private void completeWithUdpAttachment(TransportClient cli,
                                           PathSelectionReport report,
                                           CompletableFuture<VerifiedUdpPath> udpFuture,
                                           AtomicLong activePunchSessionId,
                                           CompletableFuture<SelectedConnection> resultFuture) {
        SelectedConnection conn = new SelectedConnection(cli, report, udpFuture, activePunchSessionId.get());
        udpFuture.whenComplete((verified, err) -> {
            if (verified != null && err == null) {
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
        resultFuture.complete(conn);
    }

    public static String buildPeerIdTag(String localNodeId, long punchSessionId) {
        if (punchSessionId != 0L) {
            return localNodeId + "#" + punchSessionId;
        }
        return localNodeId;
    }

    public static String extractNodeIdFromPeerTag(String peerIdTag) {
        if (Strings.isEmpty(peerIdTag)) {
            return "";
        }
        int hash = peerIdTag.indexOf('#');
        return hash > 0 ? peerIdTag.substring(0, hash) : peerIdTag;
    }

    // endregion

    // region Target Peer Helper (B-side automatic PunchInvite handling & verified UDP attachment)

    /**
     * Target-side coordinator wiring {@link RendezvousContract#EVENT_PUNCH_INVITE} + {@link PeerUdpPuncher}
     * to a target {@link TransportServer} (direct TCP and/or RRP RAW Relay {@link LocalAddress}) so verified
     * punched UDP paths attach cleanly to incoming authenticated {@link TransportSession}s regardless of
     * whether TCP/Relay or UDP punch finishes first.
     */
    public static final class PeerEndpointAcceptor extends Disposable {
        private final PathSelector selector;
        private final PeerUdpPuncher puncher;
        private final Supplier<List<LocalUdpBinding>> localBindingsSupplier;
        private final ConcurrentHashMap<String, VerifiedUdpPath> verifiedByInitiator = new ConcurrentHashMap<String, VerifiedUdpPath>();
        private final ConcurrentHashMap<String, TransportSession> latestSessionByInitiator = new ConcurrentHashMap<String, TransportSession>();

        public PeerEndpointAcceptor(@NonNull PathSelector selector,
                                    @NonNull PeerUdpPuncher puncher,
                                    @NonNull Supplier<List<LocalUdpBinding>> localBindingsSupplier) {
            this.selector = selector;
            this.puncher = puncher;
            this.localBindingsSupplier = localBindingsSupplier;
        }

        /** Binds a target {@link TransportServer} (direct TCP or LocalAddress Relay) so incoming sessions receive verified UDP paths. */
        public void bindTransportServer(@NonNull TransportServer server) {
            server.onConnected.add((s, e) -> onSessionConnected(e.getValue()));
            server.onDisconnected.add((s, e) -> {
                TransportSession closed = e.getValue();
                if (closed != null) {
                    String nodeId = extractNodeIdFromPeerTag(closed.remotePeerId());
                    latestSessionByInitiator.remove(nodeId, closed);
                }
            });
        }

        /** Subscribes to {@link RendezvousContract#EVENT_PUNCH_INVITE} so B punches simultaneously with A. */
        public void attachToRendezvous(@NonNull RendezvousContract rendezvousFacade) {
            rendezvousFacade.attachEvent(RendezvousContract.EVENT_PUNCH_INVITE, (sender, args) -> {
                if (args instanceof PunchInviteEvent) {
                    handlePunchInvite((PunchInviteEvent) args);
                }
            });
        }

        public void handlePunchInvite(@NonNull PunchInviteEvent invite) {
            if (isClosed()) {
                return;
            }
            List<LocalUdpBinding> locals = localBindingsSupplier.get();
            CandidatePlan<UdpCandidatePair> plan = selector.planUdpPairs(
                    locals, invite.getInitiatorCandidates(), null, System.currentTimeMillis());
            if (plan.getScheduled().isEmpty()) {
                return;
            }
            Config cfg = selector.getConfig();
            puncher.startPunchSession(
                    invite.getPunchSessionId(),
                    invite.getPunchKey(),
                    invite.getExpiresAtMillis(),
                    plan.getScheduled(),
                    cfg.getMaxUdpRetriesPerPair(),
                    cfg.getMaxConcurrentUdpChecks(),
                    cfg.getUdpProbeIntervalMillis(),
                    cfg.getUdpCheckBudgetMillis(),
                    cfg.getMaxUdpProbeBytesPerSession(),
                    cfg.getUdpSettleWindowMillis(),
                    verified -> onPunchVerified(invite.getInitiatorNodeId(), verified));
        }

        /** Handles a direct pre-arranged punch without Rendezvous (e.g. direct LAN UDP testing). */
        public CompletableFuture<VerifiedUdpPath> startDirectPunchResponder(String initiatorNodeId,
                                                                            long punchSessionId,
                                                                            byte[] punchKey,
                                                                            List<EndpointCandidate> initiatorCandidates) {
            List<LocalUdpBinding> locals = localBindingsSupplier.get();
            CandidatePlan<UdpCandidatePair> plan = selector.planUdpPairs(
                    locals, initiatorCandidates, null, System.currentTimeMillis());
            Config cfg = selector.getConfig();
            return puncher.startPunchSession(
                    punchSessionId,
                    punchKey,
                    System.currentTimeMillis() + cfg.getUdpCheckBudgetMillis(),
                    plan.getScheduled(),
                    cfg.getMaxUdpRetriesPerPair(),
                    cfg.getMaxConcurrentUdpChecks(),
                    cfg.getUdpProbeIntervalMillis(),
                    cfg.getUdpCheckBudgetMillis(),
                    cfg.getMaxUdpProbeBytesPerSession(),
                    cfg.getUdpSettleWindowMillis(),
                    verified -> onPunchVerified(initiatorNodeId, verified));
        }

        private void onPunchVerified(String initiatorNodeId, VerifiedUdpPath verified) {
            if (verified == null || Strings.isEmpty(initiatorNodeId)) {
                return;
            }
            verifiedByInitiator.put(initiatorNodeId, verified);
            TransportSession session = latestSessionByInitiator.get(initiatorNodeId);
            if (session != null && session.isConnected() && session.adaptiveHandler() != null) {
                session.adaptiveHandler().attachVerifiedUdpPath(
                        verified.getLocalBinding().getPort(), verified.getVerifiedPeerAddress());
            }
        }

        private void onSessionConnected(TransportSession session) {
            if (session == null || !session.isConnected()) {
                return;
            }
            String initiatorNodeId = extractNodeIdFromPeerTag(session.remotePeerId());
            if (Strings.isEmpty(initiatorNodeId)) {
                return;
            }
            latestSessionByInitiator.put(initiatorNodeId, session);
            VerifiedUdpPath verified = verifiedByInitiator.get(initiatorNodeId);
            if (verified != null && session.adaptiveHandler() != null) {
                session.adaptiveHandler().attachVerifiedUdpPath(
                        verified.getLocalBinding().getPort(), verified.getVerifiedPeerAddress());
            }
        }

        @Override
        protected void dispose() {
            verifiedByInitiator.clear();
            latestSessionByInitiator.clear();
        }
    }

    // endregion
}
