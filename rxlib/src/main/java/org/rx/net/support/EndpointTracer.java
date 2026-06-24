package org.rx.net.support;

import io.netty.channel.Channel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
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
    // 热点路径 UDP relay 每包都会尝试建立 origin 映射，靠该标记保证 link 只执行一次，
    // 避免 closeFuture 监听器无限累积与每包 MemoryCache 写入。
    static final AttributeKey<Boolean> LINKED = AttributeKey.valueOf("epTracerLinked");
    final MemoryCache<SocketAddress, InetSocketAddress> index = new MemoryCache<>(b -> b.maximumSize(5000));

    /**
     * 每个 outbound channel 仅建立一次 origin 映射，供 UDP relay 等高频热点路径调用。
     */
    public void linkOnce(InetSocketAddress inboundRemoteAddr, Channel outbound) {
        if (inboundRemoteAddr == null || outbound == null) {
            return;
        }
        Attribute<Boolean> linked = outbound.attr(LINKED);
        if (Boolean.TRUE.equals(linked.get()) || linked.setIfAbsent(Boolean.TRUE) != null) {
            return;
        }
        link(inboundRemoteAddr, outbound);
    }

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
