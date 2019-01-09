package org.rx.socks;

import org.rx.common.SystemException;

import java.net.InetSocketAddress;

public class SocketException extends SystemException {
    private InetSocketAddress localAddress;

    public InetSocketAddress getLocalAddress() {
        return localAddress;
    }

    public SocketException(InetSocketAddress localAddress, Exception ex) {
        super(ex);
        this.localAddress = localAddress;
    }

    public SocketException(InetSocketAddress localAddress, String msg) {
        super(msg);
        this.localAddress = localAddress;
    }
}
