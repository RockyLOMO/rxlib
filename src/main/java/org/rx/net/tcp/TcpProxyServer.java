//package org.rx.socks.tcp;
//
//import io.netty.bootstrap.Bootstrap;
//import io.netty.bootstrap.ServerBootstrap;
//import io.netty.channel.*;
//import lombok.RequiredArgsConstructor;
//import lombok.SneakyThrows;
//import lombok.extern.slf4j.Slf4j;
//import org.rx.core.Disposable;
//import org.rx.socks.MemoryMode;
//import org.rx.socks.Sockets;
//import org.rx.util.function.BiFunc;
//
//import java.net.InetSocketAddress;
//import java.util.concurrent.ConcurrentLinkedQueue;
//
//import static org.rx.core.Contract.require;
//
//@Slf4j
//public class TcpProxyServer extends Disposable {
//    private class FrontendHandler extends ChannelInboundHandlerAdapter {
//        @RequiredArgsConstructor
//        private class BackendHandler extends ChannelInboundHandlerAdapter {
//            private final ChannelHandlerContext inbound;
//
//            @Override
//            public void channelActive(ChannelHandlerContext ctx) {
//                flushBackend();
//            }
//
//            @Override
//            public void channelRead(ChannelHandlerContext outbound, Object msg) {
//                if (!inbound.channel().isActive()) {
//                    return;
//                }
//                inbound.writeAndFlush(msg);
//            }
//
//            @Override
//            public void channelInactive(ChannelHandlerContext ctx) {
//                if (!inbound.channel().isActive()) {
//                    return;
//                }
//                Sockets.closeOnFlushed(inbound.channel());
//            }
//
//            @Override
//            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
//                log.error("serverCaught {}", ctx.channel().remoteAddress(), cause);
//                Sockets.closeOnFlushed(ctx.channel());
//            }
//        }
//
//        private Channel outbound;
//        private final ConcurrentLinkedQueue<Object> packetQueue = new ConcurrentLinkedQueue<>();
//
//        @SneakyThrows
//        @Override
//        public void channelActive(ChannelHandlerContext inbound) {
//            InetSocketAddress proxyEndpoint = proxyRule.invoke((InetSocketAddress) inbound.channel().remoteAddress());
//            log.debug("connect to backend {}", proxyEndpoint);
//            Bootstrap bootstrap = Sockets.bootstrap(inbound.channel(), memoryMode, s -> s.pipeline().addLast(new BackendHandler(inbound)));
//            outbound = bootstrap.connect(proxyEndpoint).channel();
//        }
//
//        @Override
//        public void channelRead(ChannelHandlerContext inbound, Object msg) {
//            if (!outbound.isActive()) {
//                packetQueue.add(msg);
//                return;
//            }
//            flushBackend();
//            outbound.writeAndFlush(msg);
//        }
//
//        @Override
//        public void channelInactive(ChannelHandlerContext inbound) {
//            if (!outbound.isActive()) {
//                return;
//            }
//            Sockets.closeOnFlushed(outbound);
//        }
//
//        private void flushBackend() {
//            if (packetQueue.isEmpty()) {
//                return;
//            }
//
//            outbound.eventLoop().execute(() -> {
//                log.debug("flushBackend");
//                Object delay;
//                while ((delay = packetQueue.poll()) != null) {
//                    outbound.write(delay);
//                }
//                outbound.flush();
//            });
//        }
//
//        @Override
//        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
//            log.error("serverCaught {}", ctx.channel().remoteAddress(), cause);
//            Sockets.closeOnFlushed(ctx.channel());
//        }
//    }
//
//    private ServerBootstrap serverBootstrap;
//    private MemoryMode memoryMode;
//    private BiFunc<InetSocketAddress, InetSocketAddress> proxyRule;
//
//    public TcpProxyServer(int port, MemoryMode memoryMode, BiFunc<InetSocketAddress, InetSocketAddress> proxyRule) {
//        require(proxyRule);
//
//        serverBootstrap = Sockets.serverBootstrap(1, Runtime.getRuntime().availableProcessors(), memoryMode, s -> s.pipeline().addLast(new FrontendHandler()));
//        serverBootstrap.bind(port);
//        log.debug("Proxy Listened on port {}..", port);
//        this.memoryMode = memoryMode;
//        this.proxyRule = proxyRule;
//    }
//
//    @Override
//    protected void freeObjects() {
//        Sockets.closeBootstrap(serverBootstrap);
//    }
//}
