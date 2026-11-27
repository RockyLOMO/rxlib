package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.collection.LongObjectHashMap;
import org.rx.net.rss.udp.RssDatagramHandler;

/** All group relay sockets and their shared ingress use this channel's event loop. */
final class RssFixedUdpEntry extends ChannelInboundHandlerAdapter {
    private final LongObjectHashMap<RssDatagramHandler> routes = new LongObjectHashMap<>();

    void register(long prefix, RssDatagramHandler wire) {
        if (routes.containsKey(prefix)) {
            throw new IllegalStateException("Duplicate fixed UDP session token prefix");
        }
        routes.put(prefix, wire);
    }

    void remove(long prefix, RssDatagramHandler wire) {
        if (routes.get(prefix) == wire) {
            routes.remove(prefix);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof DatagramPacket)) {
            ctx.fireChannelRead(msg);
            return;
        }
        DatagramPacket packet = (DatagramPacket) msg;
        ByteBuf data = packet.content();
        RssDatagramHandler wire = data.readableBytes() >= 20 ? routes.get(data.getLong(data.readerIndex())) : null;
        if (wire == null) {
            packet.release();
            return;
        }
        // The full token, sequence and authentication tag are checked before any peer state changes.
        wire.receiveFixed(ctx, packet);
    }
}
