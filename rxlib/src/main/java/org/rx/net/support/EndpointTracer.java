package org.rx.net.support;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.cache.MemoryCache;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;

@Slf4j
public final class EndpointTracer {
    public static final EndpointTracer TCP = new EndpointTracer();
    public static final EndpointTracer UDP = new EndpointTracer();

    static final InetSocketAddress unknownEp = Sockets.newAnyEndpoint(0);
    final MemoryCache<InetSocketAddress, InetSocketAddress> index = new MemoryCache<>(b -> b.maximumSize(5000));

    public void link(InetSocketAddress inboundRemoteAddress, Channel outbound) {
        InetSocketAddress source = index.get(inboundRemoteAddress, k -> inboundRemoteAddress);
        index.put((InetSocketAddress) outbound.localAddress(), source);
//        log.info("EpTracer link {} <- ({} -> {})", Sockets.toString(source), inbound, outbound);
    }

    public InetSocketAddress head(Channel inbound) {
        return head((InetSocketAddress) inbound.remoteAddress());
    }

    public InetSocketAddress head(InetSocketAddress remoteAddr) {
        InetSocketAddress source = index.get(remoteAddr);
        if (source == null) {
            source = unknownEp;
        }
        log.info("EpTracer head {} <- ({})", Sockets.toString(source), remoteAddr);
        return source;
    }
}
