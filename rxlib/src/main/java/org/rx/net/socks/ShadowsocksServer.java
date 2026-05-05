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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

//@Slf4j
public class ShadowsocksServer extends Disposable implements EventPublisher<ShadowsocksServer> {
    public static final TripleAction<ShadowsocksServer, SocksContext> DIRECT_ROUTER = (s, e) -> e.setUpstream(new Upstream(e.getFirstDestination()));
    public final Delegate<ShadowsocksServer, SocksContext> onTcpRoute = Delegate.create(DIRECT_ROUTER),
            onUdpRoute = Delegate.create(DIRECT_ROUTER);
    @Getter
    final ShadowsocksConfig config;
    final ServerBootstrap bootstrap;
    final List<Channel> tcpChannels;
    final List<Channel> udpChannels;
    final AtomicInteger activeChannels = new AtomicInteger();
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
        EventExecutorGroup cryptoGroup = config.isUseDedicatedCryptoGroup() ? sharedCryptoGroup() : null;

        bootstrap = Sockets.serverBootstrap(config, channel -> {
            activeChannels.incrementAndGet();
            channel.closeFuture().addListener(f -> activeChannels.decrementAndGet());
            ICrypto _crypto = ICrypto.get(config.getMethod(), config.getPassword());
            _crypto.setForUdp(false);
            channel.attr(ShadowsocksConfig.CIPHER).set(_crypto);

            if (config.getReadTimeoutSeconds() > 0 || config.getWriteTimeoutSeconds() > 0) {
                channel.pipeline().addLast(new ProxyChannelIdleHandler(config.getReadTimeoutSeconds(), config.getWriteTimeoutSeconds()));
            }
            if (config.isUseDedicatedCryptoGroup()) {
                channel.pipeline().addLast(cryptoGroup, CipherCodec.DEFAULT, new SSProtocolCodec(), SSTcpProxyHandler.DEFAULT);
            } else {
                channel.pipeline().addLast(CipherCodec.DEFAULT, new SSProtocolCodec(), SSTcpProxyHandler.DEFAULT);
            }
        });
        tcpChannels = Sockets.bindChannels(bootstrap.attr(ShadowsocksConfig.SVR, this), config.getServerEndpoint(), config);

        //udp server
        udpChannels = Sockets.bindChannels(Sockets.udpBootstrap(config, ctx -> {
            ICrypto _crypto = ICrypto.get(config.getMethod(), config.getPassword());
            _crypto.setForUdp(true);
            ctx.attr(ShadowsocksConfig.CIPHER).set(_crypto);

            if (config.isUseDedicatedCryptoGroup()) {
                ctx.pipeline().addLast(cryptoGroup, CipherCodec.DEFAULT, new SSProtocolCodec(), SSUdpProxyHandler.DEFAULT);
            } else {
                ctx.pipeline().addLast(CipherCodec.DEFAULT, new SSProtocolCodec(), SSUdpProxyHandler.DEFAULT);
            }
        }).attr(ShadowsocksConfig.SVR, this), config.getServerEndpoint(), config);
    }

    /**
     * Returns the number of currently accepted TCP frontend channels.
     * UDP server channels and UDP outbound relay channels are intentionally not included.
     */
    public int activeChannelCount() {
        return activeChannels.get();
    }

    @Override
    protected void dispose() {
        for (Channel channel : tcpChannels) {
            channel.close();
        }
        Sockets.closeBootstrap(bootstrap);
        for (Channel channel : udpChannels) {
            channel.close();
        }
    }
}
