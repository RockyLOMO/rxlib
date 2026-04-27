package org.rx.net.transport.hybrid;

import lombok.Getter;
import org.rx.core.Delegate;
import org.rx.core.EventArgs;
import org.rx.core.EventPublisher;
import org.rx.core.NEventArgs;
import org.rx.core.Tasks;
import org.rx.exception.InvalidException;
import org.rx.net.transport.TcpClient;
import org.rx.net.transport.TcpServer;
import org.rx.net.transport.TcpServerConfig;
import org.rx.net.transport.UdpClient;
import org.rx.net.transport.protocol.PingPacket;
import org.rx.net.transport.protocol.UdpMessage;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.rx.core.Extends.quietly;

public final class HybridServer implements AutoCloseable, EventPublisher<HybridServer> {
    static final int MAX_PENDING_TCP_DATA_BEFORE_HELLO = 1024;

    public final Delegate<HybridServer, NEventArgs<HybridSession>> onConnected = Delegate.create();
    public final Delegate<HybridServer, NEventArgs<HybridSession>> onDisconnected = Delegate.create();
    public final Delegate<HybridServer, HybridServerEventArgs<Object>> onReceive = Delegate.create();
    public final Delegate<HybridServer, HybridServerEventArgs<Object>> onSend = Delegate.create();
    public final Delegate<HybridServer, HybridServerEventArgs<PingPacket>> onPing = Delegate.create();
    public final Delegate<HybridServer, NEventArgs<Throwable>> onError = Delegate.create();
    public final Delegate<HybridServer, EventArgs> onClosed = Delegate.create();

    @Getter
    private final HybridConfig config;
    @Getter
    private final HybridMetrics metrics = new HybridMetrics();
    private final UdpClient udpClient;
    private final TcpServer tcpServer;
    private final Map<Long, DefaultHybridSession> sessionsById = new ConcurrentHashMap<>();
    private final Map<TcpClient, DefaultHybridSession> sessionsByTcp = new ConcurrentHashMap<>();
    private final Map<TcpClient, Queue<HybridTcpData>> pendingTcpData = new ConcurrentHashMap<>();

    public HybridServer(HybridConfig config) {
        this.config = requireConfig(config);
        ensureTcpCodec(this.config.getTcpServerConfig(), this.config.getUdpClientConfig().getCodec());
        udpClient = new UdpClient(this.config.getUdpBindPort(), this.config.getUdpClientConfig());
        tcpServer = new TcpServer(this.config.getTcpServerConfig());
        tcpServer.onReceive.combine(this::onTcpReceive);
        tcpServer.onDisconnected.combine((s, e) -> removeSession(e.getClient()));
        tcpServer.onPing.combine((s, e) -> {
            DefaultHybridSession session = sessionsByTcp.get(e.getClient());
            if (session != null) {
                raiseEventAsync(onPing, new HybridServerEventArgs<PingPacket>(session, e.getValue()));
            }
        });
        tcpServer.onError.combine((s, e) -> raiseEventAsync(onError, new NEventArgs<Throwable>(e.getValue())));
        tcpServer.onClosed.combine((s, e) -> onTransportClosed());
        udpClient.onReceive.combine(this::onUdpReceive);
    }

    public void start() {
        tcpServer.start();
    }

    public boolean isStarted() {
        return tcpServer.isStarted();
    }

    public TcpServer tcpServer() {
        return tcpServer;
    }

    public Map<InetSocketAddress, TcpClient> tcpClients() {
        return tcpServer.getClients();
    }

    public Map<Long, HybridSession> sessions() {
        Map<Long, HybridSession> snapshot = new HashMap<Long, HybridSession>(sessionsById.size());
        snapshot.putAll(sessionsById);
        return Collections.unmodifiableMap(snapshot);
    }

    public HybridSession getSession(long sessionId) {
        return sessionsById.get(sessionId);
    }

    public boolean closeSession(long sessionId) {
        DefaultHybridSession session = sessionsById.remove(sessionId);
        if (session == null) {
            return false;
        }
        sessionsByTcp.remove(session.tcpClient, session);
        pendingTcpData.remove(session.tcpClient);
        try {
            raiseEvent(onDisconnected, new NEventArgs<HybridSession>(session));
        } finally {
            session.detach("server-close-session", false);
            quietly(session.tcpClient::close);
        }
        return true;
    }

    void onTcpReceive(TcpServer sender, org.rx.net.transport.TcpServerEventArgs<Object> e) {
        Object packet = e.getValue();
        TcpClient client = e.getClient();
        if (packet instanceof HybridHello) {
            handleHello(client, (HybridHello) packet);
            return;
        }
        DefaultHybridSession session = sessionsByTcp.get(client);
        if (packet instanceof HybridTcpData && session != null) {
            session.receiveTcp((HybridTcpData) packet);
            return;
        }
        if (packet instanceof HybridTcpData) {
            Queue<HybridTcpData> queue = pendingTcpData.computeIfAbsent(client, k -> new ConcurrentLinkedQueue<HybridTcpData>());
            if (queue.size() >= MAX_PENDING_TCP_DATA_BEFORE_HELLO) {
                metrics.tcpPendingDrops.increment();
                return;
            }
            queue.offer((HybridTcpData) packet);
        }
    }

