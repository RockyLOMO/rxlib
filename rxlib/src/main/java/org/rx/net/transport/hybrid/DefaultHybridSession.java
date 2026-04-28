package org.rx.net.transport.hybrid;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.AttributeKey;
import org.rx.core.Delegate;
import org.rx.core.NEventArgs;
import org.rx.core.Tasks;
import org.rx.core.TimeoutFuture;
import org.rx.exception.InvalidException;
import org.rx.net.punch.UdpHolePunchClient;
import org.rx.net.punch.UdpHolePunchSession;
import org.rx.net.transport.ClientDisconnectedException;
import org.rx.net.transport.TcpClient;
import org.rx.net.transport.UdpClient;
import org.rx.net.transport.UdpSendResult;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.rx.core.Extends.quietly;

final class DefaultHybridSession implements HybridSession {
    final Delegate<HybridSession, NEventArgs<Object>> onSend = Delegate.create();
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
    final AtomicInteger probeEpoch = new AtomicInteger();
    final AtomicBoolean metricsClosed = new AtomicBoolean();
    final ConcurrentMap<AttributeKey<?>, Object> attrs = new ConcurrentHashMap<>();
    final long sessionId;
    final long token;
    final String peerId;
    volatile String remotePeerId;
    volatile HybridRouteState routeState = HybridRouteState.TCP_ONLY;
    volatile InetSocketAddress udpRemoteEndpoint;
    volatile boolean closed;
    volatile TimeoutFuture<?> probeFuture;
    volatile CompletableFuture<UdpHolePunchSession> pendingPunchFuture;
    volatile UdpHolePunchClient punchClient;

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
        this.metrics.sessionOpened(routeState);
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
        sendWithResult(packet, options);
    }

    @Override
    public HybridSendResult sendWithResult(Object packet, HybridSendOptions options) {
        ensureOpen();
        NEventArgs<Object> args = new NEventArgs<Object>(packet);
        publishEvent(onSend, args);
        if (args.isCancel()) {
            CompletableFuture<Void> done = CompletableFuture.completedFuture(null);
            return new HybridSendResult(null, null, 0, 0, 0, done, done, true);
        }

        Object resolvedPacket = args.getValue();
        HybridSendOptions resolved = options == null ? HybridSendOptions.DEFAULT : options;
        long seq = sequence.incrementAndGet();
        int encodedBytes = encodedSize(resolvedPacket);
        HybridRoute route = routePolicy.select(routeState, encodedBytes, config.getUdpSmallPacketThresholdBytes(), resolved);
        if (route == HybridRoute.UDP) {
            HybridSendResult result = trySendUdp(seq, resolvedPacket, resolved, encodedBytes);
            if (result != null) {
                return result;
            }
        }
        sendTcp(seq, resolvedPacket, encodedBytes);
        CompletableFuture<Void> done = CompletableFuture.completedFuture(null);
        return new HybridSendResult(route, HybridRoute.TCP, seq, encodedBytes, 0, done, done, false);
    }

    boolean trySendUdp(long seq, Object packet, HybridSendOptions options) {
        return trySendUdp(seq, packet, options, encodedSize(packet)) != null;
    }

    HybridSendResult trySendUdp(long seq, Object packet, HybridSendOptions options, int encodedBytes) {
        InetSocketAddress endpoint = udpRemoteEndpoint;
        if (endpoint == null || udpInflight.incrementAndGet() > config.getMaxUdpInflightMessagesPerSession()) {
            udpInflight.decrementAndGet();
            metrics.udpWriteDrops.increment();
            degradeToTcpOnly("udp-inflight-overlimit");
            return null;
        }

        int waitAckTimeout = options.getWaitAckTimeoutMillis() >= 0
                ? options.getWaitAckTimeoutMillis() : config.getUdpAckTimeoutMillis();
        HybridUdpData data = new HybridUdpData(sessionId, seq, token, 0, packet);
        try {
            UdpSendResult result = udpClient.sendWithResult(endpoint, data, waitAckTimeout, options.isFullSync());
            metrics.udpSendPackets.increment();
            metrics.udpSendBytes.add(result.getEncodedBytes());
            CompletableFuture<Void> ackFuture = new CompletableFuture<Void>();
            HybridSendResult hybridResult = new HybridSendResult(HybridRoute.UDP, HybridRoute.UDP, seq, encodedBytes,
                    result.getFragmentCount(), toCompletableFuture(result.getWriteFuture()), ackFuture, false);
            result.getAckFuture().whenComplete((r, e) -> {
                udpInflight.decrementAndGet();
                if (e == null) {
                    udpFailures.set(0);
                    ackFuture.complete(null);
                    return;
                }
                if (onUdpSendFailure(seq, packet, options, e)) {
                    hybridResult.setActualRoute(HybridRoute.TCP);
                    ackFuture.complete(null);
                } else {
                    ackFuture.completeExceptionally(e);
                }
            });
            return hybridResult;
        } catch (Throwable e) {
            udpInflight.decrementAndGet();
            metrics.udpWriteDrops.increment();
            boolean fallback = onUdpSendFailure(seq, packet, options, e);
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            CompletableFuture<Void> ackFuture = fallback ? CompletableFuture.completedFuture(null) : failed;
            return new HybridSendResult(HybridRoute.UDP, fallback ? HybridRoute.TCP : null, seq, encodedBytes, 0, failed,
                    ackFuture, false);
        }
    }

    boolean onUdpSendFailure(long seq, Object packet, HybridSendOptions options, Throwable error) {
        if (error instanceof TimeoutException || error.getCause() instanceof TimeoutException) {
            metrics.udpAckTimeouts.increment();
        }
        if (udpFailures.incrementAndGet() >= config.getMaxUdpFailuresBeforeFallback()) {
            degradeToTcpOnly("udp-send-failure");
        }
        if (options.isFallbackToTcpOnUdpFailure() && isConnected()) {
            try {
                metrics.udpFallbackToTcp.increment();
                sendTcp(seq, packet, encodedSize(packet));
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    void sendTcp(long seq, Object packet) {
        sendTcp(seq, packet, encodedSize(packet));
    }

    void sendTcp(long seq, Object packet, int encodedBytes) {
        ensureOpen();
        tcpClient.send(new HybridTcpData(sessionId, seq, 0, packet));
        metrics.tcpSendPackets.increment();
        metrics.tcpSendBytes.add(encodedBytes);
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
        publishEvent(onReceive, new NEventArgs<Object>(data.packet));
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
        publishEvent(onReceive, new NEventArgs<Object>(data.packet));
    }

    boolean acceptProbe(InetSocketAddress sender, long packetSessionId, long packetToken) {
        if (packetSessionId != sessionId || packetToken != token || sender == null) {
            metrics.illegalUdpDrops.increment();
            return false;
        }
        udpRemoteEndpoint = sender;
        udpFailures.set(0);
        setRouteState(HybridRouteState.UDP_READY, "udp-probe-accepted");
        return true;
    }

    boolean isValidUdpSender(InetSocketAddress sender, long packetToken, long packetSessionId) {
        return packetSessionId == sessionId && packetToken == token && Objects.equals(udpRemoteEndpoint, sender);
    }

    void startDirectProbe(InetSocketAddress endpoint) {
        startDirectProbe(endpoint, true);
    }

    void startDirectProbe(InetSocketAddress endpoint, boolean enablePunchFallback) {
        if (!config.isEnableUdpDirect() || endpoint == null || closed) {
            return;
        }
        udpRemoteEndpoint = endpoint;
        setRouteState(HybridRouteState.UDP_PROBING, "udp-direct-probe-start");
        int epoch = probeEpoch.incrementAndGet();
        long deadlineMillis = System.currentTimeMillis() + config.getUdpProbeTimeoutMillis();
        scheduleProbe(endpoint, 0, deadlineMillis, enablePunchFallback, epoch);
    }

    void sendProbeAck(InetSocketAddress endpoint, HybridUdpProbe probe) {
        udpClient.send(endpoint, new HybridUdpProbeAck(sessionId, token, peerId, probe.probeId, probe.timestampNanos), 0, false);
    }

    void degradeToTcpOnly(String reason) {
        udpRemoteEndpoint = null;
        setRouteState(HybridRouteState.TCP_ONLY, reason);
    }

    void detach(String reason) {
        detach(reason, true);
    }

    void detach(String reason, boolean clearChannelAttrs) {
        closed = true;
        cancelProbe();
        cancelPunch();
        degradeToTcpOnly(reason);
        sequenceWindow.clear();
        if (clearChannelAttrs) {
            clearMirroredAttrs();
        }
        attrs.clear();
        onSend.purge();
        onReceive.purge();
        markMetricsClosed();
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

    void scheduleProbe(InetSocketAddress endpoint, int probeId, long deadlineMillis, boolean enablePunchFallback, int epoch) {
        long delay = probeId == 0 ? 0 : Math.max(1, config.getUdpProbeIntervalMillis());
        probeFuture = Tasks.setTimeout(() -> {
            if (closed || epoch != probeEpoch.get() || routeState == HybridRouteState.UDP_READY) {
                return;
            }
            if (probeId >= config.getUdpProbeCount() || System.currentTimeMillis() > deadlineMillis) {
                onDirectProbeFailed(enablePunchFallback);
                return;
            }
            udpClient.send(endpoint, new HybridUdpProbe(sessionId, token, peerId, probeId, System.nanoTime()), 0, false);
            scheduleProbe(endpoint, probeId + 1, deadlineMillis, enablePunchFallback, epoch);
        }, delay);
    }

    void onDirectProbeFailed(boolean enablePunchFallback) {
        if (routeState == HybridRouteState.UDP_READY || closed) {
            return;
        }
        if (enablePunchFallback && config.isEnableUdpHolePunch() && config.getRendezvousEndpoint() != null) {
            startHolePunch();
            return;
        }
        degradeToTcpOnly("udp-direct-probe-timeout");
    }

    void startHolePunch() {
        if (closed || remotePeerId == null) {
            degradeToTcpOnly("udp-punch-unavailable");
            return;
        }

        setRouteState(HybridRouteState.UDP_PUNCHING, "udp-punch-start");
        UdpHolePunchClient client = punchClient;
        if (client == null) {
            client = new UdpHolePunchClient(udpClient);
            punchClient = client;
        }
        CompletableFuture<UdpHolePunchSession> future = client.connectAsync(config.getRendezvousEndpoint(),
                Long.toString(sessionId), peerId, config.getUdpProbeTimeoutMillis(), config.getUdpProbeTimeoutMillis());
        pendingPunchFuture = future;
        future.whenComplete((session, error) -> {
            if (pendingPunchFuture != future || closed) {
                if (session != null) {
                    quietly(session::close);
                }
                return;
            }
            pendingPunchFuture = null;
            if (error != null) {
                degradeToTcpOnly("udp-punch-failed");
                return;
            }
            InetSocketAddress endpoint = session.getDirectRemoteEndpoint();
            quietly(session::close);
            if (endpoint == null) {
                degradeToTcpOnly("udp-punch-empty-endpoint");
                return;
            }
            startDirectProbe(endpoint, false);
        });
    }

    void setRouteState(HybridRouteState state, String reason) {
        HybridRouteState old = routeState;
        if (old == state) {
            return;
        }
        routeState = state;
        metrics.routeStateChanged(old, state);
        sendRouteUpdate(reason);
    }

    void sendRouteUpdate(String reason) {
        if (tcpClient == null || !tcpClient.isConnected()) {
            return;
        }
        HybridRouteUpdate update = new HybridRouteUpdate();
        update.sessionId = sessionId;
        update.routeState = routeState;
        InetSocketAddress endpoint = udpRemoteEndpoint;
        if (endpoint != null) {
            update.udpRemoteHost = endpoint.getHostString();
            update.udpRemotePort = endpoint.getPort();
        }
        update.reason = reason;
        quietly(() -> tcpClient.send(update));
    }

    void cancelProbe() {
        probeEpoch.incrementAndGet();
        TimeoutFuture<?> future = probeFuture;
        if (future != null) {
            future.cancel(false);
        }
    }

    void cancelPunch() {
        CompletableFuture<UdpHolePunchSession> future = pendingPunchFuture;
        if (future != null) {
            future.cancel(true);
        }
        UdpHolePunchClient client = punchClient;
        if (client != null) {
            quietly(client::close);
        }
    }

    void markMetricsClosed() {
        if (metricsClosed.compareAndSet(false, true)) {
            metrics.sessionClosed(routeState);
        }
    }

    static CompletableFuture<Void> toCompletableFuture(ChannelFuture future) {
        CompletableFuture<Void> promise = new CompletableFuture<>();
        future.addListener(f -> {
            if (f.isSuccess()) {
                promise.complete(null);
                return;
            }
            Throwable cause = f.cause();
            promise.completeExceptionally(cause == null ? new CompletionException("UDP write failed", null) : cause);
        });
        return promise;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T attr(AttributeKey<T> key) {
        Object value = attrs.get(key);
        if (value != null || attrs.containsKey(key)) {
            return (T) value;
        }
        if (tcpClient != null && tcpClient.getChannel() != null) {
            return tcpClient.attr(key);
        }
        return null;
    }

    @Override
    public <T> void attr(AttributeKey<T> key, T value) {
        if (value == null) {
            attrs.remove(key);
            if (tcpClient != null && tcpClient.isConnected()) {
                tcpClient.attr(key, null);
            }
            return;
        }
        attrs.put(key, value);
        if (tcpClient != null && tcpClient.isConnected()) {
            tcpClient.attr(key, value);
        }
    }

    @Override
    public boolean hasAttr(AttributeKey<?> key) {
        if (attrs.containsKey(key)) {
            return true;
        }
        return tcpClient != null && tcpClient.getChannel() != null && tcpClient.attr(key) != null;
    }

    @Override
    public Delegate<HybridSession, NEventArgs<Object>> onSend() {
        return onSend;
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
        cancelProbe();
        cancelPunch();
        sequenceWindow.clear();
        clearMirroredAttrs();
        attrs.clear();
        onSend.purge();
        onReceive.purge();
        markMetricsClosed();
        if (tcpClient != null) {
            quietly(tcpClient::close);
        }
    }

    @SuppressWarnings("unchecked")
    void clearMirroredAttrs() {
        if (tcpClient == null) {
            return;
        }
        Channel channel = tcpClient.getChannel();
        if (channel == null) {
            return;
        }
        for (AttributeKey<?> key : attrs.keySet()) {
            channel.attr((AttributeKey<Object>) key).set(null);
        }
    }
}
