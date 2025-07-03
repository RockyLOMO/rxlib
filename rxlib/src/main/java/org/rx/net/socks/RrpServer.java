package org.rx.net.socks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.core.Linq;
import org.rx.exception.InvalidException;
import org.rx.io.Serializer;
import org.rx.net.Sockets;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Extends.eq;
import static org.rx.core.Extends.tryClose;

@Slf4j
@RequiredArgsConstructor
public class RrpServer extends Disposable {
    @RequiredArgsConstructor
    static class RpClientProxy extends Disposable {
        final RrpConfig.Proxy p;
        final ServerBootstrap remoteServer;
        final Channel remoteServerChannel;
        final Map<String, Channel> remoteClients = new ConcurrentHashMap<>();

        @Override
        protected void freeObjects() throws Throwable {
            Sockets.closeOnFlushed(remoteServerChannel);
            Sockets.closeBootstrap(remoteServer);
        }
    }

    @RequiredArgsConstructor
    static class RpClient extends Disposable {
        final Channel clientChannel;
        final Map<Integer, RpClientProxy> proxyMap = new ConcurrentHashMap<>();

        @Override
        protected void freeObjects() throws Throwable {
            for (RpClientProxy v : proxyMap.values()) {
                v.close();
            }
            Sockets.closeOnFlushed(clientChannel);
        }

        public RpClientProxy getProxyCtx(int remotePort) {
            RpClientProxy ctx = proxyMap.get(remotePort);
            if (ctx == null) {
                throw new InvalidException("ProxyCtx {} not exist", remotePort);
            }
            return ctx;
        }
    }

    @RequiredArgsConstructor
    static class RemoteServerHandler extends ChannelInboundHandlerAdapter {
        final RpClient rpClient;
        final int remotePort;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            Channel inbound = ctx.channel();
            rpClient.getProxyCtx(remotePort).remoteClients.put(inbound.id().asShortText(), inbound);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Channel inbound = ctx.channel();
            Channel outbound = rpClient.clientChannel;
            //step3
            ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer();
            try {
                buf.writeByte(RrpConfig.ACTION_FORWARD);
                buf.writeInt(remotePort);
                byte[] bytes = inbound.id().asShortText().getBytes(StandardCharsets.US_ASCII);
                buf.writeInt(bytes.length);
                buf.writeBytes(bytes);
                outbound.write(buf);
            } finally {
                buf.release();
            }
            outbound.writeAndFlush(msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            Channel inbound = ctx.channel();
            rpClient.getProxyCtx(remotePort).remoteClients.remove(inbound.id().asShortText());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Channel inbound = ctx.channel();
            Channel outbound = rpClient.clientChannel;
            log.warn("RELAY {} => {}[{}] thrown", inbound.remoteAddress(), outbound.localAddress(), outbound.remoteAddress(), cause);
            Sockets.closeOnFlushed(inbound);
        }
    }

    @RequiredArgsConstructor
    static class ServerHandler extends ChannelInboundHandlerAdapter {
        final RrpServer server;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            Channel clientChannel = ctx.channel();
            server.clients.put(clientChannel, new RpClient(clientChannel));
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Channel clientChannel = ctx.channel();
            ByteBuf buf = (ByteBuf) msg;
            byte action = buf.readByte();
            if (action == RrpConfig.ACTION_REGISTER) {
                //step2
                int tokenLen = buf.readInt();
                String token = buf.readCharSequence(tokenLen, StandardCharsets.US_ASCII).toString();
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
                String idStr = buf.readCharSequence(idLen, StandardCharsets.US_ASCII).toString();
                server.clients.get(clientChannel).getProxyCtx(remotePort).remoteClients.get(idStr).writeAndFlush(buf);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            Channel clientChannel = ctx.channel();
            tryClose(server.clients.remove(clientChannel));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Channel clientChannel = ctx.channel();
            log.warn("RELAY {} => ALL thrown", clientChannel.remoteAddress(), cause);
            tryClose(server.clients.remove(clientChannel));
        }
    }

    final RrpConfig config;
    final ServerBootstrap bootstrap;
    final Map<Channel, RpClient> clients = new ConcurrentHashMap<>();
    Channel serverChannel;

    public RrpServer(@NonNull RrpConfig config) {
        this.config = config;
        bootstrap = Sockets.serverBootstrap(channel -> channel.pipeline().addLast(new ServerHandler(this)));
        serverChannel = bootstrap.bind(config.getBindPort()).addListener(Sockets.logBind(config.getBindPort())).channel();
    }

    @Override
    protected void freeObjects() throws Throwable {
        Sockets.closeOnFlushed(serverChannel);
        Sockets.closeBootstrap(bootstrap);
    }

    public void register(@NonNull Channel clientChannel, @NonNull List<RrpConfig.Proxy> pList) {
        RpClient rpClient = clients.get(clientChannel);
        if (rpClient == null) {
            throw new InvalidException("Client {} not fund", clientChannel.id());
        }

        for (RrpConfig.Proxy rp : pList) {
            String name = rp.getName();
            if (Linq.from(clients.values()).selectMany(p -> p.proxyMap.values()).any(p -> eq(p.p.getName(), name))) {
                log.warn("Proxy name {} exist", name);
                continue;
            }
            int remotePort = rp.getRemotePort();
            if (Linq.from(clients.values()).selectMany(p -> p.proxyMap.values()).any(p -> p.p.getRemotePort() == remotePort)) {
                log.warn("Proxy remotePort {} exist", remotePort);
                continue;
            }

            ServerBootstrap remoteBootstrap = Sockets.serverBootstrap(channel -> channel.pipeline()
                    .addLast(new RemoteServerHandler(rpClient, remotePort)));
            rpClient.proxyMap.put(remotePort, new RpClientProxy(rp, remoteBootstrap,
                    remoteBootstrap.bind(remotePort).addListener(Sockets.logBind(remotePort)).channel()));
        }
    }
}
