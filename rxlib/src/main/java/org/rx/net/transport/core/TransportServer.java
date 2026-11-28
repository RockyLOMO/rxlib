package org.rx.net.transport.core;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.compression.JdkZlibDecoder;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
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
import org.rx.net.EgressCodec;
import org.rx.net.EgressConfig;
import org.rx.net.Sockets;
import org.rx.net.rss.RssUdpMtuBudget;
import org.rx.net.rss.tcp.AdaptiveTcpHandler;
import org.rx.net.rss.tcp.AdaptiveUdpPort;
import org.rx.net.transport.TcpServerConfig;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.rx.core.Extends.quietly;

/**
 * Shared Transport Core v2 reliable ordered {@link ByteBuf} server.
 * Supports physical TCP {@link EgressCodec} (including server-only {@code AUTO} with explicit
 * {@code allowedCodecs}), bounded handshake replay cache, optional post-handshake AES-128-GCM AEAD,
 * optional post-handshake business zlib compression, single-port daily adaptive UDP, and in-process
 * {@link LocalAddress} / {@link LocalChannel} virtual carriers (e.g. RRP RAW Relay).
 */
@Slf4j
public final class TransportServer extends Disposable implements EventPublisher<TransportServer> {
    private static final int MAX_REPLAY_NONCES = 4096;
    private static final long REPLAY_WINDOW_MILLIS = 60_000L;

    public final Delegate<TransportServer, NEventArgs<TransportSession>> onConnected = Delegate.create();
    public final Delegate<TransportServer, NEventArgs<TransportSession>> onDisconnected = Delegate.create();
    public final Delegate<TransportServer, NEventArgs<Throwable>> onError = Delegate.create();
    public final Delegate<TransportServer, EventArgs> onClosed = Delegate.create();

    @Getter
    private final TransportConfig config;
    private final String localPeerId;
    private final Map<Long, DefaultTransportSession> sessionsById = new ConcurrentHashMap<Long, DefaultTransportSession>();
    private final AtomicInteger clientCount = new AtomicInteger();
    private final Object replayLock = new Object();
    private final LinkedHashMap<NonceKey, Long> seenClientNonces = new LinkedHashMap<NonceKey, Long>(128, 0.75f, false);

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

