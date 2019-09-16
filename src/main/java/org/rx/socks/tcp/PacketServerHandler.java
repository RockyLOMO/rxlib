package org.rx.socks.tcp;

import io.netty.channel.ChannelHandlerContext;
import org.rx.core.NEventArgs;
import org.rx.socks.tcp.impl.ErrorPacket;
import org.rx.socks.tcp.impl.HandshakePacket;

import static org.rx.core.AsyncTask.TaskFactory;
import static org.rx.core.Contract.as;

public class PacketServerHandler<T extends SessionClient> extends TcpServer.BaseServerHandler<T> {
    public PacketServerHandler(TcpServer<T> server) {
        super(server);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        super.channelRead(ctx, msg);
        SessionPacket pack;
        if ((pack = as(msg, SessionPacket.class)) == null) {
            ctx.writeAndFlush(ErrorPacket.error("Error pack"));
            TaskFactory.scheduleOnce(ctx::close, 4 * 1000);
            return;
        }

        if (pack instanceof HandshakePacket) {
            SessionId sessionId = ((HandshakePacket) pack).getSessionId();
            T client = server.createClient(ctx);
            NEventArgs<T> args = new NEventArgs<>(client);
            server.raiseEvent(server.onConnected, args);
            if (args.isCancel()) {
                log.warn("Close client");
                ctx.close();
            } else {
                server.addClient(sessionId, client);
                ctx.writeAndFlush(pack);
            }
            return;
        }

        //异步避免阻塞
        TaskFactory.run(() -> server.raiseEvent(server.onReceive, new PackEventArgs<>(server.findClient(ctx.channel().id()), pack)));
    }
}
