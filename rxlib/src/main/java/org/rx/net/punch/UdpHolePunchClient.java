package org.rx.net.punch;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.NEventArgs;
import org.rx.exception.InvalidException;
import org.rx.net.transport.ClientDisconnectedException;
import org.rx.net.transport.UdpClient;
import org.rx.net.transport.protocol.UdpMessage;
import org.rx.util.function.TripleAction;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
public final class UdpHolePunchClient implements AutoCloseable {
    @RequiredArgsConstructor
    static final class PendingSession {
        final String roomId;
        final String localPeerId;
        final String remotePeerId;
        final InetSocketAddress rendezvousEndpoint;
        final InetSocketAddress observedLocalEndpoint;
        final InetSocketAddress observedRemoteEndpoint;
        final CompletableFuture<InetSocketAddress> directFuture = new CompletableFuture<>();
    }

    private final TripleAction<UdpClient, NEventArgs<UdpMessage>> receiveHandler = this::onReceive;
    private final ConcurrentMap<String, PendingSession> pendingSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, UdpHolePunchSession> activeSessionsByKey = new ConcurrentHashMap<>();
    private final ConcurrentMap<InetSocketAddress, UdpHolePunchSession> activeSessionsByEndpoint = new ConcurrentHashMap<>();
    @Getter
    private final UdpClient transport;
    private final boolean ownTransport;
    @Getter
    private volatile int rendezvousPollIntervalMillis = 200;
    @Getter
    private volatile int peerWaitTimeoutMillis = 30 * 1000;
    @Getter
    private volatile int directConnectTimeoutMillis = 5 * 1000;
    @Getter
    private volatile int directProbeCount = 8;
    @Getter
    private volatile int directProbeIntervalMillis = 120;
    @Getter
    private volatile int rendezvousRequestTimeoutMillis = 1500;
    private volatile boolean closed;

    public UdpHolePunchClient(int bindPort) {
        this(new UdpClient(bindPort), true);
    }

    public UdpHolePunchClient(UdpClient transport) {
        this(transport, false);
    }

    private UdpHolePunchClient(UdpClient transport, boolean ownTransport) {
        if (transport == null) {
            throw new InvalidException("transport is null");
        }
        this.transport = transport;
        this.ownTransport = ownTransport;
        transport.onReceive.combine(receiveHandler);
    }

    public InetSocketAddress getLocalEndpoint() {
        return transport.getLocalEndpoint();
    }

    public void setRendezvousPollIntervalMillis(int rendezvousPollIntervalMillis) {
        this.rendezvousPollIntervalMillis = requirePositive(rendezvousPollIntervalMillis, "rendezvousPollIntervalMillis");
    }

    public void setPeerWaitTimeoutMillis(int peerWaitTimeoutMillis) {
        this.peerWaitTimeoutMillis = requirePositive(peerWaitTimeoutMillis, "peerWaitTimeoutMillis");
    }

    public void setDirectConnectTimeoutMillis(int directConnectTimeoutMillis) {
        this.directConnectTimeoutMillis = requirePositive(directConnectTimeoutMillis, "directConnectTimeoutMillis");
    }

    public void setDirectProbeCount(int directProbeCount) {
        this.directProbeCount = requirePositive(directProbeCount, "directProbeCount");
    }

    public void setDirectProbeIntervalMillis(int directProbeIntervalMillis) {
        this.directProbeIntervalMillis = requirePositive(directProbeIntervalMillis, "directProbeIntervalMillis");
    }

    public void setRendezvousRequestTimeoutMillis(int rendezvousRequestTimeoutMillis) {
        this.rendezvousRequestTimeoutMillis = requirePositive(rendezvousRequestTimeoutMillis, "rendezvousRequestTimeoutMillis");
    }

    public UdpHolePunchSession connect(InetSocketAddress rendezvousEndpoint, String roomId, String peerId) throws TimeoutException {
        return connect(rendezvousEndpoint, roomId, peerId, peerWaitTimeoutMillis, directConnectTimeoutMillis);
    }

