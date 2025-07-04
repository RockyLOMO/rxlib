package org.rx.net.socks;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Constants;
import org.rx.core.Disposable;
import org.rx.core.Sys;
import org.rx.core.Tasks;
import org.rx.exception.InvalidException;
import org.rx.io.Serializer;
import org.rx.net.Sockets;
import org.rx.net.TransportFlags;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;

import static org.rx.core.Extends.circuitContinue;
import static org.rx.core.Extends.tryClose;
import static org.rx.core.Sys.toJsonString;
import static org.rx.net.socks.RrpConfig.ATTR_CONN_FUTURE;

@Slf4j
public class RrpClient extends Disposable {
    static class RpClientProxy extends Disposable {
        final RrpConfig.Proxy p;
        final Channel serverChannel;
        final Map<String, Channel> localChannels = new ConcurrentHashMap<>();
        InetSocketAddress localEndpoint;
        SocksProxyServer localSS;

        @Override
        protected void freeObjects() throws Throwable {
            Sockets.closeOnFlushed(serverChannel);
            localChannels.clear();
            tryClose(localSS);
        }

        public RpClientProxy(RrpConfig.Proxy p, Channel serverChannel) {
            this.p = p;
            this.serverChannel = serverChannel;
            SocksConfig conf = new SocksConfig(0);
//            conf.setTransportFlags(TransportFlags.SERVER_COMPRESS_READ.flags());
            conf.setTransportFlags(TransportFlags.SERVER_AES_READ.flags());
            localSS = new SocksProxyServer(conf, null, ch -> {
                int bindPort = ((InetSocketAddress) ch.localAddress()).getPort();
                log.debug("RrpClient Local SS bind R{} <-> L{}", p.getRemotePort(), bindPort);
                localEndpoint = Sockets.newLoopbackEndpoint(bindPort);
            });
        }
    }

    @RequiredArgsConstructor
    static class SocksClientHandler extends ChannelInboundHandlerAdapter {
        final RpClientProxy proxyCtx;
        final String channelId;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Channel localChannel = ctx.channel();
            Channel serverChannel = proxyCtx.serverChannel;
            //step5
            ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer();
            buf.writeByte(RrpConfig.ACTION_FORWARD);
            buf.writeInt(proxyCtx.p.remotePort);
            byte[] bytes = channelId.getBytes(StandardCharsets.US_ASCII);
            buf.writeInt(bytes.length);
            buf.writeBytes(bytes);
//            serverChannel.write(buf);

            serverChannel.writeAndFlush(Unpooled.wrappedBuffer(buf, (ByteBuf) msg));
            log.debug("RrpClient step5 {}({}) {} -> serverChannel", proxyCtx.serverChannel, channelId, localChannel);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            tryClose(proxyCtx.localChannels.remove(channelId));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            Channel localChannel = ctx.channel();
            Channel serverChannel = proxyCtx.serverChannel;
            log.warn("RELAY {} => {}[{}] thrown", localChannel.remoteAddress(), serverChannel.localAddress(), serverChannel.remoteAddress(), cause);
        }
    }

    class ClientHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            Channel serverChannel = ctx.channel();
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
            serverChannel.writeAndFlush(buf);
            log.debug("RrpClient step1 {} -> {}", toJsonString(config.getProxies()), serverChannel);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Channel serverChannel = ctx.channel();
            ByteBuf buf = (ByteBuf) msg;
            //step4
            byte action = buf.readByte();
            if (action != RrpConfig.ACTION_FORWARD) {
                log.warn("Invalid action {}", action);
                serverChannel.close();
                return;
            }

            int remotePort = buf.readInt();
            int idLen = buf.readInt();
            String channelId = buf.readCharSequence(idLen, StandardCharsets.US_ASCII).toString();
            RpClientProxy proxyCtx = proxyMap.get(remotePort);
            Channel localChannel = proxyCtx.localChannels.computeIfAbsent(channelId, k -> {
                RrpConfig conf = Sys.deepClone(config);
//                conf.setTransportFlags(TransportFlags.CLIENT_COMPRESS_WRITE.flags());
                conf.setTransportFlags(TransportFlags.CLIENT_AES_WRITE.flags());
                ChannelFuture connF = Sockets.bootstrap(conf, ch -> {
                    Sockets.addClientHandler(ch, conf, proxyCtx.localEndpoint);
                    ch.pipeline().addLast(new SocksClientHandler(proxyCtx, channelId));
                }).connect(proxyCtx.localEndpoint);
                Channel ch = connF.channel();
                ch.attr(ATTR_CONN_FUTURE).set(connF);
                connF.addListener((ChannelFutureListener) f -> ch.attr(ATTR_CONN_FUTURE).set(null));
                return ch;
            });
            ChannelFuture connF = localChannel.attr(ATTR_CONN_FUTURE).get();
            if (connF == null) {
                localChannel.writeAndFlush(buf);
            } else {
//                ByteBuf finalBuf = Unpooled.copiedBuffer(buf);
                connF.addListener((ChannelFutureListener) f -> {
                    if (f.isSuccess()) {
                        f.channel().writeAndFlush(buf);
                    }
                });
            }

