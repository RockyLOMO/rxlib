package org.rx.net.rpc;

import org.rx.bean.DateTime;
import org.rx.core.Extends;
import org.rx.net.rpc.protocol.HandshakePacket;

import java.net.InetSocketAddress;

public interface RpcClientMeta extends Extends {
    boolean isConnected();

    InetSocketAddress getRemoteEndpoint();

    DateTime getConnectedTime();

    HandshakePacket getHandshakePacket();
}
