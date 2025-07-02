package org.rx.net.socks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.core.Linq;
import org.rx.exception.InvalidException;
import org.rx.io.Serializer;
import org.rx.net.Sockets;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Extends.eq;

@Slf4j
@RequiredArgsConstructor
public class RrpServer extends Disposable {
    @RequiredArgsConstructor
    static class PortCtx {
        final RrpConfig.Proxy p;
        final ServerBootstrap remoteServer;
    }

    @RequiredArgsConstructor
    static class ClientCtx {
        final Channel clientChannel;
        final Map<Integer, PortCtx> portCtxMap = new ConcurrentHashMap<>();
    }

    @RequiredArgsConstructor
    static class RemoteServerHandler extends ChannelInboundHandlerAdapter {
        final ClientCtx clientCtx;
        final int remotePort;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Channel outbound = clientCtx.clientChannel;
            ByteBuf buf = ByteBufAllocator.DEFAULT.bu
            outbound.write(remotePort);
            outbound.writeAndFlush(msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            clientCtx.portCtxMap.remove(remotePort);
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Channel inbound = ctx.channel();
            Channel outbound = clientCtx.clientChannel;
            log.warn("RELAY {} => {}[{}] thrown", inbound.remoteAddress(), outbound.localAddress(), outbound.remoteAddress(), cause);
            Sockets.closeOnFlushed(inbound);
        }
    }

    class ServerHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            Channel clientChannel = ctx.channel();
            clients.put(clientChannel, new ClientCtx(clientChannel));
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            Channel clientChannel = ctx.channel();
            ByteBuf buf = (ByteBuf) msg;
            byte action = buf.readByte();
            if (action == 1) {
                int len = buf.readInt();
                byte[] data = new byte[len];
                buf.readBytes(data, 0, len);
                List<RrpConfig.Proxy> pList = Serializer.DEFAULT.deserializeFromBytes(data);
                register(clientChannel, pList);
                return;
            }

        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            Channel clientChannel = ctx.channel();
            clients.remove(clientChannel);
        }
    }

    final RrpConfig config;
    final ServerBootstrap bootstrap;
    final Map<Channel, ClientCtx> clients = new ConcurrentHashMap<>();
    volatile Channel serverChannel;

    public RrpServer(@NonNull RrpConfig config) {
        this.config = config;
        bootstrap = Sockets.serverBootstrap(channel -> {
            ChannelPipeline pipeline = channel.pipeline();
            pipeline.addLast(new ServerHandler());
        });
        bootstrap.bind(config.getBindPort()).addListeners(Sockets.logBind(config.getBindPort()), (ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                return;
            }
            serverChannel = f.channel();
        });
    }

    @Override
    protected void freeObjects() throws Throwable {
        Sockets.closeBootstrap(bootstrap);
    }

    public void register(@NonNull Channel clientChannel, @NonNull List<RrpConfig.Proxy> pList) {
        ClientCtx clientCtx = clients.get(clientChannel);
        if (clientCtx == null) {
            throw new InvalidException("Client {} not fund", clientChannel.id());
        }

        for (RrpConfig.Proxy rp : pList) {
            String rpName = rp.getName();
            int b = rpName.indexOf(":");
            String token, name;
            if (b == -1) {
                token = null;
                name = rpName;
            } else {
                token = rpName.substring(0, b);
                name = rpName.substring(b + 1);
            }
            if (!eq(token, config.getToken())) {
                throw new InvalidException("Invalid token {}", token);
            }
            if (Linq.from(clients.values()).selectMany(p -> p.portCtxMap.values()).any(p -> eq(p.p.getName(), name))) {
                throw new InvalidException("Proxy {} exist", name);
            }
            rp.setName(name);

            int remotePort = rp.getRemotePort();
            ServerBootstrap remoteBootstrap = Sockets.serverBootstrap(channel -> {
                ChannelPipeline pipeline = channel.pipeline();
                pipeline.addLast(new RemoteServerHandler(clientCtx, remotePort));
            });
            remoteBootstrap.bind(remotePort).addListener(Sockets.logBind(remotePort));
            clientCtx.portCtxMap.put(rp.getRemotePort(), new PortCtx(rp, remoteBootstrap));
        }
    }
}
