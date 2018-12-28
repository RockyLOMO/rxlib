package org.rx.socks.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.rx.Logger;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import static org.rx.Contract.require;

public class DirectServerHandler extends SimpleChannelInboundHandler<byte[]> {
    private static class ClientState {
        private ProxyClient directClient;
        //        private int                         length;
        //        private MemoryStream                stream;

        public ProxyClient getDirectClient() {
            return directClient;
        }

        public ClientState(boolean enableSsl, SocketAddress directAddress,
                           BiConsumer<ChannelHandlerContext, byte[]> onReceive) {
            require(directAddress, onReceive);

            directClient = new ProxyClient();
            directClient.setEnableSsl(enableSsl);
            directClient.connect((InetSocketAddress) directAddress, onReceive);
            //            stream = new MemoryStream(32, true);
        }

        //        private int readRemoteAddress(byte[] bytes) {
        //            int offset = 0;
        //            if (length == -1) {
        //                stream.setLength(length = Bytes.toInt(bytes, 0));
        //                stream.setPosition(0);
        //                offset = Integer.BYTES;
        //            }
        //            int count = length - stream.getPosition();
        //            stream.write(bytes, offset, Math.min(count, bytes.length));
        //            if (stream.getPosition() < length) {
        //                return -1;
        //            }
        //
        //            directAddress = Sockets.parseAddress(Bytes.toString(stream.getBuffer(), 0, length));
        //            length = -1;
        //            return bytes.length - count;
        //        }
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
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        clients.put(ctx, new ClientState(enableSsl, directAddress, (directChannel, bytes) -> {
            ctx.writeAndFlush(bytes);
            Logger.info("DirectServerHandler %s recv %s bytes from %s", ctx.channel().remoteAddress(), bytes.length,
                    directAddress);
        }));
        Logger.info("DirectServerHandler %s connect %s", ctx.channel().remoteAddress(), directAddress);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, byte[] bytes) {
        ClientState state = clients.get(ctx);
        require(state);

        ProxyClient directClient = state.getDirectClient();
        directClient.send(bytes);
        Logger.info("DirectServerHandler %s send %s bytes to %s",
                directClient.getHandler().getChannel().remoteAddress(), bytes.length, ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        clients.remove(ctx);
        Logger.info("DirectServerHandler %s disconnect %s", ctx.channel().remoteAddress(), directAddress);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
        Logger.error(cause, "DirectServerHandler");
        ctx.close();
    }
}
