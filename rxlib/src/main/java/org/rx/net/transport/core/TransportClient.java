package org.rx.net.transport.core;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Delegate;
import org.rx.core.EventArgs;
import org.rx.core.EventPublisher;
import org.rx.core.FluentWait;
import org.rx.core.NEventArgs;
import org.rx.core.ThreadPool;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.rss.RssUdpMtuBudget;
import org.rx.net.rss.tcp.AdaptiveTcpHandler;
import org.rx.net.transport.AbstractTcpReconnectClient;
import org.rx.net.transport.ClientDisconnectedException;
import org.rx.net.transport.TcpClientConfig;
import org.slf4j.helpers.MessageFormatter;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

import static org.rx.core.Extends.ifNull;
import static org.rx.core.Extends.quietly;

/**
 * Shared Transport Core reliable ordered {@link ByteBuf} client.
 * Uses pure TCP when {@code enableAdaptiveUdp = false} (zero UDP sockets) or single-port daily
 * {@link AdaptiveTcpHandler} UDP acceleration with TCP repair when enabled.
 */
@Slf4j
public final class TransportClient extends AbstractTcpReconnectClient implements EventPublisher<TransportClient> {
    static final ThreadPool SCHEDULER = Sockets.newRpcScheduler();

    public final Delegate<TransportClient, EventArgs> onConnected = Delegate.create();
    public final Delegate<TransportClient, EventArgs> onDisconnected = Delegate.create();
    public final Delegate<TransportClient, NEventArgs<InetSocketAddress>> onReconnecting = Delegate.create();
    public final Delegate<TransportClient, EventArgs> onReconnected = Delegate.create();
    public final Delegate<TransportClient, NEventArgs<TransportSession>> onSessionReady = Delegate.create();
    public final Delegate<TransportClient, NEventArgs<Throwable>> onError = Delegate.create();

    @Getter
    private final TransportConfig config;
    private final String localPeerId;
    private final Sockets.ReconnectLogState reconnectLogState = new Sockets.ReconnectLogState();

    private volatile DefaultTransportSession session;
    private volatile CompletableFuture<TransportSession> handshakePromise;
    private volatile InetSocketAddress connectingEp;
    private volatile BiConsumer<TransportSession, ByteBuf> receiveHandler;

    public TransportClient(@NonNull TransportConfig config) {
        if (config.getTcpClientConfig() == null) {
            config.setTcpClientConfig(new TcpClientConfig());
        }
        this.config = config;
        this.localPeerId = TransportProtocol.resolvePeerId(config.getPeerId());
    }

    @Override
    public ThreadPool asyncScheduler() {
        return SCHEDULER;
    }

    public void setReceiveHandler(BiConsumer<TransportSession, ByteBuf> handler) {
        this.receiveHandler = handler;
    }

    public void resetHandlers() {
        onConnected.purge();
        onDisconnected.purge();
        onReconnecting.purge();
        onReconnected.purge();
        onSessionReady.purge();
        onError.purge();
        receiveHandler = null;
    }

    @Override
    public boolean isConnected() {
        DefaultTransportSession current = session;
        return current != null && current.isConnected();
    }

    public boolean isSessionReady() {
        return isConnected();
    }

    public TransportSession session() {
        return session;
    }

