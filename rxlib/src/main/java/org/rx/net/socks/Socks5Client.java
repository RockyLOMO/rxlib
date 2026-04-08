package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Delegate;
import org.rx.core.Disposable;
import org.rx.core.EventPublisher;
import org.rx.core.NEventArgs;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.util.function.BiAction;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

/**
 * SOCKS5 client that supports both CONNECT (TCP proxy) and UDP_ASSOCIATE (UDP relay) commands.
 *
 * <p>Usage – CONNECT:
 * <pre>{@code
 * Socks5Client client = new Socks5Client(AuthenticEndpoint.valueOf("user:pass@proxy:1080"));
 * client.connect(new UnresolvedEndpoint("example.com", 80))
 *       .addListener(f -> {
 *           Channel ch = (Channel) ((Future<?>) f).getNow();
 *           ch.writeAndFlush(Unpooled.copiedBuffer("GET / HTTP/1.0\r\n\r\n", CharsetUtil.UTF_8));
 *       });
 * }</pre>
 *
 * <p>Usage – UDP_ASSOCIATE:
 * <pre>{@code
 * client.udpAssociateAsync().thenAccept(session -> {
 *     session.onReceive.combine((s, e) -> {
 *         DatagramPacket pkt = e.getValue();
 *         // pkt.sender() is the decoded SOCKS5 source; pkt.content() is the payload (must be released)
 *     });
 *     session.send(new UnresolvedEndpoint("8.8.8.8", 53), dnsQueryBuf);
 * });
 * }</pre>
 */
@Slf4j
public class Socks5Client extends Disposable {

    // -------------------------------------------------------------------------
    // Socks5UdpSession
    // -------------------------------------------------------------------------

    /**
     * Active UDP relay session returned by {@link #udpAssociateAsync()}.
     * Wraps a TCP control channel (kept alive for the proxy to maintain the relay)
     * and a local UDP channel that sends/receives datagrams through the SOCKS5 relay.
     *
     * <p>The two channels share a lifecycle: closing either one closes the other.
     * Call {@link #close()} to tear down both.
     */
    public static class Socks5UdpSession extends Disposable implements EventPublisher<Socks5UdpSession> {

        static final AttributeKey<Socks5UdpSession> ATTR = AttributeKey.valueOf("socks5UdpSession");

        final Channel tcpControl;
        final Channel udpRelay;
        /** The address of the SOCKS5 proxy's UDP relay endpoint (resolved from the proxy response). */
        @Getter
        final InetSocketAddress relayAddress;

        /**
         * Fired (asynchronously) whenever a UDP datagram is received from the relay.
         * The {@link DatagramPacket} value has:
         * <ul>
         *   <li>{@code sender()} – the decoded SOCKS5 source address (original sender)</li>
         *   <li>{@code content()} – the application payload (caller must {@code release()} it)</li>
         * </ul>
         */
        public final Delegate<Socks5UdpSession, NEventArgs<DatagramPacket>> onReceive = Delegate.create();

        Socks5UdpSession(Channel tcpControl, Channel udpRelay, InetSocketAddress relayAddress) {
            this.tcpControl = tcpControl;
            this.udpRelay = udpRelay;
            this.relayAddress = relayAddress;
        }

        /**
         * Sends a UDP datagram to {@code destination} via the SOCKS5 relay.
         * A SOCKS5 UDP header is prepended automatically. The {@code payload} reference
         * count is transferred to the datagram; do not use it after this call.
         */
        public ChannelFuture send(@NonNull UnresolvedEndpoint destination, @NonNull ByteBuf payload) {
            CompositeByteBuf packet = UdpManager.socks5Encode(payload, destination);
            return udpRelay.writeAndFlush(new DatagramPacket(packet, relayAddress));
        }

        @Override
        protected void dispose() {
            Sockets.closeOnFlushed(tcpControl);
            Sockets.closeOnFlushed(udpRelay);
        }
    }

    // -------------------------------------------------------------------------
    // UdpResponseHandler – sharable inbound handler for the local UDP channel
    // -------------------------------------------------------------------------

    @ChannelHandler.Sharable
    static class UdpResponseHandler extends SimpleChannelInboundHandler<DatagramPacket> {

        static final UdpResponseHandler DEFAULT = new UdpResponseHandler();

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
            Socks5UdpSession session = ctx.channel().attr(Socks5UdpSession.ATTR).get();
            if (session == null || session.onReceive.isEmpty()) {
                return;
            }
            // Retain so the ByteBuf survives the async dispatch (SimpleChannelInboundHandler
            // releases msg after channelRead0 returns).
            ByteBuf content = msg.content().retain();
            try {
                UnresolvedEndpoint src = UdpManager.socks5Decode(content);
                // After socks5Decode the readerIndex points at the application payload.
                DatagramPacket decoded = new DatagramPacket(
                        content.retain(),  // caller must release
                        msg.recipient(),
                        new InetSocketAddress(src.getHost(), src.getPort()));
                session.raiseEventAsync(session.onReceive, new NEventArgs<>(decoded))
                        .whenComplete((r, e) -> decoded.release());
            } finally {
                content.release();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("socks5 UDP response error", cause);
        }
    }

    // -------------------------------------------------------------------------
    // Socks5Client
    // -------------------------------------------------------------------------

    private final AuthenticEndpoint proxyServer;
    private final SocketConfig config;

    public Socks5Client(@NonNull AuthenticEndpoint proxyServer) {
        this(proxyServer, null);
    }

