package org.rx.net.transport.core;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.compression.JdkZlibDecoder;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
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
import org.rx.net.EgressCodec;
import org.rx.net.EgressConfig;
import org.rx.net.Sockets;
import org.rx.net.rss.RssUdpMtuBudget;
import org.rx.net.rss.tcp.AdaptiveTcpHandler;
import org.rx.net.transport.AbstractTcpReconnectClient;
import org.rx.net.transport.ClientDisconnectedException;
import org.rx.net.transport.TcpClientConfig;
import org.slf4j.helpers.MessageFormatter;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
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
 * Shared Transport Core v2 reliable ordered {@link ByteBuf} client.
 * Supports physical TCP {@link EgressCodec}, optional post-handshake AES-128-GCM AEAD encryption,
 * optional post-handshake business zlib compression, single-port daily {@link AdaptiveTcpHandler},
 * and in-process {@link LocalChannel} virtual carriers (e.g. RRP RAW Relay).
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
    private volatile SocketAddress localCarrierTarget;
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
        awaitReady(connectAsync(remoteEp), remoteEp);
    }

    /** Connects over an in-process Netty {@link LocalAddress} virtual carrier (e.g. RRP RAW Relay stream). */
    public void connectLocal(@NonNull LocalAddress localAddress) throws TimeoutException {
        awaitReady(connectLocalAsync(localAddress), localAddress);
    }

    public Future<Void> connectLocalAsync(@NonNull LocalAddress localAddress) {
        validateClientConfig(true);
        CompletableFuture<Void> carrierFuture = beginLocalConnect(localAddress);
        return chainHandshakeReady(carrierFuture, localAddress);
    }

    public Future<Void> connectAsync(@NonNull InetSocketAddress remoteEp) {
        validateClientConfig(false);
        CompletableFuture<Void> tcpFuture = beginTcpConnect(remoteEp);
        return chainHandshakeReady(tcpFuture, remoteEp);
    }

    private void awaitReady(Future<Void> future, SocketAddress target) throws TimeoutException {
        try {
            future.get(Math.max(500, config.getTcpClientConfig().getConnectTimeoutMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw InvalidException.sneaky(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException) {
                throw (TimeoutException) cause;
            }
            TimeoutException ex = new TimeoutException(MessageFormatter.format("{} connect {} fail", this, target).getMessage());
            ex.initCause(cause);
            throw ex;
        } catch (CancellationException e) {
            TimeoutException ex = new TimeoutException(MessageFormatter.format("{} connect {} cancelled", this, target).getMessage());
            ex.initCause(e);
            throw ex;
        }
    }

    private CompletableFuture<Void> chainHandshakeReady(CompletableFuture<Void> connectFuture, SocketAddress target) {
        CompletableFuture<Void> readyFuture = new CompletableFuture<Void>();
        connectFuture.whenComplete((r, err) -> {
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
                readyFuture.completeExceptionally(new ClientDisconnectedException(target));
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

    private void validateClientConfig(boolean localCarrier) {
        if (config.isEnableEncryption() && !TransportProtocol.hasAuthSecret(config.getAuthSecret())) {
            throw new IllegalArgumentException("TransportClient encryption requires non-empty authSecret");
        }
        if (!localCarrier) {
            resolveEffectiveEgress(config.getTcpClientConfig()).validateClient();
        }
    }

    private EgressConfig resolveEffectiveEgress(TcpClientConfig tcpConf) {
        EgressConfig top = config.effectiveEgressConfig();
        if (top.getTcpCodec() != EgressCodec.NONE) {
            return top;
        }
        if (tcpConf != null && tcpConf.getEgressConfig() != null) {
            return tcpConf.getEgressConfig();
        }
        return EgressConfig.none();
    }

    private synchronized CompletableFuture<Void> beginLocalConnect(@NonNull LocalAddress localAddress) {
        CompletableFuture<Void> promise = currentConnectPromise();
        if (promise != null && !promise.isDone()) {
            return promise;
        }
        TcpClientConfig tcpConf = config.getTcpClientConfig();
        if (tcpConf.getReactorName() == null) {
            tcpConf.setReactorName(Sockets.ReactorNames.RPC);
        }
        tcpConf.setEnableReconnect(false);
        this.localCarrierTarget = localAddress;
        handshakePromise = new CompletableFuture<TransportSession>();
        return beginConnect(Sockets.bootstrap(tcpConf, localAddress, ch -> initChannel(ch, tcpConf, true)));
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
        this.localCarrierTarget = null;
        tcpConf.setServerEndpoint(remoteEp);
        handshakePromise = new CompletableFuture<TransportSession>();
        return beginConnect(Sockets.bootstrap(tcpConf, ch -> initChannel(ch, tcpConf, false)));
    }

    private void initChannel(Channel ch, TcpClientConfig tcpConf, boolean localCarrier) {
        ChannelPipeline pipeline = ch.pipeline();
        EgressConfig egress = localCarrier ? EgressConfig.none() : resolveEffectiveEgress(tcpConf);
        if (!localCarrier) {
            EgressCodec.installClient(pipeline, egress);
            Sockets.addTcpClientHandler(ch, tcpConf, tcpConf.getServerEndpoint());
        } else {
            ch.attr(EgressCodec.ATTR_SELECTED).set(EgressCodec.NONE);
        }
        int hb = tcpConf.getHeartbeatTimeout();
        if (hb > 0) {
            pipeline.addLast("coreIdle", new IdleStateHandler(hb, Math.max(1, hb / 2), 0));
        }
        pipeline.addLast("coreHandshake", new ClientHandshakeHandler(tcpConf, egress.getTcpCodec(), localCarrier));
    }

    public void send(@NonNull ByteBuf payload) {
        DefaultTransportSession active = ensureConnectedSession(payload);
        active.send(payload);
    }

    public ChannelFuture sendAsync(@NonNull ByteBuf payload) {
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
        return !isClosed() && localCarrierTarget == null && config.getTcpClientConfig().isEnableReconnect() && !isConnected();
    }

    @Override
    protected SocketAddress resolveConnectEndpoint(boolean reconnect) {
        if (localCarrierTarget != null) {
            return localCarrierTarget;
        }
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
        if (endpoint instanceof InetSocketAddress) {
            InetSocketAddress ep = (InetSocketAddress) endpoint;
            connectingEp = null;
            reconnectLogState.reset();
            config.getTcpClientConfig().setServerEndpoint(ep);
            if (reconnect) {
                Sockets.logReconnectSuccess(log, this, ep, true);
            }
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
            hs.completeExceptionally(new ClientDisconnectedException(
                    localCarrierTarget != null ? localCarrierTarget : config.getTcpClientConfig().getServerEndpoint()));
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
        private final EgressCodec egressCodec;
        private final boolean localCarrier;
        private final long proposedSessionId = TransportProtocol.randomSessionId();
        private final byte[] clientNonce = TransportProtocol.randomBytes(TransportProtocol.CLIENT_NONCE_BYTES);
        private final boolean requestAdaptive;
        private final boolean requestDeferredAdaptive;
        private final boolean requestCompress;
        private final boolean requestAead;
        private ScheduledFuture<?> timeoutTask;
        private boolean completed;

        ClientHandshakeHandler(TcpClientConfig tcpConf, EgressCodec egressCodec, boolean localCarrier) {
            this.tcpConf = tcpConf;
            this.egressCodec = egressCodec == null ? EgressCodec.NONE : egressCodec;
            this.localCarrier = localCarrier;
            boolean wantAdaptive = config.isEnableAdaptiveUdp() && tcpConf.getSocksCarrierProxy() == null;
            this.requestAdaptive = wantAdaptive && !localCarrier;
            this.requestDeferredAdaptive = wantAdaptive && localCarrier;
            this.requestCompress = config.isEnableCompression();
            this.requestAead = config.isEnableEncryption();
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            int flags = 0;
            if (requestAdaptive || requestDeferredAdaptive) {
                flags |= TransportProtocol.FLAG_ADAPTIVE_UDP;
            }
            if (requestCompress) {
                flags |= TransportProtocol.FLAG_COMPRESS;
            }
            if (requestAead) {
                flags |= TransportProtocol.FLAG_AEAD;
            }
            ByteBuf hello = TransportProtocol.encodeHello(ctx.alloc(), flags, egressCodec, config.getCompressionLevel(),
                    config.getUdpMtu(), proposedSessionId, clientNonce, localPeerId, config.getAuthSecret());
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
            if (!TransportProtocol.verifyHelloAckAuth(config.getAuthSecret(), clientNonce, ack)) {
                failHandshake(ctx, new SecurityException("Transport server handshake authentication failed"));
                return;
            }
            if (ack.egressCode != egressCodec.wireCode()) {
                failHandshake(ctx, new SecurityException("Transport server egress mode mismatch: expected="
                        + egressCodec.wireCode() + " actual=" + ack.egressCode));
                return;
            }
            boolean useAead = (ack.flags & TransportProtocol.FLAG_AEAD) != 0;
            if (requestAead && !useAead) {
                failHandshake(ctx, new SecurityException("Transport server downgraded required AEAD encryption"));
                return;
            }
            if (useAead && !TransportProtocol.hasAuthSecret(config.getAuthSecret())) {
                failHandshake(ctx, new SecurityException("Transport server enabled AEAD without shared authSecret"));
                return;
            }
            boolean useCompress = requestCompress && (ack.flags & TransportProtocol.FLAG_COMPRESS) != 0;
            int negotiatedCompLevel = useCompress ? Math.max(1, Math.min(9, ack.compressionLevel > 0 ? ack.compressionLevel : config.getCompressionLevel())) : 0;

            completed = true;
            cancelTimeout();

            boolean adaptive = requestAdaptive
                    && (ack.flags & TransportProtocol.FLAG_ADAPTIVE_UDP) != 0
                    && ack.serverUdpPort > 0;
            boolean deferredAdaptive = requestDeferredAdaptive
                    && (ack.flags & TransportProtocol.FLAG_ADAPTIVE_UDP) != 0;
            int negotiatedMtu = RssUdpMtuBudget.negotiate(config.getUdpMtu(), ack.udpMtu);
            Channel ch = ctx.channel();
            ChannelPipeline p = ctx.pipeline();

            ByteBuf leftover = in.isReadable() ? in.readRetainedSlice(in.readableBytes()) : null;
            byte[] adaptiveKey = null;
            AdaptiveTcpHandler adaptiveHandler = null;
            if (adaptive) {
                adaptiveKey = TransportProtocol.deriveAdaptiveKey(config.getAuthSecret(),
                        ack.sessionId, clientNonce, ack.serverNonce);
                AdaptiveTcpHandler.install(ch, ack.sessionId, adaptiveKey, null,
                        ack.serverUdpPort, ack.serverUdpPort, 0, false, null, negotiatedMtu);
                adaptiveHandler = p.get(AdaptiveTcpHandler.class);
            } else if (deferredAdaptive) {
                adaptiveKey = TransportProtocol.deriveAdaptiveKey(config.getAuthSecret(),
                        ack.sessionId, clientNonce, ack.serverNonce);
                AdaptiveTcpHandler.installDeferred(ch, ack.sessionId, adaptiveKey, false, negotiatedMtu);
                adaptiveHandler = p.get(AdaptiveTcpHandler.class);
            }

            if (useAead) {
                byte[] aeadMaterial = TransportProtocol.deriveAeadKeyMaterial(config.getAuthSecret(),
                        ack.sessionId, clientNonce, ack.serverNonce);
                try {
                    p.addLast("coreAead", new TransportAeadCodec(true, aeadMaterial));
                } finally {
                    Arrays.fill(aeadMaterial, (byte) 0);
                }
            }
            if (useCompress) {
                int maxInflate = Math.max(config.getMaxFrameBytes() + 64 * 1024, EgressCodec.DEFAULT_MAX_INFLATE_BYTES);
                p.addLast("coreZipDecoder", new JdkZlibDecoder(ZlibWrapper.ZLIB, maxInflate));
                p.addLast("coreZipEncoder", ZlibCodecFactory.newZlibEncoder(ZlibWrapper.ZLIB, negotiatedCompLevel));
            }

            boolean wasReconnect = session != null || connectingEp != null;
            DefaultTransportSession created = new DefaultTransportSession(ack.sessionId, localPeerId,
                    ack.peerId, ch, config.getMaxFrameBytes(), adaptive || deferredAdaptive, adaptiveHandler,
                    adaptiveKey, useAead, useCompress, egressCodec);
            p.addLast("coreFrameDecoder", new LengthFieldBasedFrameDecoder(config.getMaxFrameBytes() + 1, 0, 4, 0, 4));
            p.addLast("coreSession", new ClientSessionHandler(created));

            String firstPostHandler = (adaptive || deferredAdaptive) ? "rssAdaptiveLength"
                    : (useAead ? "coreAead" : (useCompress ? "coreZipDecoder" : "coreFrameDecoder"));
            p.remove(this);

            // Tell server our post-handshake stack is installed before server sends business frames.
            created.sendControlFrame(TransportProtocol.FRAME_READY, 0L, false);

            if (leftover != null) {
                forwardToHandler(p, firstPostHandler, leftover);
            }

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
                failHandshake(ctx, new ClientDisconnectedException(
                        localCarrierTarget != null ? localCarrierTarget : tcpConf.getServerEndpoint()));
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

    static void forwardToHandler(ChannelPipeline p, String targetHandlerName, ByteBuf leftover) {
        String prevName = null;
        for (String name : p.names()) {
            if (targetHandlerName.equals(name)) {
                break;
            }
            prevName = name;
        }
        ChannelHandlerContext prevCtx = prevName == null ? null : p.context(prevName);
        if (prevCtx != null) {
            prevCtx.fireChannelRead(leftover);
        } else {
            p.fireChannelRead(leftover);
        }
    }

    private final class ClientSessionHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final DefaultTransportSession bound;

        ClientSessionHandler(DefaultTransportSession bound) {
            super(false);
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
