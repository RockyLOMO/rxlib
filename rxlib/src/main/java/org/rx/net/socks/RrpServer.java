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
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.core.Strings;
import org.rx.core.Tasks;
import org.rx.exception.InvalidException;
import org.rx.io.FurySerializer;
import org.rx.net.EgressConfig;
import org.rx.net.Sockets;
import org.rx.net.registry.NodeRegistry;
import org.rx.net.transport.core.TransportConfig;
import org.rx.net.transport.core.TransportServer;
import org.rx.net.transport.core.TransportSession;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.rx.core.Extends.tryClose;
import static org.rx.net.socks.RrpConfig.*;

/**
 * Reverse Relay Proxy (RRP) server built on shared pure-TCP {@link TransportServer} v2 and
 * unified {@link org.rx.net.EgressCodec}.
 * <p>
 * Supports two business modes:
 * <ul>
 *     <li>SOCKS5 reverse proxy ({@code ACTION_REGISTER}/{@code ACTION_FORWARD}/{@code ACTION_SYNC_CLOSE}).</li>
 *     <li>{@link NodeRegistry}-authorized opaque RAW logical stream Relay ({@code ACTION_RAW_*}) with
 *     4-tier pending byte budgets (global / tenant / node / stream), per-stream pause/resume, fair
 *     round-robin drain against HOL blocking, zero Fury/crypto/compression on relayed payload, and
 *     immediate revocation teardown.</li>
 * </ul>
 */
@Slf4j
public class RrpServer extends Disposable {
    static final int MAX_TOKEN_LEN = 256;
    static final int MAX_CHANNEL_ID_LEN = 128;
    static final int MAX_ID_LEN = 128;
    static final int MAX_SECRET_LEN = 512;
    static final int MAX_RAW_SERVICES_PER_NODE = 64;
    static final int MAX_REGISTER_BYTES = 1024 * 1024; // 1 MiB cap for serialized proxies
    static final int MAX_PENDING_FORWARD_BYTES = 1024 * 1024; // 1 MiB cap per SOCKS remote channel
    static final AttributeKey<RemoteRelayBuffer> ATTR_REMOTE_RELAY_BUF = AttributeKey.valueOf("rRemoteRelayBuf");
    static final AttributeKey<String> ATTR_REMOTE_CHANNEL_ID = AttributeKey.valueOf("rRemoteChannelId");
    private static final String WRITABILITY_HANDLER_NAME = "rrpWritability";
    static final String SUBSYSTEM_RRP_RELAY = "rrp-relay";

    static class RemoteRelayBuffer {
        final Queue<ByteBuf> pendingWrites = new ConcurrentLinkedQueue<ByteBuf>();
        final AtomicInteger pendingBytes = new AtomicInteger();
        final AtomicInteger draining = new AtomicInteger();

        boolean offer(Channel channel, ByteBuf payload) {
            if (!channel.isActive()) {
                ReferenceCountUtil.release(payload);
                return false;
            }

            int bytes = payload.readableBytes();
            int queuedBytes = pendingBytes.addAndGet(bytes);
            if (queuedBytes > MAX_PENDING_FORWARD_BYTES) {
                pendingBytes.addAndGet(-bytes);
                ReferenceCountUtil.release(payload);
                log.warn("RrpServer remote channel {} queued bytes {} exceed cap {}, close channel",
                        channel, queuedBytes, MAX_PENDING_FORWARD_BYTES);
                closeChannel(channel);
                return false;
            }

            pendingWrites.offer(payload);
            scheduleDrain(channel);
            return true;
        }

