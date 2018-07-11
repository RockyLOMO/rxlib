package org.rx.socks.proxy;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.rx.socks.Bytes;
import org.rx.socks.Sockets;
import org.rx.util.MemoryStream;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.Contract.require;

public class DirectServerHandler extends SimpleChannelInboundHandler<byte[]> {
    private static class ClientState {
        private final ChannelHandlerContext serverChannel;
        private ProxyClient                 directClient;
        private SocketAddress               directAddress;

        private int                         length;
        private MemoryStream                stream;

        public ClientState(ChannelHandlerContext ctx, boolean enableSsl, SocketAddress directAddress) {
            serverChannel = ctx;
            directClient = new ProxyClient();
            directClient.setEnableSsl(enableSsl);
            this.directAddress = directAddress;

            stream = new MemoryStream(32, true);
        }

        public void transfer(byte[] bytes) {
            int offset = 0;
            if (directAddress == null) {
                offset = readRemoteAddress(bytes);
                if (offset == -1) {
                    return;
                }
            }
            if (!directClient.isConnected()) {
                directClient.connect((InetSocketAddress) directAddress, (directChannel, data) -> {
                    serverChannel.writeAndFlush(data);
                });
                if (offset > 0) {
                    directClient.send(Unpooled.wrappedBuffer(bytes, offset, bytes.length - offset));
                }
                return;
            }

            directClient.send(bytes);
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

            directAddress = Sockets.parseAddress(Bytes.toString(stream.getBuffer(), 0, length));
            length = -1;
            return bytes.length - count;
        }
    }

    private final Map<ChannelHandlerContext, ClientState> clients;
    private boolean                                       enableSsl;
    private SocketAddress                                 directAddress;

    public DirectServerHandler(boolean enableSsl, SocketAddress directAddress) {
        require(directAddress);

        clients = new ConcurrentHashMap<>();
        this.enableSsl = enableSsl;
        this.directAddress = directAddress;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, byte[] bytes) throws Exception {
        ClientState state = clients.computeIfAbsent(ctx, p -> new ClientState(ctx, enableSsl, directAddress));
        state.transfer(bytes);
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
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}