    public Socks5Client(@NonNull AuthenticEndpoint proxyServer, SocketConfig config) {
        this.proxyServer = proxyServer;
        this.config = config != null ? config : new SocketConfig();
    }

    /**
     * Establishes a TCP tunnel through the SOCKS5 proxy using the CONNECT command.
     *
     * @param destination the host/port to connect to through the proxy
     * @return a future that completes (with the ready {@link Channel}) after the SOCKS5 handshake
     */
    public Future<Channel> connect(@NonNull UnresolvedEndpoint destination) {
        return connect(destination, null);
    }

    /**
     * Establishes a TCP tunnel through the SOCKS5 proxy.
     * {@code initChannel} is invoked on the channel before the proxy handshake begins,
     * allowing additional handlers to be appended to the pipeline.
     *
     * @param destination  the host/port to tunnel to
     * @param initChannel  optional extra pipeline setup (may be {@code null})
     * @return a future that completes with the ready {@link Channel} after the SOCKS5 handshake
     */
    public Future<Channel> connect(@NonNull UnresolvedEndpoint destination, BiAction<Channel> initChannel) {
        checkNotClosed();
        Socks5ClientHandler handler = createHandler(Socks5CommandType.CONNECT);
        Sockets.bootstrap(config, ch -> {
            ch.pipeline().addLast(handler);
            if (initChannel != null) {
                initChannel.accept(ch);
            }
        }).connect(destination.socketAddress());
        return handler.connectFuture();
    }

    /**
     * Sends a UDP_ASSOCIATE request to the SOCKS5 proxy and returns a session object
     * that can send/receive UDP datagrams through the relay.
     *
     * <p>The returned future completes once the proxy has acknowledged the association
     * and the local UDP channel is bound.  The caller should set up
     * {@link Socks5UdpSession#onReceive} listeners before or immediately after receiving
     * the session.
     *
     * @return a future resolving to a live {@link Socks5UdpSession}
     */
    public CompletableFuture<Socks5UdpSession> udpAssociateAsync() {
        checkNotClosed();
        CompletableFuture<Socks5UdpSession> result = new CompletableFuture<>();
        Socks5ClientHandler handler = createHandler(Socks5CommandType.UDP_ASSOCIATE);

        // Set callback BEFORE connect() so there is no race with the handshake.
        handler.setHandshakeCallback(() -> {
            try {
                // connectFuture() is already done by the time this callback fires.
                Channel tcpControl = (Channel) handler.connectFuture().getNow();
                InetSocketAddress bindAddr = handler.getBindAddress();
                InetSocketAddress relayAddr = resolveRelayAddress(bindAddr);

                log.debug("socks5 UDP_ASSOCIATE relay address: {}", relayAddr);

                // Bind a local UDP channel for the application to send/receive through.
                Sockets.udpBootstrap(config, udpCh ->
                        udpCh.pipeline().addLast(UdpResponseHandler.DEFAULT)
                ).bind(0).addListener((ChannelFutureListener) f -> {
                    if (!f.isSuccess()) {
                        log.warn("socks5 UDP relay bind failed", f.cause());
                        result.completeExceptionally(f.cause());
                        tcpControl.close();
                        return;
                    }
                    Channel udpRelay = f.channel();
                    Socks5UdpSession session = new Socks5UdpSession(tcpControl, udpRelay, relayAddr);
                    udpRelay.attr(Socks5UdpSession.ATTR).set(session);

                    // Tie lifecycle: either channel closing closes the other.
                    tcpControl.closeFuture().addListener(x -> {
                        if (udpRelay.isOpen()) {
                            udpRelay.close();
                        }
                    });
                    udpRelay.closeFuture().addListener(x -> {
                        if (tcpControl.isOpen()) {
                            tcpControl.close();
                        }
                    });

                    result.complete(session);
                });
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });

        // For UDP_ASSOCIATE the actual SOCKS5 command destination is 0.0.0.0:0 (set inside
        // sendCommand()).  We pass the proxy address as the bootstrap "destination" so
        // ProxyHandler connects to the right server; the destination value is ignored by
        // sendCommand() when commandType == UDP_ASSOCIATE.
        Sockets.bootstrap(config, ch -> ch.pipeline().addLast(handler))
                .connect(proxyServer.getEndpoint())
                .addListener((ChannelFutureListener) f -> {
                    if (!f.isSuccess()) {
                        result.completeExceptionally(f.cause());
                    }
                });

        return result;
    }

    @Override
    protected void dispose() {
        // The client itself holds no channels; each connect()/udpAssociateAsync() call
        // creates independent channels that are managed by their futures / sessions.
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Socks5ClientHandler createHandler(Socks5CommandType commandType) {
        return new Socks5ClientHandler(
                proxyServer.getEndpoint(),
                proxyServer.getUsername(),
                proxyServer.getPassword(),
                commandType);
    }

    /**
     * If the proxy replied with a wildcard bind address (e.g. {@code 0.0.0.0}), use the
     * proxy server's own IP together with the returned port; otherwise use the address as-is.
     */
    private InetSocketAddress resolveRelayAddress(InetSocketAddress bindAddr) {
        if (bindAddr.getAddress() != null && bindAddr.getAddress().isAnyLocalAddress()) {
            return new InetSocketAddress(proxyServer.getEndpoint().getAddress(), bindAddr.getPort());
        }
        return bindAddr;
    }
}