    void handleHello(TcpClient client, HybridHello hello) {
        if (hello.version != 1) {
            throw new InvalidException("Unsupported hybrid version {}", hello.version);
        }
        DefaultHybridSession old = sessionsByTcp.get(client);
        if (old != null && old.sessionId == hello.sessionId) {
            client.send(newAck(old));
            return;
        }
        if (old != null) {
            sessionsById.remove(old.sessionId, old);
            old.detach("session-replaced");
        }

        DefaultHybridSession session = new DefaultHybridSession(config, udpClient, client, metrics,
                hello.sessionId, hello.udpToken, "server-" + udpClient.getLocalEndpoint().getPort());
        session.remotePeerId = hello.peerId;
        session.onReceive().combine((s, e) -> raiseEvent(onReceive, new HybridServerEventArgs<Object>(s, e.getValue())));
        session.onSend().combine((s, e) -> {
            HybridServerEventArgs<Object> args = new HybridServerEventArgs<Object>(s, e.getValue());
            raiseEvent(onSend, args);
            e.setValue(args.getValue());
            e.setCancel(args.isCancel());
        });
        DefaultHybridSession conflict = sessionsById.putIfAbsent(session.sessionId, session);
        if (conflict != null) {
            metrics.tcpSessionConflicts.increment();
            session.detach("session-id-conflict", false);
            pendingTcpData.remove(client);
            quietly(client::close);
            return;
        }
        sessionsByTcp.put(client, session);
        client.send(newAck(session));
        raiseEventAsync(onConnected, new NEventArgs<HybridSession>(session));
        drainPendingTcpData(client, session);

        InetSocketAddress endpoint = HybridClient.udpEndpoint(hello.udpLocalHost, hello.udpLocalPort, client.getRemoteEndpoint());
        session.startDirectProbe(endpoint);
    }

    void drainPendingTcpData(TcpClient client, DefaultHybridSession session) {
        Queue<HybridTcpData> queue = pendingTcpData.remove(client);
        if (queue == null) {
            return;
        }
        HybridTcpData data;
        while ((data = queue.poll()) != null) {
            session.receiveTcp(data);
        }
    }

    void onUdpReceive(UdpClient sender, NEventArgs<UdpMessage> e) {
        UdpMessage message = e.getValue();
        Object packet = message.packet();
        if (packet instanceof HybridUdpProbe) {
            HybridUdpProbe probe = (HybridUdpProbe) packet;
            DefaultHybridSession session = sessionsById.get(probe.sessionId);
            if (session != null && session.acceptProbe(message.remoteAddress, probe.sessionId, probe.token)) {
                session.sendProbeAck(message.remoteAddress, probe);
            } else {
                metrics.illegalUdpDrops.increment();
            }
            return;
        }
        if (packet instanceof HybridUdpProbeAck) {
            HybridUdpProbeAck ack = (HybridUdpProbeAck) packet;
            DefaultHybridSession session = sessionsById.get(ack.sessionId);
            if (session != null) {
                session.acceptProbe(message.remoteAddress, ack.sessionId, ack.token);
            } else {
                metrics.illegalUdpDrops.increment();
            }
            return;
        }
        if (packet instanceof HybridUdpData) {
            HybridUdpData data = (HybridUdpData) packet;
            DefaultHybridSession session = sessionsById.get(data.sessionId);
            if (session != null) {
                session.receiveUdp(message.remoteAddress, data);
            } else {
                metrics.illegalUdpDrops.increment();
            }
        }
    }

    HybridHelloAck newAck(DefaultHybridSession session) {
        HybridHelloAck ack = new HybridHelloAck();
        ack.version = 1;
        ack.sessionId = session.sessionId;
        ack.peerId = session.peerId;
        ack.udpObservedHost = HybridClient.host(udpClient.getLocalEndpoint());
        ack.udpObservedPort = udpClient.getLocalEndpoint().getPort();
        ack.udpToken = session.token;
        ack.acceptedUdpSmallPacketThresholdBytes = config.getUdpSmallPacketThresholdBytes();
        ack.enableUdpDirect = config.isEnableUdpDirect();
        ack.enableUdpHolePunch = config.isEnableUdpHolePunch();
        return ack;
    }

    void removeSession(TcpClient client) {
        DefaultHybridSession session = sessionsByTcp.remove(client);
        pendingTcpData.remove(client);
        if (session == null) {
            return;
        }
        sessionsById.remove(session.sessionId, session);
        try {
            raiseEvent(onDisconnected, new NEventArgs<HybridSession>(session));
        } finally {
            session.detach("tcp-disconnected", false);
        }
    }

    void onTransportClosed() {
        for (DefaultHybridSession session : sessionsById.values()) {
            session.detach("server-closed");
            quietly(session.tcpClient::close);
        }
        sessionsById.clear();
        sessionsByTcp.clear();
        pendingTcpData.clear();
        quietly(udpClient::close);
        onSend.purge();
        onReceive.purge();
        onPing.purge();
        onConnected.purge();
        onDisconnected.purge();
        onError.purge();
        raiseEventAsync(onClosed, EventArgs.EMPTY);
    }

    @Override
    public void close() {
        for (DefaultHybridSession session : sessionsById.values()) {
            session.detach("server-closing");
            quietly(session.tcpClient::close);
        }
        sessionsById.clear();
        sessionsByTcp.clear();
        pendingTcpData.clear();
        tcpServer.close();
        udpClient.close();
        onSend.purge();
        onReceive.purge();
        onPing.purge();
    }

    static HybridConfig requireConfig(HybridConfig config) {
        if (config == null) {
            throw new InvalidException("HybridConfig is null");
        }
        if (config.getTcpServerConfig() == null) {
            throw new InvalidException("TcpServerConfig is null");
        }
        if (config.getUdpClientConfig() == null) {
            throw new InvalidException("UdpClientConfig is null");
        }
        return config;
    }

    static void ensureTcpCodec(TcpServerConfig config, org.rx.net.transport.UdpClientCodec udpCodec) {
        if (config.getCodec() == null) {
            config.setCodec(new HybridTcpChannelCodec(udpCodec));
        }
    }
}