    public UdpHolePunchSession connect(InetSocketAddress rendezvousEndpoint, String roomId, String peerId,
                                       int waitPeerTimeoutMillis, int directTimeoutMillis) throws TimeoutException {
        ensureOpen();
        int waitTimeout = requirePositive(waitPeerTimeoutMillis, "waitPeerTimeoutMillis");
        int directTimeout = requirePositive(directTimeoutMillis, "directTimeoutMillis");

        UdpHolePunchPackets.RendezvousResponse response = awaitPeer(rendezvousEndpoint, roomId, peerId, waitTimeout);
        String key = sessionKey(roomId, response.getPeerId());
        UdpHolePunchSession active = activeSession(key);
        if (active != null) {
            return active;
        }

        InetSocketAddress observedLocalEndpoint = response.observedEndpoint();
        InetSocketAddress observedRemoteEndpoint = response.peerEndpoint();
        PendingSession pending = new PendingSession(roomId, peerId, response.getPeerId(), rendezvousEndpoint,
                observedLocalEndpoint, observedRemoteEndpoint);
        PendingSession old = pendingSessions.putIfAbsent(key, pending);
        if (old != null) {
            throw new InvalidException("Duplicate hole punch connect {} {}", roomId, response.getPeerId());
        }

        try {
            InetSocketAddress directEndpoint = establishDirect(pending, directTimeout);
            UdpHolePunchSession session = new UdpHolePunchSession(this, roomId, peerId, response.getPeerId(),
                    rendezvousEndpoint, pending.observedLocalEndpoint, pending.observedRemoteEndpoint, directEndpoint);
            active = activeSessionsByKey.putIfAbsent(key, session);
            if (active != null && !active.isClosed()) {
                return active;
            }
            if (active != null) {
                activeSessionsByKey.put(key, session);
            }
            activeSessionsByEndpoint.put(directEndpoint, session);
            return session;
        } finally {
            pendingSessions.remove(key, pending);
        }
    }

    void detach(UdpHolePunchSession session) {
        if (session == null) {
            return;
        }
        String key = sessionKey(session.getRoomId(), session.getRemotePeerId());
        activeSessionsByKey.remove(key, session);
        InetSocketAddress directRemoteEndpoint = session.getDirectRemoteEndpoint();
        if (directRemoteEndpoint != null) {
            activeSessionsByEndpoint.remove(directRemoteEndpoint, session);
        }
    }

    private UdpHolePunchPackets.RendezvousResponse awaitPeer(InetSocketAddress rendezvousEndpoint, String roomId,
                                                             String peerId, int waitTimeoutMillis) throws TimeoutException {
        requireEndpoint(rendezvousEndpoint);
        String normalizedRoomId = requireText(roomId, "roomId");
        String normalizedPeerId = requireText(peerId, "peerId");
        long deadline = System.currentTimeMillis() + waitTimeoutMillis;
        TimeoutException lastTimeout = null;

        // 协调端只做地址交换；未匹配到对端时客户端按短周期轮询。
        while (true) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                TimeoutException ex = new TimeoutException(String.format("Wait peer timeout room=%s peer=%s",
                        normalizedRoomId, normalizedPeerId));
                if (lastTimeout != null) {
                    ex.initCause(lastTimeout);
                }
                throw ex;
            }

