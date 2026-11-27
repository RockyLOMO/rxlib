package org.rx.net.transport.core;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Delegate;
import org.rx.core.Disposable;
import org.rx.core.EventArgs;
import org.rx.core.EventPublisher;
import org.rx.core.NEventArgs;
import org.rx.core.ThreadPool;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.rss.RssUdpMtuBudget;
import org.rx.net.rss.tcp.AdaptiveTcpHandler;
import org.rx.net.rss.tcp.AdaptiveUdpPort;
import org.rx.net.transport.TcpServerConfig;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.rx.core.Extends.quietly;

/**
 * Shared Transport Core reliable ordered {@link ByteBuf} server.
 * When {@code enableAdaptiveUdp = false}, binds zero UDP sockets.
 * When {@code enableAdaptiveUdp = true}, binds one single UDP port (no port hopping) shared across sessions.
 */
@Slf4j
public final class TransportServer extends Disposable implements EventPublisher<TransportServer> {
    public final Delegate<TransportServer, NEventArgs<TransportSession>> onConnected = Delegate.create();
    public final Delegate<TransportServer, NEventArgs<TransportSession>> onDisconnected = Delegate.create();
    public final Delegate<TransportServer, NEventArgs<Throwable>> onError = Delegate.create();
    public final Delegate<TransportServer, EventArgs> onClosed = Delegate.create();

    @Getter
    private final TransportConfig config;
    private final String localPeerId;
    private final Map<Long, DefaultTransportSession> sessionsById = new ConcurrentHashMap<Long, DefaultTransportSession>();
    private final AtomicInteger clientCount = new AtomicInteger();

    private ServerBootstrap bootstrap;
    private Channel serverChannel;
    private List<Channel> serverChannels;
    private AdaptiveUdpPort adaptiveUdpPort;
    private volatile BiConsumer<TransportSession, ByteBuf> receiveHandler;

    public TransportServer(@NonNull TransportConfig config) {
        if (config.getTcpServerConfig() == null) {
            throw new IllegalArgumentException("tcpServerConfig is required");
        }
        this.config = config;
        this.localPeerId = TransportProtocol.resolvePeerId(config.getPeerId());
    }

    @Override
    public ThreadPool asyncScheduler() {
        return TransportClient.SCHEDULER;
    }

    public void setReceiveHandler(BiConsumer<TransportSession, ByteBuf> handler) {
        this.receiveHandler = handler;
    }

    public boolean isStarted() {
        return serverChannel != null;
    }

    public int listenPort() {
        Channel ch = serverChannel;
        if (ch != null && ch.localAddress() instanceof InetSocketAddress) {
            return ((InetSocketAddress) ch.localAddress()).getPort();
        }
        return config.getTcpServerConfig().getListenPort();
    }

    /** Returns the bound single-port adaptive UDP port, or 0 in pure TCP mode. */
    public int udpPort() {
        AdaptiveUdpPort port = adaptiveUdpPort;
        return port == null ? 0 : port.port();
    }

    public Map<Long, TransportSession> sessions() {
        Map<Long, TransportSession> snapshot = new HashMap<Long, TransportSession>(sessionsById.size());
        snapshot.putAll(sessionsById);
        return Collections.unmodifiableMap(snapshot);
    }

    public TransportSession getSession(long sessionId) {
        return sessionsById.get(sessionId);
    }

    public boolean closeSession(long sessionId) {
        DefaultTransportSession session = sessionsById.remove(sessionId);
        if (session == null) {
            return false;
        }
        session.close();
        return true;
    }

