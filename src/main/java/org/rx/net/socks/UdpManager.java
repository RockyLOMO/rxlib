package org.rx.net.socks;

import io.netty.channel.Channel;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.exception.ApplicationException;
import org.rx.net.socks.upstream.Upstream;
import org.rx.util.function.BiFunc;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.App.tryClose;

@Slf4j
public final class UdpManager {
    @RequiredArgsConstructor
    @Getter
    public static class UdpChannelUpstream {
        private final Channel channel;
        private final Upstream upstream;
    }

    static final Map<InetSocketAddress, UdpChannelUpstream> HOLD = new ConcurrentHashMap<>();

    public static UdpChannelUpstream openChannel(InetSocketAddress incomingEp, BiFunc<InetSocketAddress, UdpChannelUpstream> loadFn) {
        return HOLD.computeIfAbsent(incomingEp, k -> {
            try {
                return loadFn.invoke(k);
            } catch (Throwable e) {
                throw ApplicationException.sneaky(e);
            }
        });
    }

    public static void closeChannel(InetSocketAddress incomingEp) {
        UdpChannelUpstream ctx = HOLD.remove(incomingEp);
        if (ctx == null) {
            log.error("CloseChannel {} <> {}[{}]", ctx.channel, incomingEp, HOLD.keySet());
            return;
        }
        tryClose(ctx.upstream);
        ctx.channel.close();
    }

    public static Socks5AddressType valueOf(InetAddress address) {
        Socks5AddressType addrType;
        if (address instanceof Inet4Address) {
            addrType = Socks5AddressType.IPv4;
        } else if (address instanceof Inet6Address) {
            addrType = Socks5AddressType.IPv6;
        } else {
            addrType = Socks5AddressType.DOMAIN;
        }
        return addrType;
    }
}
