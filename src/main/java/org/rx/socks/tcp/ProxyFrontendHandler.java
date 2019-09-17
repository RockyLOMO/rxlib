//package org.rx.socks.tcp;
//
//import io.netty.buffer.Unpooled;
//import io.netty.channel.Channel;
//import io.netty.channel.ChannelFutureListener;
//import io.netty.channel.ChannelHandlerContext;
//
//import java.net.InetSocketAddress;
//import java.util.function.Function;
//
//import static org.rx.core.Contract.require;
//
//public class ProxyFrontendHandler<T extends SessionClient> extends TcpServer.BaseServerHandler<T> {
//    public class ProxyBackendHandler extends TcpClient.BaseClientHandler {
//        private ChannelHandlerContext inbound;
//
//        public ProxyBackendHandler(ChannelHandlerContext inbound, TcpClient client) {
//            super(client);
////            client.setAutoRead(false);
//            this.inbound = inbound;
//        }
//
//        @Override
//        public void channelActive(ChannelHandlerContext ctx) throws Exception {
//            super.channelActive(ctx);
//            client.connectStatus(true);
////            ctx.read();
////            ctx.write(Unpooled.EMPTY_BUFFER);
//
//        }
//
//        @Override
//        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
//            super.channelRead(ctx, msg);
////            if (inbound.channel().isActive()) {
////            io.netty.buffer.ByteBuf e;
////            e.get
//            inbound.channel().writeAndFlush(msg).addListener(f -> {
//                if (f.isSuccess()) {
//                    ctx.channel().read();
//                } else {
//                    inbound.channel().close();
//                }
//            });
////            }
//        }
//
//        @Override
//        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
//            super.channelInactive(ctx);
//            closeOnFlushed(inbound.channel());
//        }
//
//        @Override
//        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
//            super.exceptionCaught(ctx, cause);
//            closeOnFlushed(ctx.channel());
//        }
//    }
//
//    /**
//     * Closes the specified channel after all queued write requests are flushed.
//     */
//    public static void closeOnFlushed(Channel ch) {
//        require(ch);
//
//        if (ch.isActive()) {
//            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
//        }
//    }
//
//    private Function<InetSocketAddress, InetSocketAddress> proxyRule;
//    private TcpClient outbound;
//
//    public ProxyFrontendHandler(TcpServer<T> server, Function<InetSocketAddress, InetSocketAddress> proxyRule) {
//        super(server);
//        server.setAutoRead(false);
//        require(proxyRule);
//        this.proxyRule = proxyRule;
//    }
//
//    @Override
//    public void channelActive(ChannelHandlerContext ctx) throws Exception {
//        super.channelActive(ctx);
//        InetSocketAddress proxyEndpoint = proxyRule.apply((InetSocketAddress) ctx.channel().remoteAddress());
//        log.debug("connect to backend {}", proxyEndpoint);
//        outbound = new TcpClient(proxyEndpoint);
//        outbound.setChannelHandlers(new ProxyBackendHandler(ctx, outbound));
//        outbound.connect(true);
//        System.out.println(11111111);
//        ctx.channel().read();
//    }
//
//    @Override
//    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
//        super.channelRead(ctx, msg);
////        if (outbound.isConnected()) {
//        outbound.channel.writeAndFlush(msg).addListener(f -> {
//            if (f.isSuccess()) {
//                ctx.channel().read();
//            } else {
//                outbound.channel.close();
//            }
//        });
////        }
//    }
//
//    @Override
//    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
//        super.channelInactive(ctx);
//        if (outbound.isConnected()) {
//            log.debug("disconnect from backend {}", outbound.getServerEndpoint());
//            outbound.close();
//        }
//    }
//
//    @Override
//    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
//        super.exceptionCaught(ctx, cause);
//        closeOnFlushed(ctx.channel());
//    }
//}
