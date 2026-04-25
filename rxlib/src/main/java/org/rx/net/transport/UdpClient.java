package org.rx.net.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AttributeKey;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.*;
import org.rx.exception.InvalidException;
import org.rx.io.Serializer;
import org.rx.net.Sockets;
import org.rx.net.transport.protocol.AckSync;
import org.rx.net.transport.protocol.UdpMessage;
import org.rx.net.transport.protocol.UdpRpcResponse;
import org.rx.util.IdGenerator;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.rx.core.Extends.circuitContinue;
import static org.rx.core.Extends.quietly;

@Slf4j
public class UdpClient implements EventPublisher<UdpClient>, AutoCloseable {
    @RequiredArgsConstructor
    static final class ReceiveKey {
        final InetSocketAddress remoteAddress;
        final int messageId;

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ReceiveKey)) {
                return false;
            }
            ReceiveKey that = (ReceiveKey) obj;
            return messageId == that.messageId && Objects.equals(remoteAddress, that.remoteAddress);
        }

        @Override
        public int hashCode() {
            return 31 * remoteAddress.hashCode() + messageId;
        }
    }

    @RequiredArgsConstructor
    static final class SendContext {
        final UdpMessage message;
        final byte[] payload;
        final int fragmentCount;
        final CompletableFuture<Void> ackFuture = new CompletableFuture<>();
        volatile ChannelFuture writeFuture;
        volatile TimeoutFuture<?> resendFuture;
        volatile TimeoutFuture<?> timeoutFuture;
        volatile int resendCount;

        void cancelTimers() {
            TimeoutFuture<?> future = resendFuture;
            if (future != null) {
                future.cancel();
            }
            future = timeoutFuture;
            if (future != null) {
                future.cancel();
            }
        }
    }

    static final class ReceiveAssembly {
        final AckSync ack;
        final int alive;
        final byte[][] fragments;
        volatile TimeoutFuture<?> expireFuture;
        int receivedCount;

        ReceiveAssembly(AckSync ack, int alive, int fragmentCount) {
            this.ack = ack;
            this.alive = alive;
            this.fragments = new byte[fragmentCount][];
        }

        synchronized boolean add(int index, byte[] payload) {
            if (fragments[index] != null) {
                return false;
            }
            fragments[index] = payload;
            receivedCount++;
            return true;
        }

        synchronized boolean isComplete() {
            return receivedCount == fragments.length;
        }

        synchronized byte[] merge() {
            int length = 0;
            for (byte[] fragment : fragments) {
                length += fragment.length;
            }

            byte[] merged = new byte[length];
            int offset = 0;
            for (byte[] fragment : fragments) {
                System.arraycopy(fragment, 0, merged, offset, fragment.length);
                offset += fragment.length;
            }
            return merged;
        }
    }

    @RequiredArgsConstructor
    static final class RpcRequestContext<T extends Serializable> {
        final Class<T> responseType;
        final CompletableFuture<T> future = new CompletableFuture<>();
        volatile TimeoutFuture<?> timeoutFuture;

        void cancelTimeout() {
            TimeoutFuture<?> future = timeoutFuture;
            if (future != null) {
                future.cancel();
            }
        }
    }

    static final AttributeKey<UdpClient> OWNER = AttributeKey.valueOf("UdpClient");
    static final Handler HANDLER = new Handler();
    static final int MAGIC = 0x52585550;
    static final byte TYPE_DATA = 1;
    static final byte TYPE_ACK = 2;
    static final int ACK_HEADER_SIZE = 9;
    static final int DATA_HEADER_SIZE = 18;

    @ChannelHandler.Sharable
    static class Handler extends SimpleChannelInboundHandler<DatagramPacket> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
            UdpClient client = ctx.channel().attr(OWNER).get();
            if (client == null) {
                return;
            }
            client.handlePacket(msg);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            UdpClient client = ctx.channel().attr(OWNER).get();
            if (client == null) {
                return;
            }
            client.onHandlerError(cause);
        }
    }

    public final Delegate<UdpClient, NEventArgs<UdpMessage>> onReceive = Delegate.create();
    public final Delegate<UdpClient, NEventArgs<Throwable>> onError = Delegate.create();
    final Bootstrap bootstrap;
    @Getter
    final Channel channel;
    final IdGenerator generator = new IdGenerator();
    final ConcurrentMap<Integer, SendContext> pendingSends = new ConcurrentHashMap<>();
    final ConcurrentMap<ReceiveKey, ReceiveAssembly> pendingReceives = new ConcurrentHashMap<>();
    final Set<ReceiveKey> completedReceives = ConcurrentHashMap.newKeySet();
    final Set<ReceiveKey> inflightReceives = ConcurrentHashMap.newKeySet();
    final ConcurrentMap<Integer, RpcRequestContext<?>> pendingRequests = new ConcurrentHashMap<>();
    @Getter
    final InetSocketAddress localEndpoint;
    @Getter
    @Setter
    int waitAckTimeoutMillis = 15 * 1000;
    @Getter
    @Setter
    boolean fullSync;
    @Getter
    @Setter
    int maxResend = 2;
    @Getter
    @Setter
    int maxFragmentPayloadBytes = 1024;
    @Getter
    @Setter
    int maxFragmentCount = 128;

    public UdpClient(int bindPort) {
        bootstrap = Sockets.udpBootstrap(null, ch -> ch.pipeline().addLast(HANDLER));
        channel = bootstrap.bind(bindPort).syncUninterruptibly().channel();
        channel.attr(OWNER).set(this);
        localEndpoint = (InetSocketAddress) channel.localAddress();
    }

    public boolean isActive() {
        return channel.isActive();
    }

    public void reply(@NonNull UdpMessage request, @NonNull Serializable packet) {
        send(request.remoteAddress, UdpRpcResponse.success(request.id, packet));
    }

    public void replyError(@NonNull UdpMessage request, @NonNull Throwable error) {
        send(request.remoteAddress, UdpRpcResponse.error(request.id, error));
    }

    public <T extends Serializable> T request(InetSocketAddress remoteAddress, Object packet, Class<T> responseType) throws TimeoutException {
        return request(remoteAddress, packet, responseType, waitAckTimeoutMillis);
    }

    public <T extends Serializable> T request(InetSocketAddress remoteAddress, Object packet, Class<T> responseType, int timeoutMillis) throws TimeoutException {
        try {
            return requestAsync(remoteAddress, packet, responseType, timeoutMillis).get(timeoutMillis, TimeUnit.MILLISECONDS);
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

    public <T extends Serializable> CompletableFuture<T> requestAsync(InetSocketAddress remoteAddress, Object packet, Class<T> responseType) {
        return requestAsync(remoteAddress, packet, responseType, waitAckTimeoutMillis);
    }

    public <T extends Serializable> CompletableFuture<T> requestAsync(InetSocketAddress remoteAddress, Object packet, Class<T> responseType, int timeoutMillis) {
        ensureRequestTimeout(timeoutMillis);
        ensureSerializable(packet);

        int requestId = nextMessageId();
        RpcRequestContext<T> request = new RpcRequestContext<>(responseType);
        RpcRequestContext<?> old = pendingRequests.putIfAbsent(requestId, request);
        if (old != null) {
            throw new InvalidException("Duplicate udp request id {}", requestId);
        }

        request.timeoutFuture = Tasks.setTimeout(() -> {
            RpcRequestContext<?> ctx = pendingRequests.remove(requestId);
            if (ctx != null) {
                ctx.future.completeExceptionally(new TimeoutException(String.format("UDP rpc response timeout %s", remoteAddress)));
            }
        }, timeoutMillis);
        request.future.whenComplete((r, e) -> request.cancelTimeout());

        int transportTimeout = waitAckTimeoutMillis > 0 ? Math.min(waitAckTimeoutMillis, timeoutMillis) : timeoutMillis;
        SendContext sendContext;
        try {
            sendContext = beginSend(remoteAddress, packet, transportTimeout, false, requestId);
        } catch (Throwable e) {
            pendingRequests.remove(requestId, request);
            request.cancelTimeout();
            request.future.completeExceptionally(e);
            return request.future;
        }

        if (sendContext.message.ack != AckSync.NONE) {
            sendContext.ackFuture.whenComplete((r, e) -> {
                if (e != null) {
                    RpcRequestContext<?> ctx = pendingRequests.remove(requestId);
                    if (ctx != null) {
                        ctx.cancelTimeout();
                        ctx.future.completeExceptionally(e);
                    }
                }
            });
        }
        return request.future;
    }

    public ChannelFuture send(InetSocketAddress remoteAddress, Object packet) {
        return send(remoteAddress, packet, waitAckTimeoutMillis, fullSync);
    }

    public ChannelFuture send(InetSocketAddress remoteAddress, Object packet, int waitAckTimeoutMillis, boolean fullSync) {
        try {
            return beginSend(remoteAddress, packet, waitAckTimeoutMillis, fullSync, nextMessageId()).writeFuture;
        } catch (Throwable e) {
            return channel.newFailedFuture(e);
        }
    }

    public ChannelFuture sendAsync(InetSocketAddress remoteAddress, Object packet) throws TimeoutException {
        return sendAsync(remoteAddress, packet, waitAckTimeoutMillis, fullSync);
    }

    public ChannelFuture sendAsync(InetSocketAddress remoteAddress, Object packet, int waitAckTimeoutMillis, boolean fullSync) throws TimeoutException {
        SendContext context = beginSend(remoteAddress, packet, waitAckTimeoutMillis, fullSync, nextMessageId());
        if (context.message.ack == AckSync.NONE) {
            return context.writeFuture;
        }

        try {
            context.ackFuture.get(context.message.alive, TimeUnit.MILLISECONDS);
            return context.writeFuture;
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
        } finally {
            pendingSends.remove(context.message.id, context);
            context.cancelTimers();
        }
    }

    int nextMessageId() {
        return generator.increment();
    }

    SendContext beginSend(InetSocketAddress remoteAddress, Object packet, int waitAckTimeoutMillis, boolean fullSync, int messageId) {
        ensureSerializable(packet);
        ensureFragmentSettings();

        AckSync ack = fullSync ? AckSync.FULL : waitAckTimeoutMillis > 0 ? AckSync.SEMI : AckSync.NONE;
        int alive = waitAckTimeoutMillis > 0 ? waitAckTimeoutMillis : defaultAliveMillis();
        byte[] payload = Serializer.DEFAULT.serializeToBytes(packet);
        int fragmentCount = fragmentCount(payload.length);
        UdpMessage message = new UdpMessage(messageId, ack, alive, remoteAddress, packet);
        SendContext context = new SendContext(message, payload, fragmentCount);
        if (ack != AckSync.NONE) {
            SendContext old = pendingSends.putIfAbsent(messageId, context);
            if (old != null) {
                throw new InvalidException("Duplicate udp send id {}", messageId);
            }
            scheduleAckTimeout(context);
            scheduleResend(context);
        } else {
            context.ackFuture.complete(null);
        }

        try {
            context.writeFuture = writeFragments(context);
        } catch (Throwable e) {
            failSend(context, e);
            throw e;
        }
        return context;
    }

    void handlePacket(DatagramPacket packet) {
        InetSocketAddress sender = packet.sender();
        ByteBuf buf = packet.content();
        if (buf.readableBytes() < ACK_HEADER_SIZE) {
            log.warn("Discard too small udp packet {} bytes from {}", buf.readableBytes(), sender);
            return;
        }

        int magic = buf.getInt(buf.readerIndex());
        if (magic != MAGIC) {
            log.warn("Discard unknown udp packet magic {} from {}", Integer.toHexString(magic), sender);
            return;
        }

        int startIndex = buf.readerIndex();
        buf.readInt();
        byte type = buf.readByte();
        switch (type) {
            case TYPE_ACK:
                if (buf.readableBytes() < 4) {
                    log.warn("Discard invalid udp ack from {}", sender);
                    return;
                }
                handleAck(buf.readInt());
                return;
            case TYPE_DATA:
                if (buf.readableBytes() < DATA_HEADER_SIZE - 5) {
                    log.warn("Discard invalid udp data packet from {}", sender);
                    return;
                }
                int messageId = buf.readInt();
                AckSync ack = ackSync(buf.readByte());
                int alive = buf.readInt();
                int fragmentIndex = buf.readUnsignedShort();
                int fragmentCount = buf.readUnsignedShort();
                if (alive <= 0 || fragmentCount <= 0 || fragmentCount > maxFragmentCount || fragmentIndex >= fragmentCount) {
                    log.warn("Discard invalid udp fragment {}#{}/{} from {}", messageId, fragmentIndex, fragmentCount, sender);
                    return;
                }
                byte[] payload = new byte[buf.readableBytes()];
                buf.readBytes(payload);
                handleData(sender, messageId, ack, alive, fragmentIndex, fragmentCount, payload);
                return;
            default:
                log.warn("Discard unknown udp packet type {} from {}", type, sender);
                buf.readerIndex(startIndex);
        }
    }

    void handleAck(int messageId) {
        SendContext context = pendingSends.remove(messageId);
        if (context == null) {
            return;
        }
        context.cancelTimers();
        context.ackFuture.complete(null);
        log.debug("Receive udp ack {}", messageId);
    }

    void handleData(InetSocketAddress sender, int messageId, AckSync ack, int alive, int fragmentIndex, int fragmentCount, byte[] payload) {
        ReceiveKey key = new ReceiveKey(sender, messageId);
        if (completedReceives.contains(key)) {
            if (ack != AckSync.NONE) {
                sendAck(sender, messageId);
            }
            return;
        }
        if (inflightReceives.contains(key)) {
            return;
        }

        ReceiveAssembly assembly = pendingReceives.compute(key, (k, old) -> {
            if (old == null || old.fragments.length != fragmentCount || old.ack != ack) {
                if (old != null && old.expireFuture != null) {
                    old.expireFuture.cancel();
                }
                ReceiveAssembly created = new ReceiveAssembly(ack, alive, fragmentCount);
                created.expireFuture = Tasks.setTimeout(() -> pendingReceives.remove(k, created), alive);
                return created;
            }
            return old;
        });
        assembly.add(fragmentIndex, payload);
        if (!assembly.isComplete()) {
            return;
        }
        if (!pendingReceives.remove(key, assembly)) {
            return;
        }
        if (assembly.expireFuture != null) {
            assembly.expireFuture.cancel();
        }

        Object pack = Serializer.DEFAULT.deserializeFromBytes(assembly.merge());
        handleLogicalMessage(sender, key, new UdpMessage(messageId, ack, alive, sender, pack));
    }

    void handleLogicalMessage(InetSocketAddress sender, ReceiveKey key, UdpMessage message) {
        if (message.packet instanceof UdpRpcResponse) {
            completeRequest((UdpRpcResponse) message.packet);
            if (message.ack != AckSync.NONE) {
                markCompleted(key, message.alive);
                sendAck(sender, message.id);
            }
            return;
        }

        if (message.ack == AckSync.SEMI) {
            markCompleted(key, message.alive);
            sendAck(sender, message.id);
        } else if (message.ack == AckSync.FULL && !inflightReceives.add(key)) {
            return;
        }

        raiseEventAsync(onReceive, new NEventArgs<>(message)).whenComplete((r, e) -> {
            if (e == null) {
                onReceiveSuccess(sender, key, message);
                return;
            }
            onReceiveFailure(key, e);
        });
    }

    void onReceiveSuccess(InetSocketAddress sender, ReceiveKey key, UdpMessage message) {
        if (message.ack != AckSync.FULL) {
            return;
        }
        inflightReceives.remove(key);
        markCompleted(key, message.alive);
        sendAck(sender, message.id);
    }

    void onReceiveFailure(ReceiveKey key, Throwable error) {
        inflightReceives.remove(key);
        onHandlerError(error);
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
            request.future.completeExceptionally(new InvalidException("UDP rpc response type mismatch %s -> %s",
                    value.getClass().getName(), request.responseType.getName()));
            return;
        }

        @SuppressWarnings("unchecked")
        RpcRequestContext<Serializable> typed = (RpcRequestContext<Serializable>) request;
        typed.future.complete(value);
    }

    void markCompleted(ReceiveKey key, int alive) {
        if (!completedReceives.add(key)) {
            return;
        }
        Tasks.setTimeout(() -> completedReceives.remove(key), alive);
    }

    void scheduleAckTimeout(SendContext context) {
        context.timeoutFuture = Tasks.setTimeout(() -> {
            if (!pendingSends.remove(context.message.id, context)) {
                return;
            }
            TimeoutException ex = new TimeoutException(String.format("UDP ack timeout %s", context.message.remoteAddress));
            context.cancelTimers();
            context.ackFuture.completeExceptionally(ex);
        }, context.message.alive);
    }

    void scheduleResend(SendContext context) {
        if (maxResend <= 0 || context.message.alive <= 1) {
            return;
        }
        final long interval = Math.max(1L, context.message.alive / (long) (maxResend + 1));
        context.resendFuture = Tasks.setTimeout(() -> {
            if (context.ackFuture.isDone()) {
                circuitContinue(false);
                return;
            }
            if (context.resendCount >= maxResend) {
                circuitContinue(false);
                return;
            }
            try {
                context.resendCount++;
                context.writeFuture = writeFragments(context);
                log.debug("Resend udp message {} -> {} count={}", context.message.id, context.message.remoteAddress, context.resendCount);
            } catch (Throwable e) {
                failSend(context, e);
                circuitContinue(false);
            }
        }, interval, context, Constants.TIMER_PERIOD_FLAG);
    }

    void failSend(SendContext context, Throwable error) {
        pendingSends.remove(context.message.id, context);
        context.cancelTimers();
        context.ackFuture.completeExceptionally(error);
        onHandlerError(error);
    }

    ChannelFuture writeFragments(SendContext context) {
        ChannelPromise promise = channel.newPromise();
        try {
            int offset = 0;
            int payloadLength = context.payload.length;
            for (int i = 0; i < context.fragmentCount; i++) {
                int length = Math.min(maxFragmentPayloadBytes, payloadLength - offset);
                if (context.fragmentCount == 1 && payloadLength == 0) {
                    length = 0;
                }
                DatagramPacket packet = encodeData(context.message, i, context.fragmentCount, context.payload, offset, length);
                Sockets.UdpWriteResult result = Sockets.writeUdp(channel, packet, "transport.udp", "component=rpc");
                if (result != Sockets.UdpWriteResult.ACCEPTED) {
                    throw new InvalidException("UDP write {} -> {} {}", context.message.remoteAddress, result, context.message.id);
                }
                offset += length;
            }
            promise.setSuccess();
        } catch (Throwable e) {
            promise.setFailure(e);
            throw e;
        }
        return promise;
    }

    DatagramPacket encodeData(UdpMessage message, int fragmentIndex, int fragmentCount, byte[] payload, int offset, int length) {
        ByteBuf buf = channel.alloc().ioBuffer(DATA_HEADER_SIZE + length);
        buf.writeInt(MAGIC);
        buf.writeByte(TYPE_DATA);
        buf.writeInt(message.id);
        buf.writeByte(message.ack.ordinal());
        buf.writeInt(message.alive);
        buf.writeShort(fragmentIndex);
        buf.writeShort(fragmentCount);
        if (length > 0) {
            buf.writeBytes(payload, offset, length);
        }
        return new DatagramPacket(buf, message.remoteAddress);
    }

    void sendAck(InetSocketAddress remoteAddress, int messageId) {
        ByteBuf buf = channel.alloc().ioBuffer(ACK_HEADER_SIZE);
        buf.writeInt(MAGIC);
        buf.writeByte(TYPE_ACK);
        buf.writeInt(messageId);

        DatagramPacket packet = new DatagramPacket(buf, remoteAddress);
        Sockets.UdpWriteResult result = Sockets.writeUdp(channel, packet, "transport.udp", "component=rpc");
        if (result != Sockets.UdpWriteResult.ACCEPTED) {
            log.warn("UDP ack write {} -> {} {}", localEndpoint, remoteAddress, result);
        }
    }

    AckSync ackSync(byte value) {
        AckSync[] values = AckSync.values();
        if (value < 0 || value >= values.length) {
            return AckSync.NONE;
        }
        return values[value];
    }

    int fragmentCount(int payloadLength) {
        if (payloadLength == 0) {
            return 1;
        }
        int count = payloadLength / maxFragmentPayloadBytes;
        if (payloadLength % maxFragmentPayloadBytes != 0) {
            count++;
        }
        if (count > maxFragmentCount) {
            throw new InvalidException("UDP payload too large {} fragments > {}", count, maxFragmentCount);
        }
        return count;
    }

    void ensureSerializable(Object packet) {
        if (!(packet instanceof Serializable)) {
            throw new InvalidException("UDP packet must be Serializable");
        }
    }

    void ensureFragmentSettings() {
        if (maxFragmentPayloadBytes <= 0) {
            throw new InvalidException("maxFragmentPayloadBytes <= 0");
        }
        if (maxFragmentCount <= 0) {
            throw new InvalidException("maxFragmentCount <= 0");
        }
    }

    void ensureRequestTimeout(int timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new InvalidException("request timeout <= 0");
        }
    }

    int defaultAliveMillis() {
        return waitAckTimeoutMillis > 0 ? waitAckTimeoutMillis : 15 * 1000;
    }

    void onHandlerError(Throwable error) {
        log.error("udp client error {}", localEndpoint, error);
        quietly(() -> raiseEvent(onError, new NEventArgs<>(error)));
    }

    @Override
    public void close() {
        for (SendContext context : pendingSends.values()) {
            context.cancelTimers();
            context.ackFuture.completeExceptionally(new ClientDisconnectedException(localEndpoint));
        }
        pendingSends.clear();

        for (RpcRequestContext<?> request : pendingRequests.values()) {
            request.cancelTimeout();
            request.future.completeExceptionally(new ClientDisconnectedException(localEndpoint));
        }
        pendingRequests.clear();

        for (ReceiveAssembly assembly : pendingReceives.values()) {
            if (assembly.expireFuture != null) {
                assembly.expireFuture.cancel();
            }
        }
        pendingReceives.clear();
        completedReceives.clear();
        inflightReceives.clear();

        if (channel.isOpen()) {
            channel.close().syncUninterruptibly();
        }
    }
}
