package org.rx.net.socks;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.*;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.transport.ClientDisconnectedException;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.rx.core.Extends.circuitContinue;
import static org.rx.core.Extends.ifNull;

@Slf4j
public class RrpClient extends Disposable {
    static class Handler extends ChannelInboundHandlerAdapter {

    }

    final RrpConfig config;
    long waitConnectMillis = 4000;
    boolean enableReconnect = true;
    Bootstrap bootstrap;
    Future<Void> connectingFutureWrapper;
    boolean markEnableReconnect;
    volatile Channel channel;
    volatile ChannelFuture connectingFuture;

    public boolean isConnected() {
        return channel != null && channel.isActive();
    }

    protected boolean isShouldReconnect() {
        return enableReconnect && !isConnected();
    }

    public RrpClient(@NonNull RrpConfig config) {
        this.config = config;
    }

    @Override
    protected void freeObjects() throws Throwable {
        enableReconnect = false;
        Sockets.closeOnFlushed(channel);
    }

    public synchronized Future<Void> connectAsync() {
        if (isConnected()) {
            throw new InvalidException("Client has connected");
        }

        bootstrap = Sockets.bootstrap(config, channel -> channel.pipeline().addLast(new Handler()));
        doConnect(false);
        markEnableReconnect = enableReconnect;
        if (connectingFutureWrapper == null) {
            connectingFutureWrapper = new Future<Void>() {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    enableReconnect = false;
                    ChannelFuture f = connectingFuture;
                    if (f != null) {
                        f.cancel(mayInterruptIfRunning);
                    }
                    return true;
                }

                @Override
                public boolean isCancelled() {
                    return connectingFuture == null || connectingFuture.isCancelled();
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
        if (reconnect) {
            if (!isShouldReconnect()) {
                return;
            }
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
                        log.warn("{} reconnect {} failed will re-attempt in {}ms", this, ep, delay);
                        return delay;
                    }, this, Constants.TIMER_SINGLE_FLAG);
                } else {
                    log.warn("{} {} fail", reconnect ? "reconnect" : "connect", ep);
                }
                return;
            }
            connectingFuture = null;
            if (reconnect) {
                log.info("reconnect {} ok", ep);
            }
        });
    }

    void reconnectAsync() {
        Tasks.setTimeout(() -> doConnect(true), 1000, bootstrap, Constants.TIMER_REPLACE_FLAG);
    }

    ChannelId channelId() {
        return channel != null ? channel.id() : null;
    }

    public synchronized void send(@NonNull Object msg) {
        if (!isConnected()) {
            if (isShouldReconnect()) {
                if (!FluentWait.polling(waitConnectMillis).awaitTrue(w -> isConnected())) {
                    reconnectAsync();
                    throw new ClientDisconnectedException(channelId());
                }
            }
            if (!isConnected()) {
                throw new ClientDisconnectedException(channelId());
            }
        }

        channel.writeAndFlush(msg);
    }
}
