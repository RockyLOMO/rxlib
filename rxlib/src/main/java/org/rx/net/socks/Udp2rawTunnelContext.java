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
}
