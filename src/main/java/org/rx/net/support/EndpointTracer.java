package org.rx.net.support;

import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Cache;

import java.net.SocketAddress;

@Slf4j
public final class EndpointTracer {
    @RequiredArgsConstructor
    static class LinkedData {
        final SocketAddress head;
//        final List<SocketAddress> nodes = new CopyOnWriteArrayList<>();
    }

    final Cache<SocketAddress, LinkedData> index = Cache.getInstance(Cache.MEMORY_CACHE);

    public void link(Channel inbound, Channel outbound) {
        LinkedData data = index.get(outbound.remoteAddress(), k -> new LinkedData(inbound.remoteAddress()));
//        data.nodes.addAll(Arrays.toList(inbound.remoteAddress(), inbound.localAddress(), outbound.localAddress(), outbound.remoteAddress()));
        index.put(outbound.localAddress(), data);
//        log.info("tracer link h={} {}", data.head, String.join(" => ", NQuery.of(data.nodes).select(Object::toString)));
    }

    public SocketAddress head(Channel channel) {
        LinkedData data = index.get(channel.remoteAddress());
        SocketAddress head = data == null ? channel.remoteAddress() : data.head;
//        log.info("tracer head {}{} {}", head, data == null ? "[NOT_FOUND]" : "",
//                data == null ? "" : String.join(" => ", NQuery.of(data.nodes).select(Object::toString)));
        return head;
    }
}