    @SneakyThrows
    public synchronized void start() {
        checkNotClosed();
        if (isStarted()) {
            throw new InvalidException("TransportServer has started");
        }
        TcpServerConfig tcpConf = config.getTcpServerConfig();
        if (config.isEnableAdaptiveUdp()) {
            // Single-port daily adaptive UDP without port hopping.
            adaptiveUdpPort = AdaptiveUdpPort.listen(Sockets.newAnyEndpoint(config.getUdpBindPort()), 0, false, false);
        }
        try {
            bootstrap = Sockets.serverBootstrap(tcpConf, ch -> initChannel(ch, tcpConf))
                    .option(ChannelOption.SO_REUSEADDR, true);
            InetSocketAddress bindAddress = Sockets.newAnyEndpoint(tcpConf.getListenPort());
            serverChannels = Sockets.bindChannels(bootstrap, bindAddress, tcpConf);
            serverChannel = serverChannels.get(0);
        } catch (Throwable e) {
            AdaptiveUdpPort port = adaptiveUdpPort;
            adaptiveUdpPort = null;
            if (port != null) {
                quietly(port::close);
            }
            throw e;
        }
    }

    private void initChannel(Channel ch, TcpServerConfig tcpConf) {
        ChannelPipeline pipeline = ch.pipeline();
        int hb = tcpConf.getHeartbeatTimeout();
        if (hb > 0) {
            pipeline.addLast("coreIdle", new IdleStateHandler(hb, 0, 0));
        }
        Sockets.addTcpServerHandler(ch, tcpConf);
        pipeline.addLast("coreHandshake", new ServerHandshakeHandler(tcpConf));
    }

    @Override
    protected void dispose() {
        for (DefaultTransportSession session : sessionsById.values()) {
            quietly(session::close);
        }
        sessionsById.clear();
        AdaptiveUdpPort port = adaptiveUdpPort;
        adaptiveUdpPort = null;
        if (port != null) {
            quietly(port::close);
        }
        if (serverChannels != null) {
            for (Channel ch : serverChannels) {
                if (ch.isOpen()) {
                    ch.close();
                }
            }
        } else if (serverChannel != null && serverChannel.isOpen()) {
            serverChannel.close();
        }
        Sockets.closeBootstrap(bootstrap);
        publishEvent(onClosed, EventArgs.EMPTY);
    }

