package org.rx.net.shadowsocks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Delegate;
import org.rx.core.Disposable;
import org.rx.core.EventPublisher;
import org.rx.net.Sockets;
import org.rx.net.shadowsocks.encryption.CryptoFactory;
import org.rx.net.shadowsocks.encryption.ICrypto;
import org.rx.net.socks.ProxyChannelIdleHandler;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.upstream.Upstream;
import org.rx.util.function.TripleAction;

@Slf4j
public class ShadowsocksServer extends Disposable implements EventPublisher<ShadowsocksServer> {
    public static final TripleAction<ShadowsocksServer, SocksContext> DIRECT_ROUTER = (s, e) -> e.setUpstream(new Upstream(e.getFirstDestination()));
    public final Delegate<ShadowsocksServer, SocksContext> onRoute = Delegate.create(DIRECT_ROUTER),
            onUdpRoute = Delegate.create(DIRECT_ROUTER);
    final ShadowsocksConfig config;
    final ServerBootstrap bootstrap;
    final Channel udpChannel;

    public ShadowsocksServer(@NonNull ShadowsocksConfig config) {
        bootstrap = Sockets.serverBootstrap(this.config = config, channel -> {
            ICrypto _crypto = CryptoFactory.get(config.getMethod(), config.getPassword());
            _crypto.setForUdp(false);
            channel.attr(SSCommon.CIPHER).set(_crypto);

            channel.pipeline().addLast(CipherCodec.DEFAULT, new ProtocolCodec(), ServerTcpProxyHandler.DEFAULT);
        });
        bootstrap.attr(SocksContext.SS_SVR, this).bind(config.getServerEndpoint()).addListener(Sockets.logBind(config.getServerEndpoint().getPort()));

        //udp server
        udpChannel = Sockets.udpBootstrap(config, ctx -> {
            ICrypto _crypto = CryptoFactory.get(config.getMethod(), config.getPassword());
            _crypto.setForUdp(true);
            ctx.attr(SSCommon.CIPHER).set(_crypto);

            ctx.pipeline().addLast(CipherCodec.DEFAULT, new ProtocolCodec(), ServerUdpProxyHandler.DEFAULT);
        }).attr(SocksContext.SS_SVR, this).bind(config.getServerEndpoint()).addListener(Sockets.logBind(config.getServerEndpoint().getPort())).channel();
    }

    @Override
    protected void dispose() {
        Sockets.closeBootstrap(bootstrap);
        udpChannel.close();
    }
}
