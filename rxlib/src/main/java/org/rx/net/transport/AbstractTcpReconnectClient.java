package org.rx.net.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import lombok.Getter;
import org.rx.core.Constants;
import org.rx.core.Disposable;
import org.rx.core.Tasks;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;

import java.net.SocketAddress;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

public abstract class AbstractTcpReconnectClient extends Disposable {
    protected Bootstrap bootstrap;
    @Getter
    protected volatile Channel channel;
    protected volatile ChannelFuture connectingFuture;
    private volatile CompletableFuture<Void> connectPromise;
    private volatile long reconnectDelayMs;

    public boolean isConnected() {
        Channel c = channel;
        return c != null && c.isActive();
    }

    protected final CompletableFuture<Void> currentConnectPromise() {
        return connectPromise;
    }

    protected final void cancelPendingConnect(Throwable cause) {
        CompletableFuture<Void> promise = connectPromise;
        if (promise == null || promise.isDone()) {
            return;
        }

        Throwable ex = cause == null ? new CancellationException("connect cancelled") : cause;
        promise.completeExceptionally(ex);
        ChannelFuture f = connectingFuture;
        if (f != null) {
            f.cancel(true);
        }
    }

    protected final synchronized CompletableFuture<Void> beginConnect(Bootstrap bootstrap) {
        checkNotClosed();
        if (isConnected()) {
            throw new InvalidException("{} has connected", this);
        }

        CompletableFuture<Void> promise = connectPromise;
        if (promise != null && !promise.isDone()) {
            return promise;
        }

        this.bootstrap = bootstrap;
        connectPromise = promise = new CompletableFuture<Void>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                if (!cancelled) {
                    return false;
                }

                ChannelFuture f = connectingFuture;
                if (f != null) {
                    f.cancel(mayInterruptIfRunning);
                }
                return true;
            }
        };
        try {
            doConnect(false);
        } catch (Throwable e) {
            completeConnectFailure(e);
        }
        return promise;
    }

    protected final synchronized void doConnect(boolean reconnect) {
        if (isConnected()) {
            reconnectDelayMs = 0L;
            completeConnectSuccess();
            return;
        }
        if (connectingFuture != null && !connectingFuture.isDone()) {
            return;
        }
        if (reconnect && !canRetryConnect()) {
            completeConnectFailure(new CancellationException("connect cancelled"));
            return;
        }

        final SocketAddress endpoint;
        try {
            endpoint = resolveConnectEndpoint(reconnect);
        } catch (Throwable e) {
            onConnectFailure(null, reconnect, e);
            completeConnectFailure(e);
            return;
        }

        try {
            connectingFuture = bootstrap.connect(endpoint).addListener((ChannelFutureListener) f -> {
                connectingFuture = null;
                if (!f.isSuccess()) {
                    Throwable cause = f.cause();
                    if (canRetryConnect()) {
                        scheduleRetry(endpoint, cause);
                    } else {
                        Throwable ex = cause == null ? new InvalidException("{} {} {} fail", this, reconnect ? "reconnect" : "connect", endpoint) : cause;
                        onConnectFailure(endpoint, reconnect, ex);
                        completeConnectFailure(ex);
                    }
                    return;
                }

                Channel connectedChannel = f.channel();
                if (isClosed()) {
                    Sockets.closeOnFlushed(connectedChannel);
                    completeConnectFailure(new CancellationException("client closed"));
                    return;
                }

                reconnectDelayMs = 0L;
                channel = connectedChannel;
                onConnectSuccess(endpoint, reconnect, connectedChannel);
                completeConnectSuccess();
            });
        } catch (Throwable e) {
            if (canRetryConnect()) {
                scheduleRetry(endpoint, e);
                return;
            }

            onConnectFailure(endpoint, reconnect, e);
            completeConnectFailure(e);
        }
    }

    protected final void reconnectAsync() {
        if (bootstrap == null) {
            return;
        }
        Tasks.setTimeout(() -> doConnect(true), 1000, this, Constants.TIMER_SINGLE_FLAG);
    }

    private void scheduleRetry(SocketAddress endpoint, Throwable cause) {
        long previous = reconnectDelayMs;
        long delay = previous >= 5000 ? 5000 : Math.max(previous * 2, 100);
        reconnectDelayMs = delay;
        onReconnectRetry(endpoint, delay, cause);
        Tasks.setTimeout(() -> doConnect(true), delay, this, Constants.TIMER_REPLACE_FLAG);
    }

    private boolean canRetryConnect() {
        return !isConnectCancelled() && isShouldReconnect();
    }

    private boolean isConnectCancelled() {
        CompletableFuture<Void> promise = connectPromise;
        return promise != null && promise.isCancelled();
    }

    private void completeConnectSuccess() {
        CompletableFuture<Void> promise = connectPromise;
        if (promise != null && !promise.isDone()) {
            promise.complete(null);
        }
    }

    private void completeConnectFailure(Throwable cause) {
        CompletableFuture<Void> promise = connectPromise;
        if (promise != null && !promise.isDone()) {
            promise.completeExceptionally(cause);
        }
    }

    protected abstract boolean isShouldReconnect();

    protected abstract SocketAddress resolveConnectEndpoint(boolean reconnect);

    protected void onConnectSuccess(SocketAddress endpoint, boolean reconnect, Channel channel) {
    }

    protected void onConnectFailure(SocketAddress endpoint, boolean reconnect, Throwable cause) {
    }

    protected void onReconnectRetry(SocketAddress endpoint, long delayMs) {
    }

    protected void onReconnectRetry(SocketAddress endpoint, long delayMs, Throwable cause) {
        onReconnectRetry(endpoint, delayMs);
    }
}
