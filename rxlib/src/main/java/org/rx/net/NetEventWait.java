package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.AttributeKey;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.ConcurrentWeakMap;
import org.rx.core.*;
import org.rx.exception.TraceHandler;
import org.rx.io.Bytes;
import org.rx.net.http.HttpClient;
import org.rx.util.function.PredicateFunc;
import org.rx.util.function.TripleFunc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Constants.TIMEOUT_INFINITE;
import static org.rx.core.Extends.eachQuietly;

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

    public static final String DEFAULT_URL_SUFFIX = "/mx/health?x=0";
    public static TripleFunc<InetSocketAddress, String, Set<String>> HTTP_SIGNAL_HANDLER;
    static final AttributeKey<Set<NetEventWait>> REF = AttributeKey.valueOf("Ref");
    static final Map<InetSocketAddress, NioDatagramChannel> channels = new ConcurrentHashMap<>(8);

    public static void appendDefaultUrlSuffix(Set<String> set) {
        List<String> list = Linq.from(set).select(u -> u + DEFAULT_URL_SUFFIX).toList();
        set.clear();
        set.addAll(list);
    }

    public static void multicastLocal(InetSocketAddress multicastEndpoint, String group, int mcId) {
        NioDatagramChannel channel = channels.get(multicastEndpoint);
        if (channel == null) {
            return;
        }
        multicastLocal(channel, group, mcId);
    }

    static void multicastLocal(Channel channel, String group, int mcId) {
        for (NetEventWait w : channel.attr(REF).get()) {
            if (!Strings.hashEquals(w.group, group)) {
                log.info("multicast skip {} ~ {}@{}", w.idString, group, Integer.toHexString(mcId));
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
                    c.attr(REF).set(Collections.newSetFromMap(new ConcurrentWeakMap<>(true)));
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
        return await(timeoutMillis, TIMEOUT_INFINITE, null);
    }

    public boolean await(long timeoutMillis, long intervalMillis, PredicateFunc<Integer> isTrue) {
        if (intervalMillis >= 0 && isTrue != null) {
            long deadline = System.nanoTime() + timeoutMillis * Constants.NANO_TO_MILLIS;
            synchronized (wait) {
                wait.reset();
                int i = 1;
                do {
                    if (wait.waitOne(intervalMillis) || isTrue.test(i)) {
                        return true;
                    }
                    i++;
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
        signalAll(HTTP_SIGNAL_HANDLER != null);
    }

    public void signalAll(boolean httpSignal) {
        synchronized (wait) {
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

            if (httpSignal && HTTP_SIGNAL_HANDLER != null) {
                Set<String> urls = HTTP_SIGNAL_HANDLER.apply(multicastEndpoint, group);
                if (CollectionUtils.isEmpty(urls)) {
                    return;
                }
                Tasks.run(() -> {
                    HttpClient client = new HttpClient();
                    Map<String, Object> params = new HashMap<>();
                    params.put("multicast", Sockets.toString(multicastEndpoint));
                    params.put("group", group);
                    params.put("mcId", mcId);
                    eachQuietly(urls, u -> client.get(HttpClient.buildUrl(u, params)));
                });
            }
        }
    }
}
