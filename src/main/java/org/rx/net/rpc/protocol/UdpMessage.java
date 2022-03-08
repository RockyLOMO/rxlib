package org.rx.net.rpc.protocol;

import lombok.RequiredArgsConstructor;

import java.io.Serializable;
import java.net.InetSocketAddress;

@RequiredArgsConstructor
public final class UdpMessage implements Serializable {
    private static final long serialVersionUID = -5893732698305228464L;
    public final int id;
    public final AckSync ack;
    public final int alive;

    public final InetSocketAddress remoteAddress;
    public final Object packet;

    public final <T> T packet() {
        return (T) packet;
    }
}
