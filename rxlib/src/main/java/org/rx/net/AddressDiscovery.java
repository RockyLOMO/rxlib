package org.rx.net;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.RxConfig;
import org.rx.net.EndpointCandidate.AddressFamily;
import org.rx.net.EndpointCandidate.CandidateType;
import org.rx.net.EndpointCandidate.Protocol;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Shared bounded address discovery and endpoint observer separating local interface candidates,
 * HTTPS public IPv4 observations, TCP observed endpoints, and UDP socket external mappings.
 * Does not conflate observation domains and enforces explicit bounds/deduplication.
 */
@Slf4j
public final class AddressDiscovery {
    public static final int DEFAULT_MAX_CANDIDATES = 16;
    public static final long DEFAULT_CANDIDATE_TTL_MILLIS = 60_000L;
    private static final int MAX_OBSERVED_CACHE_ENTRIES = 512;

    @Getter
    @Setter
    public static final class LocalDiscoveryPolicy {
        public static final LocalDiscoveryPolicy DEFAULT = new LocalDiscoveryPolicy();

        private boolean includeVirtual;
        private boolean includeIpv6 = true;
        private boolean includeUniqueLocalIpv6;
        private int maxCandidates = DEFAULT_MAX_CANDIDATES;
        private int maxIpv6PerInterface = 2;
        private long ttlMillis = DEFAULT_CANDIDATE_TTL_MILLIS;
    }

    /** Lightweight snapshot of a network interface for testable, zero-JNI candidate discovery. */
    public static final class InterfaceSnapshot {
        final String name;
        final boolean up;
        final boolean loopback;
        final boolean virtual;
        final boolean pointToPoint;
        final List<InetAddress> addresses;

        public InterfaceSnapshot(String name, boolean up, boolean loopback, boolean virtual,
                                 boolean pointToPoint, List<InetAddress> addresses) {
            this.name = name == null ? "" : name;
            this.up = up;
            this.loopback = loopback;
            this.virtual = virtual;
            this.pointToPoint = pointToPoint;
            this.addresses = addresses == null ? Collections.<InetAddress>emptyList() : addresses;
        }
    }

    private static final Comparator<EndpointCandidate> CANDIDATE_ORDER = new Comparator<EndpointCandidate>() {
        @Override
        public int compare(EndpointCandidate a, EndpointCandidate b) {
            int c = Integer.compare(a.getCandidateType().ordinal(), b.getCandidateType().ordinal());
            if (c != 0) return c;
            c = Boolean.compare(b.isVerified(), a.isVerified());
            if (c != 0) return c;
            c = a.getIp().compareTo(b.getIp());
            if (c != 0) return c;
            return Integer.compare(a.getPort(), b.getPort());
        }
    };

    private final LongSupplier clock;
    private final Supplier<List<InterfaceSnapshot>> interfaceSupplier;
    private final Function<String[], String> httpsPublicIpv4Observer;
    /** Keyed by scopeKey + "|" + candidateType.name() so HTTPS/TCP/UDP observations never overwrite each other. */
    private final ConcurrentHashMap<String, EndpointCandidate> observedCache =
            new ConcurrentHashMap<String, EndpointCandidate>();

    public static final AddressDiscovery DEFAULT = new AddressDiscovery(
            System::currentTimeMillis,
            AddressDiscovery::snapshotSystemInterfaces,
            PublicIpObserver::observe);

    public AddressDiscovery() {
        this(System::currentTimeMillis, AddressDiscovery::snapshotSystemInterfaces, PublicIpObserver::observe);
    }

    AddressDiscovery(LongSupplier clock,
                     Supplier<List<InterfaceSnapshot>> interfaceSupplier,
                     Function<String[], String> httpsPublicIpv4Observer) {
        this.clock = clock;
        this.interfaceSupplier = interfaceSupplier;
        this.httpsPublicIpv4Observer = httpsPublicIpv4Observer;
    }

    public static List<EndpointCandidate> discoverLocalCandidates(int port, Protocol protocol, LocalDiscoveryPolicy policy) {
        return DEFAULT.discoverLocal(port, protocol, policy);
    }

