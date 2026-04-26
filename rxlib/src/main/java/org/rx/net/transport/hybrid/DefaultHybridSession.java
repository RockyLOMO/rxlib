package org.rx.net.transport.hybrid;

import org.rx.core.Delegate;
import org.rx.core.NEventArgs;
import org.rx.exception.InvalidException;
import org.rx.net.transport.ClientDisconnectedException;
import org.rx.net.transport.TcpClient;
import org.rx.net.transport.UdpClient;
import org.rx.net.transport.UdpSendResult;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.rx.core.Extends.quietly;

final class DefaultHybridSession implements HybridSession {
    final Delegate<HybridSession, NEventArgs<Object>> onReceive = Delegate.create();
    final HybridConfig config;
    final UdpClient udpClient;
    final TcpClient tcpClient;
    final HybridMetrics metrics;
    final HybridRoutePolicy routePolicy = new HybridRoutePolicy();
    final HybridSequenceWindow sequenceWindow;
    final AtomicLong sequence = new AtomicLong();
    final AtomicInteger udpFailures = new AtomicInteger();
    final AtomicInteger udpInflight = new AtomicInteger();
    final long sessionId;
    final long token;
    final String peerId;
    volatile HybridRouteState routeState = HybridRouteState.TCP_ONLY;
    volatile InetSocketAddress udpRemoteEndpoint;
    volatile boolean closed;

    DefaultHybridSession(HybridConfig config, UdpClient udpClient, TcpClient tcpClient, HybridMetrics metrics,
                         long sessionId, long token, String peerId) {
        this.config = config;
        this.udpClient = udpClient;
        this.tcpClient = tcpClient;
        this.metrics = metrics;
        this.sessionId = sessionId;
        this.token = token;
        this.peerId = peerId;
        this.sequenceWindow = new HybridSequenceWindow(Math.max(config.getUdpAckTimeoutMillis(), config.getUdpProbeTimeoutMillis()) * 2);
    }

    @Override
    public boolean isConnected() {
        return !closed && tcpClient != null && tcpClient.isConnected();
    }

    @Override
    public HybridRouteState routeState() {
        return routeState;
    }

    @Override
    public InetSocketAddress tcpRemoteEndpoint() {
        return tcpClient == null ? null : tcpClient.getRemoteEndpoint();
    }

    @Override
    public InetSocketAddress udpRemoteEndpoint() {
        return udpRemoteEndpoint;
    }

    @Override
    public void send(Object packet) {
        send(packet, HybridSendOptions.DEFAULT);
    }

    @Override
    public void send(Object packet, HybridSendOptions options) {
        ensureOpen();
        HybridSendOptions resolved = options == null ? HybridSendOptions.DEFAULT : options;
        long seq = sequence.incrementAndGet();
        int encodedBytes = encodedSize(packet);
        HybridRoute route = routePolicy.select(routeState, encodedBytes, config.getUdpSmallPacketThresholdBytes(), resolved);
        if (route == HybridRoute.UDP && trySendUdp(seq, packet, resolved)) {
            return;
        }
        sendTcp(seq, packet);
    }

    boolean trySendUdp(long seq, Object packet, HybridSendOptions options) {
        InetSocketAddress endpoint = udpRemoteEndpoint;
        if (endpoint == null || udpInflight.incrementAndGet() > config.getMaxUdpInflightMessagesPerSession()) {
            udpInflight.decrementAndGet();
            metrics.udpWriteDrops.increment();
            degradeToTcpOnly("udp-inflight-overlimit");
            return false;
        }

        int waitAckTimeout = options.getWaitAckTimeoutMillis() >= 0
                ? options.getWaitAckTimeoutMillis() : config.getUdpAckTimeoutMillis();
        HybridUdpData data = new HybridUdpData(sessionId, seq, token, 0, packet);
        try {
            UdpSendResult result = udpClient.sendWithResult(endpoint, data, waitAckTimeout, options.isFullSync());
            metrics.udpSendPackets.increment();
            result.getAckFuture().whenComplete((r, e) -> {
                udpInflight.decrementAndGet();
                if (e == null) {
                    udpFailures.set(0);
                    return;
                }
                onUdpSendFailure(seq, packet, options, e);
            });
            return true;
        } catch (Throwable e) {
            udpInflight.decrementAndGet();
            metrics.udpWriteDrops.increment();
            onUdpSendFailure(seq, packet, options, e);
            return true;
        }
    }

