package org.rx.net.rpc;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AttributeKey;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.IdGenerator;
import org.rx.core.*;
import org.rx.io.MemoryStream;
import org.rx.io.Serializer;
import org.rx.net.Sockets;
import org.rx.net.rpc.protocol.Ack;
import org.rx.net.rpc.protocol.AckSync;
import org.rx.net.rpc.protocol.UdpMessage;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

import static org.rx.core.App.tryAs;

@Slf4j
public class UdpClient implements EventTarget<UdpClient> {
    @RequiredArgsConstructor
    static class Context {
        public final UdpMessage message;
        public final ManualResetEvent syncRoot = new ManualResetEvent();

        public int resend;
        public ScheduledFuture<?> future;
    }

    static final AttributeKey<UdpClient> OWNER = AttributeKey.valueOf("UdpRpcClient");
    static final Handler HANDLER = new Handler();
    static final IdGenerator generator = new IdGenerator();
    static final Map<Integer, Context> queue = new ConcurrentHashMap<>();
    static final Set<Integer> record = ConcurrentHashMap.newKeySet();

    @ChannelHandler.Sharable
    static class Handler extends SimpleChannelInboundHandler<DatagramPacket> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) throws Exception {
            UdpClient client = ctx.channel().attr(OWNER).get();
            Serializable pack = Serializer.DEFAULT.deserialize(new MemoryStream(msg.content().retain(), false));
            if (tryAs(pack, Ack.class, ack -> {
                Context sr = queue.remove(ack.id);
                if (sr == null) {
                    return;
                }
                sr.syncRoot.set();
                sr.future.cancel(true);
                log.debug("Receive Ack {}", ack.id);
            })) {
                return;
            }

            UdpMessage message = (UdpMessage) pack;
            if (record.contains(message.id)) {
                log.debug("Consumed just send Ack {}", message.id);
                client.sendAck(msg.sender(), new Ack(message.id));
                return;
            }

            if (message.ack == AckSync.SEMI) {
                client.sendAck(msg.sender(), new Ack(message.id));
            }
            client.raiseEventAsync(client.onReceive, new NEventArgs<>(message)).whenComplete((r, e) -> {
                if (e != null) {
                    return;
                }
                if (message.ack == AckSync.FULL) {
                    client.sendAck(msg.sender(), new Ack(message.id));
                }
            });
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            App.log("exceptionCaught", cause);
        }
    }

    public volatile BiConsumer<UdpClient, NEventArgs<UdpMessage>> onReceive;
    final Bootstrap bootstrap;
    final Channel channel;
    @Getter
    @Setter
    long waitAckTimeoutMillis = 15 * 1000;
    @Getter
    @Setter
    boolean fullSync;
    @Getter
    @Setter
    int maxResend = 2;

    public UdpClient(int bindPort) {
        bootstrap = Sockets.udpBootstrap(ch -> ch.pipeline().addLast(HANDLER));
        channel = bootstrap.bind(bindPort).addListener(Sockets.logBind(bindPort)).channel();
        channel.attr(OWNER).set(this);
    }

    DatagramPacket serialize(InetSocketAddress remoteAddress, Object message) {
        MemoryStream stream = new MemoryStream(true);
        Serializer.DEFAULT.serialize(message, stream);
        ByteBuf buf = stream.getBuffer().readerIndex(0);
        if (buf.readableBytes() > 1024) {
            log.warn("Too large packet size 4 udp. {} > 1024", buf.readableBytes());
        }
        return new DatagramPacket(buf, remoteAddress);
    }

    void sendAck(InetSocketAddress remoteAddress, Ack ack) {
        record.add(ack.id);
        Tasks.scheduleOnce(() -> record.remove(ack.id), waitAckTimeoutMillis * 2);
        channel.writeAndFlush(serialize(remoteAddress, ack)).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }

    @SneakyThrows
    public <T extends Serializable> ChannelFuture sendAsync(InetSocketAddress remoteAddress, T packet) {
        return sendAsync(remoteAddress, packet, waitAckTimeoutMillis, fullSync);
    }

    public <T extends Serializable> ChannelFuture sendAsync(InetSocketAddress remoteAddress, T packet, long waitAckTimeoutMillis, boolean fullSync) throws TimeoutException {
        AckSync as = fullSync ? AckSync.FULL : waitAckTimeoutMillis > 0 ? AckSync.SEMI : AckSync.NONE;
        UdpMessage message = new UdpMessage(generator.increment(), as, remoteAddress, packet);

        if (message.ack != AckSync.NONE) {
            Context ctx = new Context(message);
            queue.put(message.id, ctx);
            ctx.future = Tasks.scheduleUntil(() -> channel.writeAndFlush(serialize(remoteAddress, message)).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE),
                    () -> ++ctx.resend > maxResend, waitAckTimeoutMillis / maxResend);
        }
        ChannelFuture future = channel.writeAndFlush(serialize(remoteAddress, message)).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        if (message.ack != AckSync.NONE) {
            try {
                queue.get(message.id).syncRoot.waitOne(waitAckTimeoutMillis);
            } finally {
                queue.remove(message.id);
            }
        }
        return future;
    }
}