    public void connect(@NonNull InetSocketAddress remoteEp) throws TimeoutException {
        Future<Void> future = connectAsync(remoteEp);
        try {
            future.get(config.getTcpClientConfig().getConnectTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw InvalidException.sneaky(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException) {
                throw (TimeoutException) cause;
            }
            TimeoutException ex = new TimeoutException(MessageFormatter.format("{} connect {} fail", this, remoteEp).getMessage());
            ex.initCause(cause);
            throw ex;
        } catch (CancellationException e) {
            TimeoutException ex = new TimeoutException(MessageFormatter.format("{} connect {} cancelled", this, remoteEp).getMessage());
            ex.initCause(e);
            throw ex;
        }
    }

    public Future<Void> connectAsync(@NonNull InetSocketAddress remoteEp) {
        CompletableFuture<Void> tcpFuture = beginTcpConnect(remoteEp);
        CompletableFuture<Void> readyFuture = new CompletableFuture<Void>();
        tcpFuture.whenComplete((r, err) -> {
            if (err != null) {
                readyFuture.completeExceptionally(err);
                return;
            }
            DefaultTransportSession active = session;
            if (active != null && active.isConnected()) {
                readyFuture.complete(null);
                return;
            }
            CompletableFuture<TransportSession> hs = handshakePromise;
            if (hs == null) {
                readyFuture.completeExceptionally(new ClientDisconnectedException(remoteEp));
                return;
            }
            hs.whenComplete((s, hsErr) -> {
                if (hsErr != null) {
                    readyFuture.completeExceptionally(hsErr);
                } else {
                    readyFuture.complete(null);
                }
            });
        });
        return readyFuture;
    }

    private synchronized CompletableFuture<Void> beginTcpConnect(@NonNull InetSocketAddress remoteEp) {
        CompletableFuture<Void> promise = currentConnectPromise();
        TcpClientConfig tcpConf = config.getTcpClientConfig();
        InetSocketAddress currentEp = tcpConf.getServerEndpoint();
        if (promise != null && !promise.isDone()) {
            if (currentEp != null && !currentEp.equals(remoteEp)) {
                throw new InvalidException("{} is connecting {}", this, currentEp);
            }
            return promise;
        }

        if (tcpConf.getReactorName() == null) {
            tcpConf.setReactorName(Sockets.ReactorNames.RPC);
        }
        tcpConf.setServerEndpoint(remoteEp);
        handshakePromise = new CompletableFuture<TransportSession>();
        return beginConnect(Sockets.bootstrap(tcpConf, ch -> initChannel(ch, tcpConf)));
    }

    private void initChannel(Channel ch, TcpClientConfig tcpConf) {
        ChannelPipeline pipeline = ch.pipeline();
        int hb = tcpConf.getHeartbeatTimeout();
        if (hb > 0) {
            pipeline.addLast("coreIdle", new IdleStateHandler(hb, Math.max(1, hb / 2), 0));
        }
        Sockets.addTcpClientHandler(ch, tcpConf, tcpConf.getServerEndpoint());
        pipeline.addLast("coreHandshake", new ClientHandshakeHandler(tcpConf));
    }

    public void send(@NonNull ByteBuf payload) {
        DefaultTransportSession active = ensureConnectedSession(payload);
        active.send(payload);
    }

    public io.netty.channel.ChannelFuture sendAsync(@NonNull ByteBuf payload) {
        DefaultTransportSession active = session;
        if (active == null || !active.isConnected()) {
            try {
                active = ensureConnectedSession(payload);
            } catch (ClientDisconnectedException e) {
                Channel ch = getChannel();
                if (ch != null) {
                    return ch.newFailedFuture(e);
                }
                throw e;
            }
        }
        return active.sendAsync(payload);
    }

    private DefaultTransportSession ensureConnectedSession(ByteBuf payloadToReleaseOnError) {
        DefaultTransportSession active = session;
        if (active != null && active.isConnected()) {
            return active;
        }
        synchronized (this) {
            active = session;
            if (active == null || !active.isConnected()) {
                if (isShouldReconnect()) {
                    if (!FluentWait.polling(config.getTcpClientConfig().getWaitConnectMillis()).awaitTrue(w -> isConnected())) {
                        reconnectAsync();
                        payloadToReleaseOnError.release();
                        throw new ClientDisconnectedException(this);
                    }
                }
                active = session;
                if (active == null || !active.isConnected()) {
                    payloadToReleaseOnError.release();
                    throw new ClientDisconnectedException(this);
                }
            }
            return active;
        }
    }

    @Override
    protected synchronized boolean isShouldReconnect() {
        return !isClosed() && config.getTcpClientConfig().isEnableReconnect() && !isConnected();
    }

    @Override
    protected SocketAddress resolveConnectEndpoint(boolean reconnect) {
        if (reconnect) {
            handshakePromise = new CompletableFuture<TransportSession>();
            NEventArgs<InetSocketAddress> args = new NEventArgs<InetSocketAddress>(
                    ifNull(connectingEp, config.getTcpClientConfig().getServerEndpoint()));
            publishEvent(onReconnecting, args);
            return connectingEp = args.getValue();
        }
        return config.getTcpClientConfig().getServerEndpoint();
    }

    @Override
    protected void onConnectSuccess(SocketAddress endpoint, boolean reconnect, Channel channel) {
        InetSocketAddress ep = (InetSocketAddress) endpoint;
        connectingEp = null;
        reconnectLogState.reset();
        config.getTcpClientConfig().setServerEndpoint(ep);
        if (reconnect) {
            Sockets.logReconnectSuccess(log, this, ep, true);
        }
    }

    @Override
    protected void onConnectFailure(SocketAddress endpoint, boolean reconnect, Throwable cause) {
        Sockets.logConnectFailure(log, this, endpoint, reconnect, cause, true);
    }

    @Override
    protected void onReconnectRetry(SocketAddress endpoint, long delayMs, Throwable cause) {
        Sockets.logReconnectRetry(log, reconnectLogState, this, endpoint, delayMs, cause, true);
    }

    @Override
    protected void dispose() {
        config.getTcpClientConfig().setEnableReconnect(false);
        CancellationException cancelled = new CancellationException("transport client closed");
        cancelPendingConnect(cancelled);
        CompletableFuture<TransportSession> hs = handshakePromise;
        if (hs != null && !hs.isDone()) {
            hs.completeExceptionally(cancelled);
        }
        DefaultTransportSession current = session;
        session = null;
        if (current != null) {
            current.close();
        } else {
            Sockets.closeOnFlushed(getChannel());
        }
    }

    private void onChannelClosed(Channel ch, DefaultTransportSession closedSession) {
        if (closedSession != null) {
            closedSession.markDisconnected();
            if (session == closedSession) {
                session = null;
            }
        }
        CompletableFuture<TransportSession> hs = handshakePromise;
        if (hs != null && !hs.isDone() && getChannel() == ch) {
            hs.completeExceptionally(new ClientDisconnectedException(config.getTcpClientConfig().getServerEndpoint()));
        }
        publishEvent(onDisconnected, EventArgs.EMPTY);
        if (isShouldReconnect()) {
            reconnectAsync();
        }
    }

    private void handleError(ChannelHandlerContext ctx, Throwable cause) {
        log.error("transport client error {}", ctx.channel().remoteAddress(), cause);
        if (!ctx.channel().isActive()) {
            return;
        }
        NEventArgs<Throwable> args = new NEventArgs<Throwable>(cause);
        quietly(() -> publishEvent(onError, args));
        if (args.isCancel()) {
            return;
        }
        Sockets.closeOnFlushed(ctx.channel());
    }

    private final class ClientHandshakeHandler extends ByteToMessageDecoder {
        private final TcpClientConfig tcpConf;
        private final long proposedSessionId = TransportProtocol.randomSessionId();
        private final byte[] clientNonce = TransportProtocol.randomBytes(TransportProtocol.CLIENT_NONCE_BYTES);
        private final boolean requestAdaptive;
        private ScheduledFuture<?> timeoutTask;
        private boolean completed;

        ClientHandshakeHandler(TcpClientConfig tcpConf) {
            this.tcpConf = tcpConf;
            this.requestAdaptive = config.isEnableAdaptiveUdp() && tcpConf.getSocksCarrierProxy() == null;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            int flags = requestAdaptive ? TransportProtocol.FLAG_ADAPTIVE_UDP : 0;
            ByteBuf hello = TransportProtocol.encodeHello(ctx.alloc(), flags, config.getUdpMtu(),
                    proposedSessionId, clientNonce, localPeerId);
            ctx.writeAndFlush(hello);
            int timeoutMs = Math.max(500, Math.min(config.getHandshakeTimeoutMillis(),
                    Math.max(500, tcpConf.getConnectTimeoutMillis())));
            timeoutTask = ctx.executor().schedule(() -> {
                if (!completed) {
                    failHandshake(ctx, new TimeoutException("Transport handshake timeout"));
                }
            }, timeoutMs, TimeUnit.MILLISECONDS);
            super.channelActive(ctx);
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            TransportProtocol.HelloAck ack;
            try {
                ack = TransportProtocol.tryDecodeHelloAck(in);
            } catch (RuntimeException bad) {
                failHandshake(ctx, bad);
                return;
            }
            if (ack == null) {
                return;
            }
            completed = true;
            cancelTimeout();

            boolean adaptive = requestAdaptive
                    && (ack.flags & TransportProtocol.FLAG_ADAPTIVE_UDP) != 0
                    && ack.serverUdpPort > 0;
            int negotiatedMtu = RssUdpMtuBudget.negotiate(config.getUdpMtu(), ack.udpMtu);
            Channel ch = ctx.channel();
            ChannelPipeline p = ctx.pipeline();

            // Install framing + session handler above cipher/idle, then AdaptiveTcpHandler below cipher (strict barrier).
            AdaptiveTcpHandler adaptiveHandler = null;
            if (adaptive) {
                byte[] key = TransportProtocol.deriveAdaptiveKey(config.getAuthSecret(),
                        ack.sessionId, clientNonce, ack.serverNonce);
                AdaptiveTcpHandler.install(ch, ack.sessionId, key, null,
                        ack.serverUdpPort, ack.serverUdpPort, 0, false, null, negotiatedMtu);
                adaptiveHandler = p.get(AdaptiveTcpHandler.class);
            }

            boolean wasReconnect = session != null || connectingEp != null;
            DefaultTransportSession created = new DefaultTransportSession(ack.sessionId, localPeerId,
                    ack.peerId, ch, config.getMaxFrameBytes(), adaptive, adaptiveHandler);
            p.addLast("coreFrameDecoder", new LengthFieldBasedFrameDecoder(config.getMaxFrameBytes() + 1, 0, 4, 0, 4));
            p.addLast("coreSession", new ClientSessionHandler(created));
            p.remove(this);

            // Tell server we have installed AdaptiveTcpHandler so server can send freely with zero coalescing race.
            created.sendControlFrame(TransportProtocol.FRAME_READY, 0L, false);

            session = created;
            CompletableFuture<TransportSession> hs = handshakePromise;
            if (hs != null) {
                hs.complete(created);
            }
            publishEventAsync(onSessionReady, new NEventArgs<TransportSession>(created));
            if (wasReconnect) {
                publishEventAsync(onReconnected, EventArgs.EMPTY);
            } else {
                publishEventAsync(onConnected, EventArgs.EMPTY);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            cancelTimeout();
            if (!completed) {
                failHandshake(ctx, new ClientDisconnectedException(tcpConf.getServerEndpoint()));
                onChannelClosed(ctx.channel(), null);
            }
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cancelTimeout();
            failHandshake(ctx, cause);
        }

        private void failHandshake(ChannelHandlerContext ctx, Throwable cause) {
            completed = true;
            cancelTimeout();
            CompletableFuture<TransportSession> hs = handshakePromise;
            if (hs != null && !hs.isDone()) {
                hs.completeExceptionally(cause);
            }
            Sockets.closeOnFlushed(ctx.channel());
        }

        private void cancelTimeout() {
            ScheduledFuture<?> t = timeoutTask;
            if (t != null) {
                timeoutTask = null;
                t.cancel(false);
            }
        }
    }

    private final class ClientSessionHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final DefaultTransportSession bound;

        ClientSessionHandler(DefaultTransportSession bound) {
            super(false); // manual release in dispatchData / switch
            this.bound = bound;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) {
            if (!frame.isReadable()) {
                frame.release();
                return;
            }
            byte type = frame.readByte();
            switch (type) {
                case TransportProtocol.FRAME_DATA:
                    bound.dispatchData(frame, receiveHandler);
                    break;
                case TransportProtocol.FRAME_PING:
                    try {
                        if (frame.readableBytes() >= 8) {
                            bound.handlePing(frame.readLong());
                        }
                    } finally {
                        frame.release();
                    }
                    break;
                case TransportProtocol.FRAME_PONG:
                    try {
                        if (frame.readableBytes() >= 8) {
                            bound.handlePong(frame.readLong());
                        }
                    } finally {
                        frame.release();
                    }
                    break;
                default:
                    frame.release();
                    break;
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            onChannelClosed(ctx.channel(), bound);
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof IdleStateEvent) {
                IdleStateEvent e = (IdleStateEvent) evt;
                switch (e.state()) {
                    case READER_IDLE:
                        log.warn("transport client heartbeat loss {}", ctx.channel().remoteAddress());
                        ctx.close();
                        break;
                    case WRITER_IDLE:
                        bound.sendPing();
                        break;
                    default:
                        break;
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            handleError(ctx, cause);
        }
    }
}
