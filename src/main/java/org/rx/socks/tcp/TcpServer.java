package org.rx.socks.tcp;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.core.*;
import org.rx.socks.Sockets;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import static org.rx.core.Contract.*;

@Slf4j
@RequiredArgsConstructor
public class TcpServer<T extends Serializable> extends Disposable implements EventTarget<TcpServer<T>> {
    public volatile BiConsumer<TcpServer<T>, PackEventArgs<T>> onConnected, onDisconnected, onSend, onReceive;
    public volatile BiConsumer<TcpServer<T>, ErrorEventArgs<T>> onError;
    public volatile BiConsumer<TcpServer<T>, EventArgs> onClosed;
    @Getter
    private final TcpConfig config;
    @Getter
    private final Class<T> stateType;
    private ServerBootstrap bootstrap;
    private SslContext sslCtx;
    private final Map<String, Set<SessionClient<T>>> clients = new ConcurrentHashMap<>();
    @Getter
    private volatile boolean isStarted;
    @Getter
    @Setter
    private volatile int capacity = 1000000;

    public int getClientSize() {
        return clients.size();
    }

    @Override
    protected void freeObjects() {
        Sockets.closeBootstrap(bootstrap);
        isStarted = false;
        raiseEvent(onClosed, EventArgs.Empty);
    }

    public void start() {
        start(false);
    }

    @SneakyThrows
    public void start(boolean waitClose) {
        if (isStarted) {
            throw new InvalidOperationException("Server has started");
        }

        if (config.isEnableSsl()) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        }
        bootstrap = Sockets.serverBootstrap(1, config.getWorkThread(), config.getMemoryMode(), null)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeout())
                .childHandler(new TcpChannelInitializer(config, sslCtx == null ? null : channel -> sslCtx.newHandler(channel.alloc())));
        ChannelFuture future = bootstrap.bind(config.getEndpoint()).addListeners(Sockets.FireExceptionThenCloseOnFailure, f -> {
            if (!f.isSuccess()) {
                return;
            }
            isStarted = true;
            log.debug("Listened on port {}..", config.getEndpoint());
        });
        if (waitClose) {
            future.channel().closeFuture().sync();
        }
    }

    protected void addClient(SessionClient<T> client) {
        require(client.getGroupId());

        clients.computeIfAbsent(client.getGroupId(), k -> Collections.synchronizedSet(new HashSet<>())).add(client);
    }

    protected void removeClient(SessionClient<T> client) {
        Set<SessionClient<T>> set = getClients(client.getGroupId());
        if (set.removeIf(p -> p == client) && set.isEmpty()) {
            clients.remove(client.getGroupId());
        }
    }

    public Set<SessionClient<T>> getClients(String groupId) {
        require(groupId);

        Set<SessionClient<T>> set = clients.get(groupId);
        if (CollectionUtils.isEmpty(set)) {
            throw new InvalidOperationException(String.format("GroupId %s not found", groupId));
        }
        return set;
    }

    public SessionClient<T> getClient(String groupId, ChannelId channelId) {
        SessionClient<T> client = NQuery.of(getClients(groupId)).where(p -> p.getId().equals(channelId)).singleOrDefault();
        if (client == null) {
            throw new InvalidOperationException(String.format("GroupId %s with ClientId %s not found", groupId, channelId));
        }
        return client;
    }

    public void send(SessionClient<T> client, Serializable pack) {
        checkNotClosed();
        require(client, pack);

        PackEventArgs<T> args = new PackEventArgs<>(client, pack);
        raiseEvent(onSend, args);
        if (args.isCancel()) {
            return;
        }
        client.ctx.writeAndFlush(pack).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }
}