//            Channel localChannel = proxyCtx.localChannels.computeIfAbsent(channelId, k -> Sockets.bootstrap(config, ch -> ch.pipeline()
//                            .addLast(new SocksClientHandler(proxyCtx, channelId)))
//                    .connect(proxyCtx.localEndpoint).syncUninterruptibly().channel());
//            localChannel.writeAndFlush(buf);
            log.debug("RrpClient step4 {}({}) serverChannel -> {}", serverChannel, channelId, localChannel);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            Channel serverChannel = ctx.channel();
            log.debug("clientInactive {}", serverChannel.remoteAddress());

            reconnectAsync();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Channel serverChannel = ctx.channel();
            log.warn("RELAY {} => ALL thrown", serverChannel.remoteAddress(), cause);
        }
    }

    final RrpConfig config;
    final Map<Integer, RpClientProxy> proxyMap = new ConcurrentHashMap<>();
    Bootstrap bootstrap;
    Future<Void> connectingFutureWrapper;
    volatile Channel channel;
    volatile ChannelFuture connectingFuture;

    public boolean isConnected() {
        Channel c = channel;
        return c != null && c.isActive();
    }

    protected synchronized boolean isShouldReconnect() {
        return config.isEnableReconnect() && !isConnected();
    }

    public RrpClient(@NonNull RrpConfig config) {
        this.config = config;
    }

    @Override
    protected void freeObjects() throws Throwable {
        config.setEnableReconnect(false);
        Sockets.closeOnFlushed(channel);
    }

    public synchronized Future<Void> connectAsync() {
        if (isConnected()) {
            throw new InvalidException("{} has connected", this);
        }

        bootstrap = Sockets.bootstrap(config, channel -> channel.pipeline()
                .addLast(Sockets.intLengthFieldDecoder())
                .addLast(Sockets.INT_LENGTH_FIELD_ENCODER)
                .addLast(new ClientHandler()));
        doConnect(false);
        if (connectingFutureWrapper == null) {
            connectingFutureWrapper = new Future<Void>() {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    synchronized (RrpClient.this) {
                        config.setEnableReconnect(false);
                    }
                    ChannelFuture f = connectingFuture;
                    if (f != null) {
                        f.cancel(mayInterruptIfRunning);
                    }
                    return true;
                }

                @Override
                public boolean isCancelled() {
                    ChannelFuture f = connectingFuture;
                    return f == null || f.isCancelled();
                }

                @Override
                public boolean isDone() {
                    return connectingFuture == null;
                }

                @Override
                public Void get() throws InterruptedException, ExecutionException {
                    ChannelFuture f = connectingFuture;
                    if (f == null) {
                        return null;
                    }
                    return f.get();
                }

                @Override
                public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
                    ChannelFuture f = connectingFuture;
                    if (f == null) {
                        return null;
                    }
                    return f.get(timeout, unit);
                }
            };
        }
        return connectingFutureWrapper;
    }

    synchronized void doConnect(boolean reconnect) {
        if (isConnected()) {
            return;
        }
        if (reconnect && !isShouldReconnect()) {
            return;
        }

        InetSocketAddress ep = Sockets.parseEndpoint(config.getServerEndpoint());
        connectingFuture = bootstrap.connect(ep).addListeners(Sockets.logConnect(ep), (ChannelFutureListener) f -> {
            channel = f.channel();
            if (!f.isSuccess()) {
                if (isShouldReconnect()) {
                    Tasks.timer().setTimeout(() -> {
                        doConnect(true);
                        circuitContinue(isShouldReconnect());
                    }, d -> {
                        long delay = d >= 5000 ? 5000 : Math.max(d * 2, 100);
                        log.debug("{} reconnect {} failed will re-attempt in {}ms", this, ep, delay);
                        return delay;
                    }, this, Constants.TIMER_SINGLE_FLAG);
                } else {
                    log.debug("{} {} {} fail", this, reconnect ? "reconnect" : "connect", ep);
                }
                return;
            }
            connectingFuture = null;
            if (reconnect) {
                log.debug("{} reconnect {} ok", this, ep);
            }
        });
    }

    void reconnectAsync() {
        Tasks.setTimeout(() -> doConnect(true), 1000, bootstrap, Constants.TIMER_REPLACE_FLAG);
    }

//    public synchronized void send(@NonNull Object msg) {
//        if (!isConnected()) {
//            if (isShouldReconnect()) {
//                if (!FluentWait.polling(config.getWaitConnectMillis()).awaitTrue(w -> isConnected())) {
//                    reconnectAsync();
//                    throw new ClientDisconnectedException(this);
//                }
//            }
//            if (!isConnected()) {
//                throw new ClientDisconnectedException(this);
//            }
//        }
//
//        channel.writeAndFlush(msg);
//    }
}
