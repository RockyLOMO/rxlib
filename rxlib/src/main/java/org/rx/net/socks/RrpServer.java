package org.rx.net.socks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.core.Linq;
import org.rx.exception.InvalidException;
import org.rx.io.Serializer;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;
import org.rx.net.TransportFlags;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Extends.eq;
import static org.rx.core.Extends.tryClose;
import static org.rx.net.socks.RrpConfig.*;

@Slf4j
@RequiredArgsConstructor
public class RrpServer extends Disposable {
    @RequiredArgsConstructor
    static class RpClientProxy extends Disposable {
        final RrpConfig.Proxy p;
        final ServerBootstrap remoteServer;
        final Map<String, Channel> remoteClients = new ConcurrentHashMap<>();
        Channel remoteServerChannel;

        @Override
        protected void freeObjects() throws Throwable {
            Sockets.closeOnFlushed(remoteServerChannel);
            Sockets.closeBootstrap(remoteServer);
            remoteClients.clear();
        }
    }

    @RequiredArgsConstructor
    static class RpClient extends Disposable {
        final Channel clientChannel;
        final Map<Integer, RpClientProxy> proxyMap = new ConcurrentHashMap<>();

        @Override
        protected void freeObjects() throws Throwable {
            Sockets.closeOnFlushed(clientChannel);
            for (RpClientProxy v : proxyMap.values()) {
                v.close();
            }
            proxyMap.clear();
        }

        public RpClientProxy getProxyCtx(int remotePort) {
            RpClientProxy ctx = proxyMap.get(remotePort);
            if (ctx == null) {
                throw new InvalidException("ProxyCtx {} not exist", remotePort);
            }
            return ctx;
        }
    }

    @ChannelHandler.Sharable
    static class RemoteServerHandler extends ChannelInboundHandlerAdapter {
        static final RemoteServerHandler DEFAULT = new RemoteServerHandler();

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            Channel inbound = ctx.channel();
            RpClientProxy rpClientProxy = Sockets.getAttr(inbound, ATTR_SVR_PROXY);
            rpClientProxy.remoteClients.put(inbound.id().asShortText(), inbound);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Channel inbound = ctx.channel();
            RpClient rpClient = Sockets.getAttr(inbound, ATTR_SVR_CLI);
            RpClientProxy rpClientProxy = Sockets.getAttr(inbound, ATTR_SVR_PROXY);
            Channel outbound = rpClient.clientChannel;
            //step3
            ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer();
            buf.writeByte(RrpConfig.ACTION_FORWARD);
            buf.writeInt(rpClientProxy.p.remotePort);
            String channelId = inbound.id().asShortText();
            byte[] bytes = channelId.getBytes(StandardCharsets.US_ASCII);
            buf.writeInt(bytes.length);
            buf.writeBytes(bytes);

            outbound.writeAndFlush(Unpooled.wrappedBuffer(buf, (ByteBuf) msg));
            log.debug("RrpServer step3 {}({}) -> clientChannel", rpClient.clientChannel, channelId);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            Channel inbound = ctx.channel();
            RpClient rpClient = Sockets.getAttr(inbound, ATTR_SVR_CLI);
            RpClientProxy rpClientProxy = Sockets.getAttr(inbound, ATTR_SVR_PROXY);
            Channel outbound = rpClient.clientChannel;
            //step7 remoteClose
            ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer();
            buf.writeByte(RrpConfig.ACTION_SYNC_CLOSE);
            buf.writeInt(rpClientProxy.p.remotePort);
            String channelId = inbound.id().asShortText();
            byte[] bytes = channelId.getBytes(StandardCharsets.US_ASCII);
            buf.writeInt(bytes.length);
            buf.writeBytes(bytes);
            outbound.writeAndFlush(buf);

