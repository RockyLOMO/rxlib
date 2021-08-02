package org.rx.net.shadowsocks;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.net.Sockets;
import org.rx.net.shadowsocks.encryption.CryptoFactory;
import org.rx.net.shadowsocks.encryption.ICrypto;
import org.rx.net.shadowsocks.obfs.ObfsFactory;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.util.function.BiFunc;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ShadowsocksServer extends Disposable {
    final ShadowsocksConfig config;
    final ServerBootstrap bootstrap;
    final Channel udpChannel;
    final BiFunc<UnresolvedEndpoint, Upstream> router;

    public ShadowsocksServer(@NonNull ShadowsocksConfig config, BiFunc<UnresolvedEndpoint, Upstream> router) {
        if (router == null) {
            router = SocksProxyServer.DIRECT_ROUTER;
        }
        this.router = router;

        bootstrap = Sockets.serverBootstrap(this.config = config, ctx -> {
            ctx.attr(SSCommon.IS_UDP).set(false);

            ICrypto _crypto = CryptoFactory.get(config.getMethod(), config.getPassword());
            _crypto.setForUdp(false);
            ctx.attr(SSCommon.CIPHER).set(_crypto);

            ctx.pipeline().addLast(new IdleStateHandler(0, 0, config.getTcpIdleTime(), TimeUnit.SECONDS) {
                @Override
                protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
                    log.info("{} timeout {}", ctx.remoteAddress(), state);
                    Sockets.closeOnFlushed(ctx);
                    return super.newIdleStateEvent(state, first);
                }
            });

            //obfs pugin
            List<ChannelHandler> obfsHandlers = ObfsFactory.getObfsHandler(config.getObfs());
            if (obfsHandlers != null) {
                for (ChannelHandler obfsHandler : obfsHandlers) {
                    ctx.pipeline().addLast(obfsHandler);
                }
            }

            //ss
            ctx.pipeline().addLast(new SSServerReceiveHandler(), new SSServerSendHandler(),
                    new SSCipherCodec(), new SSProtocolCodec(),
                    new SSServerTcpProxyHandler(this));
        });
        bootstrap.bind(config.getServerEndpoint()).addListener(Sockets.logBind(config.getServerEndpoint().getPort()));

        //udp server
        udpChannel = Sockets.udpBootstrap(true)
                .option(ChannelOption.SO_RCVBUF, SocksConfig.UDP_BUF_SIZE)// 设置UDP读缓冲区为64k
                .option(ChannelOption.SO_SNDBUF, SocksConfig.UDP_BUF_SIZE)// 设置UDP写缓冲区为64k
                .handler(new ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    protected void initChannel(NioDatagramChannel ctx) throws Exception {
                        ctx.attr(SSCommon.IS_UDP).set(true);

                        ICrypto _crypto = CryptoFactory.get(config.getMethod(), config.getPassword());
                        _crypto.setForUdp(true);
                        ctx.attr(SSCommon.CIPHER).set(_crypto);

                        ctx.pipeline().addLast(new SSServerReceiveHandler(), new SSServerSendHandler(),
                                new SSCipherCodec(), new SSProtocolCodec(),
                                new SSServerUdpProxyHandler());
                    }
                }).bind(config.getServerEndpoint()).channel();
    }

    @Override
    protected void freeObjects() {
        Sockets.closeBootstrap(bootstrap);
        udpChannel.close();
    }
}
