package org.rx.net.tcp;

import io.netty.channel.ChannelId;
import io.netty.util.Attribute;

import java.io.Serializable;
import java.util.Date;

public interface ITcpClient extends AutoCloseable {
    ChannelId getId();

    String getGroupId();

    boolean isConnected();

    Date getConnectedTime();

    void send(Serializable pack);

    <T> Attribute<T> attr(String name);

    boolean hasAttr(String name);
}
