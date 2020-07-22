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
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.DateTime;
import org.rx.bean.Tuple;
import org.rx.core.*;
import org.rx.core.StringBuilder;
import org.rx.socks.Sockets;
import org.rx.socks.tcp.packet.HandshakePacket;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import static org.rx.core.Contract.*;

@Slf4j
@RequiredArgsConstructor
public class TcpServer extends Disposable implements EventTarget<TcpServer> {
    @RequiredArgsConstructor
    private class TcpClient extends Disposable implements ITcpClient {
        protected final Channel channel;
        @Getter
        private final DateTime connectedTime = DateTime.now();
        @Getter
        private volatile String groupId;

        @Override
        public boolean isConnected() {
            return channel.isActive();
        }

        @Override
        public ChannelId getId() {
            return channel.id();
        }

        public InetSocketAddress getRemoteAddress() {
            return (InetSocketAddress) channel.remoteAddress();
        }

        @Override
        protected void freeObjects() {
            Sockets.closeOnFlushed(channel);
        }

        @Override
        public synchronized void send(Serializable pack) {
            if (!raiseSend(this, pack)) {
                return;
            }
            channel.writeAndFlush(pack).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        }

        @Override
        public <T> Attribute<T> attr(String name) {
            return channel.attr(AttributeKey.valueOf(name));
        }

        @Override
        public boolean hasAttr(String name) {
            return channel.hasAttr(AttributeKey.valueOf(name));
        }
    }

    //not shareable
    private class PacketServerHandler extends ChannelInboundHandlerAdapter {
        private TcpClient client;

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

            client = new TcpClient(channel);
            clients.add(client);
            PackEventArgs args = new PackEventArgs(client, null);
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
                if ((client.groupId = p.getGroupId()) == null) {
                    log.warn("Handshake with non groupId");
                }
            })) {
                return;
            }

            //异步避免阻塞
            Tasks.run(() -> raiseEvent(onReceive, new PackEventArgs(client, pack)));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            log.debug("serverInactive {}", ctx.channel().remoteAddress());
            clients.remove(client);
            raiseEvent(onDisconnected, new PackEventArgs(client, null));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Channel channel = ctx.channel();
            log.error("serverCaught {}", channel.remoteAddress(), cause);
            if (!channel.isActive()) {
                return;
            }

            ErrorEventArgs args = new ErrorEventArgs(client, cause);
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

    public volatile BiConsumer<TcpServer, PackEventArgs> onConnected, onDisconnected, onSend, onReceive;
    public volatile BiConsumer<TcpServer, ErrorEventArgs> onError;
    public volatile BiConsumer<TcpServer, EventArgs> onClosed;
    @Getter
    private final TcpConfig config;
    private ServerBootstrap bootstrap;
    private SslContext sslCtx;
    private volatile Channel channel;
    private final List<TcpClient> clients = new CopyOnWriteArrayList<>();
    @Getter
    private volatile boolean isStarted;
    @Getter
    @Setter
    private int capacity = 10000;

    public int getClientSize() {
        return clients.size();
    }

    @Override
    protected synchronized void freeObjects() {
        isStarted = false;
        Sockets.closeOnFlushed(channel);
        Sockets.closeBootstrap(bootstrap);
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
        bootstrap = Sockets.serverBootstrap(config.isTryEpoll(), 1, config.getWorkThread(), config.getMemoryMode(), channel -> {
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
        bootstrap.bind(config.getEndpoint()).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                log.error("Listened on port {} fail..", config.getEndpoint(), f.cause());
                f.channel().close();
                return;
            }
            isStarted = true;
            channel = f.channel();
            log.debug("Listened on port {}..", config.getEndpoint());
        });
    }

    public String dump() {
        StringBuilder sb = new StringBuilder();
        for (Tuple<String, NQuery<TcpClient>> tuple : NQuery.of(clients).groupBy(p -> p.groupId, Tuple::of)) {
            sb.appendLine("%s:", tuple.left);
            int i = 1;
            for (TcpClient client : tuple.right) {
                sb.append("\t%s:%s", client.getRemoteAddress(), client.getId());
                if (i++ % 3 == 0) {
                    sb.appendLine();
                }
            }
        }
        return sb.toString();
    }

    protected boolean raiseSend(TcpClient client, Serializable pack) {
        checkNotClosed();

        PackEventArgs args = new PackEventArgs(client, pack);
        raiseEvent(onSend, args);
        return !args.isCancel() && client.isConnected();
    }

    public List<ITcpClient> getClients(String groupId) {
        checkNotClosed();

        NQuery<TcpClient> q = NQuery.of(clients);
        if (groupId != null) {
            q = q.where(p -> eq(p.groupId, groupId));
            if (!q.any()) {
                throw new InvalidOperationException(String.format("Clients with GroupId %s not found", groupId));
            }
        }
        return q.<ITcpClient>cast().toList();
    }

    public ITcpClient getClient(String groupId, ChannelId channelId) {
        checkNotClosed();

        NQuery<TcpClient> q = NQuery.of(clients);
        if (groupId != null) {
            q = q.where(p -> eq(p.groupId, groupId) && eq(p.getId(), channelId));
            if (!q.any()) {
                throw new InvalidOperationException(String.format("Clients with GroupId %s and ClientId %s not found", groupId, channelId));
            }
        }
        return q.<ITcpClient>cast().first();
    }
}
