package org.rx.net.rpc;

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
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.*;
import org.rx.core.StringBuilder;
import org.rx.core.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.rpc.packet.HandshakePacket;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import static org.rx.core.App.*;

@Slf4j
@RequiredArgsConstructor
public class RpcServer extends Disposable implements EventTarget<RpcServer> {
    class Handler extends ChannelInboundHandlerAdapter {
        private RpcServerClient client;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
//        super.channelActive(ctx);
            Channel channel = ctx.channel();
            log.debug("serverActive {}", channel.remoteAddress());
            if (clients.size() > config.getCapacity()) {
                log.warn("Force close client, Not enough capacity {}/{}.", clients.size(), config.getCapacity());
                Sockets.closeOnFlushed(channel);
                return;
            }

            clients.add(client = new RpcServerClient(channel.id(), (InetSocketAddress) channel.remoteAddress()));
            client.channel = channel;
            RpcServerEventArgs<Serializable> args = new RpcServerEventArgs<>(client, null);
            raiseEvent(onConnected, args);
            if (args.isCancel()) {
                log.warn("Force close client");
                Sockets.closeOnFlushed(channel);
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Channel channel = ctx.channel();
            log.debug("serverRead {} {}", channel.remoteAddress(), msg.getClass());

            Serializable pack;
            if ((pack = as(msg, Serializable.class)) == null) {
                log.warn("Packet discard");
                Sockets.closeOnFlushed(channel);
                return;
            }
            if (!isConnected(client) || tryAs(pack, HandshakePacket.class, p -> client.setHandshakePacket(p))) {
                log.debug("Handshake: {}", toJsonString(client.getHandshakePacket()));
                return;
            }

            raiseEventAsync(onReceive, new RpcServerEventArgs<>(client, pack));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.debug("serverInactive {}", ctx.channel().remoteAddress());
            clients.remove(client);
            raiseEventAsync(onDisconnected, new RpcServerEventArgs<>(client, null));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Channel channel = ctx.channel();
            App.log("serverCaught {}", channel.remoteAddress(), cause);
            if (!channel.isActive()) {
                return;
            }

            RpcServerEventArgs<Throwable> args = new RpcServerEventArgs<>(client, cause);
            quietly(() -> raiseEvent(onError, args));
            if (args.isCancel()) {
                return;
            }
            Sockets.closeOnFlushed(channel);
        }
    }

    public volatile BiConsumer<RpcServer, RpcServerEventArgs<Serializable>> onConnected, onDisconnected, onSend, onReceive;
    public volatile BiConsumer<RpcServer, RpcServerEventArgs<Throwable>> onError;
    public volatile BiConsumer<RpcServer, EventArgs> onClosed;

    @Getter
    private final RpcServerConfig config;
    private ServerBootstrap bootstrap;
    private SslContext sslCtx;
    private volatile Channel serverChannel;
    private final List<RpcServerClient> clients = new CopyOnWriteArrayList<>();
    @Getter
    private volatile boolean isStarted;

    public List<RpcServerClient> getClients() {
        return Collections.unmodifiableList(clients);
    }

    public RpcServerClient getClient(ChannelId id) {
        checkNotClosed();

        return NQuery.of(clients).single(p -> eq(p.getId(), id));
    }

    @Override
    protected synchronized void freeObjects() {
        isStarted = false;
        Sockets.closeOnFlushed(serverChannel);
        Sockets.closeBootstrap(bootstrap);
        raiseEvent(onClosed, EventArgs.EMPTY);
    }

    @SneakyThrows
    public synchronized void start() {
        if (isStarted) {
            throw new InvalidException("Server has started");
        }

        if (config.isEnableSsl()) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        }
        bootstrap = Sockets.serverBootstrap(config.getWorkThread(), config.getMemoryMode(), channel -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (sslCtx != null) {
                pipeline.addLast(sslCtx.newHandler(channel.alloc()));
            }
            if (config.isEnableCompress()) {
                pipeline.addLast(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP));
                pipeline.addLast(ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
            }
            pipeline.addLast(new ObjectEncoder(),
                    new ObjectDecoder(RpcServerConfig.MAX_OBJECT_SIZE, ClassResolvers.weakCachingConcurrentResolver(RpcServer.class.getClassLoader())),
                    new Handler());
        }).option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeoutMillis());
        InetSocketAddress endpoint = Sockets.getAnyEndpoint(config.getListenPort());
        bootstrap.bind(endpoint).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.error("Listened on port {} fail..", endpoint, f.cause());
                isStarted = false;
                return;
            }
            serverChannel = f.channel();
            log.debug("Listened on port {}..", endpoint);
        });
        isStarted = true;
    }

    public String dump() {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (RpcServerClient client : NQuery.of(clients).orderByDescending(p -> p.getHandshakePacket().getEventVersion())) {
            sb.append("\t%s:%s", client.getRemoteAddress(), client.getId());
            if (i++ % 3 == 0) {
                sb.appendLine();
            }
        }
        return sb.toString();
    }

    protected boolean isConnected(RpcServerClient client) {
        return isStarted && client.channel.isActive();
    }

    public void send(ChannelId id, Serializable pack) {
        send(getClient(id), pack);
    }

    protected void send(@NonNull RpcServerClient client, Serializable pack) {
        checkNotClosed();

        RpcServerEventArgs<Serializable> args = new RpcServerEventArgs<>(client, pack);
        if (args.isCancel() || !isConnected(client)) {
            log.warn("Client disconnected");
            return;
        }

        client.channel.writeAndFlush(pack).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }

    public void closeClient(RpcServerClient client) {
        Sockets.closeOnFlushed(client.channel);
    }
}
