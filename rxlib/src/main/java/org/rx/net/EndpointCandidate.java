package org.rx.net;

import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

import java.io.Serializable;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Structured, bounded network endpoint candidate distinguishing address family, transport protocol,
 * candidate type, observation source, TTL, and verification state.
 */
@Getter
@ToString
public final class EndpointCandidate implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum AddressFamily {
        IPV4,
        IPV6
    }

    public enum Protocol {
        ANY,
        TCP,
        UDP
    }

    public enum CandidateType {
        LAN,
        HOST,
        GLOBAL_V6,
        UDP_MAPPED,
        TCP_OBSERVED,
        HTTPS_PUBLIC
    }

    private final AddressFamily addressFamily;
    private final String ip;
    private final int port;
    private final Protocol protocol;
    private final CandidateType candidateType;
    private final String source;
    private final long observedAtMillis;
    private final long ttlMillis;
    private final boolean verified;

    public EndpointCandidate(@NonNull AddressFamily addressFamily,
                             @NonNull String ip,
                             int port,
                             @NonNull Protocol protocol,
                             @NonNull CandidateType candidateType,
                             String source,
                             long observedAtMillis,
                             long ttlMillis,
                             boolean verified) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
        this.addressFamily = addressFamily;
        this.ip = stripScopeId(ip.trim());
        this.port = port;
        this.protocol = protocol;
        this.candidateType = candidateType;
        this.source = source == null ? "" : source;
        this.observedAtMillis = observedAtMillis;
        this.ttlMillis = Math.max(0L, ttlMillis);
        this.verified = verified;
    }

    public static AddressFamily familyOf(@NonNull InetAddress address) {
        if (address instanceof Inet4Address) {
            return AddressFamily.IPV4;
        }
        if (address instanceof Inet6Address) {
            return AddressFamily.IPV6;
        }
        throw new IllegalArgumentException("Unsupported InetAddress family: " + address.getClass());
    }

    public static String stripScopeId(String ip) {
        if (ip == null) {
            return "";
        }
        int pct = ip.indexOf('%');
        return pct > 0 ? ip.substring(0, pct) : ip;
    }

    public boolean isExpired(long nowMillis) {
        return ttlMillis > 0L && nowMillis - observedAtMillis >= ttlMillis;
    }

    /** Returns true when this candidate exposes local/private topology or NAT mapping sensitive to unauthorized peers. */
    public boolean isSensitive() {
        return candidateType == CandidateType.LAN
                || candidateType == CandidateType.HOST
                || candidateType == CandidateType.GLOBAL_V6
                || candidateType == CandidateType.UDP_MAPPED
                || candidateType == CandidateType.TCP_OBSERVED;
    }

    public InetSocketAddress toSocketAddress() {
        return new InetSocketAddress(Sockets.parseIpAddress(ip), port);
    }

    public String dedupeKey() {
        return addressFamily.ordinal() + "|" + ip + "|" + port + "|" + protocol.ordinal() + "|" + candidateType.ordinal();
    }

    public EndpointCandidate withVerified(boolean verified, long nowMillis) {
        return new EndpointCandidate(addressFamily, ip, port, protocol, candidateType, source, nowMillis, ttlMillis, verified);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EndpointCandidate)) return false;
        EndpointCandidate that = (EndpointCandidate) o;
        return port == that.port
                && addressFamily == that.addressFamily
                && protocol == that.protocol
                && candidateType == that.candidateType
                && Objects.equals(ip, that.ip);
    }

    @Override
    public int hashCode() {
        return Objects.hash(addressFamily, ip, port, protocol, candidateType);
    }
}
