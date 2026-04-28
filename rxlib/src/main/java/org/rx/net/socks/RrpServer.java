package org.rx.net.socks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.exception.InvalidException;
import org.rx.io.Serializer;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;
import org.rx.net.TransportFlags;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.rx.core.Extends.eq;
import static org.rx.core.Extends.tryClose;
import static org.rx.net.socks.RrpConfig.*;

@Slf4j
@RequiredArgsConstructor
public class RrpServer extends Disposable {
    static final int MAX_TOKEN_LEN = 256;
    static final int MAX_CHANNEL_ID_LEN = 128;
    static final int MAX_REGISTER_BYTES = 1024 * 1024; // 1 MiB cap for serialized proxies
    static final int MAX_PENDING_FORWARD_BYTES = 1024 * 1024; // 1 MiB cap per remote channel
    static final AttributeKey<RemoteRelayBuffer> ATTR_REMOTE_RELAY_BUF = AttributeKey.valueOf("rRemoteRelayBuf");

    static class RemoteRelayBuffer {
        final Queue<ByteBuf> pendingWrites = new ConcurrentLinkedQueue<>();
        final AtomicInteger pendingBytes = new AtomicInteger();
        final AtomicInteger draining = new AtomicInteger();

        boolean offer(Channel channel, ByteBuf payload) {
            if (!channel.isActive()) {
                io.netty.util.ReferenceCountUtil.release(payload);
                return false;
            }

            int bytes = payload.readableBytes();
            int queuedBytes = pendingBytes.addAndGet(bytes);
            if (queuedBytes > MAX_PENDING_FORWARD_BYTES) {
                pendingBytes.addAndGet(-bytes);
                io.netty.util.ReferenceCountUtil.release(payload);
                log.warn("RrpServer remote channel {} queued bytes {} exceed cap {}, close channel", channel, queuedBytes, MAX_PENDING_FORWARD_BYTES);
                closeChannel(channel);
                return false;
            }

            pendingWrites.offer(payload);
            scheduleDrain(channel);
            return true;
        }

        void scheduleDrain(Channel channel) {
            if (!channel.isActive() || !draining.compareAndSet(0, 1)) {
                return;
            }
            if (channel.eventLoop().inEventLoop()) {
                drain(channel);
                return;
            }
            channel.eventLoop().execute(() -> drain(channel));
        }

        void drain(Channel channel) {
            boolean flushed = false;
            try {
                ByteBuf payload;
                while (channel.isActive() && channel.isWritable() && (payload = pendingWrites.poll()) != null) {
                    pendingBytes.addAndGet(-payload.readableBytes());
                    channel.write(payload).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
                    flushed = true;
                }
                if (flushed) {
                    channel.flush();
                }
            } finally {
                draining.set(0);
                if (channel.isActive() && channel.isWritable() && !pendingWrites.isEmpty()) {
                    scheduleDrain(channel);
                }
            }
        }

        void releaseAll() {
            ByteBuf payload;
            while ((payload = pendingWrites.poll()) != null) {
                io.netty.util.ReferenceCountUtil.release(payload);
            }
            pendingBytes.set(0);
            draining.set(0);
        }
    }

    @RequiredArgsConstructor
    static class RpClientProxy extends Disposable {
        final RrpServer server;
        final RpClient owner;
        final RrpConfig.Proxy p;
        final ServerBootstrap remoteServer;
        final Map<String, Channel> remoteClients = new ConcurrentHashMap<>();
        volatile Channel remoteServerChannel;
        volatile ChannelFuture bindFuture;

        void syncRemoteReadState(boolean clientWritable) {
            for (Channel ch : remoteClients.values()) {
                if (clientWritable) {
                    Sockets.enableAutoRead(ch);
                } else {
                    Sockets.disableAutoRead(ch);
                }
            }
        }

