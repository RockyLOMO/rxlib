package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import org.rx.io.Bytes;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.net.Sockets;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
    final UdpRedundantMode redundantMode;
    final UdpRedundantMultiplierResolver redundantResolver;
    final UdpRedundantStats redundantStats;
    final Udp2rawMtuState mtuState;
    final TrafficUser trafficUser;
    final long idleTimeoutMillis;
    final int maxSessions;
    final long createdAtMillis;
    final ConcurrentMap<Udp2rawSessionKey, Udp2rawSession> sessions = new ConcurrentHashMap<>();
    final ConcurrentMap<InetSocketAddress, PeerGuard> peerGuards = new ConcurrentHashMap<>();
    private final AtomicLong nextRedundantAdjustAtMillis = new AtomicLong();
    private volatile ScheduledFuture<?> mtuProbeFuture;
    private volatile Channel mtuProbeChannel;
    private volatile InetSocketAddress mtuProbePeer;
    private volatile boolean closed;
    volatile long lastActiveAtMillis;

    Udp2rawTunnelContext(Udp2rawServerEntryManager manager, String tunnelId,
            long sessionHi, long sessionLo, byte[] sessionSecret,
            Udp2rawAuthMode authMode, UdpCompressConfig compressConfig,
            UdpRedundantConfig redundantConfig, UdpRedundantMode redundantMode,
            long idleTimeoutMillis, int maxSessions, int initialMtu,
            TrafficUser trafficUser, long now) {
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
        this.redundantMode = redundantMode != null ? redundantMode : UdpRedundantMode.BIDIRECTIONAL;
        this.redundantResolver = Udp2rawPayloadSupport.isRedundantEnabled(redundantConfig)
                ? redundantConfig.buildMultiplierResolver() : null;
        this.redundantStats = Udp2rawPayloadSupport.newAdaptiveStats(redundantConfig);
        this.mtuState = initialMtu > 0 ? new Udp2rawMtuState(initialMtu, "server") : null;
        this.trafficUser = trafficUser != null ? trafficUser : TrafficUser.ANONYMOUS;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.maxSessions = Math.max(1, maxSessions);
        this.createdAtMillis = now;
        this.lastActiveAtMillis = now;
        if (this.redundantStats != null) {
            nextRedundantAdjustAtMillis.set(now + Udp2rawPayloadSupport.REDUNDANT_ADJUST_INTERVAL_MILLIS);
        }
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

    boolean allowPeerPacket(InetSocketAddress peer, long now) {
        SocksConfig config = manager.server.getConfig();
        int limit = config.getUdp2rawPeerRateLimitPerSecond();
        if (limit <= 0 || peer == null) {
            return true;
        }
        PeerGuard guard = peerGuard(peer);
        if (guard.allowPacket(now, limit, config.getUdp2rawPeerRateLimitBurst())) {
            return true;
        }
        DiagnosticMetrics.record("socks.udp2raw.peer.rate.limit.count", 1D, "result=drop");
        return false;
    }

    void recordAuthFailure(InetSocketAddress peer, long now) {
        if (peer == null) {
            return;
        }
        PeerGuard guard = peerGuard(peer);
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

    void recordRedundantReceived() {
        if (redundantStats != null) {
            redundantStats.recordReceived();
        }
    }

    void recordRedundantUnique(String direction) {
        if (redundantStats == null) {
            return;
        }
        redundantStats.recordUnique();
        Udp2rawPayloadSupport.adjustAdaptiveStats(redundantStats, nextRedundantAdjustAtMillis, direction);
    }

    int currentMtu() {
        return mtuState != null ? mtuState.currentMtu() : 0;
    }

    synchronized void noteMtuPeer(Channel channel, InetSocketAddress peer) {
        if (closed || mtuState == null || mtuState.currentMtu() <= 0 || channel == null || peer == null) {
            return;
        }
        boolean channelChanged = mtuProbeChannel != null && mtuProbeChannel != channel;
        mtuProbeChannel = channel;
        mtuProbePeer = peer;
        if (channelChanged && mtuProbeFuture != null) {
            mtuProbeFuture.cancel(false);
            mtuProbeFuture = null;
        }
        if (mtuProbeFuture == null || mtuProbeFuture.isDone()) {
            scheduleMtuProbe(1000L);
        }
    }

    SocksContext trafficContext(InetSocketAddress source, UnresolvedEndpoint destination) {
        if (trafficUser == null || trafficUser.isAnonymous() || source == null || source.getAddress() == null) {
            return null;
        }
        TrafficLoginInfo loginInfo = trafficUser.getLoginIps()
                .computeIfAbsent(source.getAddress(), ip -> new TrafficLoginInfo());
        SocksContext context = SocksContext.getCtx(source, destination);
        SocksUserTraffic.attach(context, trafficUser, loginInfo);
        return context;
    }

    void removeSession(Udp2rawSessionKey key, Udp2rawSession session, String reason) {
        if (sessions.remove(key, session)) {
            DiagnosticMetrics.record("socks.udp2raw.session.close.count", 1D, "reason=" + reason);
            DiagnosticMetrics.record("socks.udp2raw.session.active.count", sessions.size(), "action=close");
        }
    }

    void close(String reason) {
        closed = true;
        ScheduledFuture<?> future = mtuProbeFuture;
        if (future != null) {
            future.cancel(false);
            mtuProbeFuture = null;
        }
        for (Udp2rawSession session : sessions.values()) {
            session.close(reason);
        }
        sessions.clear();
    }

    long expireAtMillis() {
        return lastActiveAtMillis + idleTimeoutMillis;
    }

    private PeerGuard peerGuard(InetSocketAddress peer) {
        PeerGuard guard = peerGuards.get(peer);
        if (guard != null) {
            return guard;
        }
        PeerGuard created = new PeerGuard();
        PeerGuard old = peerGuards.putIfAbsent(peer, created);
        return old != null ? old : created;
    }

    private synchronized void scheduleMtuProbe(long delayMillis) {
        final Channel channel = mtuProbeChannel;
        if (closed || channel == null || !channel.isActive() || mtuProbePeer == null
                || mtuState == null || mtuState.currentMtu() <= 0) {
            return;
        }
        mtuProbeFuture = channel.eventLoop().schedule(new Runnable() {
            @Override
            public void run() {
                runMtuProbe();
            }
        }, Math.max(1L, delayMillis), TimeUnit.MILLISECONDS);
    }

    private void runMtuProbe() {
        Channel channel = mtuProbeChannel;
        InetSocketAddress peer = mtuProbePeer;
        if (closed || channel == null || !channel.isActive() || peer == null
                || mtuState == null || mtuState.currentMtu() <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        Udp2rawMtuState.Probe probe = mtuState.nextProbe(now);
        if (probe != null) {
            sendMtuProbe(channel, peer, probe);
        }
        scheduleMtuProbe(mtuState.nextDelayMillis(System.currentTimeMillis()));
    }

    private void sendMtuProbe(Channel channel, InetSocketAddress peer, Udp2rawMtuState.Probe probe) {
        ByteBuf encoded = null;
        try {
            encoded = Udp2rawMtuProbeSupport.encodeProbe(channel.alloc(), sessionSecret,
                    sessionHi, sessionLo, probe.seq, probe.mtu);
            Sockets.UdpWriteResult result = Sockets.writeUdp(channel,
                    new Sockets.UdpMtuProbeDatagramPacket(encoded, peer),
                    "socks.udp2raw", "flow=mtu-probe,side=server");
            encoded = null;
            if (result != Sockets.UdpWriteResult.ACCEPTED) {
                DiagnosticMetrics.record("socks.udp2raw.mtu.probe.count", 1D,
                        "action=local-drop,side=server,result=" + result);
            }
        } catch (Throwable e) {
            Bytes.release(encoded);
            DiagnosticMetrics.record("socks.udp2raw.mtu.probe.count", 1D,
                    "action=encode-fail,side=server");
        }
    }

    private static final class PeerGuard {
        private static final long AUTH_FAIL_WINDOW_MILLIS = 10_000L;

        private final AtomicLong rateWindow = new AtomicLong();
        private long authFailWindowStartMillis;
        private int authFailures;
        private volatile long blockedUntilMillis;

        boolean isBlocked(long now) {
            return blockedUntilMillis > now;
        }

        boolean allowPacket(long now, int limitPerSecond, int burst) {
            int limit = burst > 0 ? burst : limitPerSecond;
            long second = (now / 1000L) & 0xffffffffL;
            for (;;) {
                long current = rateWindow.get();
                long currentSecond = current >>> 32;
                int count = (int) current;
                long next;
                if (currentSecond != second) {
                    next = (second << 32) | 1L;
                } else {
                    if (count >= limit) {
                        return false;
                    }
                    next = (second << 32) | ((count + 1L) & 0xffffffffL);
                }
                if (rateWindow.compareAndSet(current, next)) {
                    return true;
                }
            }
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
