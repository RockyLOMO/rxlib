package org.rx.socks.tcp;

import io.netty.channel.ChannelHandlerContext;
import org.rx.core.InvalidOperationException;
import org.rx.socks.tcp.impl.ErrorPacket;
import org.rx.socks.tcp.impl.HandshakePacket;

public class PacketClientHandler extends TcpClient.BaseClientHandler {
    public PacketClientHandler(TcpClient client) {
        super(client);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        super.channelActive(ctx);

        HandshakePacket handshake = new HandshakePacket();
        handshake.setSessionId(getClient().getSessionId());
        ctx.writeAndFlush(handshake);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ErrorPacket) {
            exceptionCaught(ctx, new InvalidOperationException(String.format("Server error message: %s", ((ErrorPacket) msg).getErrorMessage())));
            return;
        }
        if (msg instanceof HandshakePacket) {
            HandshakePacket handshake = (HandshakePacket) msg;
            return;
        }

        super.channelRead(ctx, msg);
    }
}