            int requestTimeout = (int) Math.min(remain, rendezvousRequestTimeoutMillis);
            try {
                UdpHolePunchPackets.RendezvousResponse response = transport.request(rendezvousEndpoint,
                        new UdpHolePunchPackets.RendezvousRequest(normalizedRoomId, normalizedPeerId),
                        UdpHolePunchPackets.RendezvousResponse.class, requestTimeout);
                if (response.getPeerId() != null && response.peerEndpoint() != null) {
                    return response;
                }
            } catch (TimeoutException e) {
                lastTimeout = e;
            }
            sleepQuietly(Math.min(remain, rendezvousPollIntervalMillis));
        }
    }

    private InetSocketAddress establishDirect(PendingSession pending, int directTimeoutMillis) throws TimeoutException {
        if (pending.observedRemoteEndpoint == null) {
            throw new TimeoutException(String.format("Peer %s has no observed endpoint", pending.remotePeerId));
        }
        long deadline = System.currentTimeMillis() + directTimeoutMillis;
        int probes = 0;

        // 在同一本地端口上连续发探测包，驱动 NAT 建立双向映射。
        while (true) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                throw new TimeoutException(String.format("Direct punch timeout room=%s local=%s remote=%s",
                        pending.roomId, pending.localPeerId, pending.remotePeerId));
            }

            if (probes < directProbeCount) {
                probes++;
                transport.send(pending.observedRemoteEndpoint,
                        new UdpHolePunchPackets.DirectProbe(pending.roomId, pending.localPeerId), 0, false);
            }

            try {
                return pending.directFuture.get(Math.min(remain, directProbeIntervalMillis), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw InvalidException.sneaky(e);
            } catch (ExecutionException e) {
                throw InvalidException.sneaky(e.getCause());
            } catch (TimeoutException ignored) {
            }
        }
    }

    private UdpHolePunchSession activeSession(String key) {
        UdpHolePunchSession session = activeSessionsByKey.get(key);
        if (session == null) {
            return null;
        }
        if (!session.isClosed()) {
            return session;
        }
        detach(session);
        return null;
    }

    private void onReceive(UdpClient sender, NEventArgs<UdpMessage> e) {
        UdpMessage message = e.getValue();
        Object packet = message.packet();
        if (packet instanceof UdpHolePunchPackets.DirectProbe) {
            handleProbe(message, (UdpHolePunchPackets.DirectProbe) packet);
            return;
        }
        if (packet instanceof UdpHolePunchPackets.DirectProbeAck) {
            handleProbeAck(message, (UdpHolePunchPackets.DirectProbeAck) packet);
            return;
        }

        UdpHolePunchSession session = activeSessionsByEndpoint.get(message.remoteAddress);
        if (session != null && !session.isClosed()) {
            session.fireReceive(message);
        }
    }

    private void handleProbe(UdpMessage message, UdpHolePunchPackets.DirectProbe packet) {
        String key = sessionKey(packet.getRoomId(), packet.getPeerId());
        PendingSession pending = pendingSessions.get(key);
        if (pending != null) {
            // 首次收到对端探测即认为直连路径可用，并立刻回 ACK 固化映射。
            pending.directFuture.complete(message.remoteAddress);
            transport.send(message.remoteAddress,
                    new UdpHolePunchPackets.DirectProbeAck(packet.getRoomId(), pending.localPeerId), 0, false);
            return;
        }

        UdpHolePunchSession session = activeSession(key);
        if (session == null) {
            return;
        }
        updateDirectEndpoint(session, message.remoteAddress);
        transport.send(message.remoteAddress,
                new UdpHolePunchPackets.DirectProbeAck(packet.getRoomId(), session.getLocalPeerId()), 0, false);
    }

    private void handleProbeAck(UdpMessage message, UdpHolePunchPackets.DirectProbeAck packet) {
        String key = sessionKey(packet.getRoomId(), packet.getPeerId());
        PendingSession pending = pendingSessions.get(key);
        if (pending != null) {
            pending.directFuture.complete(message.remoteAddress);
            return;
        }

        UdpHolePunchSession session = activeSession(key);
        if (session != null) {
            updateDirectEndpoint(session, message.remoteAddress);
        }
    }

    private void updateDirectEndpoint(UdpHolePunchSession session, InetSocketAddress directEndpoint) {
        if (session == null || directEndpoint == null) {
            return;
        }
        InetSocketAddress oldEndpoint = session.getDirectRemoteEndpoint();
        if (Objects.equals(oldEndpoint, directEndpoint)) {
            return;
        }
        if (oldEndpoint != null) {
            activeSessionsByEndpoint.remove(oldEndpoint, session);
        }
        session.updateDirectRemoteEndpoint(directEndpoint);
        activeSessionsByEndpoint.put(directEndpoint, session);
        log.debug("UDP hole punch session {}:{} endpoint {} -> {}", session.getRoomId(), session.getRemotePeerId(),
                oldEndpoint, directEndpoint);
    }

    private void ensureOpen() {
        if (closed) {
            throw new InvalidException("UdpHolePunchClient closed {}", getLocalEndpoint());
        }
    }

    private int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new InvalidException("{} <= 0", name);
        }
        return value;
    }

    private InetSocketAddress requireEndpoint(InetSocketAddress endpoint) {
        if (endpoint == null) {
            throw new InvalidException("endpoint is null");
        }
        return endpoint;
    }

    private String requireText(String value, String name) {
        if (value == null) {
            throw new InvalidException("{} is null", name);
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new InvalidException("{} is empty", name);
        }
        return normalized;
    }

    private String sessionKey(String roomId, String remotePeerId) {
        return requireText(roomId, "roomId") + '\n' + requireText(remotePeerId, "remotePeerId");
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw InvalidException.sneaky(e);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        transport.onReceive.remove(receiveHandler);

        for (UdpHolePunchSession session : new ArrayList<>(activeSessionsByKey.values())) {
            session.close();
        }
        activeSessionsByKey.clear();
        activeSessionsByEndpoint.clear();

        ClientDisconnectedException ex = new ClientDisconnectedException(getLocalEndpoint());
        for (PendingSession pending : pendingSessions.values()) {
            pending.directFuture.completeExceptionally(ex);
        }
        pendingSessions.clear();

        if (ownTransport) {
            transport.close();
        }
    }
}
