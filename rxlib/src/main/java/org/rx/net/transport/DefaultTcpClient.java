package org.rx.net.transport;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Constants;
import org.rx.core.Delegate;
import org.rx.core.EventArgs;
import org.rx.core.FluentWait;
import org.rx.core.NEventArgs;
import org.rx.core.NtpClock;
import org.rx.core.ThreadPool;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.transport.protocol.ErrorPacket;
import org.rx.net.transport.protocol.PingPacket;
import org.slf4j.helpers.MessageFormatter;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.rx.core.Extends.as;
import static org.rx.core.Extends.ifNull;
import static org.rx.core.Extends.quietly;
import static org.rx.core.Extends.tryAs;

@Slf4j
public class DefaultTcpClient extends AbstractTcpReconnectClient implements TcpClient {
    @RequiredArgsConstructor
    static class ClientHandler extends ChannelInboundHandlerAdapter {
        final DefaultTcpClient owner;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            Channel channel = ctx.channel();
            log.debug("clientActive {}", channel.remoteAddress());

            owner.publishEventAsync(owner.onConnected, EventArgs.EMPTY);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Channel channel = ctx.channel();
            log.debug("clientRead {} {}", channel.remoteAddress(), msg.getClass());
            if (tryAs(msg, ErrorPacket.class, p -> exceptionCaught(ctx, new InvalidException("Server error: {}", p.getErrorMessage())))) {
                return;
            }
            if (tryAs(msg, PingPacket.class, p -> {
                log.info("clientHeartbeat pong {} {}ms", channel.remoteAddress(), NtpClock.UTC.millis() - p.getTimestamp());
                owner.publishEventAsync(owner.onPong, p);
            })) {
                return;
            }

            owner.publishEventAsync(owner.onReceive, new NEventArgs<>(msg));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            Channel channel = ctx.channel();
            log.info("clientInactive {}", channel.remoteAddress());

            owner.publishEvent(owner.onDisconnected, EventArgs.EMPTY);
            owner.reconnectAsync();
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            Channel channel = ctx.channel();
            if (evt instanceof IdleStateEvent) {
                IdleStateEvent e = (IdleStateEvent) evt;
                switch (e.state()) {
                    case READER_IDLE:
                        log.warn("clientHeartbeat loss {}", channel.remoteAddress());
                        ctx.close();
                        break;
                    case WRITER_IDLE:
                        log.debug("clientHeartbeat ping {}", channel.remoteAddress());
                        ctx.writeAndFlush(new PingPacket());
                        break;
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Channel channel = ctx.channel();
            log.error("clientCaught {}", channel.remoteAddress(), cause);
            if (!channel.isActive()) {
                return;
            }

            NEventArgs<Throwable> args = new NEventArgs<>(cause);
            quietly(() -> owner.publishEvent(owner.onError, args));
            if (args.isCancel()) {
                return;
            }
            Sockets.closeOnFlushed(channel);
        }
    }

    static final TcpClientConfig NULL_CONF = new TcpClientConfig();
    public final Delegate<TcpClient, EventArgs> onConnected = Delegate.create(),
            onDisconnected = Delegate.create();
    public final Delegate<TcpClient, NEventArgs<InetSocketAddress>> onReconnecting = Delegate.create(),
            onReconnected = Delegate.create();
    public final Delegate<TcpClient, NEventArgs<Object>> onSend = Delegate.create(),
            onReceive = Delegate.create();
    public final Delegate<TcpClient, PingPacket> onPong = Delegate.create();
    public final Delegate<TcpClient, NEventArgs<Throwable>> onError = Delegate.create();
    @Getter
    final TcpClientConfig config;
    @Getter
    InetSocketAddress remoteEndpoint, localEndpoint;
    volatile InetSocketAddress connectingEp;

    @Override
    public @NonNull ThreadPool asyncScheduler() {
        return TcpServer.SCHEDULER;
    }

    protected synchronized boolean isShouldReconnect() {
        return config.isEnableReconnect() && !isConnected();
    }

    public DefaultTcpClient(@NonNull TcpClientConfig config) {
        this.config = config;
    }

    protected DefaultTcpClient() {
        this.config = NULL_CONF;
    }

    @Override
    protected void dispose() {
        config.setEnableReconnect(false);
        cancelPendingConnect(new CancellationException("client closed"));
        Sockets.closeOnFlushed(getChannel());
    }

