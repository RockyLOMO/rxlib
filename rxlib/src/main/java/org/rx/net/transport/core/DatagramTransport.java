package org.rx.net.transport.core;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.net.Sockets;
import org.rx.net.rss.udp.RssDatagramHandler;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Shared Transport Core raw {@link ByteBuf} UDP datagram endpoint (zero serialization dependency).
 * <p>
 * Ownership contract:
 * <ul>
 *     <li>{@link #send(InetSocketAddress, ByteBuf)} ALWAYS consumes and releases {@code payload}.</li>
 *     <li>Receive handlers borrow {@code payload} for the callback duration; retain it if handed off asynchronously.</li>
 * </ul>
 * Optional authenticated fragmentation / LZ4 / FEC / AES can be attached via {@link #installDatagramHandler(RssDatagramHandler)}.
 */
@Slf4j
public final class DatagramTransport extends Disposable {
    @Getter
    private final DatagramTransportConfig config;
    private final List<Channel> channels;
    private final Channel primaryChannel;
    private volatile BiConsumer<InetSocketAddress, ByteBuf> receiveHandler;

    public static DatagramTransport bind(int port) {
        return new DatagramTransport(new DatagramTransportConfig(port));
    }

    public static DatagramTransport bind(@NonNull DatagramTransportConfig config) {
        return new DatagramTransport(config);
    }

    private DatagramTransport(@NonNull DatagramTransportConfig config) {
        this.config = config;
        int recvBytes = Math.max(2048, config.getMaxReceiveBytes());
        Bootstrap bootstrap = Sockets.udpBootstrap(config, ch -> {
            ch.pipeline().addLast("coreDatagram", new SimpleChannelInboundHandler<DatagramPacket>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                    BiConsumer<InetSocketAddress, ByteBuf> handler = receiveHandler;
                    if (handler != null) {
                        handler.accept(packet.sender(), packet.content());
                    }
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    log.warn("datagram transport error local={}", ctx.channel().localAddress(), cause);
                }
            });
        }).option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(recvBytes));

        InetSocketAddress bindAddress = Sockets.newAnyEndpoint(config.getBindPort());
        this.channels = Collections.unmodifiableList(Sockets.bindChannels(bootstrap, bindAddress, config));
        this.primaryChannel = this.channels.get(0);
    }

    public void setReceiveHandler(BiConsumer<InetSocketAddress, ByteBuf> handler) {
        this.receiveHandler = handler;
    }

    /** Installs a configured {@link RssDatagramHandler} on the primary UDP channel for bounded fragmentation / LZ4 / FEC / AES. */
    public void installDatagramHandler(@NonNull RssDatagramHandler handler) {
        checkNotClosed();
        if (primaryChannel.eventLoop().inEventLoop()) {
            RssDatagramHandler.install(primaryChannel.pipeline(), handler);
            return;
        }
        primaryChannel.eventLoop().submit(() -> RssDatagramHandler.install(primaryChannel.pipeline(), handler))
                .syncUninterruptibly();
    }

    public InetSocketAddress localEndpoint() {
        return (InetSocketAddress) primaryChannel.localAddress();
    }

    public Channel channel() {
        return primaryChannel;
    }

    public List<Channel> channels() {
        return channels;
    }

    public ChannelFuture send(@NonNull InetSocketAddress recipient, @NonNull ByteBuf payload) {
        if (isClosed() || !primaryChannel.isActive()) {
            payload.release();
            return primaryChannel.newFailedFuture(new IllegalStateException("DatagramTransport closed"));
        }
        if (recipient.isUnresolved()) {
            payload.release();
            return primaryChannel.newFailedFuture(new IllegalArgumentException("Unresolved UDP recipient: " + recipient));
        }
        return primaryChannel.writeAndFlush(new DatagramPacket(payload, recipient));
    }

    @Override
    protected void dispose() {
        for (Channel ch : channels) {
            if (ch.isOpen()) {
                ch.close();
            }
        }
    }
}
