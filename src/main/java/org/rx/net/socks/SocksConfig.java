package org.rx.net.socks;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.rx.net.socks.upstream.Upstream;
import org.rx.util.function.BiFunc;
import org.rx.util.function.TripleFunc;

import java.io.Serializable;
import java.net.SocketAddress;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SocksConfig implements Serializable {
    private int listenPort = 1080;
    private int acceptors = 2;
    private int backlog = 128;
    private int connectTimeoutMillis = 3000;
    private int readTimeoutSeconds = 60;
    private int writeTimeoutSeconds = 60;
    private BiFunc<SocketAddress, Upstream> upstreamSupplier;
    private TripleFunc<SocketAddress, Upstream, Upstream> upstreamPreReconnect;
}