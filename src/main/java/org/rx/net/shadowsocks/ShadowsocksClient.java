package org.rx.net.shadowsocks;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.net.Sockets;
import org.rx.net.shadowsocks.socks5.SocksServerHandler;

import java.util.concurrent.TimeUnit;

@Slf4j
public class ShadowsocksClient extends Disposable {
    final ShadowsocksConfig config;
    final ServerBootstrap tcpBootstrap;

    public ShadowsocksClient(int localSocksPort, ShadowsocksConfig config) {
        this.config = config;
        tcpBootstrap = Sockets.serverBootstrap(ctx -> {
            ctx.pipeline().addLast(new IdleStateHandler(0, 0, SSCommon.TCP_PROXY_IDLE_TIME, TimeUnit.SECONDS) {
                                       @Override
                                       protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
                                           ctx.close();
                                           return super.newIdleStateEvent(state, first);
                                       }
                                   },
                    new SocksPortUnificationServerHandler(), SocksServerHandler.INSTANCE,
                    new SSClientTcpProxyHandler(config));
        });
        tcpBootstrap.bind(localSocksPort).addListener(f -> {
            if (!f.isSuccess()) {
                log.error("Listened on port {} fail", localSocksPort, f.cause());
            }
        });

        Bootstrap udpBootstrap = Sockets.udpBootstrap()
                .option(ChannelOption.SO_RCVBUF, 64 * 1024)// 设置UDP读缓冲区为64k
                .option(ChannelOption.SO_SNDBUF, 64 * 1024)// 设置UDP写缓冲区为64k
                .handler(new ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    protected void initChannel(NioDatagramChannel ctx) throws Exception {
                        ctx.pipeline().addLast(new SSClientUdpProxyHandler(config));
                    }
                });
        udpBootstrap.bind(localSocksPort).addListener(f -> {
            if (!f.isSuccess()) {
                log.error("Udp listened on port {} fail", localSocksPort, f.cause());
            }
        });
    }

    @Override
    protected void freeObjects() {
        Sockets.closeBootstrap(tcpBootstrap);
    }
}
