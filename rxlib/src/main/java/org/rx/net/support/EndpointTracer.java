package org.rx.net.support;

import io.netty.channel.Channel;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.Tuple;
import org.rx.core.Cache;

import java.net.SocketAddress;

@Slf4j
public final class EndpointTracer {
    @RequiredArgsConstructor
    @EqualsAndHashCode
    static class LinkedData {
        final SocketAddress head;
//        final List<SocketAddress> nodes = new CopyOnWriteArrayList<>();
    }

    final Cache<Tuple<String, SocketAddress>, LinkedData> index = Cache.getInstance(Cache.MEMORY_CACHE);

    Tuple<String, SocketAddress> key(SocketAddress sa) {
        return Tuple.of("EpTracer", sa);
    }

    public void link(Channel inbound, Channel outbound) {
        LinkedData data = index.get(key(outbound.remoteAddress()), k -> new LinkedData(inbound.remoteAddress()));
//        data.nodes.addAll(Arrays.toList(inbound.remoteAddress(), inbound.localAddress(), outbound.localAddress(), outbound.remoteAddress()));
        index.put(key(outbound.localAddress()), data);
        log.info("EpTracer link {} <- {} {}", data.head, inbound, outbound);
    }

    public SocketAddress head(Channel channel) {
        LinkedData data = index.get(key(channel.remoteAddress()));
        SocketAddress head = data == null ? channel.remoteAddress() : data.head;
        log.info("EpTracer head {} <- {}", head, channel);
        return head;
    }
}
