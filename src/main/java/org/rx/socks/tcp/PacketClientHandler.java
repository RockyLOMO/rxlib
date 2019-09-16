package org.rx.socks.tcp;

import io.netty.channel.ChannelHandlerContext;
import org.rx.core.InvalidOperationException;
import org.rx.core.NEventArgs;
import org.rx.socks.tcp.impl.ErrorPacket;
import org.rx.socks.tcp.impl.HandshakePacket;

import static org.rx.core.Contract.as;

public class PacketClientHandler extends TcpClient.BaseClientHandler {
    public PacketClientHandler(TcpClient client) {
        super(client);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        super.channelRead(ctx, msg);
        SessionPacket pack;
        if ((pack = as(msg, SessionPacket.class)) == null) {
            ctx.close();
            return;
        }
        if (pack instanceof ErrorPacket) {
            exceptionCaught(ctx, new InvalidOperationException(String.format("Server error message: %s", ((ErrorPacket) pack).getErrorMessage())));
            return;
        }
        if (pack instanceof HandshakePacket) {
            NEventArgs<ChannelHandlerContext> args = new NEventArgs<>(ctx);
            client.raiseEvent(client.onConnected, args);
            if (args.isCancel()) {
                ctx.close();
            } else {
                client.isConnected = true;
                client.connectWaiter.set();
            }
            return;
        }

        client.raiseEvent(client.onReceive, new PackEventArgs<>(ctx, pack));
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        HandshakePacket handshake = new HandshakePacket();
        handshake.setSessionId(client.sessionId);
        ctx.writeAndFlush(handshake);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        client.isConnected = false;

        NEventArgs<ChannelHandlerContext> args = new NEventArgs<>(ctx);
        client.raiseEvent(client.onDisconnected, args);
        client.reconnect();
    }
}
