package org.rx.socks.tcp;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Reflects;
import org.rx.core.Tasks;
import org.rx.socks.Sockets;
import org.rx.socks.tcp.packet.ErrorPacket;
import org.rx.socks.tcp.packet.HandshakePacket;

import java.io.Serializable;
import java.lang.ref.WeakReference;

import static org.rx.core.Contract.*;

@Slf4j
public class PacketServerHandler<T extends Serializable> extends ChannelInboundHandlerAdapter {
    private WeakReference<TcpServer<T>> weakRef;
    private SessionClient<T> client;

    private TcpServer<T> getServer() {
        TcpServer<T> server = weakRef.get();
        require(server);
        return server;
    }

    public PacketServerHandler(TcpServer<T> server) {
        require(server);
        this.weakRef = new WeakReference<>(server);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
//        super.channelActive(ctx);
        log.debug("serverActive {}", ctx.channel().remoteAddress());
        TcpServer<T> server = getServer();
        if (server.getClientSize() > server.getCapacity()) {
            log.warn("Not enough capacity");
            Sockets.closeOnFlushed(ctx.channel());
            return;
        }
        client = new SessionClient<>(ctx, server.getStateType() == null ? null : Reflects.newInstance(server.getStateType()));
        PackEventArgs<T> args = new PackEventArgs<>(client, null);
        server.raiseEvent(server.onConnected, args);
        if (args.isCancel()) {
            log.warn("Close client");
            Sockets.closeOnFlushed(ctx.channel());
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        log.debug("serverRead {} {}", ctx.channel().remoteAddress(), msg.getClass());
        TcpServer<T> server = getServer();

        Serializable pack;
        if ((pack = as(msg, Serializable.class)) == null) {
            ctx.writeAndFlush(new ErrorPacket("Packet discard"));
            Sockets.closeOnFlushed(ctx.channel());
            return;
        }
        if (tryAs(pack, HandshakePacket.class, p -> {
            client.setGroupId(p.getGroupId());
            server.addClient(client);
        })) {
            return;
        }

        if (client.getGroupId() == null) {
            log.warn("ServerHandshake fail");
            return;
        }
        //异步避免阻塞
        Tasks.run(() -> server.raiseEvent(server.onReceive, new PackEventArgs<>(client, pack)));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.debug("serverInactive {}", ctx.channel().remoteAddress());
        TcpServer<T> server = getServer();
        try {
            server.raiseEvent(server.onDisconnected, new PackEventArgs<>(client, null));
        } finally {
            server.removeClient(client);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("serverCaught {}", ctx.channel().remoteAddress(), cause);
        TcpServer<T> server = getServer();
        ErrorEventArgs<T> args = new ErrorEventArgs<>(client, cause);
        try {
            server.raiseEvent(server.onError, args);
        } catch (Exception e) {
            log.error("serverCaught", e);
        }
        if (args.isCancel()) {
            return;
        }
        Sockets.closeOnFlushed(ctx.channel());
    }
}
