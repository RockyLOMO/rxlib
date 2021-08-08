package org.rx.net.socks;

import io.netty.channel.Channel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.core.exception.ApplicationException;
import org.rx.net.socks.upstream.Upstream;
import org.rx.util.function.BiFunc;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.App.tryClose;

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
        tryClose(ctx.upstream);
        ctx.channel.close();
    }
}
