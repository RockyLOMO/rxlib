package org.rx.net.transport.core;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DuplexChannel;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.NtpClock;
import org.rx.net.Sockets;
import org.rx.net.rss.tcp.AdaptiveTcpHandler;
import org.rx.net.transport.ClientDisconnectedException;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

@Slf4j
final class DefaultTransportSession implements TransportSession {
    private final long sessionId;
    private final String localPeerId;
    private final String remotePeerId;
    private final Channel channel;
    private final InetSocketAddress localEndpoint;
    private final InetSocketAddress remoteEndpoint;
    private final int maxFrameBytes;
    private final boolean adaptiveNegotiated;
    private final AdaptiveTcpHandler adaptiveHandler;
    private final Map<Object, Object> attrs = new ConcurrentHashMap<Object, Object>();

    private volatile boolean connected = true;
    private volatile long lastHeartbeatMillis;
    private volatile long heartbeatRttMillis = -1L;
    private volatile BiConsumer<TransportSession, ByteBuf> receiveHandler;

    DefaultTransportSession(long sessionId, String localPeerId, String remotePeerId, Channel channel,
                            int maxFrameBytes, boolean adaptiveNegotiated, AdaptiveTcpHandler adaptiveHandler) {
        this.sessionId = sessionId;
        this.localPeerId = localPeerId;
        this.remotePeerId = remotePeerId;
        this.channel = channel;
        this.localEndpoint = channel.localAddress() instanceof InetSocketAddress ? (InetSocketAddress) channel.localAddress() : null;
        this.remoteEndpoint = channel.remoteAddress() instanceof InetSocketAddress ? (InetSocketAddress) channel.remoteAddress() : null;
        this.maxFrameBytes = maxFrameBytes;
        this.adaptiveNegotiated = adaptiveNegotiated;
        this.adaptiveHandler = adaptiveHandler;
    }

    @Override
    public long sessionId() {
        return sessionId;
    }

    @Override
    public String localPeerId() {
        return localPeerId;
    }

    @Override
    public String remotePeerId() {
        return remotePeerId;
    }

    @Override
    public boolean isConnected() {
        return connected && channel.isActive();
    }

    @Override
    public boolean isWritable() {
        return isConnected() && channel.isWritable();
    }

    @Override
    public boolean isUsingUdp() {
        return adaptiveHandler != null && adaptiveHandler.usingUdp();
    }

    @Override
    public boolean isAdaptiveUdpNegotiated() {
        return adaptiveNegotiated;
    }

    @Override
    public InetSocketAddress remoteEndpoint() {
        return remoteEndpoint;
    }

    @Override
    public InetSocketAddress localEndpoint() {
        return localEndpoint;
    }

    @Override
    public long lastHeartbeatMillis() {
        return lastHeartbeatMillis;
    }

    @Override
    public long heartbeatRttMillis() {
        return heartbeatRttMillis;
    }

    @Override
    public Channel channel() {
        return channel;
    }

