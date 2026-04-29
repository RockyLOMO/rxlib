package org.rx.net.socks;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.util.AttributeKey;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.Tuple;
import org.rx.core.Disposable;
import org.rx.core.Sys;
import org.rx.io.Serializer;
import org.rx.net.Sockets;
import org.rx.net.TransportFlags;
import org.rx.net.transport.AbstractTcpReconnectClient;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.rx.core.Extends.*;
import static org.rx.core.Sys.toJsonString;
import static org.rx.net.socks.RrpConfig.ATTR_CLI_CONN;
import static org.rx.net.socks.RrpConfig.ATTR_CLI_PROXY;

@Slf4j
public class RrpClient extends AbstractTcpReconnectClient {
    static final int MAX_CHANNEL_ID_LEN = RrpServer.MAX_CHANNEL_ID_LEN;
    static final int MAX_PENDING_FORWARD_BYTES = RrpServer.MAX_PENDING_FORWARD_BYTES;
    static final boolean enableMemoryChannel = true;
    static final AttributeKey<LocalRelayBuffer> ATTR_LOCAL_RELAY_BUF = AttributeKey.valueOf("rLocalRelayBuf");
    static final AttributeKey<ServerRelayBuffer> ATTR_SERVER_RELAY_BUF = AttributeKey.valueOf("rServerRelayBuf");
    static final AtomicLong LOCAL_ADDRESS_SEQ = new AtomicLong();

    static abstract class PendingWriteBuffer {
        final Queue<ByteBuf> pendingWrites = new ConcurrentLinkedQueue<>();
        final AtomicInteger pendingBytes = new AtomicInteger();
        final AtomicInteger draining = new AtomicInteger();

        boolean offer(Channel channel, ByteBuf payload) {
            if (!canQueue(channel)) {
                io.netty.util.ReferenceCountUtil.release(payload);
                onRejected(channel);
                return false;
            }

            int bytes = payload.readableBytes();
            int queuedBytes = pendingBytes.addAndGet(bytes);
            if (queuedBytes > MAX_PENDING_FORWARD_BYTES) {
                pendingBytes.addAndGet(-bytes);
                io.netty.util.ReferenceCountUtil.release(payload);
                onOverflow(channel, queuedBytes);
                return false;
            }

            pendingWrites.offer(payload);
            scheduleDrain(channel);
            onQueueStateChanged(channel);
            return true;
        }

        boolean hasPendingWrites() {
            return !pendingWrites.isEmpty();
        }

        boolean canQueue(Channel channel) {
            return channel.isOpen();
        }

        boolean canWrite(Channel channel) {
            return channel.isActive() && channel.isWritable();
        }

