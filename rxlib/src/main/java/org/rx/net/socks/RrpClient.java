package org.rx.net.socks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.Tuple;
import org.rx.core.Disposable;
import org.rx.core.Strings;
import org.rx.core.Sys;
import org.rx.io.FurySerializer;
import org.rx.net.EgressCodec;
import org.rx.net.EgressConfig;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;
import org.rx.net.transport.ClientDisconnectedException;
import org.rx.net.transport.core.TransportClient;
import org.rx.net.transport.core.TransportConfig;
import org.rx.net.transport.core.TransportSession;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.rx.core.Extends.eq;
import static org.rx.core.Extends.tryClose;
import static org.rx.net.socks.RrpConfig.*;

/**
 * Reverse Relay Proxy (RRP) client built on shared pure-TCP {@link TransportClient} v2 and
 * unified {@link EgressCodec}.
 * <p>
 * Preserves SOCKS5 reverse proxy mode (with all sensitive credentials stripped from logs) and adds
 * {@link org.rx.net.registry.NodeRegistry}-authorized RAW logical stream hosting &amp; virtual
 * {@link LocalAddress} / {@link io.netty.channel.local.LocalChannel} carrier bridging so end-to-end
 * AEAD {@link TransportClient}/{@link org.rx.net.transport.core.TransportServer} or RPC can run
 * transparently over RRP Relay without extra public ports or Relay-side deserialization.
 */
@Slf4j
public class RrpClient extends Disposable {
    static final int MAX_CHANNEL_ID_LEN = RrpServer.MAX_CHANNEL_ID_LEN;
    static final int MAX_PENDING_FORWARD_BYTES = RrpServer.MAX_PENDING_FORWARD_BYTES;
    static final boolean enableMemoryChannel = true;
    static final AttributeKey<LocalRelayBuffer> ATTR_LOCAL_RELAY_BUF = AttributeKey.valueOf("rLocalRelayBuf");
    static final AttributeKey<ServerRelayBuffer> ATTR_SERVER_RELAY_BUF = AttributeKey.valueOf("rServerRelayBuf");
    static final AtomicLong LOCAL_ADDRESS_SEQ = new AtomicLong();
    private static final String WRITABILITY_HANDLER_NAME = "rrpClientWritability";

    /** Listener for callback-mode RAW logical streams. Borrowed {@code data} is released after callback returns. */
    public interface RawStreamListener {
        void onData(long streamId, ByteBuf data);

        default void onHalfClose(long streamId) {
        }

        default void onClose(long streamId, byte reason) {
        }

        default void onPause(long streamId) {
        }

        default void onResume(long streamId) {
        }
    }

    static abstract class PendingWriteBuffer {
        final Queue<ByteBuf> pendingWrites = new ConcurrentLinkedQueue<ByteBuf>();
        final AtomicInteger pendingBytes = new AtomicInteger();
        final AtomicInteger draining = new AtomicInteger();

        boolean offer(Channel channel, ByteBuf payload) {
            if (!canQueue(channel)) {
                ReferenceCountUtil.release(payload);
                onRejected(channel);
                return false;
            }

            int bytes = payload.readableBytes();
            int queuedBytes = pendingBytes.addAndGet(bytes);
            if (queuedBytes > MAX_PENDING_FORWARD_BYTES) {
                pendingBytes.addAndGet(-bytes);
                ReferenceCountUtil.release(payload);
                onOverflow(channel, queuedBytes);
                return false;
            }

            pendingWrites.offer(payload);
            scheduleDrain(channel);
            onQueueStateChanged(channel);
            return true;
        }

        boolean hasPendingWrites() {
            return !pendingWrites.isEmpty();
        }

        boolean canQueue(Channel channel) {
            return channel.isOpen();
        }

        boolean canWrite(Channel channel) {
            return channel.isActive() && channel.isWritable();
        }

        void scheduleDrain(Channel channel) {
            if (!canWrite(channel) || !draining.compareAndSet(0, 1)) {
                return;
            }
            if (channel.eventLoop().inEventLoop()) {
                drain(channel);
                return;
            }
            channel.eventLoop().execute(() -> drain(channel));
        }

        void drain(Channel channel) {
            boolean flushed = false;
            try {
                ByteBuf payload;
                while (canWrite(channel) && (payload = pendingWrites.poll()) != null) {
                    pendingBytes.addAndGet(-payload.readableBytes());
                    channel.write(payload).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
                    flushed = true;
                }
                if (flushed) {
                    channel.flush();
                }
            } finally {
                draining.set(0);
                if (canWrite(channel) && !pendingWrites.isEmpty()) {
                    scheduleDrain(channel);
                } else {
                    onQueueStateChanged(channel);
                }
            }
        }

        void releaseAll() {
            ByteBuf payload;
            while ((payload = pendingWrites.poll()) != null) {
                ReferenceCountUtil.release(payload);
            }
            pendingBytes.set(0);
            draining.set(0);
            onRelease();
        }

        void onQueueStateChanged(Channel channel) {
        }

        void onRelease() {
        }

        void onRejected(Channel channel) {
        }

        abstract void onOverflow(Channel channel, int queuedBytes);
    }

    static class LocalRelayBuffer extends PendingWriteBuffer {
        final RrpClient owner;
        final Channel serverChannel;

        LocalRelayBuffer(RrpClient owner, Channel serverChannel) {
            this.owner = owner;
            this.serverChannel = serverChannel;
        }

        @Override
        void onQueueStateChanged(Channel channel) {
            syncServerReadState();
        }

        @Override
        void onRelease() {
            syncServerReadState();
        }

        @Override
        void onRejected(Channel channel) {
            syncServerReadState();
        }

        @Override
        void onOverflow(Channel channel, int queuedBytes) {
            log.warn("RrpClient local channel {} queued bytes {} exceed cap {}, close channel",
                    channel, queuedBytes, MAX_PENDING_FORWARD_BYTES);
            RrpServer.closeChannel(channel);
            syncServerReadState();
        }

        void syncServerReadState() {
            if (owner != null) {
                owner.syncServerReadState(serverChannel);
            }
        }
    }

    static class ServerRelayBuffer extends PendingWriteBuffer {
        final Channel localChannel;

        ServerRelayBuffer(Channel localChannel) {
            this.localChannel = localChannel;
        }

        @Override
        boolean canQueue(Channel channel) {
            return channel.isActive() && localChannel.isOpen();
        }

        @Override
        void onQueueStateChanged(Channel channel) {
            syncLocalReadState(channel);
        }

        @Override
        void onRelease() {
            if (localChannel.isOpen()) {
                Sockets.enableAutoRead(localChannel);
            }
        }

        @Override
        void onRejected(Channel channel) {
            RrpServer.closeChannel(localChannel);
        }

        @Override
        void onOverflow(Channel channel, int queuedBytes) {
            log.warn("RrpClient server channel {} queued bytes {} exceed cap {}, close local channel {}",
                    channel, queuedBytes, MAX_PENDING_FORWARD_BYTES, localChannel);
            RrpServer.closeChannel(localChannel);
        }

