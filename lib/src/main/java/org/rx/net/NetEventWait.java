package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.AttributeKey;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.*;
import org.rx.exception.TraceHandler;
import org.rx.io.Bytes;
import org.rx.util.function.PredicateFunc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.rx.core.Constants.TIMEOUT_INFINITE;

@Slf4j
public final class NetEventWait extends Disposable implements WaitHandle {
    @ChannelHandler.Sharable
    static class Handler extends SimpleChannelInboundHandler<DatagramPacket> {
        static final Handler DEFAULT = new Handler();

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
            ByteBuf buf = packet.content();
            int multicastId = buf.readInt();
            String mcId = Integer.toHexString(multicastId);
            String group = buf.toString(StandardCharsets.UTF_8);
            NetEventWait w = ctx.channel().attr(REF).get();
            if (!Strings.hashEquals(w.group, group)) {
                log.info("multicast skip {} ~ {}[{}]", w.idString, group, mcId);
                return;
            }

            log.info("multicast signal {} <- {}", w.idString, mcId);
            w.wait.set();
        }
    }

    static final AttributeKey<NetEventWait> REF = AttributeKey.valueOf("Ref");
    final String group;
    final String idString;
    final InetSocketAddress multiCastEndpoint;
    final NioDatagramChannel channel;
    final ResetEventWait wait = new ResetEventWait();
    @Setter
    int multicastCount = 1;

    public NetEventWait(String group) {
        this(group, new InetSocketAddress("239.0.0.2", 80));
    }

    public NetEventWait(@NonNull String group, @NonNull InetSocketAddress multiCastEndpoint) {
        this.group = group;
        idString = group + "@" + Integer.toHexString(hashCode());
        this.multiCastEndpoint = multiCastEndpoint;
        channel = (NioDatagramChannel) Sockets.udpBootstrap(MemoryMode.LOW, true, c -> {
                    c.attr(REF).set(this);
                    c.pipeline().addLast(Handler.DEFAULT);
                })
                .bind(multiCastEndpoint.getPort()).addListener((ChannelFutureListener) f -> {
                    if (!f.isSuccess()) {
                        TraceHandler.INSTANCE.log("multicast bind error {}", idString, f.cause());
                        return;
                    }
                    NioDatagramChannel c = (NioDatagramChannel) f.channel();
                    c.joinGroup(multiCastEndpoint.getAddress());
                    log.info("multicast join {} -> {}", idString, multiCastEndpoint);
                }).channel();
    }

    @Override
    protected void freeObjects() {
        channel.close();
    }

    public boolean await() {
        return await(TIMEOUT_INFINITE);
    }

    @Override
    public boolean await(long timeoutMillis) {
        return await(timeoutMillis, 0, null);
    }

    public boolean await(long timeoutMillis, long intervalMillis, PredicateFunc<NetEventWait> isTrue) {
        if (intervalMillis > 0 && isTrue != null) {
            long deadline = System.nanoTime() + timeoutMillis * 1000000;
            System.out.println(deadline);
            synchronized (wait) {
                wait.reset();
                do {
                    if (wait.waitOne(timeoutMillis) || isTrue.test(this)) {
                        return true;
                    }
                } while (System.nanoTime() < deadline);
                return false;
            }
        }

        synchronized (wait) {
            wait.reset();
            return wait.waitOne(timeoutMillis);
        }
    }

    @Override
    public void signalAll() {
        wait.set();

        ByteBuf buf = Bytes.directBuffer();
        buf.writeInt(hashCode());
        buf.writeCharSequence(group, StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(buf, multiCastEndpoint);
        int last = multicastCount - 1;
        for (int i = 0; i < multicastCount; i++) {
            if (i < last) {
                packet.retain();
            }
            channel.writeAndFlush(packet);
        }
    }
}
