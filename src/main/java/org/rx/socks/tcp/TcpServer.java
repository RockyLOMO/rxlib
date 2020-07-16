package org.rx.socks.tcp;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.core.*;
import org.rx.socks.Sockets;
import org.rx.socks.tcp.packet.HandshakePacket;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import static org.rx.core.Contract.*;

@Slf4j
@RequiredArgsConstructor
public class TcpServer<T extends Serializable> extends Disposable implements EventTarget<TcpServer<T>> {
    //not shareable
    private class PacketServerHandler extends ChannelInboundHandlerAdapter {
        private SessionClient<T> client;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
//        super.channelActive(ctx);
            Channel channel = ctx.channel();
            log.debug("serverActive {}", channel.remoteAddress());
            if (getClientSize() > getCapacity()) {
                log.warn("Not enough capacity");
                Sockets.closeOnFlushed(channel);
                return;
            }
            client = new SessionClient<>(channel, getStateType() == null ? null : Reflects.newInstance(getStateType()));
            PackEventArgs<T> args = new PackEventArgs<>(client, null);
            raiseEvent(onConnected, args);
            if (args.isCancel()) {
                log.warn("Close client");
                Sockets.closeOnFlushed(channel);
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Channel channel = ctx.channel();
            log.debug("serverRead {} {}", channel.remoteAddress(), msg.getClass());

            Serializable pack;
            if ((pack = as(msg, Serializable.class)) == null) {
//                ctx.writeAndFlush(new ErrorPacket("Packet discard"));
                log.warn("Packet discard");
                Sockets.closeOnFlushed(channel);
                return;
            }
            if (tryAs(pack, HandshakePacket.class, p -> {
                client.setGroupId(p.getGroupId());
                addClient(client);
            })) {
                return;
            }

            if (client.getGroupId() == null) {
                log.warn("ServerHandshake fail");
                return;
            }
            //异步避免阻塞
            Tasks.run(() -> raiseEvent(onReceive, new PackEventArgs<>(client, pack)));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.debug("serverInactive {}", ctx.channel().remoteAddress());
            removeClient(client);
            raiseEvent(onDisconnected, new PackEventArgs<>(client, null));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Channel channel = ctx.channel();
            log.error("serverCaught {}", channel.remoteAddress(), cause);
            if (!channel.isActive()) {
                return;
            }

            ErrorEventArgs<T> args = new ErrorEventArgs<>(client, cause);
            try {
                raiseEvent(onError, args);
            } catch (Exception e) {
                log.error("serverCaught", e);
            }
            if (args.isCancel()) {
                return;
            }
            Sockets.closeOnFlushed(channel);
        }
    }

    public volatile BiConsumer<TcpServer<T>, PackEventArgs<T>> onConnected, onDisconnected, onSend, onReceive;
    public volatile BiConsumer<TcpServer<T>, ErrorEventArgs<T>> onError;
    public volatile BiConsumer<TcpServer<T>, EventArgs> onClosed;
    @Getter
    private final TcpConfig config;
    @Getter
    private final Class<T> stateType;
    private ServerBootstrap bootstrap;
    private SslContext sslCtx;
    private volatile Channel channel;
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
    protected synchronized void freeObjects() {
        isStarted = false;
        Sockets.closeOnFlushed(channel, f -> Sockets.closeBootstrap(bootstrap));
        raiseEvent(onClosed, EventArgs.Empty);
    }

    @SneakyThrows
    public synchronized void start() {
        if (isStarted) {
            throw new InvalidOperationException("Server has started");
        }

        if (config.isEnableSsl()) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        }
        bootstrap = Sockets.serverBootstrap(1, config.getWorkThread(), config.getMemoryMode(), channel -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (sslCtx != null) {
                pipeline.addLast(sslCtx.newHandler(channel.alloc()));
            }
            if (config.isEnableCompress()) {
                pipeline.addLast(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP));
                pipeline.addLast(ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
            }
            pipeline.addLast(new ObjectEncoder(),
                    new ObjectDecoder(ClassResolvers.weakCachingConcurrentResolver(TcpConfig.class.getClassLoader())),
                    new PacketServerHandler());
        }).option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeout());
        bootstrap.bind(config.getEndpoint()).addListeners(Sockets.FireExceptionThenCloseOnFailure, (ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                return;
            }
            isStarted = true;
            channel = f.channel();
            log.debug("Listened on port {}..", config.getEndpoint());
        });
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
        if (args.isCancel() || !client.channel.isActive()) {
            return;
        }
        client.channel.writeAndFlush(pack).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }
}
