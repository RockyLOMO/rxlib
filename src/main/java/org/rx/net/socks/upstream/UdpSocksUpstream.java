package org.rx.net.socks.upstream;

import io.netty.channel.*;
import lombok.NonNull;
import org.rx.bean.RandomList;
import org.rx.core.ShellExecutor;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.TransportUtil;
import org.rx.net.socks.SocksConfig;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static org.rx.core.App.tryClose;

public class UdpSocksUpstream extends UdpUpstream implements AutoCloseable {
    final SocksConfig config;
    final RandomList<UpstreamSupport> servers;
    ShellExecutor udp2raw;

    public UdpSocksUpstream(@NonNull UnresolvedEndpoint dstEp, @NonNull SocksConfig config,
                            @NonNull RandomList<UpstreamSupport> servers) {
        super(dstEp);
        this.config = config;
        this.servers = servers;
    }

    @Override
    public void initChannel(Channel channel) {
        super.initChannel(channel);

        UpstreamSupport next = servers.next();
        AuthenticEndpoint svrEp = socksServer = next.getEndpoint();
        TransportUtil.addBackendHandler(channel, config, svrEp.getEndpoint());

//        channel.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
//            @Override
//            public void bind(ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise) throws Exception {
//                super.bind(ctx, localAddress, promise);
//                promise.addListener((ChannelFutureListener) f -> {
//                    if (!f.isSuccess()) {
//                        return;
//                    }
//
//                    int lport = ((InetSocketAddress) f.channel().localAddress()).getPort();
//                    udp2raw = new ShellExecutor(String.format("udp2raw_mp.exe -c -l0.0.0.0:%s -r%s:%s -k \"%s\" --raw-mode faketcp --cipher-mode xor --auth-mode simple",
//                            lport,
//                            svrEp.getEndpoint().getHostString(), svrEp.getEndpoint().getPort() - 1, svrEp.getUsername()))
////                            .start(ShellExecutor.fileOut(String.format("./udp/udp2raw_mp_%s.log", lport)))
//                            .start(ShellExecutor.CONSOLE_OUT)
//                    ;
//                });
//            }
//        });
    }

    @Override
    public void close() throws Exception {
        tryClose(udp2raw);
    }
}
