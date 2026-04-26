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
import org.rx.net.transport.protocol.UdpMessage;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class HybridServer implements AutoCloseable, EventPublisher<HybridServer> {
    public final Delegate<HybridServer, NEventArgs<HybridSession>> onConnected = Delegate.create();
    public final Delegate<HybridServer, NEventArgs<HybridSession>> onDisconnected = Delegate.create();
    public final Delegate<HybridServer, NEventArgs<Object>> onReceive = Delegate.create();
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

    public HybridServer(HybridConfig config) {
        this.config = requireConfig(config);
        ensureTcpCodec(this.config.getTcpServerConfig(), this.config.getUdpClientConfig().getCodec());
        udpClient = new UdpClient(this.config.getUdpBindPort(), this.config.getUdpClientConfig());
        tcpServer = new TcpServer(this.config.getTcpServerConfig());
        tcpServer.onReceive.combine(this::onTcpReceive);
        tcpServer.onDisconnected.combine((s, e) -> removeSession(e.getClient()));
        tcpServer.onError.combine((s, e) -> raiseEventAsync(onError, new NEventArgs<Throwable>(e.getValue())));
        tcpServer.onClosed.combine((s, e) -> raiseEventAsync(onClosed, EventArgs.EMPTY));
        udpClient.onReceive.combine(this::onUdpReceive);
    }

    public void start() {
        tcpServer.start();
    }

    public boolean isStarted() {
        return tcpServer.isStarted();
    }

    public Map<InetSocketAddress, TcpClient> tcpClients() {
        return tcpServer.getClients();
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

        DefaultHybridSession session = new DefaultHybridSession(config, udpClient, client, metrics,
                hello.sessionId, hello.udpToken, "server-" + udpClient.getLocalEndpoint().getPort());
        session.onReceive().combine((s, e) -> raiseEventAsync(onReceive, e));
        sessionsById.put(session.sessionId, session);
        sessionsByTcp.put(client, session);
        client.send(newAck(session));
        raiseEventAsync(onConnected, new NEventArgs<HybridSession>(session));

        InetSocketAddress endpoint = HybridClient.udpEndpoint(hello.udpLocalHost, hello.udpLocalPort, client.getRemoteEndpoint());
        Tasks.runAsync(() -> session.startDirectProbe(endpoint));
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
        if (session == null) {
            return;
        }
        sessionsById.remove(session.sessionId, session);
        session.detach("tcp-disconnected");
        raiseEventAsync(onDisconnected, new NEventArgs<HybridSession>(session));
    }

    @Override
    public void close() {
        for (DefaultHybridSession session : sessionsById.values()) {
            session.close();
        }
        sessionsById.clear();
        sessionsByTcp.clear();
        tcpServer.close();
        udpClient.close();
        onReceive.purge();
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
