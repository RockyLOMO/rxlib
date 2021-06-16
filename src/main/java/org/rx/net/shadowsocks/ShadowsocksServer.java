package org.rx.net.shadowsocks;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFutureListener;
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
import org.rx.net.shadowsocks.ss.*;
import org.rx.net.shadowsocks.ss.obfs.ObfsFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ShadowsocksServer extends Disposable {
    final ServerBootstrap bootstrap;

    public ShadowsocksServer(@NonNull ShadowsocksConfig config) {
        bootstrap = Sockets.serverBootstrap(config, ctx -> {
            ctx.attr(SSCommon.IS_UDP).set(false);

            ICrypto _crypto = CryptoFactory.get(config.getMethod(), config.getPassword());
            _crypto.setForUdp(false);
            ctx.attr(SSCommon.CIPHER).set(_crypto);

            ctx.pipeline().addLast("timeout", new IdleStateHandler(0, 0, SSCommon.TCP_PROXY_IDEL_TIME, TimeUnit.SECONDS) {
                @Override
                protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
                    ctx.close();
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
            ctx.pipeline().addLast("ssCheckerReceive", new SSServerCheckerReceive())
                    .addLast("ssCheckerSend", new SSServerCheckerSend())
                    .addLast("ssCipherCodec", new SSCipherCodec())
                    .addLast("ssProtocolCodec", new SSProtocolCodec())
                    .addLast("ssTcpProxy", new SSServerTcpProxyHandler());
        });
        bootstrap.bind(config.getEndpoint()).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.error("Listened on port {} fail", config.getEndpoint(), f.cause());
            }
        });

        //udp server
        Bootstrap udpBootstrap = Sockets.udpBootstrap(bootstrap.config().group())
                .option(ChannelOption.SO_RCVBUF, 64 * 1024)// 设置UDP读缓冲区为64k
                .option(ChannelOption.SO_SNDBUF, 64 * 1024)// 设置UDP写缓冲区为64k
                .handler(new ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    protected void initChannel(NioDatagramChannel ctx) throws Exception {
                        ctx.attr(SSCommon.IS_UDP).set(true);

                        ICrypto _crypto = CryptoFactory.get(config.getMethod(), config.getPassword());
                        _crypto.setForUdp(true);
                        ctx.attr(SSCommon.CIPHER).set(_crypto);

                        ctx.pipeline().addLast("ssCheckerReceive", new SSServerCheckerReceive())
                                .addLast("ssCheckerSend", new SSServerCheckerSend())
                                .addLast("ssCipherCodec", new SSCipherCodec())
                                .addLast("ssProtocolCodec", new SSProtocolCodec())
                                .addLast("ssUdpProxy", new SSServerUdpProxyHandler());
                    }
                });
        udpBootstrap.bind(config.getEndpoint());
    }

    @Override
    protected void freeObjects() {
        Sockets.closeBootstrap(bootstrap);
    }
}