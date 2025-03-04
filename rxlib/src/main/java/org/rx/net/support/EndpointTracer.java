package org.rx.net.support;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Cache;
import org.rx.core.cache.MemoryCache;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static org.rx.core.Sys.fastCacheKey;

@Slf4j
public final class EndpointTracer {
    static final InetSocketAddress unknownAddr = Sockets.newAnyEndpoint(0);
    final Cache<String, SocketAddress> index = Cache.getInstance(MemoryCache.class);

    String key(SocketAddress sa) {
        return fastCacheKey("EpTrace", sa);
    }

    public void link(Channel inbound, Channel outbound) {
        SocketAddress addr = index.get(key(outbound.localAddress()), k -> inbound.remoteAddress());
//        log.info("EpTracer link {} <- {} {}", data.head, inbound, outbound);
    }

    public SocketAddress head(Channel channel) {
        //inbound channel
        SocketAddress addr = index.get(key(channel.remoteAddress()));
        if (addr == null) {
            //outbound channel
            addr = index.get(key(channel.localAddress()));
        }
        if (addr == null) {
            addr = unknownAddr;
        }
//        log.info("EpTracer head {} <- {}", head, channel);
        return addr;
    }
}
