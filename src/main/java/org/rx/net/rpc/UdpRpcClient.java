package org.rx.net.rpc;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AttributeKey;
import org.rx.bean.IdGenerator;
import org.rx.core.App;
import org.rx.core.EventTarget;
import org.rx.io.MemoryStream;
import org.rx.io.Serializer;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class UdpRpcClient implements EventTarget<UdpRpcClient> {
    static final AttributeKey<UdpRpcClient> OWNER = AttributeKey.valueOf("UdpRpcClient");
    static final Handler HANDLER = new Handler();

    @ChannelHandler.Sharable
    static class Handler extends SimpleChannelInboundHandler<DatagramPacket> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) throws Exception {
            UdpClientEventArgs e = new UdpClientEventArgs(msg.sender(), Serializer.DEFAULT.deserialize(new MemoryStream(msg.content().retain(), false)));
            UdpRpcClient client = ctx.channel().attr(OWNER).get();
            client.raiseEventAsync(client.onReceive, e);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            App.log("UdpRpcClient", cause);
        }
    }

    public volatile BiConsumer<UdpRpcClient, UdpClientEventArgs> onReceive;
    final Bootstrap bootstrap;
    final Channel channel;
    final IdGenerator generator = new IdGenerator();
    final Map<Integer, Serializer> queue = new ConcurrentHashMap<>();

    public UdpRpcClient(int bindPort) {
        bootstrap = Sockets.udpBootstrap(ch -> ch.pipeline().addLast(HANDLER));
        channel = bootstrap.bind(bindPort).addListener(Sockets.logBind(bindPort)).channel();
        channel.attr(OWNER).set(this);
    }

    public <T> void send(InetSocketAddress remoteAddress, T pack) {
        MemoryStream stream = new MemoryStream(true);
        Serializer.DEFAULT.serialize(pack, stream);
        channel.writeAndFlush(new DatagramPacket(stream.getBuffer().readerIndex(0), remoteAddress)).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }
}
