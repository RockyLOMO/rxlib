package org.rx.socks.tcp;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.EventArgs;
import org.rx.core.InvalidOperationException;
import org.rx.core.NEventArgs;
import org.rx.socks.Sockets;
import org.rx.socks.tcp.packet.ErrorPacket;

import java.io.Serializable;
import java.lang.ref.WeakReference;

import static org.rx.core.Contract.as;
import static org.rx.core.Contract.require;

@Slf4j
public class PacketClientHandler extends ChannelInboundHandlerAdapter {
    private WeakReference<TcpClient> weakRef;

    private TcpClient getClient() {
        TcpClient client = weakRef.get();
        require(client);
        return client;
    }

    public PacketClientHandler(TcpClient client) {
        require(client);
        this.weakRef = new WeakReference<>(client);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.debug("clientActive {}", ctx.channel().remoteAddress());
        TcpClient client = getClient();
        client.ctx = ctx;
        client.isConnected = true;

        ctx.writeAndFlush(client.getHandshake());

        client.raiseEvent(client.onConnected, EventArgs.Empty);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ErrorPacket) {
            exceptionCaught(ctx, new InvalidOperationException(String.format("Server error message: %s", ((ErrorPacket) msg).getErrorMessage())));
            return;
        }
        log.debug("clientRead {} {}", ctx.channel().remoteAddress(), msg.getClass());

        TcpClient client = getClient();
        Serializable pack;
        if ((pack = as(msg, Serializable.class)) == null) {
            log.debug("channelRead discard {} {}", ctx.channel().remoteAddress(), msg.getClass());
            return;
        }
        client.raiseEvent(client.onReceive, new NEventArgs<>(pack));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.debug("clientInactive {}", ctx.channel().remoteAddress());
        TcpClient client = getClient();
        client.isConnected = false;

        NEventArgs<ChannelHandlerContext> args = new NEventArgs<>(ctx);
        client.raiseEvent(client.onDisconnected, args);
        client.reconnect();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("clientCaught {}", ctx.channel().remoteAddress(), cause);
        TcpClient client = getClient();
        NEventArgs<Throwable> args = new NEventArgs<>(cause);
        try {
            client.raiseEvent(client.onError, args);
        } catch (Exception e) {
            log.error("clientCaught", e);
        }
        if (args.isCancel()) {
            return;
        }
        Sockets.closeOnFlushed(ctx.channel());
    }
}
