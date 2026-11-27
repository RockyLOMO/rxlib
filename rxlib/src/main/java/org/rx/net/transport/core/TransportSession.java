package org.rx.net.transport.core;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.AttributeKey;

import java.net.InetSocketAddress;
import java.util.function.BiConsumer;

/**
 * Reliable ordered {@link ByteBuf} frame session over TCP with optional single-port daily adaptive UDP fast path.
 * <p>
 * Ownership contract:
 * <ul>
 *     <li>{@link #send(ByteBuf)} and {@link #sendAsync(ByteBuf)} ALWAYS consume and release {@code payload} on all paths.</li>
 *     <li>Receive listeners borrow {@code payload} for the duration of the callback; retain it if handed off asynchronously.</li>
 * </ul>
 */
public interface TransportSession extends AutoCloseable {
    long sessionId();

    String localPeerId();

    String remotePeerId();

    boolean isConnected();

    boolean isWritable();

    /** True only while the single-port daily adaptive UDP fast path is active for this session. */
    boolean isUsingUdp();

    /** True if the session negotiated adaptive UDP support during handshake. */
    boolean isAdaptiveUdpNegotiated();

    InetSocketAddress remoteEndpoint();

    InetSocketAddress localEndpoint();

    long lastHeartbeatMillis();

    long heartbeatRttMillis();

    Channel channel();

    void send(ByteBuf payload);

    ChannelFuture sendAsync(ByteBuf payload);

    ChannelFuture shutdownOutput();

    void setReceiveHandler(BiConsumer<TransportSession, ByteBuf> handler);

    <T> boolean hasAttr(AttributeKey<T> key);

    default boolean hasAttr(String name) {
        return hasAttr(AttributeKey.valueOf(name));
    }

    <T> T attr(AttributeKey<T> key);

    default <T> T attr(String name) {
        return attr(AttributeKey.valueOf(name));
    }

    <T> void attr(AttributeKey<T> key, T val);

    default <T> void attr(String name, T val) {
        attr(AttributeKey.valueOf(name), val);
    }

    @Override
    void close();
}