    public List<EndpointCandidate> discoverLocal(int port, Protocol protocol, LocalDiscoveryPolicy policy) {
        LocalDiscoveryPolicy p = policy == null ? LocalDiscoveryPolicy.DEFAULT : policy;
        Protocol proto = protocol == null ? Protocol.ANY : protocol;
        int cap = Math.max(1, Math.min(64, p.getMaxCandidates()));
        int maxV6PerNic = Math.max(0, p.getMaxIpv6PerInterface());
        long now = clock.getAsLong();

        Map<String, EndpointCandidate> deduped = new LinkedHashMap<String, EndpointCandidate>();
        List<InterfaceSnapshot> nics = interfaceSupplier.get();
        if (nics != null) {
            for (InterfaceSnapshot nic : nics) {
                if (nic == null || !nic.up || nic.loopback) {
                    continue;
                }
                if (!p.isIncludeVirtual() && (nic.virtual || looksVirtualInterface(nic.name))) {
                    continue;
                }
                int v6Added = 0;
                for (InetAddress addr : nic.addresses) {
                    if (deduped.size() >= cap) {
                        break;
                    }
                    if (addr == null || addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                            || addr.isLinkLocalAddress() || addr.isMulticastAddress()) {
                        continue;
                    }
                    if (addr instanceof Inet4Address) {
                        CandidateType type = Sockets.isGlobalIpv4(addr) ? CandidateType.HOST : CandidateType.LAN;
                        EndpointCandidate cand = new EndpointCandidate(
                                AddressFamily.IPV4, addr.getHostAddress(), port, proto, type,
                                "nic:" + nic.name, now, p.getTtlMillis(), false);
                        deduped.putIfAbsent(cand.dedupeKey(), cand);
                    } else if (addr instanceof Inet6Address && p.isIncludeIpv6()) {
                        if (v6Added >= maxV6PerNic) {
                            continue;
                        }
                        byte[] raw = addr.getAddress();
                        boolean ula = (raw[0] & 0xFE) == 0xFC; // fc00::/7
                        boolean globalV6 = isGlobalUnicastIpv6(raw);
                        if (!globalV6 && !(ula && p.isIncludeUniqueLocalIpv6())) {
                            continue;
                        }
                        CandidateType type = globalV6 ? CandidateType.GLOBAL_V6 : CandidateType.LAN;
                        EndpointCandidate cand = new EndpointCandidate(
                                AddressFamily.IPV6, addr.getHostAddress(), port, proto, type,
                                "nic:" + nic.name, now, p.getTtlMillis(), false);
                        if (deduped.putIfAbsent(cand.dedupeKey(), cand) == null) {
                            v6Added++;
                        }
                    }
                }
                if (deduped.size() >= cap) {
                    break;
                }
            }
        }
        List<EndpointCandidate> result = new ArrayList<EndpointCandidate>(deduped.values());
        Collections.sort(result, CANDIDATE_ORDER);
        return result;
    }

    /**
     * Observes public IPv4 via independent HTTPS quorum and caches separately under {@link CandidateType#HTTPS_PUBLIC}.
     * Never populates or substitutes for {@link CandidateType#TCP_OBSERVED} or {@link CandidateType#UDP_MAPPED}.
     */
    public EndpointCandidate observeHttpsPublicIpv4(@NonNull String scopeKey, String[] endpoints, long ttlMillis, boolean useCache) {
        long now = clock.getAsLong();
        String cacheKey = observedKey(scopeKey, CandidateType.HTTPS_PUBLIC);
        if (useCache) {
            EndpointCandidate cached = observedCache.get(cacheKey);
            if (cached != null && !cached.isExpired(now)) {
                return cached;
            }
        }
        String[] effective = endpoints != null && endpoints.length > 0
                ? endpoints : RxConfig.INSTANCE.getNet().getPublicIpEndpoints();
        String ip = httpsPublicIpv4Observer.apply(effective);
        EndpointCandidate candidate = new EndpointCandidate(
                AddressFamily.IPV4, ip, 0, Protocol.ANY, CandidateType.HTTPS_PUBLIC,
                "https-quorum", clock.getAsLong(), ttlMillis > 0L ? ttlMillis : DEFAULT_CANDIDATE_TTL_MILLIS, true);
        putObserved(cacheKey, candidate);
        return candidate;
    }

    /** Records a server-observed TCP peer endpoint under {@link CandidateType#TCP_OBSERVED}. */
    public EndpointCandidate recordTcpObserved(@NonNull String scopeKey, @NonNull InetSocketAddress observed,
                                               String source, long ttlMillis) {
        if (observed.isUnresolved() || observed.getAddress() == null) {
            throw new IllegalArgumentException("Observed TCP endpoint must be resolved: " + observed);
        }
        EndpointCandidate candidate = new EndpointCandidate(
                EndpointCandidate.familyOf(observed.getAddress()),
                observed.getAddress().getHostAddress(),
                observed.getPort(),
                Protocol.TCP,
                CandidateType.TCP_OBSERVED,
                source == null ? "tcp-peer" : source,
                clock.getAsLong(),
                ttlMillis > 0L ? ttlMillis : DEFAULT_CANDIDATE_TTL_MILLIS,
                true);
        putObserved(observedKey(scopeKey, CandidateType.TCP_OBSERVED), candidate);
        return candidate;
    }

    /** Records an actual UDP socket external mapping under {@link CandidateType#UDP_MAPPED}. */
    public EndpointCandidate recordUdpMapped(@NonNull String scopeKey, @NonNull InetSocketAddress mapped,
                                             String source, long ttlMillis, boolean verified) {
        if (mapped.isUnresolved() || mapped.getAddress() == null) {
            throw new IllegalArgumentException("Mapped UDP endpoint must be resolved: " + mapped);
        }
        EndpointCandidate candidate = new EndpointCandidate(
                EndpointCandidate.familyOf(mapped.getAddress()),
                mapped.getAddress().getHostAddress(),
                mapped.getPort(),
                Protocol.UDP,
                CandidateType.UDP_MAPPED,
                source == null ? "udp-mapped" : source,
                clock.getAsLong(),
                ttlMillis > 0L ? ttlMillis : DEFAULT_CANDIDATE_TTL_MILLIS,
                verified);
        putObserved(observedKey(scopeKey, CandidateType.UDP_MAPPED), candidate);
        return candidate;
    }