    void onUdpSendFailure(long seq, Object packet, HybridSendOptions options, Throwable error) {
        if (error instanceof TimeoutException || error.getCause() instanceof TimeoutException) {
            metrics.udpAckTimeouts.increment();
        }
        if (udpFailures.incrementAndGet() >= config.getMaxUdpFailuresBeforeFallback()) {
            degradeToTcpOnly("udp-send-failure");
        }
        if (options.isFallbackToTcpOnUdpFailure() && isConnected()) {
            metrics.udpFallbackToTcp.increment();
            sendTcp(seq, packet);
        }
    }

    void sendTcp(long seq, Object packet) {
        ensureOpen();
        tcpClient.send(new HybridTcpData(sessionId, seq, 0, packet));
        metrics.tcpSendPackets.increment();
    }

    void receiveTcp(HybridTcpData data) {
        if (data == null || data.sessionId != sessionId) {
            return;
        }
        if (!sequenceWindow.mark(data.seq)) {
            metrics.duplicateDrops.increment();
            return;
        }
        metrics.tcpReceivePackets.increment();
        raiseEventAsync(onReceive, new NEventArgs<Object>(data.packet));
    }

    void receiveUdp(InetSocketAddress sender, HybridUdpData data) {
        if (!isValidUdpSender(sender, data == null ? 0 : data.token, data == null ? 0 : data.sessionId)) {
            metrics.illegalUdpDrops.increment();
            return;
        }
        if (!sequenceWindow.mark(data.seq)) {
            metrics.duplicateDrops.increment();
            return;
        }
        metrics.udpReceivePackets.increment();
        raiseEventAsync(onReceive, new NEventArgs<Object>(data.packet));
    }

    boolean acceptProbe(InetSocketAddress sender, long packetSessionId, long packetToken) {
        if (packetSessionId != sessionId || packetToken != token || sender == null) {
            metrics.illegalUdpDrops.increment();
            return false;
        }
        udpRemoteEndpoint = sender;
        udpFailures.set(0);
        routeState = HybridRouteState.UDP_READY;
        return true;
    }

    boolean isValidUdpSender(InetSocketAddress sender, long packetToken, long packetSessionId) {
        return packetSessionId == sessionId && packetToken == token && Objects.equals(udpRemoteEndpoint, sender);
    }

    void startDirectProbe(InetSocketAddress endpoint) {
        if (!config.isEnableUdpDirect() || endpoint == null || closed) {
            return;
        }
        udpRemoteEndpoint = endpoint;
        routeState = HybridRouteState.UDP_PROBING;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.getUdpProbeTimeoutMillis());
        for (int i = 0; i < config.getUdpProbeCount(); i++) {
            if (System.nanoTime() > deadline || routeState == HybridRouteState.UDP_READY) {
                return;
            }
            udpClient.send(endpoint, new HybridUdpProbe(sessionId, token, peerId, i, System.nanoTime()), 0, false);
            sleepProbeInterval();
        }
        if (routeState != HybridRouteState.UDP_READY) {
            routeState = HybridRouteState.TCP_ONLY;
        }
    }

    void sendProbeAck(InetSocketAddress endpoint, HybridUdpProbe probe) {
        udpClient.send(endpoint, new HybridUdpProbeAck(sessionId, token, peerId, probe.probeId, probe.timestampNanos), 0, false);
    }

    void degradeToTcpOnly(String reason) {
        routeState = HybridRouteState.TCP_ONLY;
        udpRemoteEndpoint = null;
    }

    void detach(String reason) {
        closed = true;
        degradeToTcpOnly(reason);
        sequenceWindow.clear();
        onReceive.purge();
    }

    int encodedSize(Object packet) {
        try {
            return udpClient.encodedSize(packet);
        } catch (Throwable e) {
            throw InvalidException.sneaky(e);
        }
    }

    void ensureOpen() {
        if (!isConnected()) {
            throw new ClientDisconnectedException(tcpRemoteEndpoint());
        }
    }

    void sleepProbeInterval() {
        try {
            Thread.sleep(Math.max(1, config.getUdpProbeIntervalMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw InvalidException.sneaky(e);
        }
    }

    @Override
    public Delegate<HybridSession, NEventArgs<Object>> onReceive() {
        return onReceive;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        sequenceWindow.clear();
        onReceive.purge();
        if (tcpClient != null) {
            quietly(tcpClient::close);
        }
    }
}
