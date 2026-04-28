package org.rx.net.dns;

import lombok.Getter;
import lombok.NonNull;

import java.net.InetSocketAddress;

@Getter
public class DoHEndpoint {
    public static final String DEFAULT_PATH = "/dns-query";
    public static final int DEFAULT_WEIGHT = 100;
    public static final int DEFAULT_TIMEOUT_MILLIS = 5000;

    final InetSocketAddress address;
    final String tlsHost;
    final String path;
    final int weight;
    final int timeoutMillis;

    public DoHEndpoint(@NonNull InetSocketAddress address, String tlsHost, String path) {
        this(address, tlsHost, path, DEFAULT_WEIGHT, DEFAULT_TIMEOUT_MILLIS);
    }

    public DoHEndpoint(@NonNull InetSocketAddress address, String tlsHost, String path, int weight, int timeoutMillis) {
        if (address.isUnresolved()) {
            throw new IllegalArgumentException("DoH endpoint address must be resolved IP literal");
        }
        this.address = address;
        this.tlsHost = tlsHost;
        this.path = path == null || path.isEmpty() ? DEFAULT_PATH : path;
        this.weight = weight > 0 ? weight : DEFAULT_WEIGHT;
        this.timeoutMillis = timeoutMillis > 0 ? timeoutMillis : DEFAULT_TIMEOUT_MILLIS;
    }

    public boolean isTls() {
        return tlsHost != null && !tlsHost.isEmpty();
    }
}
