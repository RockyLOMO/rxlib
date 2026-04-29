package org.rx.net.support;

import io.netty.util.NetUtil;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.rx.net.Sockets;

import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
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
        cache = Sockets.isValidIp(host) ? socketAddress : InetSocketAddress.createUnresolved(host, port);
    }

    public InetSocketAddress socketAddress() {
        if (cache == null) {
            cache = Sockets.isValidIp(host) ? new InetSocketAddress(parseAddress(host), port)
                    : InetSocketAddress.createUnresolved(host, port);
        }
        return cache;
    }

    private static InetAddress parseAddress(String host) {
        try {
            return InetAddress.getByAddress(NetUtil.createByteArrayFromIpAddressString(host));
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
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