        @Override
        protected void dispose() throws Throwable {
            server.unregisterProxy(this);
            closeChannel(remoteServerChannel);
            ChannelFuture f = bindFuture;
            if (f != null && !f.isDone()) {
                f.cancel(false);
            }
            Sockets.closeBootstrap(remoteServer);
            for (Channel ch : remoteClients.values()) {
                Sockets.closeOnFlushed(ch);
            }
            remoteClients.clear();
        }
    }

    @RequiredArgsConstructor
    static class RpClient extends Disposable {
        final RrpServer server;
        final Channel clientChannel;
        final Map<Integer, RpClientProxy> proxyMap = new ConcurrentHashMap<>();

        @Override
        protected void dispose() throws Throwable {
            Sockets.closeOnFlushed(clientChannel);
            for (RpClientProxy v : proxyMap.values()) {
                v.close();
            }
            proxyMap.clear();
        }

        public RpClientProxy getProxyCtx(int remotePort) {
            RpClientProxy ctx = proxyMap.get(remotePort);
            if (ctx == null) {
                throw new InvalidException("ProxyCtx {} not exist", remotePort);
            }
            return ctx;
        }

        void onClientChannelWritabilityChanged() {
            boolean clientWritable = clientChannel.isWritable();
            for (RpClientProxy proxy : proxyMap.values()) {
                proxy.syncRemoteReadState(clientWritable);
            }
        }

        void onRemoteChannelActive(Channel remoteChannel) {
            if (!clientChannel.isWritable()) {
                Sockets.disableAutoRead(remoteChannel);
            }
        }
    }

    @ChannelHandler.Sharable
    static class RemoteServerHandler extends ChannelInboundHandlerAdapter {
        static final RemoteServerHandler DEFAULT = new RemoteServerHandler();

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            Channel inbound = ctx.channel();
            RpClient rpClient = Sockets.getAttr(inbound, ATTR_SVR_CLI);
            RpClientProxy rpClientProxy = Sockets.getAttr(inbound, ATTR_SVR_PROXY);
            rpClientProxy.remoteClients.put(inbound.id().asShortText(), inbound);
            inbound.attr(ATTR_REMOTE_RELAY_BUF).setIfAbsent(new RemoteRelayBuffer());
            rpClient.onRemoteChannelActive(inbound);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Channel inbound = ctx.channel();
            RpClient rpClient = Sockets.getAttr(inbound, ATTR_SVR_CLI);
            RpClientProxy rpClientProxy = Sockets.getAttr(inbound, ATTR_SVR_PROXY);
            Channel outbound = rpClient.clientChannel;
            //step3
            ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer();
            buf.writeByte(RrpConfig.ACTION_FORWARD);
            buf.writeInt(rpClientProxy.p.remotePort);
            String channelId = inbound.id().asShortText();
            byte[] bytes = channelId.getBytes(StandardCharsets.US_ASCII);
            buf.writeInt(bytes.length);
            buf.writeBytes(bytes);

            outbound.writeAndFlush(Unpooled.wrappedBuffer(buf, (ByteBuf) msg));
            log.debug("RrpServer step3 {}({}) -> clientChannel", rpClient.clientChannel, channelId);
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
            Channel inbound = ctx.channel();
            RemoteRelayBuffer relayBuffer = inbound.attr(ATTR_REMOTE_RELAY_BUF).get();
            if (relayBuffer != null) {
                relayBuffer.scheduleDrain(inbound);
            }
            super.channelWritabilityChanged(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            Channel inbound = ctx.channel();
            RpClientProxy rpClientProxy = Sockets.getAttr(inbound, ATTR_SVR_PROXY);
            RpClient rpClient = Sockets.getAttr(inbound, ATTR_SVR_CLI);
            Channel outbound = rpClient.clientChannel;
            RemoteRelayBuffer relayBuffer = inbound.attr(ATTR_REMOTE_RELAY_BUF).getAndSet(null);
            if (relayBuffer != null) {
                relayBuffer.releaseAll();
            }
            //step7 remoteClose
            ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer();
            buf.writeByte(RrpConfig.ACTION_SYNC_CLOSE);
            buf.writeInt(rpClientProxy.p.remotePort);
            String channelId = inbound.id().asShortText();
            byte[] bytes = channelId.getBytes(StandardCharsets.US_ASCII);
            buf.writeInt(bytes.length);
            buf.writeBytes(bytes);
            outbound.writeAndFlush(buf);

            rpClientProxy.remoteClients.remove(channelId);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Channel inbound = ctx.channel();
            RpClient rpClient = Sockets.getAttr(inbound, ATTR_SVR_CLI);
            Channel outbound = rpClient.clientChannel;
            log.warn("RrpServer error remote RELAY {} => {}[{}] thrown", inbound.remoteAddress(), outbound.localAddress(), outbound.remoteAddress(), cause);
            Sockets.closeOnFlushed(inbound);
        }
    }

