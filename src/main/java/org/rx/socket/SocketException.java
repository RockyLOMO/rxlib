package org.rx.socket;

import java.net.InetSocketAddress;

public class SocketException extends RuntimeException {
    private InetSocketAddress localAddress;

    public InetSocketAddress getLocalAddress() {
        return localAddress;
    }

    public SocketException(InetSocketAddress localAddress, Exception ex) {
        super(ex);
        this.localAddress = localAddress;
    }
}