        void scheduleDrain(Channel channel) {
            if (!canWrite(channel) || !draining.compareAndSet(0, 1)) {
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
                while (canWrite(channel) && (payload = pendingWrites.poll()) != null) {
                    pendingBytes.addAndGet(-payload.readableBytes());
                    channel.write(payload).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
                    flushed = true;
                }
                if (flushed) {
                    channel.flush();
                }
            } finally {
                draining.set(0);
                if (canWrite(channel) && !pendingWrites.isEmpty()) {
                    scheduleDrain(channel);
                } else {
                    onQueueStateChanged(channel);
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
            onRelease();
        }

        void onQueueStateChanged(Channel channel) {
        }

        void onRelease() {
        }

        void onRejected(Channel channel) {
        }

        abstract void onOverflow(Channel channel, int queuedBytes);
    }

    static class LocalRelayBuffer extends PendingWriteBuffer {
        final RrpClient owner;
        final Channel serverChannel;

        LocalRelayBuffer(RrpClient owner, Channel serverChannel) {
            this.owner = owner;
            this.serverChannel = serverChannel;
        }

        @Override
        void onQueueStateChanged(Channel channel) {
            syncServerReadState();
        }

        @Override
        void onRelease() {
            syncServerReadState();
        }

        @Override
        void onRejected(Channel channel) {
            syncServerReadState();
        }

        @Override
        void onOverflow(Channel channel, int queuedBytes) {
            log.warn("RrpClient local channel {} queued bytes {} exceed cap {}, close channel", channel, queuedBytes, MAX_PENDING_FORWARD_BYTES);
            RrpServer.closeChannel(channel);
            syncServerReadState();
        }

        void syncServerReadState() {
            if (owner != null) {
                owner.syncServerReadState(serverChannel);
            }
        }
    }

    static class ServerRelayBuffer extends PendingWriteBuffer {
        final Channel localChannel;

        ServerRelayBuffer(Channel localChannel) {
            this.localChannel = localChannel;
        }

        @Override
        boolean canQueue(Channel channel) {
            return channel.isActive() && localChannel.isOpen();
        }

        @Override
        void onQueueStateChanged(Channel channel) {
            syncLocalReadState(channel);
        }

        @Override
        void onRelease() {
            if (localChannel.isOpen()) {
                Sockets.enableAutoRead(localChannel);
            }
        }

        @Override
        void onRejected(Channel channel) {
            RrpServer.closeChannel(localChannel);
        }

        @Override
        void onOverflow(Channel channel, int queuedBytes) {
            log.warn("RrpClient server channel {} queued bytes {} exceed cap {}, close local channel {}", channel, queuedBytes, MAX_PENDING_FORWARD_BYTES, localChannel);
            RrpServer.closeChannel(localChannel);
        }

        void syncLocalReadState(Channel serverChannel) {
            if (!localChannel.isOpen()) {
                return;
            }
            if (serverChannel.isWritable() && !hasPendingWrites()) {
                Sockets.enableAutoRead(localChannel);
            } else {
                Sockets.disableAutoRead(localChannel);
            }
        }
    }

    static class RpClientProxy extends Disposable {
        final RrpClient owner;
        final RrpConfig.Proxy p;
        final Channel serverChannel;
        final Map<String, Channel> localChannels = new ConcurrentHashMap<>();
        SocketAddress localEndpoint;
        SocksProxyServer localSS;

        @Override
        protected void dispose() throws Throwable {
            Sockets.closeOnFlushed(serverChannel);
            for (Channel ch : localChannels.values()) {
                Sockets.closeOnFlushed(ch);
            }
            localChannels.clear();
            tryClose(localSS);
        }

        public RpClientProxy(RrpConfig.Proxy p, Channel serverChannel) {
            this(null, p, serverChannel);
        }

        public RpClientProxy(RrpClient owner, RrpConfig.Proxy p, Channel serverChannel) {
            this.owner = owner;
            this.p = p;
            this.serverChannel = serverChannel;

            SocksConfig conf = new SocksConfig(0);
            conf.setTransportFlags(TransportFlags.CIPHER_BOTH.flags());
            if (enableMemoryChannel) {
                conf.setMemoryAddress(new LocalAddress("rrp-" + p.getRemotePort() + "-" + LOCAL_ADDRESS_SEQ.incrementAndGet()));
                localSS = new SocksProxyServer(conf, (u, w) -> {
                    if (!eq(p.getAuth(), u + ":" + w)) {
                        log.debug("RrpClient check {}!={}:{}", p.getAuth(), u, w);
                        return null;
                    }
                    SocksUser usr = new SocksUser(u);
                    return usr;
                }, true, null);
                localEndpoint = conf.getMemoryAddress();
                log.debug("RrpClient Local SS bind R{} <-> Memory {}", p.getRemotePort(), conf.getMemoryAddress().id());
            } else {
                localSS = new SocksProxyServer(conf, (u, w) -> {
                    if (!eq(p.getAuth(), u + ":" + w)) {
                        log.debug("RrpClient check {}!={}:{}", p.getAuth(), u, w);
                        return null;
                    }
                    SocksUser usr = new SocksUser(u);
                    return usr;
                }, ch -> {
                    int bindPort = ((InetSocketAddress) ch.localAddress()).getPort();
                    log.debug("RrpClient Local SS bind R{} <-> L{}", p.getRemotePort(), bindPort);
                    localEndpoint = Sockets.newLoopbackEndpoint(bindPort);
                });
            }
        }

        boolean hasLocalBacklog() {
            for (Channel ch : localChannels.values()) {
                if (!ch.isOpen()) {
                    continue;
                }
                LocalRelayBuffer relayBuffer = ch.attr(ATTR_LOCAL_RELAY_BUF).get();
                if ((relayBuffer != null && relayBuffer.hasPendingWrites()) || (ch.isActive() && !ch.isWritable())) {
                    return true;
                }
            }
            return false;
        }
    }

    @ChannelHandler.Sharable
    static class SocksClientHandler extends ChannelInboundHandlerAdapter {
        static final SocksClientHandler DEFAULT = new SocksClientHandler();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Channel localChannel = ctx.channel();
            Tuple<RpClientProxy, String> attr = Sockets.getAttr(localChannel, ATTR_CLI_PROXY);
            RpClientProxy proxyCtx = attr.left;
            String channelId = attr.right;
            Channel serverChannel = proxyCtx.serverChannel;
            if (!serverChannel.isActive()) {
                io.netty.util.ReferenceCountUtil.release(msg);
                Sockets.closeOnFlushed(localChannel);
                return;
            }
            //step5
            ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer();
            buf.writeByte(RrpConfig.ACTION_FORWARD);
            buf.writeInt(proxyCtx.p.remotePort);
            byte[] bytes = channelId.getBytes(StandardCharsets.US_ASCII);
            buf.writeInt(bytes.length);
            buf.writeBytes(bytes);
//            serverChannel.write(buf);

            ServerRelayBuffer relayBuffer = localChannel.attr(ATTR_SERVER_RELAY_BUF).get();
            if (relayBuffer == null) {
                ServerRelayBuffer newBuffer = new ServerRelayBuffer(localChannel);
                ServerRelayBuffer oldBuffer = localChannel.attr(ATTR_SERVER_RELAY_BUF).setIfAbsent(newBuffer);
                relayBuffer = oldBuffer == null ? newBuffer : oldBuffer;
            }
            relayBuffer.offer(serverChannel, Unpooled.wrappedBuffer(buf, (ByteBuf) msg));
            log.debug("RrpClient step5 {}({}) {} -> serverChannel", proxyCtx.serverChannel, channelId, localChannel);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            Channel localChannel = ctx.channel();
            Tuple<RpClientProxy, String> attr = Sockets.getAttr(localChannel, ATTR_CLI_PROXY);
            RpClientProxy proxyCtx = attr.left;
            String channelId = attr.right;
            Channel serverChannel = proxyCtx.serverChannel;
            LocalRelayBuffer localRelayBuffer = localChannel.attr(ATTR_LOCAL_RELAY_BUF).getAndSet(null);
            if (localRelayBuffer != null) {
                localRelayBuffer.releaseAll();
            }
            ServerRelayBuffer serverRelayBuffer = localChannel.attr(ATTR_SERVER_RELAY_BUF).getAndSet(null);
            if (serverRelayBuffer != null) {
                serverRelayBuffer.releaseAll();
            }
            proxyCtx.localChannels.remove(channelId, localChannel);
            if (proxyCtx.owner != null) {
                proxyCtx.owner.syncServerReadState(serverChannel);
            }
            if (!serverChannel.isActive()) {
                return;
            }
            //step9 localClose
            ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer();
            buf.writeByte(RrpConfig.ACTION_SYNC_CLOSE);
            buf.writeInt(proxyCtx.p.remotePort);
            byte[] bytes = channelId.getBytes(StandardCharsets.US_ASCII);
            buf.writeInt(bytes.length);
            buf.writeBytes(bytes);
            serverChannel.writeAndFlush(buf).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            Channel localChannel = ctx.channel();
            Tuple<RpClientProxy, String> attr = Sockets.getAttr(localChannel, ATTR_CLI_PROXY);
            RpClientProxy proxyCtx = attr.left;
//            String channelId = attr.right;
            Channel serverChannel = proxyCtx.serverChannel;
            log.warn("RrpClient error RELAY {} => {}[{}] thrown", localChannel.remoteAddress(), serverChannel.localAddress(), serverChannel.remoteAddress(), cause);
            Sockets.closeOnFlushed(localChannel);
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
            Channel localChannel = ctx.channel();
            LocalRelayBuffer relayBuffer = localChannel.attr(ATTR_LOCAL_RELAY_BUF).get();
            if (relayBuffer != null) {
                relayBuffer.scheduleDrain(localChannel);
            }
            super.channelWritabilityChanged(ctx);
        }
    }

