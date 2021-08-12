package org.rx.net.shadowsocks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.net.MemoryMode;
import org.rx.net.Sockets;
import org.rx.net.shadowsocks.encryption.CryptoFactory;
import org.rx.net.shadowsocks.encryption.ICrypto;
import org.rx.net.shadowsocks.obfs.ObfsFactory;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.util.function.BiFunc;

import java.util.List;

@Slf4j
public class ShadowsocksServer extends Disposable {
    final ShadowsocksConfig config;
    final ServerBootstrap bootstrap;
    final Channel udpChannel;
    final BiFunc<UnresolvedEndpoint, Upstream> router, udpRouter;

    public ShadowsocksServer(ShadowsocksConfig config, BiFunc<UnresolvedEndpoint, Upstream> router) {
        this(config, router, null);
    }

    public ShadowsocksServer(@NonNull ShadowsocksConfig config, BiFunc<UnresolvedEndpoint, Upstream> router, BiFunc<UnresolvedEndpoint, Upstream> udpRouter) {
        if (router == null) {
            router = SocksProxyServer.DIRECT_ROUTER;
        }
        if (udpRouter == null) {
            udpRouter = SocksProxyServer.DIRECT_ROUTER;
        }
        this.router = router;
        this.udpRouter = udpRouter;

        bootstrap = Sockets.serverBootstrap(this.config = config, ctx -> {
            ctx.attr(SSCommon.IS_UDP).set(false);

            ICrypto _crypto = CryptoFactory.get(config.getMethod(), config.getPassword());
            _crypto.setForUdp(false);
            ctx.attr(SSCommon.CIPHER).set(_crypto);

            ctx.pipeline().addLast(new IdleStateHandler(0, 0, config.getIdleTimeout()) {
                @Override
                protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
                    log.info("{} {}", ctx, state);
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

            SocksContext.ssServer(ctx, ShadowsocksServer.this);
            ctx.pipeline().addLast(ServerReceiveHandler.DEFAULT, ServerSendHandler.DEFAULT,
                    CipherCodec.DEFAULT, new ProtocolCodec(), ServerTcpProxyHandler.DEFAULT);
        });
        bootstrap.bind(config.getServerEndpoint()).addListener(Sockets.logBind(config.getServerEndpoint().getPort()));

        //udp server
        udpChannel = Sockets.udpBootstrap(MemoryMode.HIGH, ctx -> {
            ctx.attr(SSCommon.IS_UDP).set(true);

            ICrypto _crypto = CryptoFactory.get(config.getMethod(), config.getPassword());
            _crypto.setForUdp(true);
            ctx.attr(SSCommon.CIPHER).set(_crypto);

            SocksContext.ssServer(ctx, ShadowsocksServer.this);
            ctx.pipeline().addLast(ServerReceiveHandler.DEFAULT, ServerSendHandler.DEFAULT,
                    CipherCodec.DEFAULT, new ProtocolCodec(), ServerUdpProxyHandler.DEFAULT);
        }).bind(config.getServerEndpoint()).channel();
    }

    @Override
    protected void freeObjects() {
        Sockets.closeBootstrap(bootstrap);
        udpChannel.close();
    }
}
