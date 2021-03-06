package org.rx.net.support;

import lombok.Data;
import org.rx.net.Sockets;

import java.io.Serializable;
import java.net.InetSocketAddress;

@Data
public class UnresolvedEndpoint implements Serializable {
    private static final long serialVersionUID = -1870762625355971485L;

    public static UnresolvedEndpoint valueOf(String value) {
        InetSocketAddress endpoint = Sockets.parseEndpoint(value);
        return new UnresolvedEndpoint(endpoint.getHostString(), endpoint.getPort());
    }

    private final String host;
    private final int port;

    public InetSocketAddress toSocketAddress() {
        return new InetSocketAddress(host, port);
    }

    @Override
    public String toString() {
        return String.format("%s:%s", host, port);
    }
}
