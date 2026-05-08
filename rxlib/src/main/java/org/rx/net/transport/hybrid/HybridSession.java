package org.rx.net.transport.hybrid;

import io.netty.util.AttributeKey;
import org.rx.core.Delegate;
import org.rx.core.EventPublisher;
import org.rx.core.NEventArgs;

import java.net.InetSocketAddress;

public interface HybridSession extends AutoCloseable, EventPublisher<HybridSession> {
    default long sessionId() {
        return 0L;
    }

    default String peerId() {
        return null;
    }

    default String remotePeerId() {
        return null;
    }

    boolean isConnected();

    HybridRouteState routeState();

    InetSocketAddress tcpRemoteEndpoint();

    default InetSocketAddress tcpLocalEndpoint() {
        return null;
    }

    InetSocketAddress udpRemoteEndpoint();

    default long lastHeartbeatMillis() {
        return 0L;
    }

    default long heartbeatRttMillis() {
        return -1L;
    }

    void send(Object packet);

    void send(Object packet, HybridSendOptions options);

    HybridSendResult sendWithResult(Object packet, HybridSendOptions options);

    <T> T attr(AttributeKey<T> key);

    <T> void attr(AttributeKey<T> key, T value);

    boolean hasAttr(AttributeKey<?> key);

    default boolean hasAttr(String name) {
        return hasAttr(AttributeKey.valueOf(name));
    }

    default <T> T attr(String name) {
        return attr(AttributeKey.<T>valueOf(name));
    }

    default <T> void attr(String name, T value) {
        attr(AttributeKey.<T>valueOf(name), value);
    }

    Delegate<HybridSession, NEventArgs<Object>> onSend();

    Delegate<HybridSession, NEventArgs<Object>> onReceive();
}