    /** Returns the bound single-port adaptive UDP port, or 0 in pure TCP / local-carrier mode. */
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
        validateServerConfig(false);
        TcpServerConfig tcpConf = config.getTcpServerConfig();
        if (config.isEnableAdaptiveUdp()) {
            adaptiveUdpPort = AdaptiveUdpPort.listen(Sockets.newAnyEndpoint(config.getUdpBindPort()), 0, false, false);
        }
        try {
            bootstrap = Sockets.serverBootstrap(tcpConf, ch -> initChannel(ch, tcpConf, false))
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

    /**
     * Starts this server on an in-process Netty {@link LocalAddress} virtual carrier (zero TCP/UDP socket bind),
     * suitable for hosting an end-to-end AEAD Transport Core / RPC service behind RRP RAW Relay.
     */
    @SneakyThrows
    public synchronized void startLocal(@NonNull LocalAddress localAddress) {
        checkNotClosed();
        if (isStarted()) {
            throw new InvalidException("TransportServer has started");
        }
        validateServerConfig(true);
        TcpServerConfig tcpConf = config.getTcpServerConfig();
        bootstrap = Sockets.serverBootstrap(tcpConf, localAddress, ch -> initChannel(ch, tcpConf, true));
        serverChannels = Sockets.bindChannels(bootstrap, localAddress, tcpConf);
        serverChannel = serverChannels.get(0);
    }

    /** Attaches an already-connected virtual carrier channel (e.g. RRP RAW Relay {@link LocalChannel}). */
    public void attachCarrierChannel(@NonNull Channel ch) {
        checkNotClosed();
        validateServerConfig(true);
        initChannel(ch, config.getTcpServerConfig(), true);
    }

    private void validateServerConfig(boolean localCarrier) {
        if (config.isEnableEncryption() && !TransportProtocol.hasAuthSecret(config.getAuthSecret())) {
            throw new IllegalArgumentException("TransportServer encryption requires non-empty authSecret");
        }
        if (!localCarrier) {
            resolveEffectiveEgress(config.getTcpServerConfig()).validateServer();
        }
    }

    private EgressConfig resolveEffectiveEgress(TcpServerConfig tcpConf) {
        EgressConfig top = config.effectiveEgressConfig();
        if (top.getTcpCodec() != EgressCodec.NONE) {
            return top;
        }
        if (tcpConf != null && tcpConf.getEgressConfig() != null) {
            return tcpConf.getEgressConfig();
        }
        return EgressConfig.none();
    }

    private void initChannel(Channel ch, TcpServerConfig tcpConf, boolean localCarrier) {
        ChannelPipeline pipeline = ch.pipeline();
        if (!localCarrier && !(ch instanceof LocalChannel)) {
            EgressConfig egress = resolveEffectiveEgress(tcpConf);
            EgressCodec.installServer(pipeline, egress, EgressCodec.InnerProtocol.TRANSPORT_CORE);
            Sockets.addTcpServerHandler(ch, tcpConf);
        } else {
            ch.attr(EgressCodec.ATTR_SELECTED).set(EgressCodec.NONE);
        }
        int hb = tcpConf.getHeartbeatTimeout();
        if (hb > 0) {
            pipeline.addLast("coreIdle", new IdleStateHandler(hb, 0, 0));
        }
        pipeline.addLast("coreHandshake", new ServerHandshakeHandler(tcpConf, localCarrier || ch instanceof LocalChannel));
    }

    private boolean recordFreshClientNonce(byte[] clientNonce) {
        long now = System.currentTimeMillis();
        NonceKey key = new NonceKey(clientNonce);
        synchronized (replayLock) {
            Iterator<Map.Entry<NonceKey, Long>> it = seenClientNonces.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<NonceKey, Long> e = it.next();
                if (now - e.getValue() > REPLAY_WINDOW_MILLIS) {
                    it.remove();
                } else {
                    break;
                }
            }
            if (seenClientNonces.containsKey(key)) {
                return false;
            }
            while (seenClientNonces.size() >= MAX_REPLAY_NONCES) {
                Iterator<Map.Entry<NonceKey, Long>> evict = seenClientNonces.entrySet().iterator();
                if (!evict.hasNext()) {
                    break;
                }
                evict.next();
                evict.remove();
            }
            seenClientNonces.put(key, now);
            return true;
        }
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

    private static final class NonceKey {
        private final byte[] bytes;
        private final int hash;

        NonceKey(byte[] bytes) {
            this.bytes = bytes.clone();
            this.hash = Arrays.hashCode(this.bytes);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof NonceKey && Arrays.equals(bytes, ((NonceKey) o).bytes);
        }
    }

    private final class ServerHandshakeHandler extends ByteToMessageDecoder {
        private final TcpServerConfig tcpConf;
        private final boolean localCarrier;
        private boolean admitted;
        private boolean completed;
        private ScheduledFuture<?> timeoutTask;

        ServerHandshakeHandler(TcpServerConfig tcpConf, boolean localCarrier) {
            this.tcpConf = tcpConf;
            this.localCarrier = localCarrier;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            super.handlerAdded(ctx);
            if (ctx.channel().isActive()) {
                admitAndArm(ctx);
            }
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            admitAndArm(ctx);
            super.channelActive(ctx);
        }

        private void admitAndArm(ChannelHandlerContext ctx) {
            if (admitted) {
                return;
            }
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
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            TransportProtocol.Hello hello;
            try {
                hello = TransportProtocol.tryDecodeHello(in);
            } catch (RuntimeException bad) {
                log.warn("TransportServer invalid handshake from {}: {}", ctx.channel().remoteAddress(), bad.getMessage());
                in.skipBytes(in.readableBytes());
                completed = true;
                cancelTimeout();
                ctx.close();
                return;
            }
            if (hello == null) {
                return;
            }
            if (!TransportProtocol.verifyHelloAuth(config.getAuthSecret(), hello)) {
                log.warn("TransportServer rejected unauthenticated handshake from {}", ctx.channel().remoteAddress());
                rejectAndClose(ctx, in);
                return;
            }
            EgressCodec selectedEgress = ctx.channel().attr(EgressCodec.ATTR_SELECTED).get();
            if (selectedEgress == null) {
                selectedEgress = EgressCodec.NONE;
            }
            if (hello.egressCode != selectedEgress.wireCode()) {
                log.warn("TransportServer rejected egress mismatch from {}: selected={} claimed={}",
                        ctx.channel().remoteAddress(), selectedEgress, hello.egressCode);
                rejectAndClose(ctx, in);
                return;
            }
            if (!recordFreshClientNonce(hello.clientNonce)) {
                log.warn("TransportServer rejected replayed client nonce from {}", ctx.channel().remoteAddress());
                rejectAndClose(ctx, in);
                return;
            }
            boolean clientWantsAead = (hello.flags & TransportProtocol.FLAG_AEAD) != 0;
            if (config.isEnableEncryption() && !clientWantsAead) {
                log.warn("TransportServer rejected unencrypted client from {}", ctx.channel().remoteAddress());
                rejectAndClose(ctx, in);
                return;
            }
            if (clientWantsAead && !TransportProtocol.hasAuthSecret(config.getAuthSecret())) {
                log.warn("TransportServer rejected AEAD client without server authSecret from {}", ctx.channel().remoteAddress());
                rejectAndClose(ctx, in);
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
            boolean useAdaptive = !localCarrier && udp != null && (hello.flags & TransportProtocol.FLAG_ADAPTIVE_UDP) != 0;
            boolean deferredAdaptive = localCarrier && config.isEnableAdaptiveUdp()
                    && (hello.flags & TransportProtocol.FLAG_ADAPTIVE_UDP) != 0;
            boolean useAead = clientWantsAead;
            boolean useCompress = (hello.flags & TransportProtocol.FLAG_COMPRESS) != 0 && config.isEnableCompression();
            int negotiatedCompLevel = useCompress
                    ? Math.max(1, Math.min(9, hello.compressionLevel > 0 ? hello.compressionLevel : config.getCompressionLevel()))
                    : 0;
            int negotiatedMtu = RssUdpMtuBudget.negotiate(config.getUdpMtu(), hello.udpMtu);
            int serverUdpPort = useAdaptive ? udp.port() : 0;
            int ackFlags = 0;
            if (useAdaptive || deferredAdaptive) {
                ackFlags |= TransportProtocol.FLAG_ADAPTIVE_UDP;
            }
            if (useAead) {
                ackFlags |= TransportProtocol.FLAG_AEAD;
            }
            if (useCompress) {
                ackFlags |= TransportProtocol.FLAG_COMPRESS;
            }
            byte[] serverNonce = TransportProtocol.randomBytes(TransportProtocol.SERVER_NONCE_BYTES);

            ByteBuf ack = TransportProtocol.encodeHelloAck(ctx.alloc(), ackFlags, selectedEgress, negotiatedCompLevel,
                    negotiatedMtu, serverUdpPort, sessionId, hello.clientNonce, serverNonce, localPeerId, config.getAuthSecret());
            ctx.writeAndFlush(ack);

            Channel ch = ctx.channel();
            ChannelPipeline p = ctx.pipeline();
            ByteBuf leftover = in.isReadable() ? in.readRetainedSlice(in.readableBytes()) : null;

            byte[] adaptiveKey = null;
            AdaptiveTcpHandler adaptiveHandler = null;
            if (useAdaptive) {
                adaptiveKey = TransportProtocol.deriveAdaptiveKey(config.getAuthSecret(),
                        sessionId, hello.clientNonce, serverNonce);
                AdaptiveTcpHandler.install(ch, sessionId, adaptiveKey, udp, serverUdpPort, serverUdpPort,
                        0, false, null, negotiatedMtu);
                adaptiveHandler = p.get(AdaptiveTcpHandler.class);
            } else if (deferredAdaptive) {
                adaptiveKey = TransportProtocol.deriveAdaptiveKey(config.getAuthSecret(),
                        sessionId, hello.clientNonce, serverNonce);
                AdaptiveTcpHandler.installDeferred(ch, sessionId, adaptiveKey, true, negotiatedMtu);
                adaptiveHandler = p.get(AdaptiveTcpHandler.class);
            }

            if (useAead) {
                byte[] aeadMaterial = TransportProtocol.deriveAeadKeyMaterial(config.getAuthSecret(),
                        sessionId, hello.clientNonce, serverNonce);
                try {
                    p.addLast("coreAead", new TransportAeadCodec(false, aeadMaterial));
                } finally {
                    Arrays.fill(aeadMaterial, (byte) 0);
                }
            }
            if (useCompress) {
                int maxInflate = Math.max(config.getMaxFrameBytes() + 64 * 1024, config.getMaxBusinessInflateBytes());
                p.addLast("coreZipDecoder", new JdkZlibDecoder(ZlibWrapper.ZLIB, maxInflate));
                p.addLast("coreZipEncoder", ZlibCodecFactory.newZlibEncoder(ZlibWrapper.ZLIB, negotiatedCompLevel));
            }

            DefaultTransportSession session = new DefaultTransportSession(sessionId, localPeerId,
                    hello.peerId, ch, config.getMaxFrameBytes(), useAdaptive || deferredAdaptive, adaptiveHandler,
                    adaptiveKey, useAead, useCompress, selectedEgress);
            p.addLast("coreFrameDecoder", new LengthFieldBasedFrameDecoder(config.getMaxFrameBytes() + 1, 0, 4, 0, 4));
            p.addLast("coreSession", new ServerSessionHandler(session, admitted));
            admitted = false; // ownership transferred to ServerSessionHandler

            String firstPostHandler = (useAdaptive || deferredAdaptive) ? "rssAdaptiveLength"
                    : (useAead ? "coreAead" : (useCompress ? "coreZipDecoder" : "coreFrameDecoder"));
            p.remove(this);
            if (leftover != null) {
                TransportClient.forwardToHandler(p, firstPostHandler, leftover);
            }
        }

        private void rejectAndClose(ChannelHandlerContext ctx, ByteBuf in) {
            in.skipBytes(in.readableBytes());
            completed = true;
            cancelTimeout();
            ctx.close();
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