    class ClientHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            Channel serverChannel = ctx.channel();
            channel = serverChannel;
            closeClientProxies();
            for (RrpConfig.Proxy p : config.getProxies()) {
                proxyMap.computeIfAbsent(p.getRemotePort(), k -> new RpClientProxy(RrpClient.this, p, serverChannel));
            }

            //step1
            ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer();
            buf.writeByte(RrpConfig.ACTION_REGISTER);
            String token = config.getToken();
            if (token == null) {
                buf.writeInt(0);
            } else {
                byte[] tokenData = token.getBytes(StandardCharsets.US_ASCII);
                buf.writeInt(tokenData.length);
                buf.writeBytes(tokenData);
            }
            byte[] bytes = Serializer.DEFAULT.serializeToBytes(config.getProxies());
            buf.writeInt(bytes.length);
            buf.writeBytes(bytes);
            serverChannel.writeAndFlush(buf).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            startHeartbeat(serverChannel);
            log.debug("RrpClient step1 {} -> {}", toJsonString(config.getProxies()), serverChannel);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Channel serverChannel = ctx.channel();
            ByteBuf buf = (ByteBuf) msg;
            try {
                if (buf.readableBytes() < 1) {
                    return;
                }
                byte action = buf.readByte();
                if (action == RrpConfig.ACTION_HEARTBEAT) {
                    return;
                }
                if (buf.readableBytes() < 4 + 4) {
                    log.warn("RrpClient error Invalid frame action {} from {}", action, serverChannel.remoteAddress());
                    Sockets.closeOnFlushed(serverChannel);
                    return;
                }
                int remotePort = buf.readInt();
                int idLen = buf.readInt();
                if (idLen < 0 || idLen > MAX_CHANNEL_ID_LEN || buf.readableBytes() < idLen) {
                    log.warn("RrpClient error Invalid idLen {} from {}", idLen, serverChannel.remoteAddress());
                    Sockets.closeOnFlushed(serverChannel);
                    return;
                }
                String channelId = buf.readCharSequence(idLen, StandardCharsets.US_ASCII).toString();
                RpClientProxy proxyCtx = proxyMap.get(remotePort);
                if (proxyCtx == null) {
                    log.warn("RrpClient error Unknown remotePort {} for channelId={}", remotePort, channelId);
                    return;
                }
                if (action == RrpConfig.ACTION_FORWARD) {
                    //step4
                    log.debug("RrpClient step4 {}({}) serverChannel -> connect", serverChannel, channelId);
                    Channel localChannel = proxyCtx.localChannels.computeIfAbsent(channelId, k -> {
                        RrpConfig conf = Sys.deepClone(config);
                        conf.setTransportFlags(TransportFlags.CIPHER_BOTH.flags());
                        ChannelFuture connF;
                        if (enableMemoryChannel) {
                            connF = new Bootstrap()
                                    .group(Sockets.reactor(Sockets.ReactorNames.SHARED_TCP, true))
                                    .channel(LocalChannel.class)
                                    .handler(new ChannelInitializer<LocalChannel>() {
                                        @Override
                                        protected void initChannel(LocalChannel ch) {
                                            Sockets.addTcpClientHandler(ch, conf).pipeline()
                                                    .addLast(SocksClientHandler.DEFAULT);
                                        }
                                    }).connect(proxyCtx.localEndpoint);
                        } else {
                            connF = Sockets.bootstrap(conf, ch -> Sockets.addTcpClientHandler(ch, conf).pipeline()
                                            .addLast(SocksClientHandler.DEFAULT))
                                    .connect(proxyCtx.localEndpoint);
                        }
                        Channel ch = connF.channel();
                        ch.attr(ATTR_CLI_PROXY).set(Tuple.of(proxyCtx, channelId));
                        ch.attr(ATTR_CLI_CONN).set(connF);
                        ch.attr(ATTR_LOCAL_RELAY_BUF).set(new LocalRelayBuffer(RrpClient.this, serverChannel));
                        ch.attr(ATTR_SERVER_RELAY_BUF).set(new ServerRelayBuffer(ch));
                        connF.addListener((ChannelFutureListener) f -> {
                            ch.attr(ATTR_CLI_CONN).set(null);
                            LocalRelayBuffer relayBuffer = ch.attr(ATTR_LOCAL_RELAY_BUF).get();
                            if (f.isSuccess()) {
                                if (relayBuffer != null) {
                                    relayBuffer.scheduleDrain(ch);
                                }
                                syncServerReadState(serverChannel);
                                return;
                            }

                            if (relayBuffer != null) {
                                relayBuffer.releaseAll();
                            }
                            ServerRelayBuffer serverRelayBuffer = ch.attr(ATTR_SERVER_RELAY_BUF).get();
                            if (serverRelayBuffer != null) {
                                serverRelayBuffer.releaseAll();
                            }
                            proxyCtx.localChannels.remove(channelId, ch);
                            RrpServer.closeChannel(ch);
                            syncServerReadState(serverChannel);
                        });
                        return ch;
                    });
                    ByteBuf payload = buf.readRetainedSlice(buf.readableBytes());
                    LocalRelayBuffer relayBuffer = localChannel.attr(ATTR_LOCAL_RELAY_BUF).get();
                    if (relayBuffer == null) {
                        LocalRelayBuffer newBuffer = new LocalRelayBuffer(RrpClient.this, serverChannel);
                        LocalRelayBuffer oldBuffer = localChannel.attr(ATTR_LOCAL_RELAY_BUF).setIfAbsent(newBuffer);
                        relayBuffer = oldBuffer == null ? newBuffer : oldBuffer;
                    }
                    relayBuffer.offer(localChannel, payload);
                    log.debug("RrpClient step4 {}({}) serverChannel -> {}", serverChannel, channelId, localChannel);
                } else if (action == RrpConfig.ACTION_SYNC_CLOSE) {
                    //step8
                    Channel localChannel = proxyCtx.localChannels.get(channelId);
                    log.debug("RrpClient step8 {}({}) serverChannel -> {}", serverChannel, channelId, localChannel);
                    Sockets.closeOnFlushed(localChannel);
                } else {
                    log.warn("RrpClient error Invalid action {}", action);
                    Sockets.closeOnFlushed(serverChannel);
                }
            } finally {
                io.netty.util.ReferenceCountUtil.release(msg);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            Channel serverChannel = ctx.channel();
            log.debug("clientInactive {}", serverChannel.remoteAddress());

            stopHeartbeat();
            if (channel == serverChannel) {
                channel = null;
            }
            closeClientProxies();
            if (isShouldReconnect()) {
                reconnectAsync();
            }
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
            Channel serverChannel = ctx.channel();
            for (RpClientProxy proxy : proxyMap.values()) {
                for (Channel localChannel : proxy.localChannels.values()) {
                    ServerRelayBuffer relayBuffer = localChannel.attr(ATTR_SERVER_RELAY_BUF).get();
                    if (relayBuffer != null) {
                        relayBuffer.scheduleDrain(serverChannel);
                        relayBuffer.syncLocalReadState(serverChannel);
                    }
                }
            }
            super.channelWritabilityChanged(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Channel serverChannel = ctx.channel();
            log.warn("RrpClient error RELAY {} => ALL thrown", serverChannel.remoteAddress(), cause);
            Sockets.closeOnFlushed(serverChannel);
        }
    }