    @ChannelHandler.Sharable
    static class ServerHandler extends ChannelInboundHandlerAdapter {
        static final ServerHandler DEFAULT = new ServerHandler();

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            Channel clientChannel = ctx.channel();
            RrpServer server = Sockets.getAttr(clientChannel, ATTR_SVR);
            server.clients.put(clientChannel, new RpClient(server, clientChannel));
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Channel clientChannel = ctx.channel();
            RrpServer server = Sockets.getAttr(clientChannel, ATTR_SVR);
            ByteBuf buf = (ByteBuf) msg;
            try {
                if (buf.readableBytes() < 1) {
                    return;
                }
                byte action = buf.readByte();
                if (action == RrpConfig.ACTION_HEARTBEAT) {
                    RrpConfig.writeHeartbeat(clientChannel);
                    return;
                }
                if (action == RrpConfig.ACTION_REGISTER) {
                    //step2
                    if (buf.readableBytes() < 4) {
                        clientChannel.close();
                        return;
                    }
                    int tokenLen = buf.readInt();
                    if (tokenLen < 0 || tokenLen > MAX_TOKEN_LEN || buf.readableBytes() < tokenLen + 4) {
                        log.warn("RrpServer error Invalid tokenLen {} from {}", tokenLen, clientChannel.remoteAddress());
                        clientChannel.close();
                        return;
                    }
                    String token = tokenLen > 0 ? buf.readCharSequence(tokenLen, StandardCharsets.US_ASCII).toString() : null;
                    if (!eq(token, server.config.getToken())) {
                        log.warn("RrpServer error Invalid token {}", token);
                        clientChannel.close();
                        return;
                    }
                    int len = buf.readInt();
                    if (len < 0 || len > MAX_REGISTER_BYTES || buf.readableBytes() < len) {
                        log.warn("RrpServer error Invalid register len {} from {}", len, clientChannel.remoteAddress());
                        clientChannel.close();
                        return;
                    }
                    byte[] data = new byte[len];
                    buf.readBytes(data, 0, len);
                    List<RrpConfig.Proxy> pList = Serializer.DEFAULT.deserializeFromBytes(data);
                    server.register(clientChannel, pList);
                } else if (action == RrpConfig.ACTION_FORWARD) {
                    //step6
                    if (buf.readableBytes() < 8) {
                        return;
                    }
                    int remotePort = buf.readInt();
                    int idLen = buf.readInt();
                    if (idLen < 0 || idLen > MAX_CHANNEL_ID_LEN || buf.readableBytes() < idLen) {
                        log.warn("RrpServer error Invalid idLen {} from {}", idLen, clientChannel.remoteAddress());
                        Sockets.closeOnFlushed(clientChannel);
                        return;
                    }
                    String channelId = buf.readCharSequence(idLen, StandardCharsets.US_ASCII).toString();
                    RpClient rpClient = server.clients.get(clientChannel);
                    if (rpClient == null) {
                        return;
                    }
                    RpClientProxy proxyCtx;
                    try {
                        proxyCtx = rpClient.getProxyCtx(remotePort);
                    } catch (Exception e) {
                        log.warn("RrpServer error Invalid remotePort {} from {}", remotePort, clientChannel.remoteAddress(), e);
                        return;
                    }
                    Channel remoteChannel = proxyCtx.remoteClients.get(channelId);
                    if (remoteChannel != null) {
                        ByteBuf payload = buf.readRetainedSlice(buf.readableBytes());
                        RemoteRelayBuffer relayBuffer = remoteChannel.attr(ATTR_REMOTE_RELAY_BUF).get();
                        if (relayBuffer == null) {
                            RemoteRelayBuffer newBuffer = new RemoteRelayBuffer();
                            RemoteRelayBuffer oldBuffer = remoteChannel.attr(ATTR_REMOTE_RELAY_BUF).setIfAbsent(newBuffer);
                            relayBuffer = oldBuffer == null ? newBuffer : oldBuffer;
                        }
                        relayBuffer.offer(remoteChannel, payload);
                    }
                    log.debug("RrpServer step6 {}({}) clientChannel -> {}", clientChannel, channelId, remoteChannel);
                } else if (action == RrpConfig.ACTION_SYNC_CLOSE) {
                    //step10
                    if (buf.readableBytes() < 8) {
                        return;
                    }
                    int remotePort = buf.readInt();
                    int idLen = buf.readInt();
                    if (idLen < 0 || idLen > MAX_CHANNEL_ID_LEN || buf.readableBytes() < idLen) {
                        log.warn("RrpServer error Invalid idLen {} from {}", idLen, clientChannel.remoteAddress());
                        return;
                    }
                    String channelId = buf.readCharSequence(idLen, StandardCharsets.US_ASCII).toString();
                    RpClient rpClient = server.clients.get(clientChannel);
                    if (rpClient == null) {
                        return;
                    }
                    RpClientProxy proxyCtx;
                    try {
                        proxyCtx = rpClient.getProxyCtx(remotePort);
                    } catch (Exception e) {
                        log.warn("RrpServer error Invalid remotePort {} from {}", remotePort, clientChannel.remoteAddress(), e);
                        return;
                    }
                    Channel remoteChannel = proxyCtx.remoteClients.get(channelId);
                    log.debug("RrpServer step10 {}({}) clientChannel -> {}", clientChannel, channelId, remoteChannel);
                    Sockets.closeOnFlushed(remoteChannel);
                }
            } finally {
                io.netty.util.ReferenceCountUtil.release(msg);
            }
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
            Channel clientChannel = ctx.channel();
            RrpServer server = Sockets.getAttr(clientChannel, ATTR_SVR);
            RpClient rpClient = server.clients.get(clientChannel);
            if (rpClient != null) {
                rpClient.onClientChannelWritabilityChanged();
            }
            super.channelWritabilityChanged(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            Channel clientChannel = ctx.channel();
            log.info("RrpServer disconnected {}", clientChannel);
            RrpServer server = Sockets.getAttr(clientChannel, ATTR_SVR);
            tryClose(server.clients.remove(clientChannel));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Channel clientChannel = ctx.channel();
            log.warn("RrpServer error main RELAY {} => ALL thrown", clientChannel.remoteAddress(), cause);
            Sockets.closeOnFlushed(clientChannel);
        }
    }