    @Override
    public void connect(@NonNull InetSocketAddress remoteEp) throws TimeoutException {
        CompletableFuture<Void> promise = beginConnect(remoteEp);
        try {
            promise.get(config.getConnectTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw InvalidException.sneaky(e);
        } catch (ExecutionException e) {
            TimeoutException ex = new TimeoutException(MessageFormatter.format("{} connect {} fail", this, remoteEp).getMessage());
            ex.initCause(e.getCause());
            throw ex;
        } catch (CancellationException e) {
            TimeoutException ex = new TimeoutException(MessageFormatter.format("{} connect {} cancelled", this, remoteEp).getMessage());
            ex.initCause(e);
            throw ex;
        }
    }

    private synchronized CompletableFuture<Void> beginConnect(@NonNull InetSocketAddress remoteEp) {
        CompletableFuture<Void> promise = currentConnectPromise();
        InetSocketAddress currentEp = config.getServerEndpoint();
        if (promise != null && !promise.isDone()) {
            if (currentEp != null && !currentEp.equals(remoteEp)) {
                throw new InvalidException("{} is connecting {}", this, currentEp);
            }
            return promise;
        }

        config.setReactorName(Sockets.ReactorNames.RPC);
        config.setServerEndpoint(remoteEp);
        return beginConnect(Sockets.bootstrap(config, channel -> {
            ChannelPipeline pipeline = channel.pipeline().addLast(new IdleStateHandler(config.getHeartbeatTimeout(), config.getHeartbeatTimeout() / 2, 0));
            Sockets.addTcpClientHandler(channel, config, config.getServerEndpoint());
            TcpChannelCodec codec = config.getCodec();
            if (codec != null) {
                codec.install(pipeline);
            }
            pipeline.addLast(new ClientHandler(this));
        }));
    }

    @Override
    public Future<Void> connectAsync(@NonNull InetSocketAddress remoteEp) {
        return beginConnect(remoteEp);
    }

    @Override
    protected SocketAddress resolveConnectEndpoint(boolean reconnect) {
        if (reconnect) {
            NEventArgs<InetSocketAddress> args = new NEventArgs<>(ifNull(connectingEp, config.getServerEndpoint()));
            publishEvent(onReconnecting, args);
            return connectingEp = args.getValue();
        }
        return config.getServerEndpoint();
    }

    @Override
    protected void onConnectSuccess(SocketAddress endpoint, boolean reconnect, Channel channel) {
        InetSocketAddress ep = (InetSocketAddress) endpoint;
        connectingEp = null;
        config.setServerEndpoint(ep);
        remoteEndpoint = (InetSocketAddress) channel.remoteAddress();
        localEndpoint = (InetSocketAddress) channel.localAddress();
        if (!reconnect) {
            return;
        }

        log.info("{} reconnect {} ok", this, ep);
        publishEvent(onReconnected, new NEventArgs<>(ep));
    }

    @Override
    protected void onConnectFailure(SocketAddress endpoint, boolean reconnect, Throwable cause) {
        log.warn("{} {} {} fail", this, reconnect ? "reconnect" : "connect", endpoint);
    }

    @Override
    protected void onReconnectRetry(SocketAddress endpoint, long delayMs) {
        log.warn("{} reconnect {} failed will re-attempt in {}ms", this, endpoint, delayMs);
    }

    @Override
    public void send(@NonNull Object pack) {
        if (!isConnected()) {
            synchronized (this) {
                if (!isConnected()) {
                    if (isShouldReconnect()) {
                        if (!FluentWait.polling(config.getWaitConnectMillis()).awaitTrue(w -> isConnected())) {
                            reconnectAsync();
                            throw new ClientDisconnectedException(this);
                        }
                    }
                    if (!isConnected()) {
                        throw new ClientDisconnectedException(this);
                    }
                }
            }
        }

        NEventArgs<Object> args = new NEventArgs<>(pack);
        publishEvent(onSend, args);
        if (args.isCancel()) {
            return;
        }

        channel.writeAndFlush(pack);
        log.debug("clientWrite {} {}", config.getServerEndpoint(), pack);
    }

    @Override
    public Delegate<TcpClient, NEventArgs<Object>> onReceive() {
        return onReceive;
    }
}