    private void handleError(ChannelHandlerContext ctx, Throwable cause) {
        log.error("transport server error {}", ctx.channel().remoteAddress(), cause);
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

    private final class ServerHandshakeHandler extends ByteToMessageDecoder {
        private final TcpServerConfig tcpConf;
        private boolean admitted;
        private boolean completed;
        private ScheduledFuture<?> timeoutTask;

        ServerHandshakeHandler(TcpServerConfig tcpConf) {
            this.tcpConf = tcpConf;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            int current = clientCount.incrementAndGet();
            if (current > tcpConf.getCapacity()) {
                clientCount.decrementAndGet();
                log.warn("TransportServer capacity exceeded {}/{}", current, tcpConf.getCapacity());
                Sockets.closeOnFlushed(ctx.channel());
                return;
            }
            admitted = true;
            int timeoutMs = Math.max(500, config.getHandshakeTimeoutMillis());
            timeoutTask = ctx.executor().schedule(() -> {
                if (!completed && ctx.channel().isActive()) {
                    log.warn("TransportServer handshake timeout {}", ctx.channel().remoteAddress());
                    ctx.close();
                }
            }, timeoutMs, TimeUnit.MILLISECONDS);
            super.channelActive(ctx);
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            TransportProtocol.Hello hello;
            try {
                hello = TransportProtocol.tryDecodeHello(in);
            } catch (RuntimeException bad) {
                log.warn("TransportServer invalid handshake from {}: {}", ctx.channel().remoteAddress(), bad.getMessage());
                ctx.close();
                return;
            }
            if (hello == null) {
                return;
            }
            completed = true;
            cancelTimeout();

            long sessionId = hello.sessionId;
            if (sessionId == 0L || sessionsById.containsKey(sessionId)) {
                do {
                    sessionId = TransportProtocol.randomSessionId();
                } while (sessionsById.containsKey(sessionId));
            }
            AdaptiveUdpPort udp = adaptiveUdpPort;
            boolean useAdaptive = udp != null && (hello.flags & TransportProtocol.FLAG_ADAPTIVE_UDP) != 0;
            int negotiatedMtu = RssUdpMtuBudget.negotiate(config.getUdpMtu(), hello.udpMtu);
            int serverUdpPort = useAdaptive ? udp.port() : 0;
            int ackFlags = useAdaptive ? TransportProtocol.FLAG_ADAPTIVE_UDP : 0;
            byte[] serverNonce = TransportProtocol.randomBytes(TransportProtocol.SERVER_NONCE_BYTES);

            ByteBuf ack = TransportProtocol.encodeHelloAck(ctx.alloc(), ackFlags, negotiatedMtu,
                    serverUdpPort, sessionId, serverNonce, localPeerId);
            ctx.writeAndFlush(ack);

            Channel ch = ctx.channel();
            ChannelPipeline p = ctx.pipeline();
            AdaptiveTcpHandler adaptiveHandler = null;
            if (useAdaptive) {
                byte[] key = TransportProtocol.deriveAdaptiveKey(config.getAuthSecret(),
                        sessionId, hello.clientNonce, serverNonce);
                AdaptiveTcpHandler.install(ch, sessionId, key, udp, serverUdpPort, serverUdpPort,
                        0, false, null, negotiatedMtu);
                adaptiveHandler = p.get(AdaptiveTcpHandler.class);
            }

            DefaultTransportSession session = new DefaultTransportSession(sessionId, localPeerId,
                    hello.peerId, ch, config.getMaxFrameBytes(), useAdaptive, adaptiveHandler);
            p.addLast("coreFrameDecoder", new LengthFieldBasedFrameDecoder(config.getMaxFrameBytes() + 1, 0, 4, 0, 4));
            p.addLast("coreSession", new ServerSessionHandler(session, admitted));
            admitted = false; // ownership transferred to ServerSessionHandler
            p.remove(this);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            cancelTimeout();
            if (admitted) {
                admitted = false;
                clientCount.decrementAndGet();
            }
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cancelTimeout();
            handleError(ctx, cause);
        }

        private void cancelTimeout() {
            ScheduledFuture<?> t = timeoutTask;
            if (t != null) {
                timeoutTask = null;
                t.cancel(false);
            }
        }
    }

    private final class ServerSessionHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final DefaultTransportSession session;
        private boolean admitted;
        private boolean ready;

        ServerSessionHandler(DefaultTransportSession session, boolean admitted) {
            super(false);
            this.session = session;
            this.admitted = admitted;
        }

        private void ensureReady() {
            if (ready) {
                return;
            }
            ready = true;
            sessionsById.put(session.sessionId(), session);
            NEventArgs<TransportSession> args = new NEventArgs<TransportSession>(session);
            publishEvent(onConnected, args);
            if (args.isCancel()) {
                session.close();
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) {
            if (!frame.isReadable()) {
                frame.release();
                return;
            }
            byte type = frame.readByte();
            switch (type) {
                case TransportProtocol.FRAME_READY:
                    frame.release();
                    ensureReady();
                    break;
                case TransportProtocol.FRAME_DATA:
                    ensureReady();
                    session.dispatchData(frame, receiveHandler);
                    break;
                case TransportProtocol.FRAME_PING:
                    ensureReady();
                    try {
                        if (frame.readableBytes() >= 8) {
                            session.handlePing(frame.readLong());
                        }
                    } finally {
                        frame.release();
                    }
                    break;
                case TransportProtocol.FRAME_PONG:
                    ensureReady();
                    try {
                        if (frame.readableBytes() >= 8) {
                            session.handlePong(frame.readLong());
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
            session.markDisconnected();
            if (admitted) {
                admitted = false;
                clientCount.decrementAndGet();
            }
            sessionsById.remove(session.sessionId(), session);
            if (ready) {
                publishEventAsync(onDisconnected, new NEventArgs<TransportSession>(session));
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof IdleStateEvent) {
                IdleStateEvent e = (IdleStateEvent) evt;
                if (e.state() == IdleState.READER_IDLE) {
                    log.warn("transport server heartbeat loss {}", ctx.channel().remoteAddress());
                    ctx.close();
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            handleError(ctx, cause);
        }
    }
}
