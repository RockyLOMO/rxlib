package org.rx.net.socks;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Constants;
import org.rx.core.Disposable;
import org.rx.core.FluentWait;
import org.rx.core.Tasks;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.net.Sockets;
import org.rx.net.transport.ClientDisconnectedException;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.rx.core.Extends.circuitContinue;

@Slf4j
public class RrpClient extends Disposable {
    class ClientHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
//            Channel channel = ctx.channel();
//            log.debug("clientRead {} {}", channel.remoteAddress(), msg.getClass());
//            Serializable pack;
//            if ((pack = as(msg, Serializable.class)) == null) {
//                log.warn("clientRead discard {} {}", channel.remoteAddress(), msg.getClass());
//                return;
//            }
//            if (tryAs(pack, ErrorPacket.class, p -> exceptionCaught(ctx, new InvalidException("Server error: {}", p.getErrorMessage())))) {
//                return;
//            }
//            if (tryAs(pack, PingPacket.class, p -> {
//                log.info("clientHeartbeat pong {} {}ms", channel.remoteAddress(), NtpClock.UTC.millis() - p.getTimestamp());
//                raiseEventAsync(onPong, p);
//            })) {
//                return;
//            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            Channel channel = ctx.channel();
            log.info("clientInactive {}", channel.remoteAddress());

            reconnectAsync();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Channel channel = ctx.channel();
            TraceHandler.INSTANCE.log("clientCaught {}", channel.remoteAddress(), cause);

            close();
        }
    }

    final RrpConfig config;
    Bootstrap bootstrap;
    boolean markEnableReconnect;
    Future<Void> connectingFutureWrapper;
    String channelId;
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
            throw new InvalidException("Client has connected");
        }

        bootstrap = Sockets.bootstrap(config, channel -> channel.pipeline().addLast(new ClientHandler()));
        doConnect(false);
        markEnableReconnect = config.isEnableReconnect();
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
            channelId = channel.id().asShortText();
            if (!f.isSuccess()) {
                if (isShouldReconnect()) {
                    Tasks.timer().setTimeout(() -> {
                        doConnect(true);
                        circuitContinue(isShouldReconnect());
                    }, d -> {
                        long delay = d >= 5000 ? 5000 : Math.max(d * 2, 100);
                        log.warn("{} reconnect {} failed will re-attempt in {}ms", this, ep, delay);
                        return delay;
                    }, this, Constants.TIMER_SINGLE_FLAG);
                } else {
                    log.warn("{} {} fail", reconnect ? "reconnect" : "connect", ep);
                }
                return;
            }
            connectingFuture = null;
            synchronized (this) {
                config.setEnableReconnect(markEnableReconnect);
            }
            if (reconnect) {
                log.info("reconnect {} ok", ep);
            }
        });
    }

    void reconnectAsync() {
        Tasks.setTimeout(() -> doConnect(true), 1000, bootstrap, Constants.TIMER_REPLACE_FLAG);
    }

    public synchronized void send(@NonNull Object msg) {
        if (!isConnected()) {
            if (isShouldReconnect()) {
                if (!FluentWait.polling(config.getWaitConnectMillis()).awaitTrue(w -> isConnected())) {
                    reconnectAsync();
                    throw new ClientDisconnectedException(channelId);
                }
            }
            if (!isConnected()) {
                throw new ClientDisconnectedException(channelId);
            }
        }

        channel.writeAndFlush(msg);
    }
}
