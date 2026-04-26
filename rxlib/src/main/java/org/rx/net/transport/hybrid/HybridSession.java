package org.rx.net.transport.hybrid;

import org.rx.core.Delegate;
import org.rx.core.EventPublisher;
import org.rx.core.NEventArgs;

import java.net.InetSocketAddress;

public interface HybridSession extends AutoCloseable, EventPublisher<HybridSession> {
    boolean isConnected();

    HybridRouteState routeState();

    InetSocketAddress tcpRemoteEndpoint();

    InetSocketAddress udpRemoteEndpoint();

    void send(Object packet);

    void send(Object packet, HybridSendOptions options);

    Delegate<HybridSession, NEventArgs<Object>> onReceive();
}