    final RrpConfig config;
    final Map<Integer, RpClientProxy> proxyMap = new ConcurrentHashMap<>();
    volatile ScheduledFuture<?> heartbeatFuture;

    protected synchronized boolean isShouldReconnect() {
        return config.isEnableReconnect() && !isConnected();
    }

    public RrpClient(@NonNull RrpConfig config) {
        this.config = config;
    }

    @Override
    protected void dispose() throws Throwable {
        config.setEnableReconnect(false);
        stopHeartbeat();
        closeClientProxies();
        cancelPendingConnect(new CancellationException("client closed"));
        Sockets.closeOnFlushed(getChannel());
    }

    void closeClientProxies() {
        for (RpClientProxy proxy : proxyMap.values()) {
            tryClose(proxy);
        }
        proxyMap.clear();
    }

    void syncServerReadState(Channel serverChannel) {
        if (serverChannel == null || !serverChannel.isOpen()) {
            return;
        }
        if (serverChannel.eventLoop().inEventLoop()) {
            doSyncServerReadState(serverChannel);
            return;
        }
        serverChannel.eventLoop().execute(() -> doSyncServerReadState(serverChannel));
    }

    void doSyncServerReadState(Channel serverChannel) {
        if (!serverChannel.isActive()) {
            return;
        }
        boolean readable = true;
        for (RpClientProxy proxy : proxyMap.values()) {
            if (proxy.hasLocalBacklog()) {
                readable = false;
                break;
            }
        }
        if (readable) {
            Sockets.enableAutoRead(serverChannel);
        } else {
            Sockets.disableAutoRead(serverChannel);
        }
    }