        void syncLocalReadState(Channel serverChannel) {
            if (!localChannel.isOpen()) {
                return;
            }
            if (serverChannel.isWritable() && !hasPendingWrites()) {
                Sockets.enableAutoRead(localChannel);
            } else {
                Sockets.disableAutoRead(localChannel);
            }
        }
    }

    static class RpClientProxy extends Disposable {
        final RrpClient owner;
        final RrpConfig.Proxy p;
        final TransportSession session;
        final Channel serverChannel;
        final Map<String, Channel> localChannels = new ConcurrentHashMap<String, Channel>();
        SocketAddress localEndpoint;
        SocksProxyServer localSS;

        @Override
        protected void dispose() {
            for (Channel ch : localChannels.values()) {
                Sockets.closeOnFlushed(ch);
            }
            localChannels.clear();
            tryClose(localSS);
        }

        public RpClientProxy(RrpConfig.Proxy p, Channel serverChannel) {
            this(null, p, null, serverChannel);
        }

        public RpClientProxy(RrpClient owner, RrpConfig.Proxy p, TransportSession session, Channel serverChannel) {
            this.owner = owner;
            this.p = p;
            this.session = session;
            this.serverChannel = serverChannel;

            SocksConfig conf = new SocksConfig(0);
            // In-process LocalAddress requires zero extra cipher overhead; outer RRP TCP is AEAD-protected.
            conf.setTransportFlags(null);
            if (enableMemoryChannel) {
                conf.setMemoryAddress(new LocalAddress("rrp-" + p.getRemotePort() + "-" + LOCAL_ADDRESS_SEQ.incrementAndGet()));
                localSS = new SocksProxyServer(conf, (u, w) -> {
                    if (!eq(p.getAuth(), u + ":" + w)) {
                        log.debug("RrpClient SOCKS5 auth rejected remotePort={} user={}", p.getRemotePort(), u);
                        return null;
                    }
                    return new SocksUser(u);
                }, true, null);
                localEndpoint = conf.getMemoryAddress();
                log.debug("RrpClient Local SS bind R{} <-> Memory {}", p.getRemotePort(), conf.getMemoryAddress().id());
            } else {
                localSS = new SocksProxyServer(conf, (u, w) -> {
                    if (!eq(p.getAuth(), u + ":" + w)) {
                        log.debug("RrpClient SOCKS5 auth rejected remotePort={} user={}", p.getRemotePort(), u);
                        return null;
                    }
                    return new SocksUser(u);
                }, ch -> {
                    int bindPort = ((InetSocketAddress) ch.localAddress()).getPort();
                    log.debug("RrpClient Local SS bind R{} <-> L{}", p.getRemotePort(), bindPort);
                    localEndpoint = Sockets.newLoopbackEndpoint(bindPort);
                });
            }
        }

