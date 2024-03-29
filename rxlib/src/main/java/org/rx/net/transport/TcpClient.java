package org.rx.net.transport;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.rx.core.Delegate;
import org.rx.core.EventPublisher;
import org.rx.core.NEventArgs;
import org.rx.core.Strings;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

public interface TcpClient extends AutoCloseable, EventPublisher<TcpClient> {
    boolean isConnected();

    InetSocketAddress getRemoteEndpoint();

    void connect(InetSocketAddress remoteEp) throws TimeoutException;

    Future<Void> connectAsync(InetSocketAddress remoteEp);

    void send(Serializable pack);

    Delegate<TcpClient, NEventArgs<Serializable>> onReceive();

    Channel getChannel();

    default boolean hasAttr(String name) {
        return getChannel().hasAttr(AttributeKey.valueOf(name));
    }

    default <T> T attr(String name) {
        return (T) getChannel().attr(AttributeKey.valueOf(name)).get();
    }

    default <T> void attr(String name, T val) {
        if (!isConnected()) {
            throw new ClientDisconnectedException(Strings.EMPTY);
        }

        getChannel().attr(AttributeKey.valueOf(name)).set(val);
    }
}
