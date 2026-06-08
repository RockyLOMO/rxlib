package org.rx.net.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Delegate;
import org.rx.core.EventPublisher;
import org.rx.core.NEventArgs;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;
import org.rx.net.transport.kcp.RxKcp;
import org.rx.net.transport.protocol.AckSync;
import org.rx.net.transport.protocol.UdpMessage;
import org.rx.net.transport.protocol.UdpRpcResponse;
import org.rx.net.udp.UdpResilienceAttributes;
import org.rx.util.IdGenerator;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.rx.core.Extends.quietly;

/**
 * KCP based reliable ordered UDP message transport using the RXKC protocol.
 *
 * <p>Messages are ordered within one session. With an authentication key,
 * RXKC v2 uses an authenticated handshake and may securely migrate the
 * sender endpoint after NAT rebinding. KCP transport ACKs confirm reliable
 * delivery, not completion of an application handler.</p>
 */
@Slf4j
public class KcpClient implements EventPublisher<KcpClient>, AutoCloseable {
    static final class SessionKey {
        final InetSocketAddress remoteAddress;
        final long sessionId;
        final int conv;

        SessionKey(InetSocketAddress remoteAddress, long sessionId, int conv) {
            this.remoteAddress = remoteAddress;
            this.sessionId = sessionId;
            this.conv = conv;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof SessionKey)) {
                return false;
            }
            SessionKey that = (SessionKey) obj;
            if (conv != that.conv || sessionId != that.sessionId) {
                return false;
            }
            return sessionId != 0 || Objects.equals(remoteAddress, that.remoteAddress);
        }

        @Override
        public int hashCode() {
            return sessionId != 0
                    ? 31 * (int) (sessionId ^ (sessionId >>> 32)) + conv
                    : 31 * remoteAddress.hashCode() + conv;
        }
    }

    static final class RpcRequestContext<T extends Serializable> {
        final Class<T> responseType;
        final CompletableFuture<T> future = new CompletableFuture<>();
        volatile ScheduledFuture<?> timeoutFuture;

        RpcRequestContext(Class<T> responseType) {
            this.responseType = responseType;
        }

        void cancelTimeout() {
            ScheduledFuture<?> timeout = timeoutFuture;
            if (timeout != null) {
                timeout.cancel(false);
            }
        }
    }

    final class KcpSession {
        final SessionKey key;
        final RxKcp kcp;
        final boolean initiator;
        volatile InetSocketAddress remoteAddress;
        volatile long lastActiveMillis;
        long nextPacketSequence;
        long highestReceivedSequence;
        long receivedSequenceBits;
        boolean receivedSequence;
        boolean handshakeConfirmed;
        CompletableFuture<Void> deliveryTail = CompletableFuture.completedFuture(null);
        boolean closed;

        KcpSession(SessionKey key, InetSocketAddress remoteAddress, boolean initiator, long nowMillis) {
            this.key = key;
            this.remoteAddress = remoteAddress;
            this.initiator = initiator;
            this.lastActiveMillis = nowMillis;
            kcp = new RxKcp(key.conv, channel.alloc(), datagram -> writeKcpDatagram(this, datagram));
            kcp.setMtu(effectiveKcpMtu);
            kcp.setWindowSize(config.getSendWindow(), config.getReceiveWindow());
            kcp.setNoDelay(config.getNoDelay(), config.getIntervalMillis(),
                    config.getFastResend(), config.getNoCongestionControl());
        }

        void send(ByteBuf frame, long nowMillis) {
            if (!kcp.canSend(frame.readableBytes(), config.getMaxPendingBytesPerSession(),
                    config.getMaxPendingMessagesPerSession())) {
                throw new InvalidException("KCP session backpressure {} pendingBytes={} pendingMessages={}",
                        remoteAddress, kcp.pendingBytes(), kcp.pendingMessages());
            }
            lastActiveMillis = nowMillis;
            kcp.send(frame);
            if (config.isFlushOnSend()) {
                kcp.flushNow(nowMillis);
            }
        }

        void input(ByteBuf packet, long nowMillis) {
            lastActiveMillis = nowMillis;
            int result = kcp.input(packet, nowMillis);
            if (result != 0) {
                throw new InvalidException("Invalid KCP segment {} result={}", remoteAddress, result);
            }
            drainReceive();
        }

        void update(long nowMillis) {
            kcp.update(nowMillis);
            drainReceive();
        }

        void drainReceive() {
            ByteBuf frame;
            while ((frame = kcp.receive()) != null) {
                try {
                    handleLogicalFrame(this, frame);
                } finally {
                    ReferenceCountUtil.safeRelease(frame);
                }
            }
        }

        void deliver(UdpMessage message) {
            CompletableFuture<Void> current = deliveryTail.handle((r, e) -> null)
                    .thenCompose(r -> publishEventAsync(onReceive, new NEventArgs<>(message)));
            deliveryTail = current;
            current.whenComplete((r, e) -> {
                if (e != null) {
                    onHandlerError(e, remoteAddress);
                }
            });
        }

        boolean isNewerSequence(long sequence) {
            return !receivedSequence || sequence > highestReceivedSequence;
        }

        boolean acceptSequence(long sequence) {
            if (sequence <= 0) {
                return false;
            }
            if (!receivedSequence) {
                receivedSequence = true;
                highestReceivedSequence = sequence;
                receivedSequenceBits = 1L;
                return true;
            }
            if (sequence > highestReceivedSequence) {
                long shift = sequence - highestReceivedSequence;
                receivedSequenceBits = shift >= 64 ? 1L : (receivedSequenceBits << shift) | 1L;
                highestReceivedSequence = sequence;
                return true;
            }
            long distance = highestReceivedSequence - sequence;
            if (distance >= 64 || (receivedSequenceBits & (1L << distance)) != 0) {
                return false;
            }
            receivedSequenceBits |= 1L << distance;
            return true;
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            kcp.release();
        }
    }

    static final AttributeKey<KcpClient> OWNER = AttributeKey.valueOf("KcpClient");
    static final Handler HANDLER = new Handler();

    static final int MAGIC = 0x52584B43; // RXKC
    static final byte VERSION = 1;
    static final byte TYPE_KCP_DATA = 1;
    static final byte TYPE_CLOSE = 2;
    static final int HEADER_SIZE = 12;
    static final byte AUTH_VERSION = 2;
    static final byte TYPE_OPEN = 3;
    static final byte TYPE_OPEN_ACK = 4;
    static final byte FLAG_AUTH = 1;
    static final byte FLAG_INITIATOR = 2;
    static final int AUTH_HEADER_SIZE = 28;
    static final int AUTH_TAG_SIZE = 16;
    static final int AUTH_DATAGRAM_HEADER_SIZE = AUTH_HEADER_SIZE + AUTH_TAG_SIZE;
    static final String AUTH_ALGORITHM = "HmacSHA256";
    static final int REJECT_METRIC_SAMPLE_RATE = 64;

    static final short MESSAGE_MAGIC = (short) 0x4B4D; // KM
    static final byte MESSAGE_VERSION = 1;
    static final byte MESSAGE_TYPE_DATA = 1;
    static final int MESSAGE_HEADER_SIZE = 12;

    @ChannelHandler.Sharable
    static class Handler extends SimpleChannelInboundHandler<DatagramPacket> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
            KcpClient client = ctx.channel().attr(OWNER).get();
            if (client != null) {
                client.handlePacket(packet);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            KcpClient client = ctx.channel().attr(OWNER).get();
            if (client != null) {
                client.onHandlerError(cause);
            }
        }
    }

    public final Delegate<KcpClient, NEventArgs<UdpMessage>> onReceive = Delegate.create();
    public final Delegate<KcpClient, NEventArgs<Throwable>> onError = Delegate.create();

    final Bootstrap bootstrap;
    @Getter
    final Channel channel;
    final List<Channel> channels;
    final KcpClientConfig config;
    @Getter
    final UdpClientCodec codec;
    final IdGenerator messageIds = new IdGenerator();
    final IdGenerator convIds = new IdGenerator();
    final ConcurrentMap<SessionKey, KcpSession> sessions = new ConcurrentHashMap<>();
    final ConcurrentMap<InetSocketAddress, KcpSession> outboundSessions = new ConcurrentHashMap<>();
    final ConcurrentMap<Integer, RpcRequestContext<?>> pendingRequests = new ConcurrentHashMap<>();
    final AtomicBoolean closed = new AtomicBoolean();
    final Mac authenticationMac;
    final byte[] authenticationTag = new byte[32];
    long rejectedPacketMetricCounter;
    final int effectiveKcpMtu;
    final int tickIntervalMillis;
    volatile ScheduledFuture<?> tickFuture;
    @Getter
    final InetSocketAddress localEndpoint;

    public KcpClient(int bindPort) {
        this(bindPort, new KcpClientConfig());
    }

    public KcpClient(int bindPort, UdpClientCodec codec) {
        this(bindPort, configOf(codec));
    }

    public KcpClient(int bindPort, KcpClientConfig config) {
        this.config = requireConfig(config);
        this.codec = requireCodec(config.getCodec());
        validateConfig(config);
        byte[] authenticationKey = config.getAuthenticationKey();
        authenticationMac = newAuthenticationMac(authenticationKey);
        effectiveKcpMtu = effectiveKcpMtu(config);
        tickIntervalMillis = Math.max(10, Math.min(5000, config.getIntervalMillis()));
        // A KCP control block is EventLoop-affine; do not split one bound port over listeners.
        config.setReusePortBindCount(1);
        bootstrap = Sockets.udpBootstrap(config, ch -> ch.pipeline().addLast(HANDLER));
        channels = Sockets.bindChannels(bootstrap, Sockets.newAnyEndpoint(bindPort), config);
        channel = channels.get(0);
        for (Channel ch : channels) {
            ch.attr(OWNER).set(this);
        }
        localEndpoint = UdpClient.connectableLocalEndpoint((InetSocketAddress) channel.localAddress());
        tickFuture = channel.eventLoop().scheduleAtFixedRate(this::onTick,
                tickIntervalMillis, tickIntervalMillis, TimeUnit.MILLISECONDS);
    }

    public boolean isActive() {
        return channel.isActive() && !closed.get();
    }

    public int sessionCount() {
        return sessions.size();
    }

    public ChannelFuture send(InetSocketAddress remoteAddress, Object packet) {
        return sendMessage(remoteAddress, packet, nextMessageId());
    }

    public void reply(@NonNull UdpMessage request, @NonNull Serializable packet) {
        send(request.remoteAddress, UdpRpcResponse.success(request.id, packet));
    }

    public void replyError(@NonNull UdpMessage request, @NonNull Throwable error) {
        send(request.remoteAddress, UdpRpcResponse.error(request.id, error));
    }

    public <T extends Serializable> T request(InetSocketAddress remoteAddress, Object packet,
                                               Class<T> responseType) throws TimeoutException {
        return request(remoteAddress, packet, responseType, config.getRequestTimeoutMillis());
    }

    public <T extends Serializable> T request(InetSocketAddress remoteAddress, Object packet,
                                               Class<T> responseType, int timeoutMillis) throws TimeoutException {
        if (channel.eventLoop().inEventLoop()) {
            throw new InvalidException("KCP synchronous request cannot block EventLoop");
        }
        try {
            return requestAsync(remoteAddress, packet, responseType, timeoutMillis)
                    .get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw InvalidException.sneaky(e);
        } catch (TimeoutException e) {
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException) {
                throw (TimeoutException) cause;
            }
            throw InvalidException.sneaky(cause);
        }
    }

    public <T extends Serializable> CompletableFuture<T> requestAsync(InetSocketAddress remoteAddress, Object packet,
                                                                        Class<T> responseType) {
        return requestAsync(remoteAddress, packet, responseType, config.getRequestTimeoutMillis());
    }

    public <T extends Serializable> CompletableFuture<T> requestAsync(InetSocketAddress remoteAddress, Object packet,
                                                                        Class<T> responseType, int timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new InvalidException("KCP request timeout <= 0");
        }
        if (responseType == null) {
            throw new InvalidException("KCP responseType is null");
        }
        int requestId = nextMessageId();
        RpcRequestContext<T> request = new RpcRequestContext<>(responseType);
        if (pendingRequests.putIfAbsent(requestId, request) != null) {
            throw new InvalidException("Duplicate KCP request id {}", requestId);
        }
        request.timeoutFuture = channel.eventLoop().schedule(() -> {
            RpcRequestContext<?> removed = pendingRequests.remove(requestId);
            if (removed != null) {
                removed.future.completeExceptionally(
                        new TimeoutException(String.format("KCP rpc response timeout %s", remoteAddress)));
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
        request.future.whenComplete((r, e) -> request.cancelTimeout());

        ChannelFuture sendFuture = sendMessage(remoteAddress, packet, requestId);
        sendFuture.addListener(future -> {
            if (!future.isSuccess()) {
                RpcRequestContext<?> removed = pendingRequests.remove(requestId);
                if (removed != null) {
                    removed.future.completeExceptionally(future.cause());
                }
            }
        });
        return request.future;
    }

    public int encodedSize(Object packet) {
        ByteBuf payload = null;
        try {
            payload = codec.encode(channel.alloc(), packet);
            if (payload == null) {
                throw new InvalidException("KCP codec encode returned null");
            }
            return payload.readableBytes();
        } catch (Throwable e) {
            throw InvalidException.sneaky(e);
        } finally {
            ReferenceCountUtil.safeRelease(payload);
        }
    }

    int nextMessageId() {
        return messageIds.increment();
    }

    ChannelFuture sendMessage(InetSocketAddress remoteAddress, Object packet, int messageId) {
        ChannelPromise promise = channel.newPromise();
        if (closed.get()) {
            promise.setFailure(new ClientDisconnectedException(localEndpoint));
            return promise;
        }
        Runnable action = () -> {
            if (closed.get() || !channel.isActive()) {
                promise.tryFailure(new ClientDisconnectedException(localEndpoint));
                return;
            }
            ByteBuf frame = null;
            Throwable failure = null;
            try {
                KcpSession session = outboundSession(remoteAddress);
                frame = encodeLogicalFrame(packet, messageId);
                session.send(frame, System.currentTimeMillis());
            } catch (Throwable e) {
                failure = e;
            } finally {
                ReferenceCountUtil.safeRelease(frame);
            }
            if (failure == null) {
                promise.trySuccess();
            } else {
                promise.tryFailure(failure);
                onHandlerError(failure, remoteAddress);
            }
        };
        if (channel.eventLoop().inEventLoop()) {
            action.run();
        } else {
            channel.eventLoop().execute(action);
        }
        return promise;
    }

    ByteBuf encodeLogicalFrame(Object packet, int messageId) throws Exception {
        ByteBuf payload = null;
        ByteBuf header = null;
        CompositeByteBuf frame = null;
        try {
            payload = codec.encode(channel.alloc(), packet);
            if (payload == null) {
                throw new InvalidException("KCP codec encode returned null");
            }
            int payloadBytes = payload.readableBytes();
            if (payloadBytes > config.getMaxPayloadBytes()) {
                throw new InvalidException("KCP payload too large {} > {}", payloadBytes, config.getMaxPayloadBytes());
            }
            header = channel.alloc().ioBuffer(MESSAGE_HEADER_SIZE);
            header.writeShort(MESSAGE_MAGIC);
            header.writeByte(MESSAGE_VERSION);
            header.writeByte(MESSAGE_TYPE_DATA);
            header.writeInt(messageId);
            header.writeInt(payloadBytes);
            frame = channel.alloc().compositeBuffer(2);
            frame.addComponent(true, header);
            header = null;
            frame.addComponent(true, payload);
            payload = null;
            return frame;
        } catch (Throwable e) {
            ReferenceCountUtil.safeRelease(frame);
            ReferenceCountUtil.safeRelease(header);
            ReferenceCountUtil.safeRelease(payload);
            throw e;
        }
    }

    void handlePacket(DatagramPacket packet) {
        ByteBuf content = packet.content();
        InetSocketAddress sender = packet.sender();
        int readerIndex = content.readerIndex();
        int readableBytes = content.readableBytes();
        if (readableBytes < HEADER_SIZE || content.getInt(readerIndex) != MAGIC) {
            return;
        }
        byte version = content.getByte(readerIndex + 4);
        byte type = content.getByte(readerIndex + 5);
        InetSocketAddress remote = UdpResilienceAttributes.normalize(sender);
        if (authenticationMac == null) {
            if (version != VERSION) {
                return;
            }
            int conv = content.getInt(readerIndex + 8);
            SessionKey key = new SessionKey(remote, 0, conv);
            if (type == TYPE_CLOSE) {
                removeSession(key);
                return;
            }
            int payloadIndex = readerIndex + HEADER_SIZE;
            int payloadBytes = readableBytes - HEADER_SIZE;
            if (type != TYPE_KCP_DATA || payloadBytes < RxKcp.OVERHEAD
                    || content.getIntLE(payloadIndex) != conv) {
                return;
            }
            long now = System.currentTimeMillis();
            KcpSession session = sessions.get(key);
            boolean created = false;
            if (session == null) {
                if (sessions.size() >= config.getMaxSessions()) {
                    log.debug("KCP session limit reached remote={} limit={}", remote, config.getMaxSessions());
                    return;
                }
                session = new KcpSession(key, remote, false, now);
                KcpSession previous = sessions.putIfAbsent(key, session);
                if (previous != null) {
                    session.close();
                    session = previous;
                } else {
                    created = true;
                    outboundSessions.putIfAbsent(remote, session);
                    Sockets.addUdpPeer(channel, config, remote);
                }
            }
            try {
                session.input(content.slice(payloadIndex, payloadBytes), now);
            } catch (Throwable e) {
                if (created) {
                    removeSession(key);
                }
                onHandlerError(e, sender);
            }
            return;
        }

        byte flags = content.getByte(readerIndex + 6);
        if (version != AUTH_VERSION || readableBytes < AUTH_DATAGRAM_HEADER_SIZE
                || (flags & FLAG_AUTH) == 0 || (flags & ~(FLAG_AUTH | FLAG_INITIATOR)) != 0) {
            recordRejectedPacket("transport.kcp.auth.fail.count", "reason=protocol");
            return;
        }
        int conv = content.getInt(readerIndex + 8);
        long sessionId = content.getLong(readerIndex + 12);
        long sequence = content.getLong(readerIndex + 20);
        int payloadIndex = readerIndex + AUTH_DATAGRAM_HEADER_SIZE;
        int payloadBytes = readableBytes - AUTH_DATAGRAM_HEADER_SIZE;
        if (sessionId == 0 || sequence <= 0
                || !verifyAuthenticationTag(content, readerIndex, payloadIndex, payloadBytes)) {
            recordRejectedPacket("transport.kcp.auth.fail.count", "reason=bad-mac");
            return;
        }
        boolean fromInitiator = (flags & FLAG_INITIATOR) != 0;
        boolean invalidControl = (type == TYPE_OPEN || type == TYPE_OPEN_ACK || type == TYPE_CLOSE)
                && payloadBytes != 0;
        boolean invalidData = type == TYPE_KCP_DATA && (payloadBytes < RxKcp.OVERHEAD
                || content.getIntLE(payloadIndex) != conv);
        boolean invalidType = type != TYPE_OPEN && type != TYPE_OPEN_ACK
                && type != TYPE_CLOSE && type != TYPE_KCP_DATA;
        if (invalidControl || invalidData || invalidType) {
            recordRejectedPacket("transport.kcp.auth.fail.count", "reason=frame");
            return;
        }
        SessionKey key = new SessionKey(null, sessionId, conv);
        KcpSession session = sessions.get(key);
        boolean created = false;
        long now = System.currentTimeMillis();
        if (session == null) {
            if (type != TYPE_OPEN || !fromInitiator) {
                recordRejectedPacket("transport.kcp.auth.fail.count", "reason=data-before-open");
                return;
            }
            if (sessions.size() >= config.getMaxSessions()) {
                recordRejectedPacket("transport.kcp.auth.fail.count", "reason=max-sessions");
                return;
            }
            session = new KcpSession(key, remote, false, now);
            KcpSession previous = sessions.putIfAbsent(key, session);
            if (previous != null) {
                session.close();
                session = previous;
            } else {
                created = true;
                session.handshakeConfirmed = true;
                outboundSessions.putIfAbsent(remote, session);
                Sockets.addUdpPeer(channel, config, remote);
            }
        }
        if (fromInitiator == session.initiator) {
            recordRejectedPacket("transport.kcp.auth.fail.count", "reason=direction");
            if (created) {
                removeSession(key);
            }
            return;
        }
        boolean remoteChanged = !remote.equals(session.remoteAddress);
        if (remoteChanged && (!config.isAllowNatRebinding() || !session.isNewerSequence(sequence))) {
            recordRejectedPacket("transport.kcp.rebind.count",
                    config.isAllowNatRebinding() ? "result=reject,reason=stale" : "result=reject,reason=disabled");
            if (created) {
                removeSession(key);
            }
            return;
        }
        if (!session.acceptSequence(sequence)) {
            recordRejectedPacket("transport.kcp.auth.fail.count", "reason=replay");
            if (created) {
                removeSession(key);
            }
            return;
        }
        if (remoteChanged) {
            migrateSession(session, remote);
        }
        session.lastActiveMillis = now;
        if (type == TYPE_OPEN) {
            session.handshakeConfirmed = true;
            writeControlDatagram(session, TYPE_OPEN_ACK);
            return;
        }
        if (type == TYPE_OPEN_ACK) {
            session.handshakeConfirmed = true;
            return;
        }
        if (type == TYPE_CLOSE) {
            removeSession(key);
            return;
        }
        try {
            session.input(content.slice(payloadIndex, payloadBytes), now);
        } catch (Throwable e) {
            if (created) {
                removeSession(key);
            }
            onHandlerError(e, sender);
        }
    }

    void handleLogicalFrame(KcpSession session, ByteBuf frame) {
        if (frame.readableBytes() < MESSAGE_HEADER_SIZE) {
            onHandlerError(new InvalidException("KCP logical frame too small {}", frame.readableBytes()),
                    session.remoteAddress);
            return;
        }
        short magic = frame.readShort();
        byte version = frame.readByte();
        byte type = frame.readByte();
        int messageId = frame.readInt();
        int payloadBytes = frame.readInt();
        if (magic != MESSAGE_MAGIC || version != MESSAGE_VERSION || type != MESSAGE_TYPE_DATA
                || payloadBytes < 0 || payloadBytes > config.getMaxPayloadBytes()
                || payloadBytes != frame.readableBytes()) {
            onHandlerError(new InvalidException("Invalid KCP logical frame from {}", session.remoteAddress),
                    session.remoteAddress);
            return;
        }
        try {
            Object packet = codec.decode(frame.readSlice(payloadBytes));
            if (packet instanceof UdpRpcResponse) {
                completeRequest((UdpRpcResponse) packet);
                return;
            }
            session.deliver(new UdpMessage(messageId, AckSync.NONE, config.getSessionIdleTimeoutMillis(),
                    session.remoteAddress, packet));
        } catch (Throwable e) {
            onHandlerError(e, session.remoteAddress);
        }
    }

    void completeRequest(UdpRpcResponse response) {
        RpcRequestContext<?> request = pendingRequests.remove(response.requestId);
        if (request == null) {
            return;
        }
        request.cancelTimeout();
        if (response.error != null) {
            request.future.completeExceptionally(response.error);
            return;
        }
        Serializable value = response.value;
        if (value != null && !request.responseType.isInstance(value)) {
            request.future.completeExceptionally(new InvalidException("KCP rpc response type mismatch %s -> %s",
                    value.getClass().getName(), request.responseType.getName()));
            return;
        }
        @SuppressWarnings("unchecked")
        RpcRequestContext<Serializable> typed = (RpcRequestContext<Serializable>) request;
        typed.future.complete(value);
    }

    KcpSession outboundSession(InetSocketAddress remoteAddress) {
        if (remoteAddress == null) {
            throw new InvalidException("KCP remoteAddress is null");
        }
        InetSocketAddress remote = UdpResilienceAttributes.normalize(remoteAddress);
        KcpSession session = outboundSessions.get(remote);
        if (session != null && !session.closed) {
            return session;
        }
        if (sessions.size() >= config.getMaxSessions()) {
            throw new InvalidException("KCP session limit reached {}", config.getMaxSessions());
        }
        int conv = convIds.increment();
        long sessionId = 0;
        if (authenticationMac != null) {
            do {
                sessionId = ThreadLocalRandom.current().nextLong();
            } while (sessionId == 0);
        }
        SessionKey key = new SessionKey(remote, sessionId, conv);
        KcpSession created = new KcpSession(key, remote, true, System.currentTimeMillis());
        sessions.put(key, created);
        outboundSessions.put(remote, created);
        Sockets.addUdpPeer(channel, config, remote);
        return created;
    }

    void writeKcpDatagram(KcpSession session, ByteBuf kcpDatagram) {
        if (authenticationMac != null && !session.handshakeConfirmed) {
            writeControlDatagram(session, TYPE_OPEN);
        }
        writeDatagram(session, TYPE_KCP_DATA, kcpDatagram);
    }

    void writeControlDatagram(KcpSession session, byte type) {
        writeDatagram(session, type, null);
    }

    void writeDatagram(KcpSession session, byte type, ByteBuf kcpDatagram) {
        ByteBuf header = null;
        CompositeByteBuf content = null;
        try {
            boolean authenticated = authenticationMac != null;
            int headerSize = authenticated ? AUTH_DATAGRAM_HEADER_SIZE : HEADER_SIZE;
            header = channel.alloc().ioBuffer(headerSize, headerSize);
            header.writeInt(MAGIC);
            header.writeByte(authenticated ? AUTH_VERSION : VERSION);
            header.writeByte(type);
            header.writeByte(authenticated ? FLAG_AUTH | (session.initiator ? FLAG_INITIATOR : 0) : 0);
            header.writeByte(0);
            header.writeInt(session.key.conv);
            if (authenticated) {
                header.writeLong(session.key.sessionId);
                header.writeLong(++session.nextPacketSequence);
                header.writeZero(AUTH_TAG_SIZE);
                signDatagram(header, kcpDatagram);
            }
            ByteBuf datagram;
            if (kcpDatagram == null) {
                datagram = header;
                header = null;
            } else {
                content = channel.alloc().compositeBuffer(2);
                content.addComponents(true, header, kcpDatagram);
                header = null;
                kcpDatagram = null;
                datagram = content;
                content = null;
            }
            DatagramPacket packet = new DatagramPacket(datagram, session.remoteAddress);
            Sockets.UdpWriteResult result = Sockets.writeUdp(channel, packet, "transport.udp", "component=kcp");
            if (result != Sockets.UdpWriteResult.ACCEPTED) {
                log.debug("KCP datagram write rejected {} -> {}", session.remoteAddress, result);
            }
        } finally {
            ReferenceCountUtil.safeRelease(header);
            ReferenceCountUtil.safeRelease(content);
            ReferenceCountUtil.safeRelease(kcpDatagram);
        }
    }

    void signDatagram(ByteBuf header, ByteBuf payload) {
        try {
            updateAuthenticationMac(header, header.readerIndex(), AUTH_HEADER_SIZE);
            if (payload != null && payload.isReadable()) {
                updateAuthenticationMac(payload, payload.readerIndex(), payload.readableBytes());
            }
            authenticationMac.doFinal(authenticationTag, 0);
            header.setBytes(AUTH_HEADER_SIZE, authenticationTag, 0, AUTH_TAG_SIZE);
        } catch (Exception e) {
            throw InvalidException.sneaky(e);
        }
    }

    boolean verifyAuthenticationTag(ByteBuf content, int readerIndex, int payloadIndex, int payloadBytes) {
        try {
            updateAuthenticationMac(content, readerIndex, AUTH_HEADER_SIZE);
            if (payloadBytes > 0) {
                updateAuthenticationMac(content, payloadIndex, payloadBytes);
            }
            authenticationMac.doFinal(authenticationTag, 0);
            int diff = 0;
            int tagIndex = readerIndex + AUTH_HEADER_SIZE;
            for (int i = 0; i < AUTH_TAG_SIZE; i++) {
                diff |= authenticationTag[i] ^ content.getByte(tagIndex + i);
            }
            return diff == 0;
        } catch (Exception e) {
            throw InvalidException.sneaky(e);
        }
    }

    void updateAuthenticationMac(ByteBuf content, int index, int bytes) {
        if (bytes <= 0) {
            return;
        }
        if (content.nioBufferCount() == 1) {
            authenticationMac.update(content.internalNioBuffer(index, bytes));
            return;
        }
        ByteBuffer[] buffers = content.nioBuffers(index, bytes);
        for (ByteBuffer buffer : buffers) {
            authenticationMac.update(buffer);
        }
    }

    void onTick() {
        if (closed.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (KcpSession session : sessions.values()) {
            try {
                if (now - session.lastActiveMillis >= config.getSessionIdleTimeoutMillis()) {
                    removeSession(session.key);
                    continue;
                }
                session.update(now);
            } catch (Throwable e) {
                onHandlerError(e, session.remoteAddress);
                removeSession(session.key);
            }
        }
    }

    void migrateSession(KcpSession session, InetSocketAddress remoteAddress) {
        InetSocketAddress previous = session.remoteAddress;
        if (previous.equals(remoteAddress)) {
            return;
        }
        outboundSessions.remove(previous, session);
        session.remoteAddress = remoteAddress;
        outboundSessions.putIfAbsent(remoteAddress, session);
        Sockets.addUdpPeer(channel, config, remoteAddress);
        removePeerIfUnused(previous);
        DiagnosticMetrics.record("transport.kcp.rebind.count", 1D, "result=accepted");
    }

    void removeSession(SessionKey key) {
        KcpSession session = sessions.remove(key);
        if (session == null) {
            return;
        }
        InetSocketAddress remoteAddress = session.remoteAddress;
        outboundSessions.remove(remoteAddress, session);
        session.close();
        removePeerIfUnused(remoteAddress);
    }

    void removePeerIfUnused(InetSocketAddress remoteAddress) {
        for (KcpSession remaining : sessions.values()) {
            if (remoteAddress.equals(remaining.remoteAddress)) {
                return;
            }
        }
        Sockets.removeUdpPeer(channel, config, remoteAddress);
    }

    void recordRejectedPacket(String metric, String tags) {
        if (DiagnosticMetrics.isEnabled()
                && (++rejectedPacketMetricCounter & (REJECT_METRIC_SAMPLE_RATE - 1)) == 0) {
            DiagnosticMetrics.record(metric, REJECT_METRIC_SAMPLE_RATE, tags);
        }
    }

    void onHandlerError(Throwable error) {
        log.error("kcp client error {}", localEndpoint, error);
        quietly(() -> publishEvent(onError, new NEventArgs<>(error)));
    }

    void onHandlerError(Throwable error, InetSocketAddress sender) {
        log.error("kcp client error {} remote={}", localEndpoint, UdpClient.toIpPort(sender), error);
        quietly(() -> publishEvent(onError, new NEventArgs<>(error)));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Runnable cleanup = () -> {
            ScheduledFuture<?> tick = tickFuture;
            if (tick != null) {
                tick.cancel(false);
            }
            for (KcpSession session : sessions.values()) {
                session.close();
            }
            sessions.clear();
            outboundSessions.clear();
            for (RpcRequestContext<?> request : pendingRequests.values()) {
                request.cancelTimeout();
                request.future.completeExceptionally(new ClientDisconnectedException(localEndpoint));
            }
            pendingRequests.clear();
            for (Channel ch : channels) {
                if (ch.isOpen()) {
                    ch.close();
                }
            }
        };
        if (channel.eventLoop().inEventLoop()) {
            cleanup.run();
            return;
        }
        channel.eventLoop().submit(cleanup).syncUninterruptibly();
        for (Channel ch : channels) {
            ch.closeFuture().syncUninterruptibly();
        }
    }

    static KcpClientConfig configOf(UdpClientCodec codec) {
        KcpClientConfig config = new KcpClientConfig();
        config.setCodec(requireCodec(codec));
        return config;
    }

    static KcpClientConfig requireConfig(KcpClientConfig config) {
        if (config == null) {
            throw new InvalidException("KcpClientConfig is null");
        }
        return config;
    }

    static UdpClientCodec requireCodec(UdpClientCodec codec) {
        if (codec == null) {
            throw new InvalidException("UdpClientCodec is null");
        }
        return codec;
    }

    static void validateConfig(KcpClientConfig config) {
        if (config.getMtu() <= RxKcp.OVERHEAD || config.getIntervalMillis() <= 0
                || config.getSendWindow() <= 0 || config.getReceiveWindow() <= 0
                || config.getSessionIdleTimeoutMillis() <= 0 || config.getMaxSessions() <= 0
                || config.getRequestTimeoutMillis() <= 0
                || config.getMaxPayloadBytes() <= 0 || config.getMaxPendingBytesPerSession() <= 0
                || config.getMaxPendingMessagesPerSession() <= 0) {
            throw new InvalidException("Invalid KcpClientConfig");
        }
    }

    static int effectiveKcpMtu(KcpClientConfig config) {
        int mtu = config.getMtu();
        if (config.getUdpMtu() > 0) {
            byte[] authenticationKey = config.getAuthenticationKey();
            int headerSize = authenticationKey == null || authenticationKey.length == 0
                    ? HEADER_SIZE : AUTH_DATAGRAM_HEADER_SIZE;
            mtu = Math.min(mtu, config.getUdpMtu() - headerSize);
        }
        if (mtu <= RxKcp.OVERHEAD) {
            throw new InvalidException("KCP mtu leaves no payload bytes {}", mtu);
        }
        return mtu;
    }

    static Mac newAuthenticationMac(byte[] authenticationKey) {
        if (authenticationKey == null || authenticationKey.length == 0) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(AUTH_ALGORITHM);
            mac.init(new SecretKeySpec(authenticationKey, AUTH_ALGORITHM));
            return mac;
        } catch (Exception e) {
            throw InvalidException.sneaky(e);
        }
    }
}
