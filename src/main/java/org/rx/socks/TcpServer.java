package org.rx.socks;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import org.rx.common.Disposable;

import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class TcpServer<T extends TcpServer.ClientSession> extends Disposable {
    @RequiredArgsConstructor
    public class ClientSession {
        @Getter
        private final SessionChannelId id;
        @Getter
        private final transient ChannelHandlerContext channel;
        @Getter
        @Setter
        private Date connectedTime;
    }

    private int port;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private SslContext sslCtx;
    @Getter
    private volatile boolean isStarted;
    @Getter
    private final Map<SessionId, Set<T>> clients;

    @SneakyThrows
    public TcpServer(int port, boolean ssl) {
        clients = new ConcurrentHashMap<>();
        this.port = port;
        if (ssl) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        }
    }
}
