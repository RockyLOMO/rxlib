package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.AttributeKey;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.WeakIdentityMap;
import org.rx.core.*;
import org.rx.exception.TraceHandler;
import org.rx.io.Bytes;
import org.rx.util.function.PredicateFunc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Constants.TIMEOUT_INFINITE;

@Slf4j
public final class NetEventWait extends Disposable implements WaitHandle {
    @ChannelHandler.Sharable
    static class Handler extends SimpleChannelInboundHandler<DatagramPacket> {
        static final Handler DEFAULT = new Handler();

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
            ByteBuf buf = packet.content();
            int mcId = buf.readInt();
            String group = buf.toString(StandardCharsets.UTF_8);
            multicastLocal(ctx.channel(), group, mcId);
        }
    }

    static final AttributeKey<Set<NetEventWait>> REF = AttributeKey.valueOf("Ref");
    static final Map<InetSocketAddress, NioDatagramChannel> channels = new ConcurrentHashMap<>(8);

    static void multicastLocal(Channel channel, String group, int mcId) {
        for (NetEventWait w : channel.attr(REF).get()) {
            if (!Strings.hashEquals(w.group, group)) {
                log.info("multicast skip {} ~ {}[{}]", w.idString, group, Integer.toHexString(mcId));
                continue;
            }

            log.info("multicast signal {} <- {}", w.idString, Integer.toHexString(mcId));
            w.wait.set();
        }
    }

    final String group;
    final String idString;
    final InetSocketAddress multicastEndpoint;
    final NioDatagramChannel channel;
    final ResetEventWait wait = new ResetEventWait();
    @Setter
    int multicastCount = 1;

    public NetEventWait(String group) {
        this(group, new InetSocketAddress("239.0.0.2", 80));
    }

    public NetEventWait(@NonNull String group, @NonNull InetSocketAddress multicastEndpoint) {
        this.group = group;
        this.multicastEndpoint = multicastEndpoint;
        idString = group + "@" + Integer.toHexString(hashCode());
        channel = channels.computeIfAbsent(multicastEndpoint, k -> (NioDatagramChannel) Sockets.udpBootstrap(MemoryMode.LOW, true, c -> {
                    c.attr(REF).set(Collections.newSetFromMap(new WeakIdentityMap<>()));
                    c.pipeline().addLast(Handler.DEFAULT);
                })
                .bind(multicastEndpoint.getPort()).addListener((ChannelFutureListener) f -> {
                    if (!f.isSuccess()) {
                        TraceHandler.INSTANCE.log("multicast bind error {}", idString, f.cause());
                        return;
                    }
                    NioDatagramChannel c = (NioDatagramChannel) f.channel();
                    c.joinGroup(multicastEndpoint.getAddress());
                    log.info("multicast join {} -> {}", idString, multicastEndpoint);
                }).syncUninterruptibly().channel());
        Set<NetEventWait> refs = channel.attr(REF).get();
        if (refs != null) {
            log.info("multicast ref {}", idString);
            refs.add(this);
        }
    }

    @Override
    protected void freeObjects() {
        channel.attr(REF).get().remove(this);
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
//        wait.set();
        int mcId = hashCode();
        multicastLocal(channel, group, mcId);

        ByteBuf buf = Bytes.directBuffer();
        buf.writeInt(mcId);
        buf.writeCharSequence(group, StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(buf, multicastEndpoint);
        int last = multicastCount - 1;
        for (int i = 0; i < multicastCount; i++) {
            if (i < last) {
                packet.retain();
            }
            channel.writeAndFlush(packet);
        }
    }
}