        boolean hasLocalBacklog() {
            for (Channel ch : localChannels.values()) {
                if (!ch.isOpen()) {
                    continue;
                }
                LocalRelayBuffer relayBuffer = ch.attr(ATTR_LOCAL_RELAY_BUF).get();
                if ((relayBuffer != null && relayBuffer.hasPendingWrites()) || (ch.isActive() && !ch.isWritable())) {
                    return true;
                }
            }
            return false;
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
            TransportSession session = proxyCtx.session;
            if (session == null || !session.isConnected()) {
                ReferenceCountUtil.release(msg);
                Sockets.closeOnFlushed(localChannel);
                return;
            }
            byte[] idBytes = channelId.getBytes(StandardCharsets.US_ASCII);
            ByteBuf hdr = ctx.alloc().ioBuffer(1 + 4 + 4 + idBytes.length);
            hdr.writeByte(RrpConfig.ACTION_FORWARD);
            hdr.writeInt(proxyCtx.p.remotePort);
            hdr.writeInt(idBytes.length);
            hdr.writeBytes(idBytes);
            session.sendAsync(Unpooled.wrappedBuffer(hdr, (ByteBuf) msg));
            if (!session.isWritable()) {
                Sockets.disableAutoRead(localChannel);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            Channel localChannel = ctx.channel();
            Tuple<RpClientProxy, String> attr = Sockets.getAttr(localChannel, ATTR_CLI_PROXY);
            RpClientProxy proxyCtx = attr.left;
            String channelId = attr.right;
            LocalRelayBuffer localRelayBuffer = localChannel.attr(ATTR_LOCAL_RELAY_BUF).getAndSet(null);
            if (localRelayBuffer != null) {
                localRelayBuffer.releaseAll();
            }
            ServerRelayBuffer serverRelayBuffer = localChannel.attr(ATTR_SERVER_RELAY_BUF).getAndSet(null);
            if (serverRelayBuffer != null) {
                serverRelayBuffer.releaseAll();
            }
            proxyCtx.localChannels.remove(channelId, localChannel);
            if (proxyCtx.owner != null && proxyCtx.serverChannel != null) {
                proxyCtx.owner.syncServerReadState(proxyCtx.serverChannel);
            }
            TransportSession session = proxyCtx.session;
            if (session != null && session.isConnected()) {
                byte[] idBytes = channelId.getBytes(StandardCharsets.US_ASCII);
                ByteBuf buf = ctx.alloc().ioBuffer(1 + 4 + 4 + idBytes.length);
                buf.writeByte(RrpConfig.ACTION_SYNC_CLOSE);
                buf.writeInt(proxyCtx.p.remotePort);
                buf.writeInt(idBytes.length);
                buf.writeBytes(idBytes);
                session.sendAsync(buf);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Channel localChannel = ctx.channel();
            log.warn("RrpClient local SOCKS relay error {} thrown", localChannel.remoteAddress(), cause);
            Sockets.closeOnFlushed(localChannel);
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
            Channel localChannel = ctx.channel();
            LocalRelayBuffer relayBuffer = localChannel.attr(ATTR_LOCAL_RELAY_BUF).get();
            if (relayBuffer != null) {
                relayBuffer.scheduleDrain(localChannel);
            }
            super.channelWritabilityChanged(ctx);
        }
    }

    private static final class PendingRawOpen {
        final int reqId;
        final RawStreamListener listener;
        final CompletableFuture<Long> future = new CompletableFuture<Long>();
        volatile ScheduledFuture<?> timeoutFuture;

        PendingRawOpen(int reqId, RawStreamListener listener) {
            this.reqId = reqId;
            this.listener = listener;
        }

        void cancelTimeout() {
            ScheduledFuture<?> f = timeoutFuture;
            if (f != null) {
                timeoutFuture = null;
                f.cancel(false);
            }
        }
    }

    private static final class RawStreamState {
        final long streamId;
        final RawStreamListener listener;
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicBoolean remotePaused = new AtomicBoolean();
        volatile RawLocalBridge bridge;

        RawStreamState(long streamId, RawStreamListener listener) {
            this.streamId = streamId;
            this.listener = listener;
        }
    }

    /**
     * Bridges one RRP RAW logical stream 1:1 to a Netty {@link io.netty.channel.local.LocalChannel}
     * (either accepted on a one-shot {@link LocalAddress} on the initiator side, or connected to a
     * registered local {@link LocalAddress} service on the target side).
     */
    private final class RawLocalBridge implements RawStreamListener {
        final long streamId;
        final Queue<ByteBuf> inboundQueue = new ConcurrentLinkedQueue<ByteBuf>();
        final AtomicInteger inboundPendingBytes = new AtomicInteger();
        final AtomicInteger draining = new AtomicInteger();
        final AtomicBoolean sentPauseToPeer = new AtomicBoolean();
        final AtomicBoolean closed = new AtomicBoolean();
        volatile Channel localChannel;
        volatile ServerBootstrap oneShotBootstrap;
        volatile Channel oneShotServerChannel;
        volatile LocalAddress carrierAddr;
        volatile boolean remoteHalfClosed;
        volatile boolean remotePaused;

        RawLocalBridge(long streamId) {
            this.streamId = streamId;
        }

        void attachLocalChannel(Channel ch) {
            if (closed.get()) {
                RrpServer.closeChannel(ch);
                return;
            }
            this.localChannel = ch;
            closeOneShotListener();
            syncLocalReadState();
            scheduleInboundDrain();
        }

        void attachOneShotListener(ServerBootstrap sb, Channel serverCh) {
            this.oneShotBootstrap = sb;
            this.oneShotServerChannel = serverCh;
            if (closed.get()) {
                closeOneShotListener();
            }
        }

        void closeOneShotListener() {
            Channel sc = oneShotServerChannel;
            oneShotServerChannel = null;
            if (sc != null) {
                RrpServer.closeChannel(sc);
            }
            ServerBootstrap sb = oneShotBootstrap;
            oneShotBootstrap = null;
            if (sb != null) {
                Sockets.closeBootstrap(sb);
            }
        }

        @Override
        public void onData(long streamId, ByteBuf data) {
            if (closed.get()) {
                return;
            }
            int len = data.readableBytes();
            if (len <= 0) {
                return;
            }
            int maxPending = Math.max(1024, config.getMaxStreamRelayPendingBytes());
            int queued = inboundPendingBytes.addAndGet(len);
            if (queued > maxPending) {
                inboundPendingBytes.addAndGet(-len);
                log.warn("RrpClient RAW local bridge overflow streamId={} pending={} cap={}", streamId, queued, maxPending);
                closeLocalAndNotifyRemote(RAW_CLOSE_OVERFLOW);
                return;
            }
            ByteBuf retained = data.readRetainedSlice(len);
            inboundQueue.offer(retained);
            if (closed.get()) {
                releaseInboundQueue();
                return;
            }
            int highWater = Math.max(1024, config.getStreamPauseHighWaterBytes());
            if (queued >= highWater && sentPauseToPeer.compareAndSet(false, true)) {
                sendRawControl(ACTION_RAW_PAUSE, streamId);
            }
            scheduleInboundDrain();
        }

        @Override
        public void onHalfClose(long streamId) {
            remoteHalfClosed = true;
            scheduleInboundDrain();
        }

        @Override
        public void onClose(long streamId, byte reason) {
            closeFromRemote();
        }

        @Override
        public void onPause(long streamId) {
            remotePaused = true;
            syncLocalReadState();
        }

        @Override
        public void onResume(long streamId) {
            remotePaused = false;
            syncLocalReadState();
        }

        void syncLocalReadState() {
            Channel ch = localChannel;
            if (ch == null || !ch.isOpen()) {
                return;
            }
            TransportSession s = session();
            boolean readable = !remotePaused && s != null && s.isConnected() && s.isWritable();
            if (readable) {
                Sockets.enableAutoRead(ch);
            } else {
                Sockets.disableAutoRead(ch);
            }
        }

        void scheduleInboundDrain() {
            Channel ch = localChannel;
            if (ch == null || !ch.isActive() || !ch.isWritable() || !draining.compareAndSet(0, 1)) {
                return;
            }
            if (ch.eventLoop().inEventLoop()) {
                drainInbound(ch);
            } else {
                ch.eventLoop().execute(() -> drainInbound(ch));
            }
        }

        private void drainInbound(Channel ch) {
            boolean flushed = false;
            try {
                ByteBuf buf;
                while (!closed.get() && ch.isActive() && ch.isWritable() && (buf = inboundQueue.poll()) != null) {
                    int len = buf.readableBytes();
                    inboundPendingBytes.addAndGet(-len);
                    ch.write(buf).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
                    flushed = true;
                }
                if (flushed) {
                    ch.flush();
                }
                int lowWater = Math.max(512, Math.min(config.getStreamResumeLowWaterBytes(),
                        config.getStreamPauseHighWaterBytes()));
                if (!closed.get() && inboundPendingBytes.get() <= lowWater
                        && sentPauseToPeer.compareAndSet(true, false)) {
                    sendRawControl(ACTION_RAW_RESUME, streamId);
                }
                if (!closed.get() && remoteHalfClosed && inboundQueue.isEmpty() && ch.isActive()) {
                    ch.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                }
            } finally {
                draining.set(0);
                if (!closed.get() && ch.isActive() && ch.isWritable() && !inboundQueue.isEmpty()) {
                    scheduleInboundDrain();
                }
            }
        }

        void onLocalChannelRead(ChannelHandlerContext ctx, ByteBuf msg) {
            if (closed.get()) {
                msg.release();
                return;
            }
            sendRawData(streamId, msg);
            syncLocalReadState();
        }

        void onLocalChannelInactive() {
            closeLocalAndNotifyRemote(RAW_CLOSE_NORMAL);
        }

        void closeLocalAndNotifyRemote(byte reason) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            LocalAddress addr = carrierAddr;
            if (addr != null) {
                activeRawCarriers.remove(addr, this);
            }
            closeOneShotListener();
            releaseInboundQueue();
            Channel ch = localChannel;
            localChannel = null;
            if (ch != null) {
                Sockets.closeOnFlushed(ch);
            }
            closeRawStream(streamId, reason);
        }

        void closeFromRemote() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            LocalAddress addr = carrierAddr;
            if (addr != null) {
                activeRawCarriers.remove(addr, this);
            }
            closeOneShotListener();
            releaseInboundQueue();
            Channel ch = localChannel;
            localChannel = null;
            if (ch != null) {
                Sockets.closeOnFlushed(ch);
            }
        }

        private void releaseInboundQueue() {
            ByteBuf buf;
            while ((buf = inboundQueue.poll()) != null) {
                inboundPendingBytes.addAndGet(-buf.readableBytes());
                buf.release();
            }
        }
    }

    private final class RawLocalBridgeHandler extends ChannelInboundHandlerAdapter {
        private final RawLocalBridge bridge;

        RawLocalBridgeHandler(RawLocalBridge bridge) {
            this.bridge = bridge;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            bridge.attachLocalChannel(ctx.channel());
            super.channelActive(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ByteBuf) {
                bridge.onLocalChannelRead(ctx, (ByteBuf) msg);
            } else {
                ReferenceCountUtil.release(msg);
            }
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
            bridge.scheduleInboundDrain();
            super.channelWritabilityChanged(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            bridge.onLocalChannelInactive();
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("RrpClient RAW local bridge error streamId={}", bridge.streamId, cause);
            bridge.closeLocalAndNotifyRemote(RAW_CLOSE_NORMAL);
        }
    }

