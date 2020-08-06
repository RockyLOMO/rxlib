package org.rx.net.socks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.core.InvalidOperationException;
import org.rx.net.Sockets;

@Slf4j
@RequiredArgsConstructor
public class ProxyServer extends Disposable {
    private final int port;
    @Getter(AccessLevel.PROTECTED)
    @Setter
    private PasswordAuth passwordAuth;
    @Setter
    private FlowLogger flowLogger;
    @Setter
    private ChannelListener channelListener;
    private ServerBootstrap bootstrap;
    @Getter
    private volatile boolean isStarted;

    public boolean isAuth() {
        return passwordAuth != null;
    }

    @Override
    protected void freeObjects() {
        Sockets.closeBootstrap(bootstrap);
    }

    public synchronized void start() {
        if (isStarted) {
            throw new InvalidOperationException("Server has started");
        }

        if (flowLogger == null) {
            flowLogger = new FlowLoggerImpl();
        }
        bootstrap = Sockets.serverBootstrap(true, 2, 0, null, ch -> {
            //流量统计
            ch.pipeline().addLast(ProxyChannelTrafficShapingHandler.PROXY_TRAFFIC,
                    new ProxyChannelTrafficShapingHandler(3000, flowLogger, channelListener)
            );
            //channel超时处理
//            ch.pipeline().addLast(new IdleStateHandler(3, 30, 0))
//                    .addLast(new ChannelIdleHandler());
//            SocksPortUnificationServerHandler
            //Socks5MessagByteBuf
            ch.pipeline().addLast(Socks5ServerEncoder.DEFAULT);
            //sock5 init
            ch.pipeline().addLast(new Socks5InitialRequestDecoder())
                    .addLast(new Socks5InitialRequestHandler(ProxyServer.this));
            if (isAuth()) {
                //socks auth
                ch.pipeline().addLast(new Socks5PasswordAuthRequestDecoder())
                        .addLast(new Socks5PasswordAuthRequestHandler(ProxyServer.this));
            }
            //socks connection
            ch.pipeline().addLast(new Socks5CommandRequestDecoder())
                    .addLast(new Socks5CommandRequestHandler());
        });
        bootstrap.bind(port).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.error("Listened on port {} fail..", port, f.cause());
                f.channel().close();
                return;
            }
            isStarted = true;
        });
    }
}
