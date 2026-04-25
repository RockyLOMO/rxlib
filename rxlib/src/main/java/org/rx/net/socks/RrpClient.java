package org.rx.net.socks;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
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
import java.util.concurrent.*;

import static org.rx.core.Extends.*;
import static org.rx.core.Sys.toJsonString;
import static org.rx.net.socks.RrpConfig.ATTR_CLI_CONN;
import static org.rx.net.socks.RrpConfig.ATTR_CLI_PROXY;

@Slf4j
public class RrpClient extends AbstractTcpReconnectClient {
    static final int MAX_CHANNEL_ID_LEN = RrpServer.MAX_CHANNEL_ID_LEN;
    static final boolean enableMemoryChannel = true;

    static class RpClientProxy extends Disposable {
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
            this.p = p;
            this.serverChannel = serverChannel;

            SocksConfig conf = new SocksConfig(0);
            conf.setTransportFlags(TransportFlags.CIPHER_BOTH.flags());
            if (enableMemoryChannel) {
                conf.setMemoryAddress(new LocalAddress(RpClientProxy.class));
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

            serverChannel.writeAndFlush(Unpooled.wrappedBuffer(buf, (ByteBuf) msg))
                    .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            log.debug("RrpClient step5 {}({}) {} -> serverChannel", proxyCtx.serverChannel, channelId, localChannel);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            Channel localChannel = ctx.channel();
            Tuple<RpClientProxy, String> attr = Sockets.getAttr(localChannel, ATTR_CLI_PROXY);
            RpClientProxy proxyCtx = attr.left;
            String channelId = attr.right;
            Channel serverChannel = proxyCtx.serverChannel;
            proxyCtx.localChannels.remove(channelId, localChannel);
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
        }
    }

    class ClientHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            Channel serverChannel = ctx.channel();
            channel = serverChannel;
            closeClientProxies();
            for (RrpConfig.Proxy p : config.getProxies()) {
                proxyMap.computeIfAbsent(p.getRemotePort(), k -> new RpClientProxy(p, serverChannel));
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
                        connF.addListener((ChannelFutureListener) f -> ch.attr(ATTR_CLI_CONN).set(null));
                        return ch;
                    });
                    ChannelFuture connF = localChannel.attr(ATTR_CLI_CONN).get();
                    if (connF == null) {
                        localChannel.writeAndFlush(buf.retain());
                    } else {
                        ByteBuf retained = buf.retain();
                        connF.addListener((ChannelFutureListener) f -> {
                            if (f.isSuccess()) {
                                f.channel().writeAndFlush(retained);
                            } else {
                                io.netty.util.ReferenceCountUtil.release(retained);
                            }
                        });
                    }
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
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Channel serverChannel = ctx.channel();
            log.warn("RrpClient error RELAY {} => ALL thrown", serverChannel.remoteAddress(), cause);
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