    @ChannelHandler.Sharable
    private final class ClientWritabilityHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
            onServerWritabilityChanged(ctx.channel());
            super.channelWritabilityChanged(ctx);
        }
    }

    final RrpConfig config;
    final Map<Integer, RpClientProxy> proxyMap = new ConcurrentHashMap<Integer, RpClientProxy>();
    final Map<String, LocalAddress> hostedLocalTargets = new ConcurrentHashMap<String, LocalAddress>();
    final Map<String, BiFunction<Long, String, RawStreamListener>> hostedStreamAcceptors = new ConcurrentHashMap<String, BiFunction<Long, String, RawStreamListener>>();
    final Map<Integer, PendingRawOpen> pendingRawOpens = new ConcurrentHashMap<Integer, PendingRawOpen>();
    final Map<Long, RawStreamState> rawStreams = new ConcurrentHashMap<Long, RawStreamState>();
    private final AtomicInteger nextRawReqId = new AtomicInteger();
    private final AtomicReference<TransportSession> initializedSession = new AtomicReference<TransportSession>();
    private final ClientWritabilityHandler writabilityHandler = new ClientWritabilityHandler();

    private volatile TransportClient transportClient;
    private volatile ScheduledFuture<?> heartbeatFuture;
    private volatile CompletableFuture<Void> rawAuthFuture = new CompletableFuture<Void>();
    private volatile boolean rawAuthenticated;

    public RrpClient(@NonNull RrpConfig config) {
        this.config = config;
    }

    public boolean isConnected() {
        TransportClient tc = transportClient;
        return tc != null && tc.isConnected();
    }

    public boolean isRawAuthenticated() {
        return rawAuthenticated && isConnected();
    }

    public TransportSession session() {
        TransportClient tc = transportClient;
        return tc == null ? null : tc.session();
    }

    public Channel getChannel() {
        TransportSession s = session();
        return s == null ? null : s.channel();
    }

    public CompletableFuture<Void> awaitRawAuthenticated() {
        return rawAuthFuture;
    }

    public synchronized Future<Void> connectAsync() {
        checkNotClosed();
        TransportClient existing = transportClient;
        if (existing != null) {
            return existing.connectAsync(Sockets.parseEndpoint(config.getServerEndpoint()));
        }
        InetSocketAddress serverEp = Sockets.parseEndpoint(config.getServerEndpoint());
        boolean hasToken = !Strings.isEmpty(config.getToken());
        if (!hasToken && (!Strings.isEmpty(config.getNodeId()) || !Strings.isEmpty(config.getNodeSecret()))) {
            throw new IllegalArgumentException("RrpClient RAW Relay credentials require non-empty token for Transport Core v2 AEAD encryption");
        }
        if (!hasToken && !config.isAllowUnauthenticatedLocal()) {
            throw new IllegalArgumentException(
                    "RrpClient requires non-empty token for Transport Core v2 AEAD encryption (set allowUnauthenticatedLocal=true only for local loopback testing)");
        }
        if (!hasToken && (serverEp.getAddress() == null || !serverEp.getAddress().isLoopbackAddress())) {
            throw new SecurityException("Unauthenticated RrpClient is restricted to loopback endpoints: " + serverEp);
        }
        TransportConfig tc = TransportConfig.pureTcpClient(serverEp);
        tc.setEnableAdaptiveUdp(false);
        tc.setEnableCompression(false);
        tc.setAuthSecret(hasToken ? config.getToken() : null);
        tc.setEnableEncryption(hasToken);
        tc.setEgressConfig(config.getEgressConfig() != null
                ? config.getEgressConfig()
                : EgressConfig.client(EgressCodec.HTTP));
        tc.getTcpClientConfig().setEnableReconnect(config.isEnableReconnect());
        tc.getTcpClientConfig().setWaitConnectMillis(config.getWaitConnectMillis());
        int hbSec = config.getHeartbeatSeconds();
        tc.getTcpClientConfig().setHeartbeatTimeout(hbSec > 0 ? Math.max(30, hbSec * 3) : 0);
        if (config.getConnectTimeoutMillis() > 0) {
            tc.getTcpClientConfig().setConnectTimeoutMillis(config.getConnectTimeoutMillis());
        }

        TransportClient cli = new TransportClient(tc);
        cli.setReceiveHandler(this::handleServerFrame);
        cli.onSessionReady.add((s, e) -> onSessionReady(e.getValue()));
        cli.onDisconnected.add((s, e) -> onSessionDisconnected());
        this.transportClient = cli;
        return cli.connectAsync(serverEp);
    }

    private void onSessionReady(TransportSession session) {
        if (session == null || !session.isConnected() || isClosed()) {
            return;
        }
        if (initializedSession.getAndSet(session) == session) {
            return;
        }
        Channel ch = session.channel();
        if (ch != null) {
            ch.eventLoop().execute(() -> {
                if (ch.isActive() && ch.pipeline().get(WRITABILITY_HANDLER_NAME) == null) {
                    ch.pipeline().addLast(WRITABILITY_HANDLER_NAME, writabilityHandler);
                }
            });
        }
        closeClientProxies();
        List<RrpConfig.Proxy> proxies = config.getProxies();
        if (proxies != null && !proxies.isEmpty()) {
            for (RrpConfig.Proxy p : proxies) {
                proxyMap.computeIfAbsent(p.getRemotePort(), k -> new RpClientProxy(this, p, session, ch));
            }
            sendProxyRegister(session, proxies);
        }
        startHeartbeat(session);
        if (!Strings.isEmpty(config.getNodeId()) && !Strings.isEmpty(config.getNodeSecret())) {
            sendRawAuthFrame(session, config.getNodeId(), config.getNodeSecret(), collectHostedServiceNames());
        }
    }

    private void sendProxyRegister(TransportSession session, List<RrpConfig.Proxy> proxies) {
        byte[] proxyBytes = FurySerializer.DEFAULT.serializeToBytes(proxies);
        String token = config.getToken();
        byte[] tokenBytes = token == null ? new byte[0] : token.getBytes(StandardCharsets.US_ASCII);
        ByteBuf buf = session.channel().alloc().ioBuffer(1 + 4 + tokenBytes.length + 4 + proxyBytes.length);
        buf.writeByte(ACTION_REGISTER);
        buf.writeInt(tokenBytes.length);
        if (tokenBytes.length > 0) {
            buf.writeBytes(tokenBytes);
        }
        buf.writeInt(proxyBytes.length);
        buf.writeBytes(proxyBytes);
        session.sendAsync(buf);
        log.debug("RrpClient registered proxyCount={} on session={}", proxies.size(), session.sessionId());
    }

    private void onSessionDisconnected() {
        stopHeartbeat();
        initializedSession.set(null);
        rawAuthenticated = false;
        CompletableFuture<Void> authF = rawAuthFuture;
        if (authF != null && !authF.isDone()) {
            authF.completeExceptionally(new ClientDisconnectedException(config.getServerEndpoint()));
        }
        rawAuthFuture = new CompletableFuture<Void>();

        closeClientProxies();
        failPendingRawOpens(new ClientDisconnectedException(config.getServerEndpoint()));
        closeAllRawStreamsLocal(RAW_CLOSE_PEER_DISCONNECTED);
    }

    private void onServerWritabilityChanged(Channel serverChannel) {
        boolean writable = serverChannel != null && serverChannel.isWritable();
        for (RpClientProxy proxy : proxyMap.values()) {
            for (Channel localChannel : proxy.localChannels.values()) {
                ServerRelayBuffer relayBuffer = localChannel.attr(ATTR_SERVER_RELAY_BUF).get();
                if (relayBuffer != null) {
                    relayBuffer.scheduleDrain(serverChannel);
                    relayBuffer.syncLocalReadState(serverChannel);
                } else if (localChannel.isOpen()) {
                    if (writable) {
                        Sockets.enableAutoRead(localChannel);
                    } else {
                        Sockets.disableAutoRead(localChannel);
                    }
                }
            }
        }
        for (RawStreamState state : rawStreams.values()) {
            if (state.bridge != null) {
                state.bridge.syncLocalReadState();
            }
        }
    }

    void handleServerFrame(TransportSession session, ByteBuf buf) {
        if (buf == null || buf.readableBytes() < 1) {
            return;
        }
        byte action = buf.readByte();
        switch (action) {
            case ACTION_HEARTBEAT:
                return;
            case ACTION_FORWARD:
            case ACTION_SYNC_CLOSE:
                handleSocksServerFrame(session, action, buf);
                return;
            case ACTION_RAW_AUTH_ACK:
                handleRawAuthAck(buf);
                return;
            case ACTION_RAW_OPEN_INCOMING:
                handleRawOpenIncoming(session, buf);
                return;
            case ACTION_RAW_OPEN_ACK:
                handleRawOpenAck(buf);
                return;
            case ACTION_RAW_DATA:
                handleRawDataFromServer(buf);
                return;
            case ACTION_RAW_HALF_CLOSE:
                handleRawHalfCloseFromServer(buf);
                return;
            case ACTION_RAW_CLOSE:
                handleRawCloseFromServer(buf);
                return;
            case ACTION_RAW_PAUSE:
                handleRawPauseFromServer(buf);
                return;
            case ACTION_RAW_RESUME:
                handleRawResumeFromServer(buf);
                return;
            default:
                log.warn("RrpClient unknown action={} from server", action);
                if (session != null) {
                    session.close();
                }
        }
    }

    // region SOCKS5 Reverse Proxy Frame Handling

    private void handleSocksServerFrame(TransportSession session, byte action, ByteBuf buf) {
        if (buf.readableBytes() < 8) {
            log.warn("RrpClient invalid SOCKS frame action={}", action);
            if (session != null) {
                session.close();
            }
            return;
        }
        int remotePort = buf.readInt();
        int idLen = buf.readInt();
        if (idLen < 0 || idLen > MAX_CHANNEL_ID_LEN || buf.readableBytes() < idLen) {
            log.warn("RrpClient invalid idLen={}", idLen);
            if (session != null) {
                session.close();
            }
            return;
        }
        String channelId = buf.readCharSequence(idLen, StandardCharsets.US_ASCII).toString();
        RpClientProxy proxyCtx = proxyMap.get(remotePort);
        if (proxyCtx == null) {
            log.warn("RrpClient unknown remotePort={} channelId={}", remotePort, channelId);
            return;
        }
        Channel serverChannel = session == null ? null : session.channel();
        if (action == ACTION_FORWARD) {
            Channel localChannel = proxyCtx.localChannels.computeIfAbsent(channelId, k -> {
                RrpConfig conf = Sys.deepClone(config);
                conf.setEgressConfig(null);
                conf.setTransportFlags(null);
                ChannelFuture connF;
                if (enableMemoryChannel) {
                    connF = Sockets.bootstrap(conf, proxyCtx.localEndpoint, ch -> ch.pipeline()
                                    .addLast(SocksClientHandler.DEFAULT))
                            .connect(proxyCtx.localEndpoint);
                } else {
                    connF = Sockets.bootstrap(conf, ch -> ch.pipeline()
                                    .addLast(SocksClientHandler.DEFAULT))
                            .connect(proxyCtx.localEndpoint);
                }
                Channel ch = connF.channel();
                ch.attr(ATTR_CLI_PROXY).set(Tuple.of(proxyCtx, channelId));
                ch.attr(ATTR_CLI_CONN).set(connF);
                ch.attr(ATTR_LOCAL_RELAY_BUF).set(new LocalRelayBuffer(this, serverChannel));
                ch.attr(ATTR_SERVER_RELAY_BUF).set(new ServerRelayBuffer(ch));
                connF.addListener((ChannelFutureListener) f -> {
                    ch.attr(ATTR_CLI_CONN).set(null);
                    LocalRelayBuffer relayBuffer = ch.attr(ATTR_LOCAL_RELAY_BUF).get();
                    if (f.isSuccess()) {
                        if (relayBuffer != null) {
                            relayBuffer.scheduleDrain(ch);
                        }
                        syncServerReadState(serverChannel);
                        return;
                    }
                    if (relayBuffer != null) {
                        relayBuffer.releaseAll();
                    }
                    ServerRelayBuffer serverRelayBuffer = ch.attr(ATTR_SERVER_RELAY_BUF).get();
                    if (serverRelayBuffer != null) {
                        serverRelayBuffer.releaseAll();
                    }
                    proxyCtx.localChannels.remove(channelId, ch);
                    RrpServer.closeChannel(ch);
                    syncServerReadState(serverChannel);
                });
                return ch;
            });
            ByteBuf payload = buf.readRetainedSlice(buf.readableBytes());
            LocalRelayBuffer relayBuffer = localChannel.attr(ATTR_LOCAL_RELAY_BUF).get();
            if (relayBuffer == null) {
                LocalRelayBuffer newBuffer = new LocalRelayBuffer(this, serverChannel);
                LocalRelayBuffer oldBuffer = localChannel.attr(ATTR_LOCAL_RELAY_BUF).setIfAbsent(newBuffer);
                relayBuffer = oldBuffer == null ? newBuffer : oldBuffer;
            }
            relayBuffer.offer(localChannel, payload);
        } else if (action == ACTION_SYNC_CLOSE) {
            Channel localChannel = proxyCtx.localChannels.get(channelId);
            Sockets.closeOnFlushed(localChannel);
        }
    }

    // endregion

    // region RAW Logical Stream Relay Client API

    public void registerRawService(@NonNull String serviceName, @NonNull LocalAddress localTarget) {
        hostedLocalTargets.put(serviceName.trim(), localTarget);
    }

    public void unregisterRawService(@NonNull String serviceName) {
        hostedLocalTargets.remove(serviceName.trim());
    }

    public void registerRawStreamAcceptor(@NonNull String serviceName,
                                          @NonNull BiFunction<Long, String, RawStreamListener> acceptor) {
        hostedStreamAcceptors.put(serviceName.trim(), acceptor);
    }

    public void unregisterRawStreamAcceptor(@NonNull String serviceName) {
        hostedStreamAcceptors.remove(serviceName.trim());
    }

    public CompletableFuture<Void> authenticateNodeAsync(@NonNull String nodeId, @NonNull String nodeSecret,
                                                         Set<String> extraServices) {
        config.setNodeId(nodeId);
        config.setNodeSecret(nodeSecret);
        if (extraServices != null) {
            Set<String> merged = new HashSet<String>(extraServices);
            if (config.getRawServices() != null) {
                merged.addAll(config.getRawServices());
            }
            config.setRawServices(merged);
        }
        CompletableFuture<Void> next = new CompletableFuture<Void>();
        rawAuthFuture = next;
        rawAuthenticated = false;
        TransportSession s = session();
        if (s != null && s.isConnected()) {
            sendRawAuthFrame(s, nodeId, nodeSecret, collectHostedServiceNames());
        }
        return next;
    }

    private Set<String> collectHostedServiceNames() {
        Set<String> names = new HashSet<String>();
        if (config.getRawServices() != null) {
            names.addAll(config.getRawServices());
        }
        names.addAll(hostedLocalTargets.keySet());
        names.addAll(hostedStreamAcceptors.keySet());
        return names;
    }

    private void sendRawAuthFrame(TransportSession session, String nodeId, String secret, Set<String> services) {
        byte[] idBytes = nodeId.trim().getBytes(StandardCharsets.UTF_8);
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        int size = 1 + 2 + idBytes.length + 2 + secretBytes.length + 2;
        byte[][] svcByteList = new byte[services.size()][];
        int idx = 0;
        for (String svc : services) {
            byte[] b = svc.trim().getBytes(StandardCharsets.UTF_8);
            svcByteList[idx++] = b;
            size += 2 + b.length;
        }
        ByteBuf buf = session.channel().alloc().ioBuffer(size, size);
        buf.writeByte(ACTION_RAW_AUTH);
        buf.writeShort(idBytes.length);
        buf.writeBytes(idBytes);
        buf.writeShort(secretBytes.length);
        buf.writeBytes(secretBytes);
        buf.writeShort(svcByteList.length);
        for (byte[] b : svcByteList) {
            buf.writeShort(b.length);
            buf.writeBytes(b);
        }
        session.sendAsync(buf);
    }

    public CompletableFuture<Long> openRawStreamAsync(@NonNull String targetNodeId, @NonNull String targetService,
                                                      @NonNull RawStreamListener listener) {
        CompletableFuture<Long> failed = checkRawReady();
        if (failed != null) {
            return failed;
        }
        TransportSession s = session();
        int reqId = nextRawReqId.incrementAndGet();
        PendingRawOpen pending = new PendingRawOpen(reqId, listener);
        pendingRawOpens.put(reqId, pending);

        int timeoutMs = Math.max(500, config.getRawOpenTimeoutMillis());
        pending.timeoutFuture = s.channel().eventLoop().schedule(() -> {
            PendingRawOpen removed = pendingRawOpens.remove(reqId);
            if (removed != null && !removed.future.isDone()) {
                removed.future.completeExceptionally(new TimeoutException("RAW stream open timeout: " + targetNodeId + "/" + targetService));
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);

        byte[] nodeBytes = targetNodeId.trim().getBytes(StandardCharsets.UTF_8);
        byte[] svcBytes = targetService.trim().getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = s.channel().alloc().ioBuffer(1 + 4 + 2 + nodeBytes.length + 2 + svcBytes.length);
        buf.writeByte(ACTION_RAW_OPEN);
        buf.writeInt(reqId);
        buf.writeShort(nodeBytes.length);
        buf.writeBytes(nodeBytes);
        buf.writeShort(svcBytes.length);
        buf.writeBytes(svcBytes);
        s.sendAsync(buf);
        return pending.future;
    }

    private final ConcurrentHashMap<LocalAddress, RawLocalBridge> activeRawCarriers = new ConcurrentHashMap<LocalAddress, RawLocalBridge>();

    public int activeRawCarrierCount() {
        return activeRawCarriers.size();
    }

    /**
     * Cancels and tears down an unconsumed (or active) one-shot RAW {@link LocalAddress} carrier,
     * immediately closing its local listener and notifying the RRP server to close the RAW stream.
     */
    public boolean cancelRawCarrier(LocalAddress carrierAddr) {
        if (carrierAddr == null) {
            return false;
        }
        RawLocalBridge bridge = activeRawCarriers.remove(carrierAddr);
        if (bridge == null) {
            return false;
        }
        bridge.closeLocalAndNotifyRemote(RAW_CLOSE_NORMAL);
        return true;
    }

    /**
     * Opens an authorized RAW logical stream to {@code targetNodeId}/{@code targetService} and binds a
     * one-shot in-process {@link LocalAddress} carrier bridged 1:1 to that stream, ready for
     * {@link TransportClient#connectLocal(LocalAddress)} or {@link org.rx.net.rpc.RpcClientConfig#localCarrier}.
     */
    public CompletableFuture<LocalAddress> openRawCarrierAsync(@NonNull String targetNodeId, @NonNull String targetService) {
        CompletableFuture<LocalAddress> result = new CompletableFuture<LocalAddress>();
        final AtomicReference<RawLocalBridge> bridgeRef = new AtomicReference<RawLocalBridge>();
        final AtomicReference<LocalAddress> carrierAddrRef = new AtomicReference<LocalAddress>();

        RawStreamListener proxyListener = new RawStreamListener() {
            @Override
            public void onData(long streamId, ByteBuf data) {
                RawLocalBridge b = bridgeRef.get();
                if (b != null) {
                    b.onData(streamId, data);
                }
            }

            @Override
            public void onHalfClose(long streamId) {
                RawLocalBridge b = bridgeRef.get();
                if (b != null) {
                    b.onHalfClose(streamId);
                }
            }

            @Override
            public void onClose(long streamId, byte reason) {
                LocalAddress addr = carrierAddrRef.get();
                if (addr != null) {
                    activeRawCarriers.remove(addr);
                }
                RawLocalBridge b = bridgeRef.get();
                if (b != null) {
                    b.onClose(streamId, reason);
                }
            }

            @Override
            public void onPause(long streamId) {
                RawLocalBridge b = bridgeRef.get();
                if (b != null) {
                    b.onPause(streamId);
                }
            }

            @Override
            public void onResume(long streamId) {
                RawLocalBridge b = bridgeRef.get();
                if (b != null) {
                    b.onResume(streamId);
                }
            }
        };

        openRawStreamAsync(targetNodeId, targetService, proxyListener).whenComplete((streamId, err) -> {
            if (err != null) {
                result.completeExceptionally(err);
                return;
            }
            if (result.isCancelled() || result.isDone()) {
                closeRawStream(streamId, RAW_CLOSE_NORMAL);
                return;
            }
            RawStreamState state = rawStreams.get(streamId);
            if (state == null || state.closed.get()) {
                result.completeExceptionally(new ClientDisconnectedException("RAW stream closed before carrier bind"));
                return;
            }
            RawLocalBridge bridge = new RawLocalBridge(streamId);
            bridgeRef.set(bridge);
            state.bridge = bridge;

            LocalAddress carrierAddr = new LocalAddress("rrp-raw-" + streamId + "-" + LOCAL_ADDRESS_SEQ.incrementAndGet());
            carrierAddrRef.set(carrierAddr);
            bridge.carrierAddr = carrierAddr;
            activeRawCarriers.put(carrierAddr, bridge);
            try {
                SocketConfig localConf = new SocketConfig();
                localConf.setTransportFlags(null);
                ServerBootstrap sb = Sockets.serverBootstrap(localConf, carrierAddr,
                        ch -> ch.pipeline().addLast(new RawLocalBridgeHandler(bridge)));
                sb.bind(carrierAddr).addListener((ChannelFutureListener) bf -> {
                    if (bf.isSuccess()) {
                        bridge.attachOneShotListener(sb, bf.channel());
                        if (!result.complete(carrierAddr)) {
                            activeRawCarriers.remove(carrierAddr, bridge);
                            bridge.closeLocalAndNotifyRemote(RAW_CLOSE_NORMAL);
                        }
                    } else {
                        activeRawCarriers.remove(carrierAddr, bridge);
                        Sockets.closeBootstrap(sb);
                        bridge.closeLocalAndNotifyRemote(RAW_CLOSE_NORMAL);
                        result.completeExceptionally(bf.cause());
                    }
                });
            } catch (Throwable bindErr) {
                activeRawCarriers.remove(carrierAddr, bridge);
                bridge.closeLocalAndNotifyRemote(RAW_CLOSE_NORMAL);
                result.completeExceptionally(bindErr);
            }
        });
        return result;
    }

    public ChannelFuture sendRawData(long streamId, @NonNull ByteBuf payload) {
        TransportSession s = session();
        if (s == null || !s.isConnected() || !rawStreams.containsKey(streamId)) {
            payload.release();
            Channel ch = getChannel();
            return ch == null ? null : ch.newFailedFuture(new ClientDisconnectedException("RAW stream unavailable"));
        }
        int total = payload.readableBytes();
        if (total == 0) {
            payload.release();
            return s.channel().newSucceededFuture();
        }
        int maxChunk = Math.max(1024, config.getMaxRawFrameBytes());
        if (total <= maxChunk) {
            ByteBuf hdr = s.channel().alloc().ioBuffer(9, 9);
            hdr.writeByte(ACTION_RAW_DATA);
            hdr.writeLong(streamId);
            return s.sendAsync(Unpooled.wrappedBuffer(hdr, payload));
        }
        ChannelFuture lastFuture = null;
        try {
            while (payload.isReadable()) {
                int len = Math.min(maxChunk, payload.readableBytes());
                ByteBuf slice = payload.readRetainedSlice(len);
                ByteBuf hdr = s.channel().alloc().ioBuffer(9, 9);
                hdr.writeByte(ACTION_RAW_DATA);
                hdr.writeLong(streamId);
                lastFuture = s.sendAsync(Unpooled.wrappedBuffer(hdr, slice));
            }
            return lastFuture;
        } finally {
            payload.release();
        }
    }

    public ChannelFuture halfCloseRawStream(long streamId) {
        TransportSession s = session();
        if (s == null || !s.isConnected() || !rawStreams.containsKey(streamId)) {
            return null;
        }
        return sendRawControl(ACTION_RAW_HALF_CLOSE, streamId);
    }

    public void closeRawStream(long streamId, byte reason) {
        RawStreamState state = rawStreams.remove(streamId);
        if (state != null && state.closed.compareAndSet(false, true)) {
            if (state.bridge != null) {
                state.bridge.closeFromRemote();
            }
        }
        TransportSession s = session();
        if (s != null && s.isConnected()) {
            ByteBuf buf = s.channel().alloc().ioBuffer(10, 10);
            buf.writeByte(ACTION_RAW_CLOSE);
            buf.writeLong(streamId);
            buf.writeByte(reason);
            s.sendAsync(buf);
        }
    }

    private CompletableFuture<Long> checkRawReady() {
        TransportSession s = session();
        if (s == null || !s.isConnected()) {
            CompletableFuture<Long> f = new CompletableFuture<Long>();
            f.completeExceptionally(new ClientDisconnectedException(config.getServerEndpoint()));
            return f;
        }
        if (!rawAuthenticated) {
            CompletableFuture<Long> f = new CompletableFuture<Long>();
            f.completeExceptionally(new SecurityException("RrpClient node is not RAW-authenticated"));
            return f;
        }
        return null;
    }

    private ChannelFuture sendRawControl(byte action, long streamId) {
        TransportSession s = session();
        if (s == null || !s.isConnected()) {
            return null;
        }
        ByteBuf buf = s.channel().alloc().ioBuffer(9, 9);
        buf.writeByte(action);
        buf.writeLong(streamId);
        return s.sendAsync(buf);
    }

    private void handleRawAuthAck(ByteBuf buf) {
        if (buf.readableBytes() < 3) {
            return;
        }
        byte status = buf.readByte();
        String msg = RrpServer.readShortUtf8(buf, 256);
        CompletableFuture<Void> f = rawAuthFuture;
        if (status == RAW_STATUS_OK) {
            rawAuthenticated = true;
            if (f != null && !f.isDone()) {
                f.complete(null);
            }
        } else {
            rawAuthenticated = false;
            if (f != null && !f.isDone()) {
                f.completeExceptionally(new SecurityException("RAW auth failed (" + status + "): " + msg));
            }
        }
    }

    private void handleRawOpenIncoming(TransportSession session, ByteBuf buf) {
        if (buf.readableBytes() < 8 + 2 + 2) {
            return;
        }
        long streamId = buf.readLong();
        String initiatorNodeId = RrpServer.readShortUtf8(buf, RrpServer.MAX_ID_LEN);
        String targetService = RrpServer.readShortUtf8(buf, RrpServer.MAX_ID_LEN);
        if (Strings.isEmpty(initiatorNodeId) || Strings.isEmpty(targetService)) {
            sendRawOpenAckToServer(session, streamId, RAW_STATUS_REJECTED, "malformed open");
            return;
        }

        // Priority 1: custom callback acceptor
        BiFunction<Long, String, RawStreamListener> acceptor = hostedStreamAcceptors.get(targetService);
        if (acceptor != null) {
            try {
                RawStreamListener listener = acceptor.apply(streamId, initiatorNodeId);
                if (listener == null) {
                    sendRawOpenAckToServer(session, streamId, RAW_STATUS_REJECTED, "rejected by acceptor");
                    return;
                }
                rawStreams.put(streamId, new RawStreamState(streamId, listener));
                sendRawOpenAckToServer(session, streamId, RAW_STATUS_OK, "");
            } catch (Throwable ex) {
                log.warn("RrpClient RAW acceptor error svc={} initiator={}", targetService, initiatorNodeId, ex);
                sendRawOpenAckToServer(session, streamId, RAW_STATUS_REJECTED, "acceptor error");
            }
            return;
        }

        // Priority 2: LocalAddress virtual carrier bridge (e.g. TransportServer.startLocal / Remoting localCarrier)
        LocalAddress localTarget = hostedLocalTargets.get(targetService);
        if (localTarget == null) {
            sendRawOpenAckToServer(session, streamId, RAW_STATUS_SERVICE_NOT_FOUND, "service not registered");
            return;
        }
        RawLocalBridge bridge = new RawLocalBridge(streamId);
        RawStreamState state = new RawStreamState(streamId, bridge);
        state.bridge = bridge;
        rawStreams.put(streamId, state);

        SocketConfig localConf = new SocketConfig();
        localConf.setTransportFlags(null);
        ChannelFuture connF = Sockets.bootstrap(localConf, localTarget,
                ch -> ch.pipeline().addLast(new RawLocalBridgeHandler(bridge))).connect(localTarget);
        connF.addListener((ChannelFutureListener) f -> {
            if (f.isSuccess()) {
                sendRawOpenAckToServer(session, streamId, RAW_STATUS_OK, "");
            } else {
                rawStreams.remove(streamId, state);
                bridge.closeFromRemote();
                sendRawOpenAckToServer(session, streamId, RAW_STATUS_REJECTED, "local target connect failed");
            }
        });
    }

    private void sendRawOpenAckToServer(TransportSession session, long streamId, byte status, String message) {
        if (session == null || !session.isConnected()) {
            return;
        }
        byte[] msgBytes = message == null ? new byte[0] : message.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = session.channel().alloc().ioBuffer(1 + 8 + 1 + 2 + msgBytes.length);
        buf.writeByte(ACTION_RAW_OPEN_ACK);
        buf.writeLong(streamId);
        buf.writeByte(status);
        buf.writeShort(msgBytes.length);
        if (msgBytes.length > 0) {
            buf.writeBytes(msgBytes);
        }
        session.sendAsync(buf);
    }

    private void handleRawOpenAck(ByteBuf buf) {
        if (buf.readableBytes() < 4 + 8 + 1 + 2) {
            return;
        }
        int reqId = buf.readInt();
        long streamId = buf.readLong();
        byte status = buf.readByte();
        String msg = RrpServer.readShortUtf8(buf, 256);
        PendingRawOpen pending = pendingRawOpens.remove(reqId);
        if (pending == null) {
            if (status == RAW_STATUS_OK && streamId != 0L) {
                closeRawStream(streamId, RAW_CLOSE_NORMAL);
            }
            return;
        }
        pending.cancelTimeout();
        if (status == RAW_STATUS_OK) {
            rawStreams.put(streamId, new RawStreamState(streamId, pending.listener));
            pending.future.complete(streamId);
        } else {
            Throwable ex = status == RAW_STATUS_UNAUTHORIZED
                    ? new SecurityException("RAW stream unauthorized: " + msg)
                    : new IllegalStateException("RAW stream open failed (" + status + "): " + msg);
            pending.future.completeExceptionally(ex);
        }
    }

    private void handleRawDataFromServer(ByteBuf buf) {
        if (buf.readableBytes() < 8) {
            return;
        }
        long streamId = buf.readLong();
        RawStreamState state = rawStreams.get(streamId);
        if (state == null || state.closed.get()) {
            return;
        }
        state.listener.onData(streamId, buf);
    }

    private void handleRawHalfCloseFromServer(ByteBuf buf) {
        if (buf.readableBytes() < 8) {
            return;
        }
        long streamId = buf.readLong();
        RawStreamState state = rawStreams.get(streamId);
        if (state == null || state.closed.get()) {
            return;
        }
        state.listener.onHalfClose(streamId);
    }

    private void handleRawCloseFromServer(ByteBuf buf) {
        if (buf.readableBytes() < 9) {
            return;
        }
        long streamId = buf.readLong();
        byte reason = buf.readByte();
        RawStreamState state = rawStreams.remove(streamId);
        if (state != null && state.closed.compareAndSet(false, true)) {
            state.listener.onClose(streamId, reason);
        }
    }

    private void handleRawPauseFromServer(ByteBuf buf) {
        if (buf.readableBytes() < 8) {
            return;
        }
        long streamId = buf.readLong();
        RawStreamState state = rawStreams.get(streamId);
        if (state == null || state.closed.get()) {
            return;
        }
        state.remotePaused.set(true);
        state.listener.onPause(streamId);
    }

    private void handleRawResumeFromServer(ByteBuf buf) {
        if (buf.readableBytes() < 8) {
            return;
        }
        long streamId = buf.readLong();
        RawStreamState state = rawStreams.get(streamId);
        if (state == null || state.closed.get()) {
            return;
        }
        state.remotePaused.set(false);
        state.listener.onResume(streamId);
    }

    private void failPendingRawOpens(Throwable cause) {
        for (PendingRawOpen p : pendingRawOpens.values()) {
            p.cancelTimeout();
            if (!p.future.isDone()) {
                p.future.completeExceptionally(cause);
            }
        }
        pendingRawOpens.clear();
    }

    private void closeAllRawStreamsLocal(byte reason) {
        activeRawCarriers.clear();
        for (RawStreamState state : rawStreams.values()) {
            if (state.closed.compareAndSet(false, true)) {
                state.listener.onClose(state.streamId, reason);
            }
        }
        rawStreams.clear();
    }

    // endregion

    void closeClientProxies() {
        for (RpClientProxy proxy : proxyMap.values()) {
            tryClose(proxy);
        }
        proxyMap.clear();
    }

    void syncServerReadState(Channel serverChannel) {
        if (serverChannel == null || !serverChannel.isOpen()) {
            return;
        }
        if (serverChannel.eventLoop().inEventLoop()) {
            doSyncServerReadState(serverChannel);
            return;
        }
        serverChannel.eventLoop().execute(() -> doSyncServerReadState(serverChannel));
    }

    void doSyncServerReadState(Channel serverChannel) {
        if (!serverChannel.isActive()) {
            return;
        }
        boolean readable = true;
        for (RpClientProxy proxy : proxyMap.values()) {
            if (proxy.hasLocalBacklog()) {
                readable = false;
                break;
            }
        }
        if (readable) {
            Sockets.enableAutoRead(serverChannel);
        } else {
            Sockets.disableAutoRead(serverChannel);
        }
    }

    void startHeartbeat(TransportSession session) {
        stopHeartbeat();
        int seconds = config.getHeartbeatSeconds();
        if (seconds <= 0 || session == null || session.channel() == null) {
            return;
        }
        Channel serverChannel = session.channel();
        heartbeatFuture = serverChannel.eventLoop().scheduleAtFixedRate(() -> {
            if (session() != session || !session.isConnected()) {
                return;
            }
            RrpConfig.writeHeartbeat(session);
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

    @Override
    protected void dispose() {
        config.setEnableReconnect(false);
        stopHeartbeat();
        rawAuthenticated = false;
        CancellationException closedEx = new CancellationException("RrpClient closed");
        CompletableFuture<Void> authF = rawAuthFuture;
        if (authF != null && !authF.isDone()) {
            authF.completeExceptionally(closedEx);
        }
        failPendingRawOpens(closedEx);
        closeAllRawStreamsLocal(RAW_CLOSE_NORMAL);
        activeRawCarriers.clear();
        closeClientProxies();
        TransportClient tc = transportClient;
        transportClient = null;
        tryClose(tc);
    }
}
