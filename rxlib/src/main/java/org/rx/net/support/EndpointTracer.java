package org.rx.net.support;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Cache;
import org.rx.core.cache.MemoryCache;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

@Slf4j
public final class EndpointTracer {
    static final InetSocketAddress unknownEp = Sockets.newAnyEndpoint(0);
    final Cache<InetSocketAddress, InetSocketAddress> index = Cache.getInstance(MemoryCache.class);

    InetSocketAddress key(SocketAddress sa) {
        return ((InetSocketAddress) sa);
    }

    public void link(Channel inbound, Channel outbound) {
        InetSocketAddress source = index.get(key(inbound.remoteAddress()), k -> (InetSocketAddress) inbound.remoteAddress());
        index.put(key(outbound.localAddress()), source);
        log.info("EpTracer link {} <- ({} -> {})", Sockets.toString(source), inbound, outbound);
    }

    public InetSocketAddress head(Channel inbound) {
        return head((InetSocketAddress) inbound.remoteAddress());
    }

    public InetSocketAddress head(InetSocketAddress remoteAddr) {
        InetSocketAddress source = index.get(key(remoteAddr));
        if (source == null || Sockets.isPrivateIp(source.getAddress())) {
            source = unknownEp;
        }
        log.info("EpTracer head {} <- ({})", Sockets.toString(source), remoteAddr);
        return source;
    }
}
