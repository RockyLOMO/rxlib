package org.rx.net.transport.hybrid;

import io.netty.util.AttributeKey;
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

    HybridSendResult sendWithResult(Object packet, HybridSendOptions options);

    <T> T attr(AttributeKey<T> key);

    <T> void attr(AttributeKey<T> key, T value);

    boolean hasAttr(AttributeKey<?> key);

    Delegate<HybridSession, NEventArgs<Object>> onSend();

    Delegate<HybridSession, NEventArgs<Object>> onReceive();
}