    void startHeartbeat(Channel serverChannel) {
        stopHeartbeat();
        int seconds = config.getHeartbeatSeconds();
        if (seconds <= 0) {
            return;
        }
        heartbeatFuture = serverChannel.eventLoop().scheduleAtFixedRate(() -> {
            if (channel != serverChannel || !serverChannel.isActive()) {
                return;
            }
            RrpConfig.writeHeartbeat(serverChannel);
        }, seconds, seconds, TimeUnit.SECONDS);
    }

    void stopHeartbeat() {
        ScheduledFuture<?> f = heartbeatFuture;
        if (f == null) {
            return;
        }
        heartbeatFuture = null;
        f.cancel(false);
    }

    public synchronized Future<Void> connectAsync() {
        config.setTransportFlags(TransportFlags.CIPHER_BOTH.flags(TransportFlags.HTTP_PSEUDO_BOTH));
//        config.setTransportFlags(TransportFlags.CLIENT_HTTP_PSEUDO_BOTH.flags());
        return beginConnect(Sockets.bootstrap(config, channel -> {
            Sockets.addTcpClientHandler(channel, config).pipeline()
//                        .addLast(Sockets.intLengthFieldDecoder(), Sockets.INT_LENGTH_FIELD_ENCODER)
//                    .addLast(new HttpPseudoHeaderDecoder(), HttpPseudoHeaderEncoder.DEFAULT)
                    .addLast(new ClientHandler());
            Sockets.dumpPipeline("RrpCli", channel);
        }));
    }

    @Override
    protected SocketAddress resolveConnectEndpoint(boolean reconnect) {
        return Sockets.parseEndpoint(config.getServerEndpoint());
    }

    @Override
    protected void onConnectSuccess(SocketAddress endpoint, boolean reconnect, Channel channel) {
        if (reconnect) {
            log.debug("{} reconnect {} ok", this, endpoint);
        }
    }

    @Override
    protected void onConnectFailure(SocketAddress endpoint, boolean reconnect, Throwable cause) {
        log.debug("{} {} {} fail", this, reconnect ? "reconnect" : "connect", endpoint);
    }

    @Override
    protected void onReconnectRetry(SocketAddress endpoint, long delayMs) {
        log.debug("{} reconnect {} failed will re-attempt in {}ms", this, endpoint, delayMs);
    }
}
