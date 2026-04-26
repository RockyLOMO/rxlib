package org.rx.net.transport.hybrid;

import lombok.Getter;
import org.rx.core.Delegate;
import org.rx.core.EventArgs;
import org.rx.core.EventPublisher;
import org.rx.core.NEventArgs;
import org.rx.core.Tasks;
import org.rx.core.ThreadPool;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.transport.DefaultTcpClient;
import org.rx.net.transport.TcpClient;
import org.rx.net.transport.TcpClientConfig;
import org.rx.net.transport.UdpClient;
import org.rx.net.transport.protocol.UdpMessage;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

public final class HybridClient implements AutoCloseable, EventPublisher<HybridClient> {
    static final ThreadPool SCHEDULER = new ThreadPool(Sockets.ReactorNames.RPC);

    public final Delegate<HybridClient, EventArgs> onConnected = Delegate.create();
    public final Delegate<HybridClient, EventArgs> onDisconnected = Delegate.create();
    public final Delegate<HybridClient, NEventArgs<InetSocketAddress>> onReconnecting = Delegate.create();
    public final Delegate<HybridClient, EventArgs> onReconnected = Delegate.create();
    public final Delegate<HybridClient, NEventArgs<HybridSession>> onSessionReady = Delegate.create();
    public final Delegate<HybridClient, NEventArgs<Object>> onSend = Delegate.create();
    public final Delegate<HybridClient, NEventArgs<Object>> onReceive = Delegate.create();
    public final Delegate<HybridClient, NEventArgs<Throwable>> onError = Delegate.create();

    @Getter
    private final HybridConfig config;
    @Getter
    private final HybridMetrics metrics = new HybridMetrics();
    private final UdpClient udpClient;
    private final DefaultTcpClient tcpClient;
    private volatile DefaultHybridSession session;

    @Override
    public ThreadPool asyncScheduler() {
        return SCHEDULER;
    }

    public HybridClient(HybridConfig config) {
        this.config = requireConfig(config);
        ensureTcpCodec(this.config.getTcpClientConfig(), this.config.getUdpClientConfig().getCodec());
        udpClient = new UdpClient(this.config.getUdpBindPort(), this.config.getUdpClientConfig());
        tcpClient = new DefaultTcpClient(this.config.getTcpClientConfig());
        tcpClient.onReceive.combine(this::onTcpReceive);
        tcpClient.onConnected.combine((s, e) -> raiseEventAsync(onConnected, EventArgs.EMPTY));
        tcpClient.onDisconnected.combine((s, e) -> {
            DefaultHybridSession current = session;
            if (current != null) {
                current.detach("tcp-disconnected");
            }
            raiseEventAsync(onDisconnected, EventArgs.EMPTY);
        });
        tcpClient.onReconnecting.combine((s, e) -> {
            NEventArgs<InetSocketAddress> args = new NEventArgs<InetSocketAddress>(e.getValue());
            raiseEvent(onReconnecting, args);
            e.setValue(args.getValue());
        });
        tcpClient.onReconnected.combine((s, e) -> {
            DefaultHybridSession created = createSession();
            sendHello(created);
            raiseEventAsync(onReconnected, EventArgs.EMPTY);
        });
        tcpClient.onError.combine((s, e) -> raiseEventAsync(onError, e));
        udpClient.onReceive.combine(this::onUdpReceive);
    }

