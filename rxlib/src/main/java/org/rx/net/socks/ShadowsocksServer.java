package org.rx.net.socks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import lombok.Getter;
import lombok.NonNull;
import org.rx.core.Delegate;
import org.rx.core.Disposable;
import org.rx.core.EventPublisher;
import org.rx.net.Sockets;
import org.rx.net.socks.encryption.ICrypto;
import org.rx.net.socks.encryption.SaltHistory;
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
    // UDP cannot retrieve the remainder of a truncated datagram on the next read.
    static final FixedRecvByteBufAllocator UDP_RECV = new FixedRecvByteBufAllocator(65536);
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
        SaltHistory salts = new SaltHistory(config.getMethod(), config.getPassword());
        EventExecutorGroup cryptoGroup = config.isUseDedicatedCryptoGroup() ? sharedCryptoGroup() : null;

        bootstrap = Sockets.serverBootstrap(config, channel -> {
            activeChannels.incrementAndGet();
            channel.closeFuture().addListener(f -> activeChannels.decrementAndGet());
            ICrypto _crypto = salts.newCrypto(false);
            channel.attr(ShadowsocksConfig.CIPHER).set(_crypto);

            if (config.getReadTimeoutSeconds() > 0 || config.getWriteTimeoutSeconds() > 0) {
                channel.pipeline().addLast(new ProxyChannelIdleHandler(config.getReadTimeoutSeconds(), config.getWriteTimeoutSeconds()));
            }
            if (config.isUseDedicatedCryptoGroup()) {
                channel.pipeline().addLast(cryptoGroup, CipherCodec.DEFAULT, new SSProtocolCodec(), SSTcpProxyHandler.DEFAULT);
            } else {
                channel.pipeline().addLast(CipherCodec.DEFAULT, new SSProtocolCodec(), SSTcpProxyHandler.DEFAULT);
            }
        }).option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.ALLOW_HALF_CLOSURE, true);
        tcpChannels = Sockets.bindChannels(bootstrap.attr(ShadowsocksConfig.SVR, this), config.getServerEndpoint(), config);

        //udp server
        udpChannels = Sockets.bindChannels(Sockets.udpBootstrap(config, ctx -> {
            ICrypto _crypto = salts.newCrypto(true);
            ctx.attr(ShadowsocksConfig.CIPHER).set(_crypto);

            if (config.isUseDedicatedCryptoGroup()) {
                ctx.pipeline().addLast(cryptoGroup, CipherCodec.DEFAULT, new SSProtocolCodec(), SSUdpProxyHandler.DEFAULT);
            } else {
                ctx.pipeline().addLast(CipherCodec.DEFAULT, new SSProtocolCodec(), SSUdpProxyHandler.DEFAULT);
            }
        }).option(ChannelOption.RCVBUF_ALLOCATOR, UDP_RECV).attr(ShadowsocksConfig.SVR, this), config.getServerEndpoint(), config);
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
        java.util.List<io.netty.channel.ChannelFuture> futures = new java.util.ArrayList<>(tcpChannels.size() + udpChannels.size());
        for (Channel channel : tcpChannels) {
            futures.add(channel.close());
        }
        for (Channel channel : udpChannels) {
            futures.add(channel.close());
        }
        for (io.netty.channel.ChannelFuture future : futures) {
            if (!future.channel().eventLoop().inEventLoop()) {
                future.awaitUninterruptibly(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        boolean onTcpLoop = false;
        for (Channel channel : tcpChannels) {
            if (channel.eventLoop().inEventLoop()) {
                onTcpLoop = true;
                break;
            }
        }
        Sockets.closeBootstrap(bootstrap);
        io.netty.channel.EventLoopGroup boss = bootstrap.config().group();
        if (boss != null && boss.isShuttingDown() && !onTcpLoop) {
            boss.terminationFuture().awaitUninterruptibly(5, java.util.concurrent.TimeUnit.SECONDS);
        } else {
            awaitSelectorDeregister(tcpChannels);
        }
        awaitSelectorDeregister(udpChannels);
    }

    private static void awaitSelectorDeregister(List<Channel> channels) {
        for (Channel channel : channels) {
            io.netty.channel.EventLoop loop = channel.eventLoop();
            if (!loop.inEventLoop() && !loop.isShuttingDown()) {
                loop.schedule(() -> {
                }, 1, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .awaitUninterruptibly(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }
}