    final RrpConfig config;
    final ServerBootstrap bootstrap;
    final Map<Channel, RpClient> clients = new ConcurrentHashMap<>();
    final Map<String, RpClientProxy> proxiesByName = new ConcurrentHashMap<>();
    final Map<Integer, RpClientProxy> proxiesByPort = new ConcurrentHashMap<>();
    Channel serverChannel;

    public RrpServer(@NonNull RrpConfig config) {
        this.config = config;
        config.setTransportFlags(TransportFlags.CIPHER_BOTH.flags(TransportFlags.HTTP_PSEUDO_BOTH));
//        config.setTransportFlags(TransportFlags.SERVER_HTTP_PSEUDO_BOTH.flags());
        bootstrap = Sockets.serverBootstrap(channel -> {
                    Sockets.addTcpServerHandler(channel, config).pipeline()
//                        .addLast(Sockets.intLengthFieldDecoder(), Sockets.INT_LENGTH_FIELD_ENCODER)
//                            .addLast(new HttpPseudoHeaderDecoder(), HttpPseudoHeaderEncoder.DEFAULT)
                            .addLast(ServerHandler.DEFAULT);
                    Sockets.dumpPipeline("RrpSvr", channel);
                })
                .attr(ATTR_SVR, this)
                .attr(SocketConfig.ATTR_PSEUDO_SVR, true);
        serverChannel = bootstrap.bind(config.getBindPort()).channel();
    }