        void scheduleDrain(Channel channel) {
            if (!channel.isActive() || !draining.compareAndSet(0, 1)) {
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
                while (channel.isActive() && channel.isWritable() && (payload = pendingWrites.poll()) != null) {
                    pendingBytes.addAndGet(-payload.readableBytes());
                    channel.write(payload).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
                    flushed = true;
                }
                if (flushed) {
                    channel.flush();
                }
            } finally {
                draining.set(0);
                if (channel.isActive() && channel.isWritable() && !pendingWrites.isEmpty()) {
                    scheduleDrain(channel);
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
        }
    }

    @RequiredArgsConstructor
    static class RpClientProxy extends Disposable {
        final RrpServer server;
        final RpClient owner;
        final RrpConfig.Proxy p;
        final ServerBootstrap remoteServer;
        final Map<String, Channel> remoteClients = new ConcurrentHashMap<String, Channel>();
        final AtomicLong nextRemoteChannelId = new AtomicLong();
        volatile List<Channel> remoteServerChannels;
        volatile Channel remoteServerChannel;

        void syncRemoteReadState(boolean clientWritable) {
            for (Channel ch : remoteClients.values()) {
                if (clientWritable) {
                    Sockets.enableAutoRead(ch);
                } else {
                    Sockets.disableAutoRead(ch);
                }
            }
        }

        @Override
        protected void dispose() {
            if (server != null) {
                server.unregisterProxy(this);
            }
            List<Channel> channels = remoteServerChannels;
            if (channels != null) {
                for (Channel channel : channels) {
                    closeChannel(channel);
                }
            } else {
                closeChannel(remoteServerChannel);
            }
            Sockets.closeBootstrap(remoteServer);
            for (Channel ch : remoteClients.values()) {
                Sockets.closeOnFlushed(ch);
            }
            remoteClients.clear();
        }
    }

    static final class RawStreamDir {
        final RawRelayStream stream;
        final RpClient sender;
        final RpClient receiver;
        final Queue<ByteBuf> queue = new ConcurrentLinkedQueue<ByteBuf>();
        final AtomicInteger pendingBytes = new AtomicInteger();
        final AtomicBoolean queuedForDrain = new AtomicBoolean();
        final AtomicBoolean pausedSender = new AtomicBoolean();
        final AtomicBoolean remotePaused = new AtomicBoolean();
        final AtomicBoolean halfCloseSent = new AtomicBoolean();
        volatile boolean halfClosePending;

        RawStreamDir(RawRelayStream stream, RpClient sender, RpClient receiver) {
            this.stream = stream;
            this.sender = sender;
            this.receiver = receiver;
        }
    }

    static final class RawRelayStream {
        final long streamId;
        final int initiatorReqId;
        final RpClient initiator;
        final RpClient target;
        final String targetService;
        final String billingTenant;
        final RawStreamDir aToB;
        final RawStreamDir bToA;
        final AtomicBoolean opened = new AtomicBoolean();
        final AtomicBoolean closed = new AtomicBoolean();
        volatile ScheduledFuture<?> openTimeoutFuture;

        RawRelayStream(long streamId, int initiatorReqId, RpClient initiator, RpClient target, String targetService) {
            this.streamId = streamId;
            this.initiatorReqId = initiatorReqId;
            this.initiator = initiator;
            this.target = target;
            this.targetService = targetService;
            this.billingTenant = initiator.authenticatedTenant != null ? initiator.authenticatedTenant : "default";
            this.aToB = new RawStreamDir(this, initiator, target);
            this.bToA = new RawStreamDir(this, target, initiator);
        }

        void cancelOpenTimeout() {
            ScheduledFuture<?> f = openTimeoutFuture;
            if (f != null) {
                openTimeoutFuture = null;
                f.cancel(false);
            }
        }
    }

    static class RpClient extends Disposable {
        final RrpServer server;
        final TransportSession session;
        final Map<Integer, RpClientProxy> proxyMap = new ConcurrentHashMap<Integer, RpClientProxy>();

        // RAW Relay session state
        volatile String authenticatedNodeId;
        volatile String authenticatedTenant;
        volatile Set<String> hostedRawServices = Collections.emptySet();
        final Map<Long, RawRelayStream> rawStreams = new ConcurrentHashMap<Long, RawRelayStream>();
        final AtomicInteger activeRawStreamCount = new AtomicInteger();
        final AtomicLong pendingRawBytes = new AtomicLong();
        final Queue<RawStreamDir> readyDirs = new ConcurrentLinkedQueue<RawStreamDir>();
        final AtomicInteger rawDraining = new AtomicInteger();

        RpClient(RrpServer server, TransportSession session) {
            this.server = server;
            this.session = session;
        }

        Channel channel() {
            return session == null ? null : session.channel();
        }

        boolean isSessionConnected() {
            return session != null && session.isConnected();
        }

        boolean isSessionWritable() {
            return session != null && session.isWritable();
        }

        ChannelFuture sendFrame(ByteBuf frame) {
            if (session == null || !session.isConnected()) {
                ReferenceCountUtil.safeRelease(frame);
                return null;
            }
            return session.sendAsync(frame);
        }

        public RpClientProxy getProxyCtx(int remotePort) {
            RpClientProxy ctx = proxyMap.get(remotePort);
            if (ctx == null) {
                throw new InvalidException("ProxyCtx {} not exist", remotePort);
            }
            return ctx;
        }

        void onSessionWritabilityChanged() {
            boolean writable = isSessionWritable();
            for (RpClientProxy proxy : proxyMap.values()) {
                proxy.syncRemoteReadState(writable);
            }
            if (writable && server != null) {
                server.scheduleRawDrain(this);
            }
        }

        void onRemoteChannelActive(Channel remoteChannel) {
            if (!isSessionWritable()) {
                Sockets.disableAutoRead(remoteChannel);
            }
        }

        @Override
        protected void dispose() {
            if (server != null) {
                server.cleanupClientRawState(this, RAW_CLOSE_PEER_DISCONNECTED);
            }
            for (RpClientProxy v : proxyMap.values()) {
                tryClose(v);
            }
            proxyMap.clear();
            if (session != null) {
                session.close();
            }
        }
    }

    @ChannelHandler.Sharable
    static class RemoteServerHandler extends ChannelInboundHandlerAdapter {
        static final RemoteServerHandler DEFAULT = new RemoteServerHandler();

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            Channel inbound = ctx.channel();
            RpClient rpClient = Sockets.getAttr(inbound, ATTR_SVR_CLI);
            RpClientProxy rpClientProxy = Sockets.getAttr(inbound, ATTR_SVR_PROXY);
            String channelId = Long.toString(rpClientProxy.nextRemoteChannelId.incrementAndGet());
            inbound.attr(ATTR_REMOTE_CHANNEL_ID).set(channelId);
            rpClientProxy.remoteClients.put(channelId, inbound);
            inbound.attr(ATTR_REMOTE_RELAY_BUF).setIfAbsent(new RemoteRelayBuffer());
            rpClient.onRemoteChannelActive(inbound);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Channel inbound = ctx.channel();
            RpClient rpClient = Sockets.getAttr(inbound, ATTR_SVR_CLI);
            RpClientProxy rpClientProxy = Sockets.getAttr(inbound, ATTR_SVR_PROXY);
            ByteBuf payload = (ByteBuf) msg;
            if (!rpClient.isSessionConnected()) {
                payload.release();
                Sockets.closeOnFlushed(inbound);
                return;
            }
            String channelId = inbound.attr(ATTR_REMOTE_CHANNEL_ID).get();
            byte[] idBytes = channelId.getBytes(StandardCharsets.US_ASCII);
            ByteBuf hdr = ctx.alloc().ioBuffer(1 + 4 + 4 + idBytes.length);
            hdr.writeByte(RrpConfig.ACTION_FORWARD);
            hdr.writeInt(rpClientProxy.p.remotePort);
            hdr.writeInt(idBytes.length);
            hdr.writeBytes(idBytes);
            rpClient.sendFrame(Unpooled.wrappedBuffer(hdr, payload));
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
            Channel inbound = ctx.channel();
            RemoteRelayBuffer relayBuffer = inbound.attr(ATTR_REMOTE_RELAY_BUF).get();
            if (relayBuffer != null) {
                relayBuffer.scheduleDrain(inbound);
            }
            super.channelWritabilityChanged(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            Channel inbound = ctx.channel();
            RpClientProxy rpClientProxy = Sockets.getAttr(inbound, ATTR_SVR_PROXY);
            RpClient rpClient = Sockets.getAttr(inbound, ATTR_SVR_CLI);
            RemoteRelayBuffer relayBuffer = inbound.attr(ATTR_REMOTE_RELAY_BUF).getAndSet(null);
            if (relayBuffer != null) {
                relayBuffer.releaseAll();
            }
            String channelId = inbound.attr(ATTR_REMOTE_CHANNEL_ID).get();
            if (channelId != null) {
                rpClientProxy.remoteClients.remove(channelId, inbound);
                if (rpClient.isSessionConnected()) {
                    byte[] idBytes = channelId.getBytes(StandardCharsets.US_ASCII);
                    ByteBuf buf = ctx.alloc().ioBuffer(1 + 4 + 4 + idBytes.length);
                    buf.writeByte(RrpConfig.ACTION_SYNC_CLOSE);
                    buf.writeInt(rpClientProxy.p.remotePort);
                    buf.writeInt(idBytes.length);
                    buf.writeBytes(idBytes);
                    rpClient.sendFrame(buf);
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Channel inbound = ctx.channel();
            log.warn("RrpServer remote proxy error {} thrown", inbound.remoteAddress(), cause);
            Sockets.closeOnFlushed(inbound);
        }
    }

    @ChannelHandler.Sharable
    private static final class SessionWritabilityHandler extends ChannelInboundHandlerAdapter {
        static final SessionWritabilityHandler DEFAULT = new SessionWritabilityHandler();

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
            RpClient client = ctx.channel().attr(ATTR_SVR_CLI).get();
            if (client != null) {
                client.onSessionWritabilityChanged();
            }
            super.channelWritabilityChanged(ctx);
        }
    }

    final RrpConfig config;
    final NodeRegistry nodeRegistry;
    final TransportServer transportServer;
    final Map<Long, RpClient> clientsBySessionId = new ConcurrentHashMap<Long, RpClient>();
    final Map<String, RpClientProxy> proxiesByName = new ConcurrentHashMap<String, RpClientProxy>();
    final Map<Integer, RpClientProxy> proxiesByPort = new ConcurrentHashMap<Integer, RpClientProxy>();

    // RAW Relay state & multi-tier accounting
    final Map<String, RpClient> rawClientsByNodeId = new ConcurrentHashMap<String, RpClient>();
    final Map<Long, RawRelayStream> rawStreamsById = new ConcurrentHashMap<Long, RawRelayStream>();
    private final Object rawBudgetLock = new Object();
    private final Map<String, AtomicInteger> tenantRawStreamCounts = new ConcurrentHashMap<String, AtomicInteger>();
    private final Map<String, AtomicLong> tenantPendingBytes = new ConcurrentHashMap<String, AtomicLong>();
    private final AtomicLong nextRawStreamId = new AtomicLong();
    private final AtomicInteger activeRawStreamCount = new AtomicInteger();
    private final AtomicLong rawStreamsOpenedCount = new AtomicLong();
    private final AtomicLong rawStreamOverflowCloses = new AtomicLong();
    private final AtomicLong globalRelayPendingBytes = new AtomicLong();
    private final AtomicLong rawRelayIngressBytes = new AtomicLong();
    private final AtomicLong rawRelayEgressBytes = new AtomicLong();
    private final Consumer<NodeRegistry.OnlineNode> revokeListener;

    public RrpServer(@NonNull RrpConfig config) {
        this(config, null);
    }

    public RrpServer(@NonNull RrpConfig config, NodeRegistry nodeRegistry) {
        this.config = config;
        this.nodeRegistry = nodeRegistry;
        this.revokeListener = this::onNodeRevoked;

        int bindPort = config.getBindPort() == null ? 0 : config.getBindPort();
        TransportConfig tc = TransportConfig.pureTcpServer(bindPort);
        tc.setEnableAdaptiveUdp(false);
        tc.setEnableCompression(false);
        boolean hasToken = !Strings.isEmpty(config.getToken());
        tc.setAuthSecret(hasToken ? config.getToken() : null);
        tc.setEnableEncryption(hasToken);
        tc.setEgressConfig(config.getEgressConfig() != null
                ? config.getEgressConfig()
                : EgressConfig.serverAutoAll());
        int hbSec = config.getHeartbeatSeconds();
        tc.getTcpServerConfig().setHeartbeatTimeout(hbSec > 0 ? Math.max(30, hbSec * 3) : 0);
        if (config.getConnectTimeoutMillis() > 0) {
            tc.getTcpServerConfig().setConnectTimeoutMillis(config.getConnectTimeoutMillis());
        }

        this.transportServer = new TransportServer(tc);
        this.transportServer.onConnected.add((s, e) -> onSessionConnected(e.getValue()));
        this.transportServer.onDisconnected.add((s, e) -> onSessionDisconnected(e.getValue()));
        this.transportServer.setReceiveHandler(this::onSessionFrame);

        if (this.nodeRegistry != null) {
            this.nodeRegistry.addOnNodeRevokedListener(this.revokeListener);
        }
        try {
            this.transportServer.start();
        } catch (Throwable t) {
            if (this.nodeRegistry != null) {
                this.nodeRegistry.removeOnNodeRevokedListener(this.revokeListener);
            }
            tryClose(this.transportServer);
            throw InvalidException.sneaky(t);
        }
    }

    public int listenPort() {
        return transportServer.listenPort();
    }

    public long rawStreamsOpenedCount() {
        return rawStreamsOpenedCount.get();
    }

    public int activeRawStreamCount() {
        return Math.max(0, activeRawStreamCount.get());
    }

    public long rawStreamOverflowCloses() {
        return rawStreamOverflowCloses.get();
    }

    public long globalRelayPendingBytes() {
        return Math.max(0L, globalRelayPendingBytes.get());
    }

    public long rawRelayIngressBytes() {
        return rawRelayIngressBytes.get();
    }

    public long rawRelayEgressBytes() {
        return rawRelayEgressBytes.get();
    }

    public int authenticatedRawNodeCount() {
        return rawClientsByNodeId.size();
    }

    private void onSessionConnected(TransportSession session) {
        if (isClosed()) {
            session.close();
            return;
        }
        RpClient rpClient = new RpClient(this, session);
        clientsBySessionId.put(session.sessionId(), rpClient);
        Channel ch = session.channel();
        if (ch != null) {
            ch.attr(ATTR_SVR_CLI).set(rpClient);
            if (ch.pipeline().get(WRITABILITY_HANDLER_NAME) == null) {
                ch.pipeline().addLast(WRITABILITY_HANDLER_NAME, SessionWritabilityHandler.DEFAULT);
            }
        }
    }

    private void onSessionDisconnected(TransportSession session) {
        if (session == null) {
            return;
        }
        RpClient rpClient = clientsBySessionId.remove(session.sessionId());
        if (rpClient != null) {
            tryClose(rpClient);
        }
    }

    private void onSessionFrame(TransportSession session, ByteBuf buf) {
        RpClient rpClient = clientsBySessionId.get(session.sessionId());
        if (rpClient == null || rpClient.isClosed()) {
            return;
        }
        handleInboundFrame(rpClient, buf);
    }

    void handleInboundFrame(RpClient rpClient, ByteBuf buf) {
        if (buf == null || buf.readableBytes() < 1) {
            return;
        }
        byte action = buf.readByte();
        switch (action) {
            case ACTION_HEARTBEAT:
                RrpConfig.writeHeartbeat(rpClient.session);
                return;
            case ACTION_REGISTER:
                handleRegister(rpClient, buf);
                return;
            case ACTION_FORWARD:
                handleForward(rpClient, buf);
                return;
            case ACTION_SYNC_CLOSE:
                handleSyncClose(rpClient, buf);
                return;
            case ACTION_RAW_AUTH:
                handleRawAuth(rpClient, buf);
                return;
            case ACTION_RAW_OPEN:
                handleRawOpen(rpClient, buf);
                return;
            case ACTION_RAW_OPEN_ACK:
                handleRawOpenAckFromTarget(rpClient, buf);
                return;
            case ACTION_RAW_DATA:
                handleRawData(rpClient, buf);
                return;
            case ACTION_RAW_HALF_CLOSE:
                handleRawHalfClose(rpClient, buf);
                return;
            case ACTION_RAW_CLOSE:
                handleRawClose(rpClient, buf);
                return;
            case ACTION_RAW_PAUSE:
                handleRawPause(rpClient, buf);
                return;
            case ACTION_RAW_RESUME:
                handleRawResume(rpClient, buf);
                return;
            default:
                log.warn("RrpServer unknown action={} from session={}", action, rpClient.session.sessionId());
                rpClient.session.close();
        }
    }

    // region SOCKS5 Reverse Proxy Mode

    private void handleRegister(RpClient rpClient, ByteBuf buf) {
        if (buf.readableBytes() < 4) {
            rpClient.session.close();
            return;
        }
        int tokenLen = buf.readInt();
        if (tokenLen < 0 || tokenLen > MAX_TOKEN_LEN || buf.readableBytes() < tokenLen + 4) {
            log.warn("RrpServer invalid tokenLen={} from {}", tokenLen, rpClient.session.remoteEndpoint());
            rpClient.session.close();
            return;
        }
        String token = tokenLen > 0 ? buf.readCharSequence(tokenLen, StandardCharsets.US_ASCII).toString() : null;
        if (!constantTimeEquals(token, config.getToken())) {
            log.warn("RrpServer rejected invalid token from {}", rpClient.session.remoteEndpoint());
            rpClient.session.close();
            return;
        }
        int len = buf.readInt();
        if (len < 0 || len > MAX_REGISTER_BYTES || buf.readableBytes() < len) {
            log.warn("RrpServer invalid register len={} from {}", len, rpClient.session.remoteEndpoint());
            rpClient.session.close();
            return;
        }
        byte[] data = new byte[len];
        buf.readBytes(data, 0, len);
        List<RrpConfig.Proxy> pList;
        try {
            pList = FurySerializer.DEFAULT.deserializeFromBytes(data);
        } catch (Throwable ex) {
            log.warn("RrpServer rejected malformed proxy registration payload from {}", rpClient.session.remoteEndpoint());
            rpClient.session.close();
            return;
        }
        if (pList != null) {
            register(rpClient, pList);
        }
    }

    private void handleForward(RpClient rpClient, ByteBuf buf) {
        if (buf.readableBytes() < 8) {
            return;
        }
        int remotePort = buf.readInt();
        int idLen = buf.readInt();
        if (idLen < 0 || idLen > MAX_CHANNEL_ID_LEN || buf.readableBytes() < idLen) {
            log.warn("RrpServer invalid idLen={} from {}", idLen, rpClient.session.remoteEndpoint());
            rpClient.session.close();
            return;
        }
        String channelId = buf.readCharSequence(idLen, StandardCharsets.US_ASCII).toString();
        RpClientProxy proxyCtx = rpClient.proxyMap.get(remotePort);
        if (proxyCtx == null) {
            log.warn("RrpServer unknown remotePort={} from {}", remotePort, rpClient.session.remoteEndpoint());
            return;
        }
        Channel remoteChannel = proxyCtx.remoteClients.get(channelId);
        if (remoteChannel != null) {
            ByteBuf payload = buf.readRetainedSlice(buf.readableBytes());
            RemoteRelayBuffer relayBuffer = remoteChannel.attr(ATTR_REMOTE_RELAY_BUF).get();
            if (relayBuffer == null) {
                RemoteRelayBuffer newBuffer = new RemoteRelayBuffer();
                RemoteRelayBuffer oldBuffer = remoteChannel.attr(ATTR_REMOTE_RELAY_BUF).setIfAbsent(newBuffer);
                relayBuffer = oldBuffer == null ? newBuffer : oldBuffer;
            }
            relayBuffer.offer(remoteChannel, payload);
        }
    }

    private void handleSyncClose(RpClient rpClient, ByteBuf buf) {
        if (buf.readableBytes() < 8) {
            return;
        }
        int remotePort = buf.readInt();
        int idLen = buf.readInt();
        if (idLen < 0 || idLen > MAX_CHANNEL_ID_LEN || buf.readableBytes() < idLen) {
            log.warn("RrpServer invalid idLen={} from {}", idLen, rpClient.session.remoteEndpoint());
            return;
        }
        String channelId = buf.readCharSequence(idLen, StandardCharsets.US_ASCII).toString();
        RpClientProxy proxyCtx = rpClient.proxyMap.get(remotePort);
        if (proxyCtx == null) {
            return;
        }
        Channel remoteChannel = proxyCtx.remoteClients.get(channelId);
        Sockets.closeOnFlushed(remoteChannel);
    }

    boolean reserveProxy(RpClientProxy rpClientProxy) {
        String name = rpClientProxy.p.getName();
        int remotePort = rpClientProxy.p.getRemotePort();
        if (proxiesByName.putIfAbsent(name, rpClientProxy) != null) {
            log.warn("RrpServer Proxy name {} exist", name);
            return false;
        }
        if (proxiesByPort.putIfAbsent(remotePort, rpClientProxy) != null) {
            proxiesByName.remove(name, rpClientProxy);
            log.warn("RrpServer Proxy remotePort {} exist", remotePort);
            return false;
        }
        if (rpClientProxy.owner.proxyMap.putIfAbsent(remotePort, rpClientProxy) != null) {
            proxiesByName.remove(name, rpClientProxy);
            proxiesByPort.remove(remotePort, rpClientProxy);
            log.warn("RrpServer Proxy remotePort {} exist in client session={}", remotePort, rpClientProxy.owner.session.sessionId());
            return false;
        }
        return true;
    }

    void unregisterProxy(RpClientProxy rpClientProxy) {
        rpClientProxy.owner.proxyMap.remove(rpClientProxy.p.getRemotePort(), rpClientProxy);
        proxiesByName.remove(rpClientProxy.p.getName(), rpClientProxy);
        proxiesByPort.remove(rpClientProxy.p.getRemotePort(), rpClientProxy);
    }

    void register(@NonNull RpClient rpClient, @NonNull List<RrpConfig.Proxy> pList) {
        for (RrpConfig.Proxy rp : pList) {
            String name = rp.getName();
            if (Strings.isEmpty(name)) {
                log.warn("RrpServer Proxy empty name");
                continue;
            }
            int remotePort = rp.getRemotePort();
            ServerBootstrap remoteBootstrap = Sockets.serverBootstrap(channel -> channel.pipeline()
                    .addLast(RemoteServerHandler.DEFAULT));
            RpClientProxy rpClientProxy = new RpClientProxy(this, rpClient, rp, remoteBootstrap);
            if (!reserveProxy(rpClientProxy)) {
                tryClose(rpClientProxy);
                continue;
            }

            try {
                List<Channel> bindChannels = Sockets.bindChannels(remoteBootstrap
                        .attr(ATTR_SVR_CLI, rpClient)
                        .attr(ATTR_SVR_PROXY, rpClientProxy), Sockets.newAnyEndpoint(remotePort), config);
                rpClientProxy.remoteServerChannels = bindChannels;
                rpClientProxy.remoteServerChannel = bindChannels.get(0);
                if (rpClientProxy.isClosed() || rpClient.isClosed() || !rpClient.isSessionConnected()) {
                    tryClose(rpClientProxy);
                    continue;
                }
                log.debug("RrpServer proxy bound session={} remotePort={}", rpClient.session.sessionId(), remotePort);
            } catch (RuntimeException e) {
                log.error("RrpServer proxy bind failed session={} remotePort={}", rpClient.session.sessionId(), remotePort, e);
                tryClose(rpClientProxy);
            }
        }
    }

    // endregion

    // region NodeRegistry-Authorized RAW Logical Stream Relay

    private void handleRawAuth(RpClient rpClient, ByteBuf buf) {
        if (nodeRegistry == null || !config.isEnableRawRelay()) {
            sendRawAuthAck(rpClient, RAW_STATUS_DISABLED, "raw relay disabled");
            return;
        }
        String nodeId = readShortUtf8(buf, MAX_ID_LEN);
        String secret = readShortUtf8(buf, MAX_SECRET_LEN);
        if (Strings.isEmpty(nodeId) || Strings.isEmpty(secret) || buf.readableBytes() < 2) {
            sendRawAuthAck(rpClient, RAW_STATUS_REJECTED, "malformed raw auth");
            rpClient.session.close();
            return;
        }
        int svcCount = buf.readUnsignedShort();
        if (svcCount > MAX_RAW_SERVICES_PER_NODE) {
            sendRawAuthAck(rpClient, RAW_STATUS_REJECTED, "too many services");
            rpClient.session.close();
            return;
        }
        Set<String> services = new HashSet<String>(Math.max(4, svcCount));
        for (int i = 0; i < svcCount; i++) {
            String svc = readShortUtf8(buf, MAX_ID_LEN);
            if (Strings.isEmpty(svc)) {
                sendRawAuthAck(rpClient, RAW_STATUS_REJECTED, "invalid service name");
                rpClient.session.close();
                return;
            }
            services.add(svc.trim());
        }
        final String trimmedNodeId = nodeId.trim();
        final Set<String> immutableServices = Collections.unmodifiableSet(services);

        // Run PBKDF2 + H2 authentication strictly off-EventLoop.
        Tasks.run(() -> {
            NodeRegistry.OnlineNode onlineNode;
            try {
                onlineNode = nodeRegistry.authenticateAndAttachSession(trimmedNodeId, secret, SUBSYSTEM_RRP_RELAY, rpClient, null);
            } catch (Throwable ex) {
                log.warn("RrpServer RAW auth rejected nodeId={} reason={}", trimmedNodeId, ex.getMessage());
                sendRawAuthAck(rpClient, RAW_STATUS_UNAUTHORIZED, "authentication failed");
                return;
            }
            if (isClosed() || rpClient.isClosed() || !rpClient.isSessionConnected()) {
                nodeRegistry.detachSubsystemSession(trimmedNodeId, SUBSYSTEM_RRP_RELAY, rpClient);
                return;
            }
            String prevNodeId = rpClient.authenticatedNodeId;
            if (prevNodeId != null && !prevNodeId.equals(onlineNode.getNodeId())) {
                rawClientsByNodeId.remove(prevNodeId, rpClient);
                nodeRegistry.detachSubsystemSession(prevNodeId, SUBSYSTEM_RRP_RELAY, rpClient);
            }
            rpClient.authenticatedNodeId = onlineNode.getNodeId();
            rpClient.authenticatedTenant = onlineNode.getTenant();
            rpClient.hostedRawServices = immutableServices;
            RpClient replaced = rawClientsByNodeId.put(onlineNode.getNodeId(), rpClient);
            if (replaced != null && replaced != rpClient) {
                tryClose(replaced);
            }
            sendRawAuthAck(rpClient, RAW_STATUS_OK, "");
            log.info("RrpServer RAW node authenticated nodeId={} tenant={} services={}",
                    onlineNode.getNodeId(), onlineNode.getTenant(), immutableServices.size());
        });
    }

    private void handleRawOpen(RpClient initiator, ByteBuf buf) {
        if (buf.readableBytes() < 4) {
            initiator.session.close();
            return;
        }
        int reqId = buf.readInt();
        String targetNodeId = readShortUtf8(buf, MAX_ID_LEN);
        String targetService = readShortUtf8(buf, MAX_ID_LEN);
        if (Strings.isEmpty(targetNodeId) || Strings.isEmpty(targetService)) {
            sendRawOpenAckToInitiator(initiator, reqId, 0L, RAW_STATUS_REJECTED, "invalid target");
            return;
        }
        if (nodeRegistry == null || !config.isEnableRawRelay()) {
            sendRawOpenAckToInitiator(initiator, reqId, 0L, RAW_STATUS_DISABLED, "raw relay disabled");
            return;
        }
        String initiatorNodeId = initiator.authenticatedNodeId;
        if (initiatorNodeId == null || !nodeRegistry.isSubsystemSessionActive(initiatorNodeId, SUBSYSTEM_RRP_RELAY, initiator)) {
            sendRawOpenAckToInitiator(initiator, reqId, 0L, RAW_STATUS_UNAUTHORIZED, "initiator unauthenticated");
            return;
        }
        String normTarget = targetNodeId.trim();
        String normSvc = targetService.trim();
        if (!nodeRegistry.isPeerAuthorized(initiatorNodeId, normTarget)) {
            sendRawOpenAckToInitiator(initiator, reqId, 0L, RAW_STATUS_UNAUTHORIZED, "peer not authorized");
            return;
        }
        RpClient target = rawClientsByNodeId.get(normTarget);
        if (target == null || target.isClosed() || !target.isSessionConnected()
                || !nodeRegistry.isSubsystemSessionActive(normTarget, SUBSYSTEM_RRP_RELAY, target)) {
            sendRawOpenAckToInitiator(initiator, reqId, 0L, RAW_STATUS_TARGET_OFFLINE, "target offline");
            return;
        }
        if (!target.hostedRawServices.contains(normSvc)) {
            sendRawOpenAckToInitiator(initiator, reqId, 0L, RAW_STATUS_SERVICE_NOT_FOUND, "service not hosted");
            return;
        }

        RawRelayStream stream;
        synchronized (rawBudgetLock) {
            if (isClosed() || initiator.isClosed() || target.isClosed()) {
                sendRawOpenAckToInitiator(initiator, reqId, 0L, RAW_STATUS_TARGET_OFFLINE, "peer closing");
                return;
            }
            int maxGlobal = Math.max(1, config.getMaxGlobalRawStreams());
            int maxPerTenant = Math.max(1, config.getMaxRawStreamsPerTenant());
            int maxPerNode = Math.max(1, config.getMaxRawStreamsPerNode());
            String tenant = initiator.authenticatedTenant != null ? initiator.authenticatedTenant : "default";
            AtomicInteger tenantCounter = tenantRawStreamCounts.computeIfAbsent(tenant, k -> new AtomicInteger());
            if (activeRawStreamCount.get() >= maxGlobal
                    || tenantCounter.get() >= maxPerTenant
                    || initiator.activeRawStreamCount.get() >= maxPerNode
                    || target.activeRawStreamCount.get() >= maxPerNode) {
                sendRawOpenAckToInitiator(initiator, reqId, 0L, RAW_STATUS_LIMIT_EXCEEDED, "raw stream budget exceeded");
                return;
            }
            long streamId = nextRawStreamId.incrementAndGet();
            stream = new RawRelayStream(streamId, reqId, initiator, target, normSvc);
            rawStreamsById.put(streamId, stream);
            initiator.rawStreams.put(streamId, stream);
            target.rawStreams.put(streamId, stream);
            activeRawStreamCount.incrementAndGet();
            tenantCounter.incrementAndGet();
            initiator.activeRawStreamCount.incrementAndGet();
            if (target != initiator) {
                target.activeRawStreamCount.incrementAndGet();
            }
            rawStreamsOpenedCount.incrementAndGet();
        }

        int openTimeoutMs = Math.max(500, config.getRawOpenTimeoutMillis());
        Channel ch = initiator.channel();
        if (ch != null) {
            stream.openTimeoutFuture = ch.eventLoop().schedule(() -> {
                if (!stream.opened.get() && !stream.closed.get()) {
                    closeRawStream(stream, RAW_CLOSE_NORMAL, false, true);
                    sendRawOpenAckToInitiator(initiator, reqId, stream.streamId, RAW_STATUS_TIMEOUT, "open timeout");
                }
            }, openTimeoutMs, TimeUnit.MILLISECONDS);
        }

        sendRawOpenIncomingToTarget(target, stream.streamId, initiatorNodeId, normSvc);
    }

    private void handleRawOpenAckFromTarget(RpClient target, ByteBuf buf) {
        if (buf.readableBytes() < 8 + 1 + 2) {
            return;
        }
        long streamId = buf.readLong();
        byte status = buf.readByte();
        String msg = readShortUtf8(buf, 256);
        RawRelayStream stream = rawStreamsById.get(streamId);
        if (stream == null || stream.target != target || stream.closed.get()) {
            return;
        }
        stream.cancelOpenTimeout();
        if (!stream.opened.compareAndSet(false, true)) {
            return;
        }
        if (status == RAW_STATUS_OK) {
            sendRawOpenAckToInitiator(stream.initiator, stream.initiatorReqId, streamId, RAW_STATUS_OK, "");
        } else {
            closeRawStream(stream, RAW_CLOSE_NORMAL, false, false);
            sendRawOpenAckToInitiator(stream.initiator, stream.initiatorReqId, streamId, status, msg == null ? "" : msg);
        }
    }

    private void handleRawData(RpClient sender, ByteBuf buf) {
        if (buf.readableBytes() < 8) {
            return;
        }
        long streamId = buf.readLong();
        int payloadLen = buf.readableBytes();
        RawRelayStream stream = rawStreamsById.get(streamId);
        if (stream == null || stream.closed.get() || !stream.opened.get()) {
            return;
        }
        RawStreamDir dir;
        if (sender == stream.initiator) {
            dir = stream.aToB;
        } else if (sender == stream.target) {
            dir = stream.bToA;
        } else {
            return;
        }
        if (dir.halfClosePending) {
            return;
        }
        int maxFrame = Math.max(1024, config.getMaxRawFrameBytes());
        if (payloadLen > maxFrame) {
            log.warn("RrpServer RAW frame oversized streamId={} len={} cap={}", streamId, payloadLen, maxFrame);
            closeRawStream(stream, RAW_CLOSE_PROTOCOL_ERROR, true, true);
            return;
        }
        if (payloadLen == 0) {
            return;
        }
        ByteBuf payload = buf.readRetainedSlice(payloadLen);
        rawRelayIngressBytes.addAndGet(payloadLen);

        if (!tryReservePendingBytes(dir.receiver, dir, payloadLen)) {
            payload.release();
            rawStreamOverflowCloses.incrementAndGet();
            log.warn("RrpServer RAW pending byte cap exceeded streamId={} receiver={} close stream",
                    streamId, dir.receiver.authenticatedNodeId);
            closeRawStream(stream, RAW_CLOSE_OVERFLOW, true, true);
            return;
        }

        dir.queue.offer(payload);
        if (stream.closed.get()) {
            drainAndReleaseQueued(dir.receiver, dir);
            return;
        }
        int highWater = Math.max(1024, config.getStreamPauseHighWaterBytes());
        if (dir.pendingBytes.get() >= highWater && dir.pausedSender.compareAndSet(false, true)) {
            sendRawControl(dir.sender, ACTION_RAW_PAUSE, streamId);
        }
        enqueueDirForDrain(dir);
    }

    private void handleRawHalfClose(RpClient sender, ByteBuf buf) {
        if (buf.readableBytes() < 8) {
            return;
        }
        long streamId = buf.readLong();
        RawRelayStream stream = rawStreamsById.get(streamId);
        if (stream == null || stream.closed.get()) {
            return;
        }
        RawStreamDir dir = sender == stream.initiator ? stream.aToB
                : (sender == stream.target ? stream.bToA : null);
        if (dir == null) {
            return;
        }
        dir.halfClosePending = true;
        enqueueDirForDrain(dir);
    }

    private void handleRawClose(RpClient sender, ByteBuf buf) {
        if (buf.readableBytes() < 9) {
            return;
        }
        long streamId = buf.readLong();
        byte reason = buf.readByte();
        RawRelayStream stream = rawStreamsById.get(streamId);
        if (stream == null) {
            return;
        }
        if (sender == stream.initiator) {
            closeRawStream(stream, reason, false, true);
        } else if (sender == stream.target) {
            closeRawStream(stream, reason, true, false);
        }
    }

    private void handleRawPause(RpClient receiver, ByteBuf buf) {
        if (buf.readableBytes() < 8) {
            return;
        }
        long streamId = buf.readLong();
        RawRelayStream stream = rawStreamsById.get(streamId);
        if (stream == null || stream.closed.get()) {
            return;
        }
        RawStreamDir dir = receiver == stream.target ? stream.aToB
                : (receiver == stream.initiator ? stream.bToA : null);
        if (dir == null) {
            return;
        }
        dir.remotePaused.set(true);
        if (dir.pausedSender.compareAndSet(false, true)) {
            sendRawControl(dir.sender, ACTION_RAW_PAUSE, streamId);
        }
    }

    private void handleRawResume(RpClient receiver, ByteBuf buf) {
        if (buf.readableBytes() < 8) {
            return;
        }
        long streamId = buf.readLong();
        RawRelayStream stream = rawStreamsById.get(streamId);
        if (stream == null || stream.closed.get()) {
            return;
        }
        RawStreamDir dir = receiver == stream.target ? stream.aToB
                : (receiver == stream.initiator ? stream.bToA : null);
        if (dir == null) {
            return;
        }
        dir.remotePaused.set(false);
        maybeResumeSender(dir);
    }

    private boolean tryReservePendingBytes(RpClient receiver, RawStreamDir dir, int bytes) {
        int streamCap = Math.max(1024, config.getMaxStreamRelayPendingBytes());
        long nodeCap = Math.max(streamCap, config.getMaxNodeRelayPendingBytes());
        long tenantCap = Math.max(nodeCap, config.getMaxTenantRelayPendingBytes());
        long globalCap = Math.max(tenantCap, config.getMaxGlobalRelayPendingBytes());

        long g = globalRelayPendingBytes.addAndGet(bytes);
        if (g > globalCap) {
            globalRelayPendingBytes.addAndGet(-bytes);
            return false;
        }
        AtomicLong tCounter = tenantPendingBytes.computeIfAbsent(dir.stream.billingTenant, k -> new AtomicLong());
        long t = tCounter.addAndGet(bytes);
        if (t > tenantCap) {
            tCounter.addAndGet(-bytes);
            globalRelayPendingBytes.addAndGet(-bytes);
            return false;
        }
        long n = receiver.pendingRawBytes.addAndGet(bytes);
        if (n > nodeCap) {
            receiver.pendingRawBytes.addAndGet(-bytes);
            tCounter.addAndGet(-bytes);
            globalRelayPendingBytes.addAndGet(-bytes);
            return false;
        }
        int s = dir.pendingBytes.addAndGet(bytes);
        if (s > streamCap) {
            dir.pendingBytes.addAndGet(-bytes);
            receiver.pendingRawBytes.addAndGet(-bytes);
            tCounter.addAndGet(-bytes);
            globalRelayPendingBytes.addAndGet(-bytes);
            return false;
        }
        return true;
    }

    private void releasePendingBytes(RpClient receiver, RawStreamDir dir, int bytes) {
        if (bytes <= 0) {
            return;
        }
        dir.pendingBytes.addAndGet(-bytes);
        receiver.pendingRawBytes.addAndGet(-bytes);
        AtomicLong tCounter = tenantPendingBytes.get(dir.stream.billingTenant);
        if (tCounter != null) {
            tCounter.addAndGet(-bytes);
        }
        globalRelayPendingBytes.addAndGet(-bytes);
    }

    private void maybeResumeSender(RawStreamDir dir) {
        if (dir.stream.closed.get() || dir.remotePaused.get()) {
            return;
        }
        int lowWater = Math.max(512, Math.min(config.getStreamResumeLowWaterBytes(),
                config.getStreamPauseHighWaterBytes()));
        if (dir.pendingBytes.get() <= lowWater && dir.pausedSender.compareAndSet(true, false)) {
            sendRawControl(dir.sender, ACTION_RAW_RESUME, dir.stream.streamId);
        }
    }

    private void enqueueDirForDrain(RawStreamDir dir) {
        if (dir.queuedForDrain.compareAndSet(false, true)) {
            dir.receiver.readyDirs.offer(dir);
        }
        scheduleRawDrain(dir.receiver);
    }

    void scheduleRawDrain(RpClient receiver) {
        if (receiver == null || receiver.isClosed() || !receiver.isSessionConnected() || !receiver.isSessionWritable()) {
            return;
        }
        if (!receiver.rawDraining.compareAndSet(0, 1)) {
            return;
        }
        Channel ch = receiver.channel();
        if (ch == null) {
            receiver.rawDraining.set(0);
            return;
        }
        if (ch.eventLoop().inEventLoop()) {
            drainRawQueues(receiver);
        } else {
            ch.eventLoop().execute(() -> drainRawQueues(receiver));
        }
    }

    private void drainRawQueues(RpClient receiver) {
        try {
            RawStreamDir dir;
            while (!receiver.isClosed() && receiver.isSessionConnected() && receiver.isSessionWritable()
                    && (dir = receiver.readyDirs.poll()) != null) {
                dir.queuedForDrain.set(false);
                if (dir.stream.closed.get()) {
                    drainAndReleaseQueued(receiver, dir);
                    continue;
                }
                ByteBuf chunk = dir.queue.poll();
                if (chunk != null) {
                    int chunkLen = chunk.readableBytes();
                    ByteBuf hdr = receiver.channel().alloc().ioBuffer(9, 9);
                    hdr.writeByte(ACTION_RAW_DATA);
                    hdr.writeLong(dir.stream.streamId);
                    rawRelayEgressBytes.addAndGet(chunkLen);
                    final RawStreamDir boundDir = dir;
                    ChannelFuture f = receiver.sendFrame(Unpooled.wrappedBuffer(hdr, chunk));
                    if (f != null) {
                        f.addListener(done -> {
                            releasePendingBytes(receiver, boundDir, chunkLen);
                            maybeResumeSender(boundDir);
                        });
                    } else {
                        releasePendingBytes(receiver, boundDir, chunkLen);
                    }
                }
                if (!dir.queue.isEmpty()) {
                    if (dir.queuedForDrain.compareAndSet(false, true)) {
                        receiver.readyDirs.offer(dir);
                    }
                } else if (dir.halfClosePending && dir.halfCloseSent.compareAndSet(false, true)) {
                    sendRawControl(receiver, ACTION_RAW_HALF_CLOSE, dir.stream.streamId);
                    if (dir.stream.aToB.halfCloseSent.get() && dir.stream.bToA.halfCloseSent.get()) {
                        closeRawStream(dir.stream, RAW_CLOSE_NORMAL, false, false);
                    }
                }
            }
        } finally {
            receiver.rawDraining.set(0);
            if (!receiver.isClosed() && receiver.isSessionConnected() && receiver.isSessionWritable()
                    && !receiver.readyDirs.isEmpty()) {
                scheduleRawDrain(receiver);
            }
        }
    }

    private void drainAndReleaseQueued(RpClient receiver, RawStreamDir dir) {
        ByteBuf chunk;
        while ((chunk = dir.queue.poll()) != null) {
            int len = chunk.readableBytes();
            chunk.release();
            releasePendingBytes(receiver, dir, len);
        }
    }

    void closeRawStream(RawRelayStream stream, byte reason, boolean notifyInitiator, boolean notifyTarget) {
        if (stream == null || !stream.closed.compareAndSet(false, true)) {
            return;
        }
        stream.cancelOpenTimeout();
        synchronized (rawBudgetLock) {
            if (rawStreamsById.remove(stream.streamId, stream)) {
                activeRawStreamCount.decrementAndGet();
                AtomicInteger tc = tenantRawStreamCounts.get(stream.billingTenant);
                if (tc != null) {
                    tc.decrementAndGet();
                }
                if (stream.initiator.rawStreams.remove(stream.streamId, stream)) {
                    stream.initiator.activeRawStreamCount.decrementAndGet();
                }
                if (stream.target != stream.initiator
                        && stream.target.rawStreams.remove(stream.streamId, stream)) {
                    stream.target.activeRawStreamCount.decrementAndGet();
                }
            }
        }
        drainAndReleaseQueued(stream.target, stream.aToB);
        drainAndReleaseQueued(stream.initiator, stream.bToA);

        if (notifyInitiator && stream.initiator.isSessionConnected()) {
            sendRawClose(stream.initiator, stream.streamId, reason);
        }
        if (notifyTarget && stream.target.isSessionConnected()) {
            sendRawClose(stream.target, stream.streamId, reason);
        }
    }

    void cleanupClientRawState(RpClient rpClient, byte reason) {
        String nodeId = rpClient.authenticatedNodeId;
        if (nodeId != null) {
            rawClientsByNodeId.remove(nodeId, rpClient);
            if (nodeRegistry != null) {
                nodeRegistry.detachSubsystemSession(nodeId, SUBSYSTEM_RRP_RELAY, rpClient);
            }
        }
        for (RawRelayStream stream : rpClient.rawStreams.values()) {
            boolean notifyInitiator = stream.initiator != rpClient;
            boolean notifyTarget = stream.target != rpClient;
            closeRawStream(stream, reason, notifyInitiator, notifyTarget);
        }
        rpClient.rawStreams.clear();
        rpClient.readyDirs.clear();
    }

    private void onNodeRevoked(NodeRegistry.OnlineNode evicted) {
        if (evicted == null || evicted.getNodeId() == null) {
            return;
        }
        RpClient client = rawClientsByNodeId.get(evicted.getNodeId());
        if (client == null) {
            return;
        }
        Object subRef = evicted.getSubsystemSession(SUBSYSTEM_RRP_RELAY);
        if (subRef != null && subRef != client) {
            return;
        }
        if (subRef == null && evicted.getSessionRef() != null && evicted.getSessionRef() != client) {
            return;
        }
        log.info("RrpServer tearing down revoked nodeId={}", evicted.getNodeId());
        cleanupClientRawState(client, RAW_CLOSE_REVOKED);
        tryClose(client);
    }

    private void sendRawAuthAck(RpClient client, byte status, String message) {
        if (client == null || !client.isSessionConnected()) {
            return;
        }
        byte[] msgBytes = message == null ? new byte[0] : message.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = client.channel().alloc().ioBuffer(1 + 1 + 2 + msgBytes.length);
        buf.writeByte(ACTION_RAW_AUTH_ACK);
        buf.writeByte(status);
        buf.writeShort(msgBytes.length);
        if (msgBytes.length > 0) {
            buf.writeBytes(msgBytes);
        }
        client.sendFrame(buf);
    }

    private void sendRawOpenIncomingToTarget(RpClient target, long streamId, String initiatorNodeId, String targetService) {
        byte[] initBytes = initiatorNodeId.getBytes(StandardCharsets.UTF_8);
        byte[] svcBytes = targetService.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = target.channel().alloc().ioBuffer(1 + 8 + 2 + initBytes.length + 2 + svcBytes.length);
        buf.writeByte(ACTION_RAW_OPEN_INCOMING);
        buf.writeLong(streamId);
        buf.writeShort(initBytes.length);
        buf.writeBytes(initBytes);
        buf.writeShort(svcBytes.length);
        buf.writeBytes(svcBytes);
        target.sendFrame(buf);
    }

    private void sendRawOpenAckToInitiator(RpClient initiator, int reqId, long streamId, byte status, String message) {
        if (initiator == null || !initiator.isSessionConnected()) {
            return;
        }
        byte[] msgBytes = message == null ? new byte[0] : message.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = initiator.channel().alloc().ioBuffer(1 + 4 + 8 + 1 + 2 + msgBytes.length);
        buf.writeByte(ACTION_RAW_OPEN_ACK);
        buf.writeInt(reqId);
        buf.writeLong(streamId);
        buf.writeByte(status);
        buf.writeShort(msgBytes.length);
        if (msgBytes.length > 0) {
            buf.writeBytes(msgBytes);
        }
        initiator.sendFrame(buf);
    }

    private void sendRawControl(RpClient client, byte action, long streamId) {
        if (client == null || !client.isSessionConnected()) {
            return;
        }
        ByteBuf buf = client.channel().alloc().ioBuffer(9, 9);
        buf.writeByte(action);
        buf.writeLong(streamId);
        client.sendFrame(buf);
    }

    private void sendRawClose(RpClient client, long streamId, byte reason) {
        if (client == null || !client.isSessionConnected()) {
            return;
        }
        ByteBuf buf = client.channel().alloc().ioBuffer(10, 10);
        buf.writeByte(ACTION_RAW_CLOSE);
        buf.writeLong(streamId);
        buf.writeByte(reason);
        client.sendFrame(buf);
    }

    // endregion

    @Override
    protected void dispose() {
        if (nodeRegistry != null) {
            nodeRegistry.removeOnNodeRevokedListener(revokeListener);
        }
        for (RawRelayStream stream : rawStreamsById.values()) {
            closeRawStream(stream, RAW_CLOSE_PEER_DISCONNECTED, false, false);
        }
        rawStreamsById.clear();
        for (RpClient client : clientsBySessionId.values()) {
            tryClose(client);
        }
        clientsBySessionId.clear();
        rawClientsByNodeId.clear();
        proxiesByName.clear();
        proxiesByPort.clear();
        tryClose(transportServer);
    }

    static void closeChannel(Channel channel) {
        if (channel == null || !channel.isOpen()) {
            return;
        }
        channel.close();
    }

    static String readShortUtf8(ByteBuf buf, int maxLen) {
        if (buf.readableBytes() < 2) {
            return null;
        }
        int len = buf.readUnsignedShort();
        if (len > maxLen || buf.readableBytes() < len) {
            return null;
        }
        if (len == 0) {
            return "";
        }
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static boolean constantTimeEquals(String a, String b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
