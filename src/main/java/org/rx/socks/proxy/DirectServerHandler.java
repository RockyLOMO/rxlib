package org.rx.socks.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.rx.NQuery;
import org.rx.socks.Bytes;
import org.rx.socks.Sockets;
import org.rx.util.MemoryStream;

import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.Contract.require;

public class DirectServerHandler extends SimpleChannelInboundHandler<byte[]> {
    private static class ClientState {
        private final ChannelHandlerContext channelHandlerContext;
        private int                         length;
        private MemoryStream                stream;
        private SocketAddress               remoteAddress;
        private ProxyServer                 proxyServer;

        public ClientState(ChannelHandlerContext ctx) {
            channelHandlerContext = ctx;
            stream = new MemoryStream(32, true);
        }

        public void transfer(byte[] bytes) {
            if (remoteAddress == null) {
                int offset = readRemoteAddress(bytes);
                if (offset == 0) {
                    return;
                }
                proxyServer = new ProxyServer();
                proxyServer.start();

                channelHandlerContext.writeAndFlush(Unpooled.wrappedBuffer(bytes, offset, bytes.length - offset));
                return;
            }

            channelHandlerContext.writeAndFlush(bytes);
        }

        private int readRemoteAddress(byte[] bytes) {
            int offset = 0;
            if (length == -1) {
                stream.setLength(length = Bytes.toInt(bytes, 0));
                stream.setPosition(0);
                offset = Integer.BYTES;
            }
            int count = length - stream.getPosition();
            stream.write(bytes, offset, Math.min(count, bytes.length));
            if (stream.getPosition() < length) {
                return -1;
            }

            remoteAddress = Sockets.parseAddress(Bytes.toString(stream.getBuffer(), 0, length));
            length = -1;
            return bytes.length - count;
        }
    }

    private final Map<ChannelHandlerContext, ClientState> clients;

    public DirectServerHandler() {
        clients = new ConcurrentHashMap<>();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, byte[] bytes) throws Exception {
        ClientState state = clients.computeIfAbsent(ctx, p -> new ClientState());
        SocketAddress remoteAddress = state.getRemoteAddress(bytes);
        if (remoteAddress == null) {
            return;
        }

        state.transfer(bytes);
        //        ctx.writeAndFlush(factorial);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        System.out.println(String.format("DirectServerHandler %s Active", ctx.name()));
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        System.out.println(String.format("DirectServerHandler %s Inactive", ctx.name()));
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)  {
        cause.printStackTrace();
        ctx.close();
    }
}
