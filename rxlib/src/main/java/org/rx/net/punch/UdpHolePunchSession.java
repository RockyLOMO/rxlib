package org.rx.net.punch;

import io.netty.channel.ChannelFuture;
import lombok.Getter;
import org.rx.core.Delegate;
import org.rx.core.EventPublisher;
import org.rx.core.NEventArgs;
import org.rx.exception.InvalidException;
import org.rx.net.transport.protocol.UdpMessage;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeoutException;

import static org.rx.core.Extends.quietly;

public final class UdpHolePunchSession implements EventPublisher<UdpHolePunchSession>, AutoCloseable {
    public final Delegate<UdpHolePunchSession, NEventArgs<UdpMessage>> onReceive = Delegate.create();

    private final UdpHolePunchClient owner;
    @Getter
    private final String roomId;
    @Getter
    private final String localPeerId;
    @Getter
    private final String remotePeerId;
    @Getter
    private final InetSocketAddress rendezvousEndpoint;
    @Getter
    private final InetSocketAddress observedLocalEndpoint;
    @Getter
    private final InetSocketAddress observedRemoteEndpoint;
    @Getter
    private volatile InetSocketAddress directRemoteEndpoint;
    @Getter
    private volatile boolean closed;

    UdpHolePunchSession(UdpHolePunchClient owner, String roomId, String localPeerId, String remotePeerId,
                        InetSocketAddress rendezvousEndpoint, InetSocketAddress observedLocalEndpoint,
                        InetSocketAddress observedRemoteEndpoint, InetSocketAddress directRemoteEndpoint) {
        this.owner = owner;
        this.roomId = roomId;
        this.localPeerId = localPeerId;
        this.remotePeerId = remotePeerId;
        this.rendezvousEndpoint = rendezvousEndpoint;
        this.observedLocalEndpoint = observedLocalEndpoint;
        this.observedRemoteEndpoint = observedRemoteEndpoint;
        this.directRemoteEndpoint = directRemoteEndpoint;
    }

    public ChannelFuture send(Object packet) {
        ensureOpen();
        return owner.getTransport().send(directRemoteEndpoint, packet);
    }

    public ChannelFuture send(Object packet, int waitAckTimeoutMillis, boolean fullSync) {
        ensureOpen();
        return owner.getTransport().send(directRemoteEndpoint, packet, waitAckTimeoutMillis, fullSync);
    }

    public <T extends Serializable> T request(Object packet, Class<T> responseType) throws TimeoutException {
        ensureOpen();
        return owner.getTransport().request(directRemoteEndpoint, packet, responseType);
    }

    public <T extends Serializable> T request(Object packet, Class<T> responseType, int timeoutMillis) throws TimeoutException {
        ensureOpen();
        return owner.getTransport().request(directRemoteEndpoint, packet, responseType, timeoutMillis);
    }

    public void reply(UdpMessage request, Serializable packet) {
        ensureOpen();
        owner.getTransport().reply(request, packet);
    }

    public void replyError(UdpMessage request, Throwable error) {
        ensureOpen();
        owner.getTransport().replyError(request, error);
    }

    void fireReceive(UdpMessage message) {
        quietly(() -> raiseEvent(onReceive, new NEventArgs<>(message)));
    }

    void updateDirectRemoteEndpoint(InetSocketAddress directRemoteEndpoint) {
        this.directRemoteEndpoint = directRemoteEndpoint;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        owner.detach(this);
        onReceive.purge();
    }

    private void ensureOpen() {
        if (closed) {
            throw new InvalidException("Hole punch session {}:{} is closed", roomId, remotePeerId);
        }
        if (directRemoteEndpoint == null) {
            throw new InvalidException("Hole punch session {}:{} has no direct endpoint", roomId, remotePeerId);
        }
    }
}
