package org.rx.net.socks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import lombok.Getter;
import lombok.NonNull;
import org.rx.core.Delegate;
import org.rx.core.Disposable;
import org.rx.core.EventPublisher;
import org.rx.net.Sockets;
import org.rx.net.socks.encryption.ICrypto;
import org.rx.net.socks.upstream.Upstream;
import org.rx.util.function.TripleAction;
import org.rx.core.RxConfig;
import org.rx.core.Constants;

//@Slf4j
public class ShadowsocksServer extends Disposable implements EventPublisher<ShadowsocksServer> {
    public static final TripleAction<ShadowsocksServer, SocksContext> DIRECT_ROUTER = (s, e) -> e.setUpstream(new Upstream(e.getFirstDestination()));
    public final Delegate<ShadowsocksServer, SocksContext> onTcpRoute = Delegate.create(DIRECT_ROUTER),
            onUdpRoute = Delegate.create(DIRECT_ROUTER);
    @Getter
    final ShadowsocksConfig config;
    final ServerBootstrap bootstrap;
    final Channel udpChannel;
    private static EventExecutorGroup SHARED_CRYPTO_GROUP;

    private static synchronized EventExecutorGroup sharedCryptoGroup() {
        if (SHARED_CRYPTO_GROUP == null) {
            int amount = RxConfig.INSTANCE.getNet().getReactorThreadAmount();
            SHARED_CRYPTO_GROUP = new DefaultEventExecutorGroup(amount > 0 ? amount : Constants.CPU_THREADS * 2);
        }
        return SHARED_CRYPTO_GROUP;
    }

    public ShadowsocksServer(@NonNull ShadowsocksConfig config) {
        this.config = config;
        EventExecutorGroup cryptoGroup = sharedCryptoGroup();

        bootstrap = Sockets.serverBootstrap(config, channel -> {
            ICrypto _crypto = ICrypto.get(config.getMethod(), config.getPassword());
            _crypto.setForUdp(false);
            channel.attr(ShadowsocksConfig.CIPHER).set(_crypto);

            channel.pipeline().addLast(cryptoGroup, CipherCodec.DEFAULT, new SSProtocolCodec(), SSTcpProxyHandler.DEFAULT);
        });
        bootstrap.attr(ShadowsocksConfig.SVR, this).bind(config.getServerEndpoint());

        //udp server
        udpChannel = Sockets.udpBootstrap(config, ctx -> {
            ICrypto _crypto = ICrypto.get(config.getMethod(), config.getPassword());
            _crypto.setForUdp(true);
            ctx.attr(ShadowsocksConfig.CIPHER).set(_crypto);

            ctx.pipeline().addLast(cryptoGroup, CipherCodec.DEFAULT, new SSProtocolCodec(), SSUdpProxyHandler.DEFAULT);
        }).attr(ShadowsocksConfig.SVR, this).bind(config.getServerEndpoint()).channel();
    }

    @Override
    protected void dispose() {
        Sockets.closeBootstrap(bootstrap);
        udpChannel.close();
    }
}