            rpClientProxy.remoteClients.remove(channelId);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Channel inbound = ctx.channel();
            RpClient rpClient = Sockets.getAttr(inbound, ATTR_SVR_CLI);
            Channel outbound = rpClient.clientChannel;
            log.warn("RELAY {} => {}[{}] thrown", inbound.remoteAddress(), outbound.localAddress(), outbound.remoteAddress(), cause);
            Sockets.closeOnFlushed(inbound);
        }
    }

    @ChannelHandler.Sharable
    static class ServerHandler extends ChannelInboundHandlerAdapter {
        static final ServerHandler DEFAULT = new ServerHandler();

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            Channel clientChannel = ctx.channel();
            RrpServer server = Sockets.getAttr(clientChannel, ATTR_SVR);
            server.clients.put(clientChannel, new RpClient(clientChannel));
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Channel clientChannel = ctx.channel();
            RrpServer server = Sockets.getAttr(clientChannel, ATTR_SVR);
            ByteBuf buf = (ByteBuf) msg;
            byte action = buf.readByte();
            if (action == RrpConfig.ACTION_REGISTER) {
                //step2
                int tokenLen = buf.readInt();
                String token = tokenLen > 0 ? buf.readCharSequence(tokenLen, StandardCharsets.US_ASCII).toString() : null;
                if (!eq(token, server.config.getToken())) {
                    log.warn("Invalid token {}", token);
                    clientChannel.close();
                    return;
                }
                int len = buf.readInt();
                byte[] data = new byte[len];
                buf.readBytes(data, 0, len);
                List<RrpConfig.Proxy> pList = Serializer.DEFAULT.deserializeFromBytes(data);
                server.register(clientChannel, pList);
            } else if (action == RrpConfig.ACTION_FORWARD) {
                //step6
                int remotePort = buf.readInt();
                int idLen = buf.readInt();
                String channelId = buf.readCharSequence(idLen, StandardCharsets.US_ASCII).toString();
                Channel remoteChannel = server.clients.get(clientChannel).getProxyCtx(remotePort).remoteClients.get(channelId);
                if (remoteChannel != null) {
                    remoteChannel.writeAndFlush(buf);
                }
                log.debug("RrpServer step6 {}({}) clientChannel -> {}", clientChannel, channelId, remoteChannel);
            }
            else if (action == RrpConfig.ACTION_SYNC_CLOSE){
                //step10
                int remotePort = buf.readInt();
                int idLen = buf.readInt();
                String channelId = buf.readCharSequence(idLen, StandardCharsets.US_ASCII).toString();
                Channel remoteChannel = server.clients.get(clientChannel).getProxyCtx(remotePort).remoteClients.get(channelId);
                Sockets.closeOnFlushed(remoteChannel);
                log.debug("RrpServer step10 {}({}) clientChannel -> {}", clientChannel, channelId, remoteChannel);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            Channel clientChannel = ctx.channel();
            RrpServer server = Sockets.getAttr(clientChannel, ATTR_SVR);
            tryClose(server.clients.remove(clientChannel));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Channel clientChannel = ctx.channel();
            log.warn("RELAY {} => ALL thrown", clientChannel.remoteAddress(), cause);
            Sockets.closeOnFlushed(clientChannel);
        }
    }

    final RrpConfig config;
    final ServerBootstrap bootstrap;
    final Map<Channel, RpClient> clients = new ConcurrentHashMap<>();
    Channel serverChannel;

    public RrpServer(@NonNull RrpConfig config) {
        this.config = config;
        config.setTransportFlags(TransportFlags.SERVER_CIPHER_BOTH.flags(TransportFlags.SERVER_HTTP_PSEUDO_BOTH));
//        config.setTransportFlags(TransportFlags.SERVER_HTTP_PSEUDO_BOTH.flags());
        bootstrap = Sockets.serverBootstrap(channel -> {
                    Sockets.addServerHandler(channel, config).pipeline()
//                        .addLast(Sockets.intLengthFieldDecoder(), Sockets.INT_LENGTH_FIELD_ENCODER)
//                            .addLast(new HttpPseudoHeaderDecoder(), HttpPseudoHeaderEncoder.DEFAULT)
                            .addLast(ServerHandler.DEFAULT);
                    Sockets.dumpPipeline("RrpSvr", channel);
                })
                .attr(ATTR_SVR, this)
                .attr(SocketConfig.ATTR_PSEUDO_SVR, true);
        serverChannel = bootstrap.bind(config.getBindPort()).addListener(Sockets.logBind(config.getBindPort())).channel();
    }

    @Override
    protected void freeObjects() throws Throwable {
        Sockets.closeOnFlushed(serverChannel);
        Sockets.closeBootstrap(bootstrap);
    }

    void register(@NonNull Channel clientChannel, @NonNull List<RrpConfig.Proxy> pList) {
        RpClient rpClient = clients.get(clientChannel);
        if (rpClient == null) {
            throw new InvalidException("Client {} not fund", clientChannel.id());
        }

        for (RrpConfig.Proxy rp : pList) {
            String name = rp.getName();
            if (name == null) {
                log.warn("RrpServer Proxy empty name");
                continue;
            }
            if (Linq.from(clients.values()).selectMany(p -> p.proxyMap.values()).any(p -> eq(p.p.getName(), name))) {
                log.warn("RrpServer Proxy name {} exist", name);
                continue;
            }
            int remotePort = rp.getRemotePort();
            if (Linq.from(clients.values()).selectMany(p -> p.proxyMap.values()).any(p -> p.p.getRemotePort() == remotePort)) {
                log.warn("RrpServer Proxy remotePort {} exist", remotePort);
                continue;
            }

            ServerBootstrap remoteBootstrap = Sockets.serverBootstrap(channel -> channel.pipeline()
                    .addLast(RemoteServerHandler.DEFAULT));
            RpClientProxy rpClientProxy = new RpClientProxy(rp, remoteBootstrap);
            rpClientProxy.remoteServerChannel = remoteBootstrap
                    .attr(ATTR_SVR_CLI, rpClient)
                    .attr(ATTR_SVR_PROXY, rpClientProxy).bind(remotePort).addListener(Sockets.logBind(remotePort)).channel();
            rpClient.proxyMap.put(remotePort, rpClientProxy);
            log.debug("RrpServer step2 {} remote Tcp bind {}", clientChannel, remotePort);
        }
    }
}
