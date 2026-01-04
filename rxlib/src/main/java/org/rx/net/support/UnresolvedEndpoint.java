package org.rx.net.support;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.rx.net.Sockets;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.Objects;

@RequiredArgsConstructor
public class UnresolvedEndpoint implements Serializable {
    private static final long serialVersionUID = -1870762625355971485L;

    public static UnresolvedEndpoint valueOf(String value) {
        return new UnresolvedEndpoint(Sockets.parseEndpoint(value));
    }

    @Getter
    private final String host;
    @Getter
    private final int port;
    private transient InetSocketAddress cache;

    public UnresolvedEndpoint(@NonNull InetSocketAddress socketAddress) {
        host = socketAddress.getHostString();
        port = socketAddress.getPort();
        cache = socketAddress;
    }

    public InetSocketAddress socketAddress() {
        if (cache == null) {
            cache = new InetSocketAddress(host, port);
        }
        return cache;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UnresolvedEndpoint that = (UnresolvedEndpoint) o;
        return port == that.port && Objects.equals(host, that.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port);
    }

    @Override
    public String toString() {
        return String.format("%s:%s", host, port);
    }
}
