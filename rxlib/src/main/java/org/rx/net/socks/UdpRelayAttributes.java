package org.rx.net.socks;

import io.netty.util.AttributeKey;

final class UdpRelayAttributes {
    static final AttributeKey<Boolean> ATTR_CLIENT_LOCKED = AttributeKey.valueOf("udpClientLocked");

    private UdpRelayAttributes() {
    }
}
