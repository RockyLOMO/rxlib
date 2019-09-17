package org.rx.socks.tcp;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

import java.net.InetSocketAddress;
import java.util.function.Function;

import static org.rx.core.Contract.require;

public class ProxyFrontendHandler<T extends SessionClient> extends TcpServer.BaseServerHandler<T> {
    public class ProxyBackendHandler extends TcpClient.BaseClientHandler {
        private ChannelHandlerContext inbound;

        public ProxyBackendHandler(ChannelHandlerContext inbound, TcpClient client) {
            super(client);
            client.setAutoRead(false);
            this.inbound = inbound;
        }

        @Override
        public void channelActive(ChannelHandlerContext outbound) throws Exception {
            super.channelActive(outbound);
            client.connectStatus(true);
            outbound.read();
            outbound.write(Unpooled.EMPTY_BUFFER);
        }

        @Override
        public void channelRead(ChannelHandlerContext outbound, Object msg) throws Exception {
            super.channelRead(outbound, msg);
            if (!inbound.channel().isActive()) {
                log.warn("inbound is disconnected");
            }
            inbound.channel().writeAndFlush(msg).addListener(f -> {
                if (f.isSuccess()) {
                    log.debug("inbound write ok");
                    outbound.channel().read();
                } else {
                    log.debug("inbound write ok");
                    inbound.channel().close();
                }
            });
        }

        @Override
        public void channelInactive(ChannelHandlerContext outbound) throws Exception {
            super.channelInactive(outbound);
            client.connectStatus(false);
            closeOnFlushed(inbound.channel());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext outbound, Throwable cause) throws Exception {
            super.exceptionCaught(outbound, cause);
            closeOnFlushed(outbound.channel());
        }
    }

    /**
     * Closes the specified channel after all queued write requests are flushed.
     */
    public static void closeOnFlushed(Channel ch) {
        require(ch);

        log.debug("closeOnFlushed");
//        if (ch.isActive()) {
//            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
//        }
    }

    private Function<InetSocketAddress, InetSocketAddress> proxyRule;
    private TcpClient outbound;

    public ProxyFrontendHandler(TcpServer<T> server, Function<InetSocketAddress, InetSocketAddress> proxyRule) {
        super(server);
        server.setAutoRead(false);
        require(proxyRule);
        this.proxyRule = proxyRule;
    }

    @Override
    public void channelActive(ChannelHandlerContext inbound) throws Exception {
        super.channelActive(inbound);
        InetSocketAddress proxyEndpoint = proxyRule.apply((InetSocketAddress) inbound.channel().remoteAddress());
        log.debug("connect to backend {}", proxyEndpoint);
        outbound = new TcpClient(proxyEndpoint);
        outbound.setChannelHandlers(new ProxyBackendHandler(inbound, outbound));
        outbound.connect(true);
        log.debug("connect to backend {} ok", proxyEndpoint);
        server.addClient(SessionId.empty, server.createClient(inbound));
        inbound.channel().read();
    }

    @Override
    public void channelRead(ChannelHandlerContext inbound, Object msg) throws Exception {
        super.channelRead(inbound, msg);
        if (!outbound.isConnected()) {
            log.warn("outbound is disconnected");
            return;
        }
        outbound.channel.writeAndFlush(msg).addListener(f -> {
            if (f.isSuccess()) {
                log.debug("outbound write ok");
                inbound.channel().read();
            } else {
                log.debug("outbound write error");
                outbound.channel.close();
            }
        });
    }

    @Override
    public void channelInactive(ChannelHandlerContext inbound) throws Exception {
        super.channelInactive(inbound);
        if (outbound.isConnected()) {
            log.debug("disconnect from backend {}", outbound.getServerEndpoint());
            outbound.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext inbound, Throwable cause) throws Exception {
        super.exceptionCaught(inbound, cause);
        closeOnFlushed(inbound.channel());
    }
}