    public EndpointCandidate getObserved(@NonNull String scopeKey, @NonNull CandidateType type) {
        String key = observedKey(scopeKey, type);
        EndpointCandidate cand = observedCache.get(key);
        if (cand == null) {
            return null;
        }
        if (cand.isExpired(clock.getAsLong())) {
            observedCache.remove(key, cand);
            return null;
        }
        return cand;
    }

    public void invalidate(@NonNull String scopeKey) {
        String prefix = scopeKey + "|";
        for (String key : observedCache.keySet()) {
            if (key.startsWith(prefix)) {
                observedCache.remove(key);
            }
        }
    }

    public void invalidate(@NonNull String scopeKey, @NonNull CandidateType type) {
        observedCache.remove(observedKey(scopeKey, type));
    }

    public void invalidateAll() {
        observedCache.clear();
    }

    /**
     * Collects bounded, deduplicated, non-expired candidates combining local interface discovery
     * and separately observed endpoints for the given scope.
     */
    public List<EndpointCandidate> collectCandidates(@NonNull String scopeKey, int localPort, Protocol protocol,
                                                     LocalDiscoveryPolicy policy, int maxCandidates) {
        int cap = Math.max(1, Math.min(64, maxCandidates > 0 ? maxCandidates : DEFAULT_MAX_CANDIDATES));
        Map<String, EndpointCandidate> merged = new LinkedHashMap<String, EndpointCandidate>();
        for (EndpointCandidate local : discoverLocal(localPort, protocol, policy)) {
            if (merged.size() >= cap) break;
            merged.putIfAbsent(local.dedupeKey(), local);
        }
        for (CandidateType type : new CandidateType[]{CandidateType.UDP_MAPPED, CandidateType.TCP_OBSERVED, CandidateType.HTTPS_PUBLIC}) {
            if (merged.size() >= cap) break;
            EndpointCandidate obs = getObserved(scopeKey, type);
            if (obs != null) {
                merged.putIfAbsent(obs.dedupeKey(), obs);
            }
        }
        List<EndpointCandidate> result = new ArrayList<EndpointCandidate>(merged.values());
        Collections.sort(result, CANDIDATE_ORDER);
        return result.size() > cap ? new ArrayList<EndpointCandidate>(result.subList(0, cap)) : result;
    }

    private void putObserved(String key, EndpointCandidate candidate) {
        if (observedCache.size() >= MAX_OBSERVED_CACHE_ENTRIES && !observedCache.containsKey(key)) {
            long now = clock.getAsLong();
            for (Map.Entry<String, EndpointCandidate> e : observedCache.entrySet()) {
                if (e.getValue().isExpired(now)) {
                    observedCache.remove(e.getKey(), e.getValue());
                }
            }
            if (observedCache.size() >= MAX_OBSERVED_CACHE_ENTRIES) {
                observedCache.clear();
            }
        }
        observedCache.put(key, candidate);
    }

    private static String observedKey(String scopeKey, CandidateType type) {
        return scopeKey + "|" + type.name();
    }

    /** RFC 4291 / RFC 3587 global unicast IPv6 (2000::/3), excluding documentation 2001:db8::/32. */
    static boolean isGlobalUnicastIpv6(byte[] raw) {
        if (raw == null || raw.length != 16) {
            return false;
        }
        if ((raw[0] & 0xE0) != 0x20) {
            return false;
        }
        // Exclude 2001:db8::/32 documentation prefix
        if (raw[0] == 0x20 && raw[1] == 0x01 && raw[2] == 0x0d && (raw[3] & 0xff) == 0xb8) {
            return false;
        }
        return true;
    }

    private static boolean looksVirtualInterface(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("docker") || lower.startsWith("veth") || lower.startsWith("br-")
                || lower.startsWith("virbr") || lower.startsWith("vmnet") || lower.startsWith("vboxnet")
                || lower.startsWith("tun") || lower.startsWith("tap") || lower.startsWith("utun")
                || lower.startsWith("wg") || lower.startsWith("tailscale");
    }

    private static List<InterfaceSnapshot> snapshotSystemInterfaces() {
        List<InterfaceSnapshot> out = new ArrayList<InterfaceSnapshot>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            if (nics == null) {
                return out;
            }
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                try {
                    List<InetAddress> addrs = new ArrayList<InetAddress>();
                    Enumeration<InetAddress> en = nic.getInetAddresses();
                    while (en.hasMoreElements()) {
                        addrs.add(en.nextElement());
                    }
                    out.add(new InterfaceSnapshot(nic.getName(), nic.isUp(), nic.isLoopback(),
                            nic.isVirtual(), nic.isPointToPoint(), addrs));
                } catch (SocketException ignored) {
                }
            }
        } catch (SocketException e) {
            log.debug("Failed to enumerate local interfaces", e);
        }
        return out;
    }
}