    public void connect(InetSocketAddress serverEndpoint) throws TimeoutException {
        Future<Void> future = connectAsync(serverEndpoint);
        try {
            future.get(config.getTcpClientConfig().getConnectTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw InvalidException.sneaky(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException) {
                throw (TimeoutException) cause;
            }
            throw InvalidException.sneaky(cause);
        } catch (java.util.concurrent.TimeoutException e) {
            throw e;
        }
    }

    public Future<Void> connectAsync(InetSocketAddress serverEndpoint) {
        Future<Void> connectFuture = tcpClient.connectAsync(serverEndpoint);
        CompletableFuture<Void> promise = new CompletableFuture<>();
        if (connectFuture instanceof CompletableFuture) {
            @SuppressWarnings("unchecked")
            CompletableFuture<Void> tcpPromise = (CompletableFuture<Void>) connectFuture;
            tcpPromise.whenComplete((r, e) -> completeConnect(promise, e));
            return promise;
        }
        Tasks.runAsync(() -> {
            try {
                connectFuture.get();
                completeConnect(promise, null);
            } catch (Throwable e) {
                completeConnect(promise, e);
            }
        });
        return promise;
    }

    void completeConnect(CompletableFuture<Void> promise, Throwable error) {
        if (error != null) {
            promise.completeExceptionally(error);
            return;
        }
        DefaultHybridSession created = createSession();
        sendHello(created);
        promise.complete(null);
    }

    public boolean isConnected() {
        DefaultHybridSession current = session;
        return current != null && current.isConnected();
    }

    public boolean isSessionReady() {
        return isConnected();
    }

    public HybridSession session() {
        return session;
    }

    public void resetHandlers() {
        onConnected.purge();
        onDisconnected.purge();
        onReconnecting.purge();
        onReconnected.purge();
        onSessionReady.purge();
        onReceive.purge();
        onError.purge();
        onSend.purge();
    }

    public void send(Object packet) {
        send(packet, HybridSendOptions.DEFAULT);
    }

    public void send(Object packet, HybridSendOptions options) {
        NEventArgs<Object> args = new NEventArgs<Object>(packet);
        raiseEvent(onSend, args);
        if (args.isCancel()) {
            return;
        }
        currentSession().send(args.getValue(), options);
    }

    void onTcpReceive(TcpClient sender, NEventArgs<Object> e) {
        Object packet = e.getValue();
        DefaultHybridSession current = session;
        if (packet instanceof HybridHelloAck) {
            handleHelloAck((HybridHelloAck) packet);
            return;
        }
        if (packet instanceof HybridRouteUpdate) {
            return;
        }
        if (packet instanceof HybridTcpData && current != null) {
            current.receiveTcp((HybridTcpData) packet);
        }
    }

    void handleHelloAck(HybridHelloAck ack) {
        DefaultHybridSession current = session;
        if (current == null || ack.version != 1 || ack.sessionId != current.sessionId || ack.udpToken != current.token) {
            metrics.illegalUdpDrops.increment();
            return;
        }
        current.remotePeerId = ack.peerId;
        InetSocketAddress endpoint = udpEndpoint(ack.udpObservedHost, ack.udpObservedPort, tcpClient.getRemoteEndpoint());
        current.startDirectProbe(endpoint);
    }

    void onUdpReceive(UdpClient sender, NEventArgs<UdpMessage> e) {
        UdpMessage message = e.getValue();
        Object packet = message.packet();
        DefaultHybridSession current = session;
        if (current == null) {
            metrics.illegalUdpDrops.increment();
            return;
        }
        if (packet instanceof HybridUdpProbe) {
            HybridUdpProbe probe = (HybridUdpProbe) packet;
            if (current.acceptProbe(message.remoteAddress, probe.sessionId, probe.token)) {
                current.sendProbeAck(message.remoteAddress, probe);
            }
            return;
        }
        if (packet instanceof HybridUdpProbeAck) {
            HybridUdpProbeAck ack = (HybridUdpProbeAck) packet;
            current.acceptProbe(message.remoteAddress, ack.sessionId, ack.token);
            return;
        }
        if (packet instanceof HybridUdpData) {
            current.receiveUdp(message.remoteAddress, (HybridUdpData) packet);
        }
    }

    HybridHello newHello(DefaultHybridSession session) {
        HybridHello hello = new HybridHello();
        hello.version = 1;
        hello.sessionId = session.sessionId;
        hello.peerId = session.peerId;
        hello.udpLocalHost = host(udpClient.getLocalEndpoint());
        hello.udpLocalPort = udpClient.getLocalEndpoint().getPort();
        hello.udpToken = session.token;
        hello.udpSmallPacketThresholdBytes = config.getUdpSmallPacketThresholdBytes();
        hello.udpProbeTimeoutMillis = config.getUdpProbeTimeoutMillis();
        hello.udpFragmentPayloadBytes = udpClient.getMaxFragmentPayloadBytes();
        hello.udpMaxFragmentCount = udpClient.getMaxFragmentCount();
        hello.enableUdpDirect = config.isEnableUdpDirect();
        hello.enableUdpHolePunch = config.isEnableUdpHolePunch();
        return hello;
    }

    DefaultHybridSession createSession() {
        DefaultHybridSession old = session;
        if (old != null) {
            old.detach("session-replaced");
        }
        return new DefaultHybridSession(config, udpClient, tcpClient, metrics,
                positiveRandomLong(), positiveRandomLong(), "client-" + udpClient.getLocalEndpoint().getPort());
    }

    void bindSession(DefaultHybridSession created) {
        created.onReceive().combine((s, e) -> raiseEventAsync(onReceive, e));
    }

    void sendHello(DefaultHybridSession created) {
        bindSession(created);
        session = created;
        tcpClient.send(newHello(created));
        raiseEventAsync(onSessionReady, new NEventArgs<HybridSession>(created));
    }

    DefaultHybridSession currentSession() {
        DefaultHybridSession current = session;
        if (current == null) {
            throw new InvalidException("Hybrid client has no session");
        }
        return current;
    }

    @Override
    public void close() {
        DefaultHybridSession current = session;
        session = null;
        if (current != null) {
            current.close();
        } else {
            tcpClient.close();
        }
        udpClient.close();
        onConnected.purge();
        onDisconnected.purge();
        onReconnecting.purge();
        onReconnected.purge();
        onSessionReady.purge();
        onReceive.purge();
        onError.purge();
        onSend.purge();
    }

    static HybridConfig requireConfig(HybridConfig config) {
        if (config == null) {
            throw new InvalidException("HybridConfig is null");
        }
        if (config.getTcpClientConfig() == null) {
            config.setTcpClientConfig(new TcpClientConfig());
        }
        if (config.getUdpClientConfig() == null) {
            throw new InvalidException("UdpClientConfig is null");
        }
        return config;
    }

    static void ensureTcpCodec(TcpClientConfig config, org.rx.net.transport.UdpClientCodec udpCodec) {
        if (config.getCodec() == null) {
            config.setCodec(new HybridTcpChannelCodec(udpCodec));
        }
    }

    static String host(InetSocketAddress endpoint) {
        InetAddress address = endpoint.getAddress();
        return address == null ? endpoint.getHostString() : address.getHostAddress();
    }

    static InetSocketAddress udpEndpoint(String host, int port, InetSocketAddress tcpRemoteEndpoint) {
        if (port <= 0) {
            return null;
        }
        InetSocketAddress endpoint = new InetSocketAddress(host, port);
        InetAddress address = endpoint.getAddress();
        if (address != null && address.isAnyLocalAddress() && tcpRemoteEndpoint != null) {
            return new InetSocketAddress(tcpRemoteEndpoint.getAddress(), port);
        }
        return endpoint;
    }

    static long positiveRandomLong() {
        long value;
        do {
            value = ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE;
        } while (value == 0);
        return value;
    }
}
