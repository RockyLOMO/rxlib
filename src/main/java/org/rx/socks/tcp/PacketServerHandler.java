package org.rx.socks.tcp;

import io.netty.channel.ChannelHandlerContext;
import org.rx.socks.Sockets;
import org.rx.socks.tcp.*;
import org.rx.socks.tcp.impl.ErrorPacket;
import org.rx.socks.tcp.impl.HandshakePacket;

import static org.rx.core.AsyncTask.TaskFactory;
import static org.rx.core.Contract.as;

public class PacketServerHandler<T extends SessionClient> extends TcpServer.BaseServerHandler<T> {
    public PacketServerHandler(TcpServer<T> server) {
        super(server);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        log.debug("channelRead {} {}", ctx.channel().remoteAddress(), msg.getClass());
        TcpServer<T> server = getServer();

        SessionPacket pack;
        if ((pack = as(msg, SessionPacket.class)) == null) {
            ctx.writeAndFlush(ErrorPacket.error("Error pack"));
            Sockets.closeOnFlushed(ctx.channel());
            return;
        }

        if (pack instanceof HandshakePacket) {
            SessionId sessionId = ((HandshakePacket) pack).getSessionId();
            server.addClient(sessionId, getClient());
            ctx.writeAndFlush(pack);
            return;
        }

        //异步避免阻塞
        TaskFactory.run(() -> server.raiseEvent(server.onReceive, new PackEventArgs<>(getClient(), pack)));
    }
}
