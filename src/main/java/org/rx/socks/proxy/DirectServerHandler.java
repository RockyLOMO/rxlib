package org.rx.socks.proxy;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.rx.socks.Bytes;
import org.rx.socks.Sockets;
import org.rx.util.MemoryStream;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.Contract.require;

public class DirectServerHandler extends SimpleChannelInboundHandler<byte[]> {
    private static class ClientState {
        private final ChannelHandlerContext serverChannel;
        private int                         length;
        private MemoryStream                stream;
        private InetSocketAddress           remoteAddress;
        private ProxyClient                 directClient;

        public ClientState(ChannelHandlerContext ctx, boolean enableSsl, boolean enableCompression) {
            serverChannel = ctx;
            stream = new MemoryStream(32, true);
            directClient = new ProxyClient();
            directClient.setEnableSsl(enableSsl);
            directClient.setEnableCompression(enableCompression);
        }

        public void transfer(byte[] bytes) {
            if (remoteAddress == null) {
                int offset = readRemoteAddress(bytes);
                if (offset == -1) {
                    return;
                }

                directClient.connect(remoteAddress, (directChannel, data) -> {
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

            remoteAddress = Sockets.parseAddress(Bytes.toString(stream.getBuffer(), 0, length));
            length = -1;
            return bytes.length - count;
        }
    }

    private final Map<ChannelHandlerContext, ClientState> clients;
    private boolean                                       enableSsl, enableCompression;

    public DirectServerHandler(boolean enableSsl, boolean enableCompression) {
        clients = new ConcurrentHashMap<>();
        this.enableSsl = enableSsl;
        this.enableCompression = enableCompression;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, byte[] bytes) throws Exception {
        ClientState state = clients.computeIfAbsent(ctx, p -> new ClientState(ctx, enableSsl, enableCompression));
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