    @Override
    protected void dispose() throws Throwable {
        closeChannel(serverChannel);
        for (RpClient client : clients.values()) {
            tryClose(client);
        }
        clients.clear();
        proxiesByName.clear();
        proxiesByPort.clear();
        Sockets.closeBootstrap(bootstrap);
    }

    static void closeChannel(Channel channel) {
        if (channel == null || !channel.isOpen()) {
            return;
        }
        channel.close();
    }

    boolean reserveProxy(RpClientProxy rpClientProxy) {
        String name = rpClientProxy.p.getName();
        int remotePort = rpClientProxy.p.getRemotePort();
        if (proxiesByName.putIfAbsent(name, rpClientProxy) != null) {
            log.warn("RrpServer Proxy name {} exist", name);
            return false;
        }
        if (proxiesByPort.putIfAbsent(remotePort, rpClientProxy) != null) {
            proxiesByName.remove(name, rpClientProxy);
            log.warn("RrpServer Proxy remotePort {} exist", remotePort);
            return false;
        }
        if (rpClientProxy.owner.proxyMap.putIfAbsent(remotePort, rpClientProxy) != null) {
            proxiesByName.remove(name, rpClientProxy);
            proxiesByPort.remove(remotePort, rpClientProxy);
            log.warn("RrpServer Proxy remotePort {} exist in client {}", remotePort, rpClientProxy.owner.clientChannel);
            return false;
        }
        return true;
    }

    void unregisterProxy(RpClientProxy rpClientProxy) {
        rpClientProxy.owner.proxyMap.remove(rpClientProxy.p.getRemotePort(), rpClientProxy);
        proxiesByName.remove(rpClientProxy.p.getName(), rpClientProxy);
        proxiesByPort.remove(rpClientProxy.p.getRemotePort(), rpClientProxy);
    }

    void register(@NonNull Channel clientChannel, @NonNull List<RrpConfig.Proxy> pList) {
        RpClient rpClient = clients.get(clientChannel);
        if (rpClient == null) {
            throw new InvalidException("Client {} not fund", clientChannel.id());
        }

        for (RrpConfig.Proxy rp : pList) {
            String name = rp.getName();
            if (name == null) {
                log.warn("RrpServer Proxy empty name");
                continue;
            }
            int remotePort = rp.getRemotePort();
            ServerBootstrap remoteBootstrap = Sockets.serverBootstrap(channel -> {
                channel.pipeline()
//                        .addLast(new ProxyChannelIdleHandler(60 * 4, 0))
                        .addLast(RemoteServerHandler.DEFAULT);
            });
            RpClientProxy rpClientProxy = new RpClientProxy(this, rpClient, rp, remoteBootstrap);
            if (!reserveProxy(rpClientProxy)) {
                tryClose(rpClientProxy);
                continue;
            }

            ChannelFuture bindFuture = remoteBootstrap
                    .attr(ATTR_SVR_CLI, rpClient)
                    .attr(ATTR_SVR_PROXY, rpClientProxy)
                    .bind(remotePort);
            rpClientProxy.bindFuture = bindFuture;
            bindFuture.addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    log.error("RrpServer step2 {} remote Tcp bind {} fail", clientChannel, remotePort, f.cause());
                    tryClose(rpClientProxy);
                    return;
                }

                rpClientProxy.remoteServerChannel = f.channel();
                if (rpClientProxy.isClosed() || rpClient.isClosed() || !clientChannel.isActive()) {
                    tryClose(rpClientProxy);
                    return;
                }
                log.debug("RrpServer step2 {} remote Tcp bind {}", clientChannel, remotePort);
            });
        }
    }
}