    @Override
    public void send(ByteBuf payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload is null");
        }
        if (!isConnected()) {
            payload.release();
            throw new ClientDisconnectedException(remoteEndpoint);
        }
        int payloadBytes = payload.readableBytes();
        if (payloadBytes > maxFrameBytes) {
            payload.release();
            throw new IllegalArgumentException("Transport frame exceeds maxFrameBytes: " + payloadBytes + " > " + maxFrameBytes);
        }
        writeFrame(TransportProtocol.FRAME_DATA, payload, channel.voidPromise());
    }

    @Override
    public ChannelFuture sendAsync(ByteBuf payload) {
        if (payload == null) {
            return channel.newFailedFuture(new IllegalArgumentException("payload is null"));
        }
        if (!isConnected()) {
            payload.release();
            return channel.newFailedFuture(new ClientDisconnectedException(remoteEndpoint));
        }
        int payloadBytes = payload.readableBytes();
        if (payloadBytes > maxFrameBytes) {
            payload.release();
            return channel.newFailedFuture(new IllegalArgumentException(
                    "Transport frame exceeds maxFrameBytes: " + payloadBytes + " > " + maxFrameBytes));
        }
        ChannelPromise promise = channel.newPromise();
        writeFrame(TransportProtocol.FRAME_DATA, payload, promise);
        return promise;
    }

    void sendControlFrame(byte frameType, long value, boolean hasLong) {
        if (!channel.isActive()) {
            return;
        }
        Runnable task = () -> {
            if (!channel.isActive()) {
                return;
            }
            int bodyLen = hasLong ? 9 : 1;
            ByteBuf frame = channel.alloc().ioBuffer(4 + bodyLen, 4 + bodyLen);
            frame.writeInt(bodyLen);
            frame.writeByte(frameType);
            if (hasLong) {
                frame.writeLong(value);
            }
            channel.writeAndFlush(frame, channel.voidPromise());
        };
        if (channel.eventLoop().inEventLoop()) {
            task.run();
        } else {
            channel.eventLoop().execute(task);
        }
    }

    void sendPing() {
        sendControlFrame(TransportProtocol.FRAME_PING, NtpClock.UTC.millis(), true);
    }

    void handlePing(long timestamp) {
        long now = NtpClock.UTC.millis();
        lastHeartbeatMillis = now;
        long rtt = (now - timestamp) << 1;
        heartbeatRttMillis = rtt < 0 ? 0 : rtt;
        sendControlFrame(TransportProtocol.FRAME_PONG, timestamp, true);
    }

    void handlePong(long timestamp) {
        long now = NtpClock.UTC.millis();
        lastHeartbeatMillis = now;
        long rtt = now - timestamp;
        heartbeatRttMillis = rtt < 0 ? 0 : rtt;
    }

    private void writeFrame(byte frameType, ByteBuf payload, ChannelPromise promise) {
        Runnable task = () -> {
            if (!channel.isActive()) {
                payload.release();
                if (!promise.isVoid()) {
                    promise.tryFailure(new ClientDisconnectedException(remoteEndpoint));
                }
                return;
            }
            int bodyLen = 1 + payload.readableBytes();
            ByteBuf header = channel.alloc().ioBuffer(5, 5);
            header.writeInt(bodyLen);
            header.writeByte(frameType);
            channel.write(header, channel.voidPromise());
            channel.writeAndFlush(payload, promise);
        };
        if (channel.eventLoop().inEventLoop()) {
            task.run();
        } else {
            try {
                channel.eventLoop().execute(task);
            } catch (RuntimeException rejected) {
                payload.release();
                if (!promise.isVoid()) {
                    promise.tryFailure(rejected);
                }
            }
        }
    }

    @Override
    public ChannelFuture shutdownOutput() {
        if (channel instanceof DuplexChannel) {
            return ((DuplexChannel) channel).shutdownOutput();
        }
        return channel.close();
    }

    @Override
    public void setReceiveHandler(BiConsumer<TransportSession, ByteBuf> handler) {
        this.receiveHandler = handler;
    }

    void dispatchData(ByteBuf payload, BiConsumer<TransportSession, ByteBuf> fallbackHandler) {
        try {
            BiConsumer<TransportSession, ByteBuf> handler = this.receiveHandler;
            if (handler != null) {
                handler.accept(this, payload);
            } else if (fallbackHandler != null) {
                fallbackHandler.accept(this, payload);
            }
        } finally {
            ReferenceCountUtil.safeRelease(payload);
        }
    }

    void markDisconnected() {
        connected = false;
    }

    @Override
    public <T> boolean hasAttr(AttributeKey<T> key) {
        return key != null && attrs.containsKey(key.name());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T attr(AttributeKey<T> key) {
        return key == null ? null : (T) attrs.get(key.name());
    }

    @Override
    public <T> void attr(AttributeKey<T> key, T val) {
        if (key == null) {
            return;
        }
        if (val == null) {
            attrs.remove(key.name());
        } else {
            attrs.put(key.name(), val);
        }
    }

    @Override
    public void close() {
        connected = false;
        Sockets.closeOnFlushed(channel);
    }
}
