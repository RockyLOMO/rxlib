package org.rx.net.socks;

import io.netty.util.AttributeKey;

import java.net.InetSocketAddress;

final class UdpRelayAttributes {
    static final AttributeKey<Boolean> ATTR_CLIENT_LOCKED = AttributeKey.valueOf("udpClientLocked");
    static final AttributeKey<InetSocketAddress> ATTR_CLIENT_ORIGIN_ADDR = AttributeKey.valueOf("udpClientOriginAddr");

    private UdpRelayAttributes() {
    }
}
