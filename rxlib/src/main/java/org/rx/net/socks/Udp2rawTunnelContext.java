package org.rx.net.socks;

import io.netty.channel.Channel;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class Udp2rawTunnelContext {
    final Udp2rawServerEntryManager manager;
    final String tunnelId;
    final long sessionHi;
    final long sessionLo;
    final byte[] sessionSecret;
    final Udp2rawAuthMode authMode;
    final UdpCompressConfig compressConfig;
    final UdpCompressStats compressStats;
    final UdpRedundantConfig redundantConfig;
    final UdpRedundantMultiplierResolver redundantResolver;
    final long idleTimeoutMillis;
    final int maxSessions;
    final long createdAtMillis;
    final ConcurrentMap<Udp2rawSessionKey, Udp2rawSession> sessions = new ConcurrentHashMap<>();
    final ConcurrentMap<InetSocketAddress, PeerGuard> peerGuards = new ConcurrentHashMap<>();
    volatile long lastActiveAtMillis;

    Udp2rawTunnelContext(Udp2rawServerEntryManager manager, String tunnelId,
            long sessionHi, long sessionLo, byte[] sessionSecret,
            Udp2rawAuthMode authMode, UdpCompressConfig compressConfig,
            UdpRedundantConfig redundantConfig, long idleTimeoutMillis, int maxSessions, long now) {
        this.manager = manager;
        this.tunnelId = tunnelId;
        this.sessionHi = sessionHi;
        this.sessionLo = sessionLo;
        this.sessionSecret = sessionSecret;
        this.authMode = authMode != null ? authMode : Udp2rawAuthMode.FIRST_PACKET_MAC;
        this.compressConfig = compressConfig;
        this.compressStats = Udp2rawPayloadSupport.isCompressEnabled(compressConfig)
                ? new UdpCompressStats(compressConfig) : null;
        this.redundantConfig = redundantConfig;
        this.redundantResolver = Udp2rawPayloadSupport.isRedundantEnabled(redundantConfig)
                ? redundantConfig.buildMultiplierResolver() : null;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.maxSessions = Math.max(1, maxSessions);
        this.createdAtMillis = now;
        this.lastActiveAtMillis = now;
    }

    Udp2rawSession session(Udp2rawSessionKey key) {
        return sessions.get(key);
    }

    Udp2rawSession getOrCreateSession(Udp2rawSessionKey key, InetSocketAddress udp2rawPeer,
            InetSocketAddress clientSource, UnresolvedEndpoint destination, Channel entryChannel) {
        Udp2rawSession session = sessions.get(key);
        if (session != null) {
            return session;
        }
        if (clientSource == null || destination == null) {
            return null;
        }
        if (sessions.size() >= maxSessions) {
            DiagnosticMetrics.record("socks.udp2raw.drop.count", 1D, "reason=max-sessions");
            return null;
        }
        Udp2rawSession created = new Udp2rawSession(this, key, udp2rawPeer, clientSource, destination, entryChannel);
        Udp2rawSession old = sessions.putIfAbsent(key, created);
        if (old != null) {
            created.close("duplicate-create");
            return old;
        }
        DiagnosticMetrics.record("socks.udp2raw.session.create.count", 1D, "result=success");
        DiagnosticMetrics.record("socks.udp2raw.session.active.count", sessions.size(), "action=create");
        return created;
    }

    void touch() {
        lastActiveAtMillis = System.currentTimeMillis();
    }

    boolean isPeerBlocked(InetSocketAddress peer, long now) {
        PeerGuard guard = peer != null ? peerGuards.get(peer) : null;
        return guard != null && guard.isBlocked(now);
    }

    void recordAuthFailure(InetSocketAddress peer, long now) {
        if (peer == null) {
            return;
        }
        PeerGuard guard = peerGuards.get(peer);
        if (guard == null) {
            PeerGuard created = new PeerGuard();
            PeerGuard old = peerGuards.putIfAbsent(peer, created);
            guard = old != null ? old : created;
        }
        SocksConfig config = manager.server.getConfig();
        guard.recordAuthFailure(now, config.getUdp2rawBadAuthThreshold(),
                config.getUdp2rawBadAuthFuseSeconds() * 1000L);
    }

    void recordAuthSuccess(InetSocketAddress peer) {
        PeerGuard guard = peer != null ? peerGuards.get(peer) : null;
        if (guard != null) {
            guard.clearAuthFailures();
        }
    }

    void removeSession(Udp2rawSessionKey key, Udp2rawSession session, String reason) {
        if (sessions.remove(key, session)) {
            DiagnosticMetrics.record("socks.udp2raw.session.close.count", 1D, "reason=" + reason);
            DiagnosticMetrics.record("socks.udp2raw.session.active.count", sessions.size(), "action=close");
        }
    }

    void close(String reason) {
        for (Udp2rawSession session : sessions.values()) {
            session.close(reason);
        }
        sessions.clear();
    }

    long expireAtMillis() {
        return lastActiveAtMillis + idleTimeoutMillis;
    }

    private static final class PeerGuard {
        private static final long AUTH_FAIL_WINDOW_MILLIS = 10_000L;

        private long authFailWindowStartMillis;
        private int authFailures;
        private volatile long blockedUntilMillis;

        boolean isBlocked(long now) {
            return blockedUntilMillis > now;
        }

        synchronized void recordAuthFailure(long now, int threshold, long fuseMillis) {
            if (authFailWindowStartMillis == 0L || now - authFailWindowStartMillis > AUTH_FAIL_WINDOW_MILLIS) {
                authFailWindowStartMillis = now;
                authFailures = 0;
            }
            if (++authFailures < threshold) {
                return;
            }
            blockedUntilMillis = now + fuseMillis;
            authFailures = 0;
            authFailWindowStartMillis = now;
            DiagnosticMetrics.record("socks.udp2raw.peer.block.count", 1D, "reason=bad-auth");
        }

        synchronized void clearAuthFailures() {
            authFailures = 0;
            authFailWindowStartMillis = 0L;
        }
    }
}
