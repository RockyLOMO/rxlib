package org.rx.net.support;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.cache.MemoryCache;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

@Slf4j
public final class EndpointTracer {
    public static final EndpointTracer TCP = new EndpointTracer();
    public static final EndpointTracer UDP = new EndpointTracer();

    static final InetSocketAddress unknownEp = Sockets.newAnyEndpoint(0);
    final MemoryCache<SocketAddress, InetSocketAddress> index = new MemoryCache<>(b -> b.maximumSize(5000));

    public void link(InetSocketAddress inboundRemoteAddr, Channel outbound) {
        if (inboundRemoteAddr == null) {
            return;
        }
        InetSocketAddress source = index.get(inboundRemoteAddr, k -> inboundRemoteAddr);

        SocketAddress outboundLocalAddr = outbound.localAddress();
        if (outboundLocalAddr == null) {
            return;
        }
        index.put(outboundLocalAddr, source);
        outbound.closeFuture().addListener(f -> index.remove(outboundLocalAddr));
//        log.info("EpTracer link {} <- ({} -> {})", Sockets.toString(source), inbound, outbound);
    }

    public InetSocketAddress head(Channel inbound) {
        return head(inbound != null ? inbound.remoteAddress() : null);
    }

    public InetSocketAddress head(SocketAddress remoteAddr) {
        InetSocketAddress source = find(remoteAddr);
        if (source == null) {
            source = unknownEp;
        }
        log.info("EpTracer head {} <- ({})", Sockets.toString(source), remoteAddr);
        return source;
    }

    public InetSocketAddress find(SocketAddress remoteAddr) {
        if (remoteAddr == null) {
            return null;
        }

        return index.get(remoteAddr);
    }
}
