package org.rx.net.rpc;

import io.netty.channel.Channel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import org.rx.core.Delegate;
import org.rx.core.EventTarget;
import org.rx.core.NEventArgs;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.concurrent.Future;

public interface RpcClient extends AutoCloseable, EventTarget<RpcClient> {
    boolean isConnected();

    InetSocketAddress getRemoteEndpoint();

    void connect(InetSocketAddress remoteEp);

    Future<Void> connectAsync(InetSocketAddress remoteEp);

    void send(Serializable pack);

    Delegate<RpcClient, NEventArgs<Serializable>> onReceive();

    Channel getChannel();

    default boolean hasAttr(String name) {
        if (!isConnected()) {
            throw new ClientDisconnectedException("");
        }

        return getChannel().hasAttr(AttributeKey.valueOf(name));
    }

    default <T> Attribute<T> attr(String name) {
        if (!isConnected()) {
            throw new ClientDisconnectedException("");
        }

        return getChannel().attr(AttributeKey.valueOf(name));
    }
}
