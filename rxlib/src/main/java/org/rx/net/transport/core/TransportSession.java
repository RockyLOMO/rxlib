package org.rx.net.transport.core;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.AttributeKey;
import org.rx.net.EgressCodec;
import org.rx.net.rss.tcp.AdaptiveTcpHandler;

import java.net.InetSocketAddress;
import java.util.function.BiConsumer;

/**
 * Reliable ordered {@link ByteBuf} frame session over TCP (or virtual LocalChannel carrier)
 * with optional post-handshake AEAD encryption, business compression, and single-port daily adaptive UDP fast path.
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

    /** True if per-session AES-128-GCM AEAD encryption is active on this session. */
    default boolean isEncrypted() {
        return false;
    }

    /** True if post-handshake business zlib compression is active on this session. */
    default boolean isCompressed() {
        return false;
    }

    /** Returns the negotiated physical TCP {@link EgressCodec} for this session. */
    default EgressCodec egressCodec() {
        return EgressCodec.NONE;
    }

    /** Returns the installed {@link AdaptiveTcpHandler}, or {@code null} in pure TCP mode. */
    default AdaptiveTcpHandler adaptiveHandler() {
        return null;
    }

    /** Returns the derived 32-byte per-session adaptive UDP HMAC key (when adaptive mode is negotiated), or {@code null}. */
    default byte[] adaptiveUdpKey() {
        return null;
    }

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
