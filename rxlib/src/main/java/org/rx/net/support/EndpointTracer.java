package org.rx.net.support;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Cache;
import org.rx.core.cache.MemoryCache;

import java.net.SocketAddress;

import static org.rx.core.Sys.fastCacheKey;

@Slf4j
public final class EndpointTracer {
    final Cache<String, SocketAddress> index = Cache.getInstance(MemoryCache.class);

    String key(SocketAddress sa) {
        return fastCacheKey("EpTrace", sa);
    }

    public void link(Channel inbound, Channel outbound) {
        SocketAddress data = index.get(key(outbound.localAddress()), k -> inbound.remoteAddress());
//        log.info("EpTracer link {} <- {} {}", data.head, inbound, outbound);
    }

    public SocketAddress head(Channel channel) {
        SocketAddress data = index.get(key(channel.remoteAddress()));
        SocketAddress head = data == null ? channel.remoteAddress() : data;
//        log.info("EpTracer head {} <- {}", head, channel);
        return head;
    }
}
