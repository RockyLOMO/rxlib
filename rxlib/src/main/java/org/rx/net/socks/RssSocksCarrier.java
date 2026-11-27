package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AttributeKey;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.rss.RssUdpMtuBudget;
import org.rx.diagnostic.DiagnosticMetrics;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

/** RFC 1928 envelope on the outer hop, limited to negotiated RSS peer endpoints. */
public final class RssSocksCarrier extends ChannelDuplexHandler {
    public static final String TCP_HANDLER = "rssOuterSocks";
    public static final AttributeKey<AuthenticEndpoint> OUTER_PROXY = AttributeKey.valueOf("rssOuterProxy");
    public static final AttributeKey<InetSocketAddress> INNER_PEER = AttributeKey.valueOf("rssCarrierPeer");

    private final InetSocketAddress primary, alias;
    private final UdpManager.HeaderTemplate firstHeader, secondHeader;
    public static final AttributeKey<Integer> OUTER_MTU = AttributeKey.valueOf("rssCarrierOuterMtu");
    private final boolean dedicated;
    private final int outerMtu;
    private volatile Socks5Client.Socks5UdpLease lease;
    private volatile boolean closed;

    public RssSocksCarrier(InetSocketAddress primary, InetSocketAddress alias, boolean dedicated, int outerMtu) {
        this.outerMtu = RssUdpMtuBudget.negotiate(outerMtu, RssUdpMtuBudget.MAX_DATAGRAM);
        this.primary = primary;
        this.alias = alias;
        firstHeader = UdpManager.socks5HeaderTemplate(primary);
        secondHeader = primary.equals(alias) ? firstHeader : UdpManager.socks5HeaderTemplate(alias);
        this.dedicated = dedicated;
    }

    public CompletableFuture<Void> open(Channel channel, AuthenticEndpoint proxy) {
        Socks5Client client = new Socks5Client(proxy);
        return client.udpAssociateLeaseAsync((InetSocketAddress) channel.localAddress()).thenAccept(association -> {
            channel.closeFuture().addListener(f -> association.close());
            if (closed || !channel.isActive() || !association.getTcpControl().isActive()) {
                association.close();
                throw new IllegalStateException("Outer SOCKS5 association closed");
            }
            lease = association;
            if (closed) {
                association.close();
                throw new IllegalStateException("Outer SOCKS5 association cancelled");
            }
            association.getTcpControl().closeFuture().addListener(f -> { if (!closed) channel.close(); });
        }).whenComplete((v, e) -> client.close());
    }

    public static void installTcp(Channel channel, AuthenticEndpoint proxy, InetSocketAddress peer, int timeoutMillis, int outerMtu) {
        Socks5ClientHandler outer = new Socks5ClientHandler(proxy.requireEndpoint(), proxy.getUsername(), proxy.getPassword());
        outer.setConnectTimeoutMillis(timeoutMillis);
        channel.attr(OUTER_PROXY).set(proxy);
        channel.attr(INNER_PEER).set(peer);
        channel.attr(OUTER_MTU).set(outerMtu);
        channel.pipeline().addFirst(TCP_HANDLER, outer);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (!(msg instanceof DatagramPacket)) {
            ctx.write(msg, promise);
            return;
        }
        DatagramPacket packet = (DatagramPacket) msg;
        InetSocketAddress recipient = packet.recipient();
        UdpManager.HeaderTemplate header = primary.equals(recipient) ? firstHeader : alias.equals(recipient) ? secondHeader : null;
        if (header == null && !dedicated) {
            ctx.write(msg, promise);
            return;
        }
        if (header != null && packet.content().readableBytes() > outerMtu - header.length()) {
            packet.release();
            DiagnosticMetrics.record("rss.udp.carrier.finalMtuDrop", 1);
            promise.tryFailure(new IllegalArgumentException("SOCKS5 UDP packet exceeds carrier MTU"));
            return;
        }
        if (header == null || lease == null || !lease.getTcpControl().isActive()) {
            packet.release();
            promise.tryFailure(new IllegalStateException("SOCKS5 UDP carrier unavailable"));
            return;
        }
        ByteBuf wrapped = header.composite(ctx.alloc(), packet.content(), false);
        ctx.write(new DatagramPacket(wrapped, lease.getRelayAddress()), promise);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof DatagramPacket)) {
            ctx.fireChannelRead(msg);
            return;
        }
        DatagramPacket packet = (DatagramPacket) msg;
        if (lease == null || !lease.getRelayAddress().equals(packet.sender())) {
            if (dedicated || primary.equals(packet.sender()) || alias.equals(packet.sender())) packet.release();
            else ctx.fireChannelRead(msg);
            return;
        }
        ByteBuf bytes = packet.content();
        if (bytes.readableBytes() > outerMtu) {
            packet.release();
            DiagnosticMetrics.record("rss.udp.carrier.rxMtuDrop", 1);
            return;
        }
        InetSocketAddress sender;
        if (firstHeader.matches(bytes)) {
            sender = primary;
            bytes.skipBytes(firstHeader.length());
        } else if (secondHeader != firstHeader && secondHeader.matches(bytes)) {
            sender = alias;
            bytes.skipBytes(secondHeader.length());
        } else {
            packet.release();
            return;
        }
        ctx.fireChannelRead(new DatagramPacket(bytes, packet.recipient(), sender));
    }

    public void close() {
        closed = true;
        if (lease != null) lease.close();
    }
}
