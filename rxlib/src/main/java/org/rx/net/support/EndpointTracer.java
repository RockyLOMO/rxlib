package org.rx.net.support;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.Tuple;
import org.rx.core.Cache;

import java.net.SocketAddress;

@Slf4j
public final class EndpointTracer {
    final Cache<Tuple<String, SocketAddress>, SocketAddress> index = Cache.getInstance(Cache.MEMORY_CACHE);

    Tuple<String, SocketAddress> key(SocketAddress sa) {
        return Tuple.of("EpTracer", sa);
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
