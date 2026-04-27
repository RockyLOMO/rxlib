package org.rx.net.punch;

import lombok.Getter;
import org.rx.core.NEventArgs;
import org.rx.core.Tasks;
import org.rx.net.transport.UdpClient;
import org.rx.net.transport.protocol.UdpMessage;
import org.rx.util.function.TripleAction;

import java.net.InetSocketAddress;
import java.util.concurrent.ScheduledFuture;

public final class UdpHolePunchServer implements AutoCloseable {
    public static final int DEFAULT_SESSION_TTL_MILLIS = 30 * 1000;
    public static final int DEFAULT_CLEANUP_INTERVAL_MILLIS = 5 * 1000;
    public static final int DEFAULT_MAX_PEERS_PER_ROOM = 2;

    private final TripleAction<UdpClient, NEventArgs<UdpMessage>> receiveHandler = this::onReceive;
    private final UdpHolePunchRegistry registry;
    private final ScheduledFuture<?> cleanupFuture;
    @Getter
    private final UdpClient transport;

    public UdpHolePunchServer(int bindPort) {
        this(bindPort, DEFAULT_SESSION_TTL_MILLIS, DEFAULT_CLEANUP_INTERVAL_MILLIS, DEFAULT_MAX_PEERS_PER_ROOM);
    }

    public UdpHolePunchServer(int bindPort, int sessionTtlMillis, int cleanupIntervalMillis, int maxPeersPerRoom) {
        transport = new UdpClient(bindPort);
        registry = new UdpHolePunchRegistry(sessionTtlMillis, maxPeersPerRoom);
        // 定期清理等待中的房间，避免长期占用匹配表。
        cleanupFuture = Tasks.schedulePeriod(() -> registry.cleanup(System.currentTimeMillis()),
                cleanupIntervalMillis, cleanupIntervalMillis);
        transport.onReceive.combine(receiveHandler);
    }

    public InetSocketAddress getLocalEndpoint() {
        return transport.getLocalEndpoint();
    }

    private void onReceive(UdpClient sender, NEventArgs<UdpMessage> e) {
        UdpMessage message = e.getValue();
        Object packet = message.packet();
        if (!(packet instanceof UdpHolePunchPackets.RendezvousRequest)) {
            return;
        }

        try {
            UdpHolePunchPackets.RendezvousRequest request = (UdpHolePunchPackets.RendezvousRequest) packet;
            UdpHolePunchRegistry.Snapshot snapshot = registry.register(request.getRoomId(), request.getPeerId(),
                    message.remoteAddress, System.currentTimeMillis());
            sender.reply(message, new UdpHolePunchPackets.RendezvousResponse(
                    snapshot.observedEndpoint.getHostString(),
                    snapshot.observedEndpoint.getPort(),
                    snapshot.peerId,
                    snapshot.peerEndpoint == null ? null : snapshot.peerEndpoint.getHostString(),
                    snapshot.peerEndpoint == null ? 0 : snapshot.peerEndpoint.getPort(),
                    snapshot.waiting,
                    snapshot.expireAt));
        } catch (Throwable ex) {
            sender.replyError(message, ex);
        }
    }

    @Override
    public void close() {
        transport.onReceive.remove(receiveHandler);
        cleanupFuture.cancel(false);
        transport.close();
    }
}
