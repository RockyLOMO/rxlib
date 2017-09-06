package org.rx.socket;

import org.rx.common.InvalidOperationException;

import java.net.InetSocketAddress;

public class SocketException extends InvalidOperationException {
    private InetSocketAddress localAddress;

    public InetSocketAddress getLocalAddress() {
        return localAddress;
    }

    public SocketException(InetSocketAddress localAddress, Exception ex) {
        super(ex);
        this.localAddress = localAddress;
    }
}
