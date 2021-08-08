package org.rx.net.socks.upstream;

import io.netty.channel.Channel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.NonNull;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.UdpManager;
import org.rx.net.support.UnresolvedEndpoint;

public class UdpUpstream extends Upstream {
    public UdpUpstream(@NonNull UnresolvedEndpoint dstEp) {
        super(dstEp);
    }

    @Override
    public void initChannel(Channel channel) {
        SocksProxyServer server = SocksContext.server(channel);
        channel.pipeline().addLast(new IdleStateHandler(0, 0, server.getConfig().getUdpTimeoutSeconds()) {
            @Override
            protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
                UdpManager.closeChannel(SocksContext.attr(channel, SocksContext.UDP_IN_ENDPOINT));
                return super.newIdleStateEvent(state, first);
            }
        });
    }
}
