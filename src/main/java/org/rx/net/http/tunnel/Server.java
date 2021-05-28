package org.rx.net.http.tunnel;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Tasks;
import org.rx.io.HybridStream;
import org.rx.io.IOStream;
import org.rx.io.Bytes;
import org.rx.net.Sockets;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.rx.core.App.quietly;

@Slf4j
public class Server {
    @RequiredArgsConstructor
    class SocksContext {
        private final String appName;
        private final String inboundSocksId;
        private final LinkedBlockingQueue<IOStream<?, ?>> inboundQueue = new LinkedBlockingQueue<>();
        private volatile boolean outboundReady;
        private volatile Channel outboundChannel;
        private final LinkedBlockingQueue<MultipartFile> outboundQueue = new LinkedBlockingQueue<>();

        public boolean isBackendActive() {
            return outboundReady && outboundChannel.isActive();
        }

        @SneakyThrows
        public void prepareBackend() {
            synchronized (outboundQueue) {
                MultipartFile stream;
                while ((stream = outboundQueue.poll()) != null) {
                    ByteBuf buf = Bytes.copyInputStream(stream.getInputStream());
                    try {
                        outboundChannel.write(buf);
                    } finally {
                        buf.release();
                    }
                }
                outboundChannel.flush();
                outboundReady = true;
            }
        }

        @SneakyThrows
        public void flushBackend(MultipartFile stream) {
            synchronized (outboundQueue) {
                if (!isBackendActive()) {
                    outboundQueue.offer(stream);
                    return;
                }
            }

            ByteBuf buf = Bytes.copyInputStream(stream.getInputStream());
            try {
                outboundChannel.writeAndFlush(buf);
            } finally {
                buf.release();
            }
        }

        public void closeBackend() {
            outboundReady = false;
            Tasks.scheduleOnce(() -> {
                Map<String, SocksContext> contextMap = holds.get(appName);
                if (contextMap == null) {
                    return;
                }
                contextMap.remove(inboundSocksId);
            }, timeWaitSeconds * 1000L);
        }
    }

    @RequiredArgsConstructor
    static class BackendHandler extends ChannelInboundHandlerAdapter {
        private final SocksContext socksContext;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            socksContext.prepareBackend();
        }

        @SneakyThrows
        @Override
        public void channelRead(ChannelHandlerContext outbound, Object msg) {
            HybridStream stream = new HybridStream();
            ByteBuf buf = (ByteBuf) msg;
            try {
                buf.readBytes(stream.getWriter(), buf.readableBytes());
            } finally {
                buf.release();
            }
            socksContext.inboundQueue.offer(stream);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            socksContext.closeBackend();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("BackendHandler {}", ctx.channel().remoteAddress(), cause);
            Sockets.closeOnFlushed(ctx.channel());
        }
    }

    public static final String GROUP_NAME = "TUNNEL";

    private int timeWaitSeconds = 20;
    //appName,socksId
    private final Map<String, Map<String, SocksContext>> holds = new ConcurrentHashMap<>();

    private SocksContext getSocksContext(SendPack pack) {
        return holds.computeIfAbsent(pack.getAppName(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(pack.getSocksId(), k -> {
                    SocksContext socksContext = new SocksContext(pack.getAppName(), pack.getSocksId());
                    socksContext.outboundChannel = Sockets.bootstrap(GROUP_NAME, channel -> channel.pipeline().addLast(new BackendHandler(socksContext))).connect(pack.getRemoteEndpoint()).channel();
                    return socksContext;
                });
    }

    public void frontendOffer(SendPack pack) {
        getSocksContext(pack).flushBackend(pack.getBinary());
    }

    public ReceivePack frontendPoll(SendPack pack) {
        ReceivePack receivePack = new ReceivePack(pack.getSocksId());
        SocksContext socksContext = getSocksContext(pack);
        socksContext.inboundQueue.drainTo(receivePack.getBinaries());
        if (receivePack.getBinaries().isEmpty()) {
            IOStream<?, ?> stream = quietly(() -> socksContext.inboundQueue.poll(timeWaitSeconds, TimeUnit.SECONDS));
            if (stream != null) {
                receivePack.getBinaries().add(stream);
                socksContext.inboundQueue.drainTo(receivePack.getBinaries());
            }
        }
        return receivePack;
    }
}
