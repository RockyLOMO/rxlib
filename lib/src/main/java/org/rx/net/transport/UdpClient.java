package org.rx.net.transport;

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
import org.rx.exception.TraceHandler;
import org.rx.io.MemoryStream;
import org.rx.io.Serializer;
import org.rx.net.Sockets;
import org.rx.net.transport.protocol.Ack;
import org.rx.net.transport.protocol.AckSync;
import org.rx.net.transport.protocol.UdpMessage;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import static org.rx.core.Extends.*;

@Slf4j
public class UdpClient implements EventTarget<UdpClient> {
    @RequiredArgsConstructor
    static class Context {
        public final UdpMessage message;
        public final ResetEventWait syncRoot = new ResetEventWait();

        public int resend;
        public Future<?> future;
    }

    static final AttributeKey<UdpClient> OWNER = AttributeKey.valueOf("UdpClient");
    static final Handler HANDLER = new Handler();
    static final IdGenerator generator = new IdGenerator();
    static final Map<Integer, Context> queue = new ConcurrentHashMap<>();
    static final Set<Integer> record = ConcurrentHashMap.newKeySet();

    @ChannelHandler.Sharable
    static class Handler extends SimpleChannelInboundHandler<DatagramPacket> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) throws Exception {
            UdpClient client = ctx.channel().attr(OWNER).get();
            Object pack = Serializer.DEFAULT.deserialize(new MemoryStream(msg.content().retain(), false));
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
                client.sendAck(msg.sender(), message);
                return;
            }

            if (message.ack == AckSync.SEMI) {
                client.sendAck(msg.sender(), message);
            }
            client.raiseEventAsync(client.onReceive, new NEventArgs<>(message)).whenComplete((r, e) -> {
                if (e != null) {
                    return;
                }
                if (message.ack == AckSync.FULL) {
                    client.sendAck(msg.sender(), message);
                }
            });
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            TraceHandler.INSTANCE.log(cause);
        }
    }

    public final Delegate<UdpClient, NEventArgs<UdpMessage>> onReceive = Delegate.create();
    final Bootstrap bootstrap;
    final Channel channel;
    @Getter
    @Setter
    int waitAckTimeoutMillis = 15 * 1000;
    @Getter
    @Setter
    boolean fullSync;
    @Getter
    @Setter
    int maxResend = 2;

    public UdpClient(int bindPort) {
        bootstrap = Sockets.udpBootstrap(null, ch -> ch.pipeline().addLast(HANDLER));
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

    void sendAck(InetSocketAddress remoteAddress, UdpMessage message) {
        record.add(message.id);
        Tasks.setTimeout(() -> record.remove(message.id), message.alive);
        channel.writeAndFlush(serialize(remoteAddress, new Ack(message.id)));
    }

    @SneakyThrows
    public <T> ChannelFuture sendAsync(InetSocketAddress remoteAddress, T packet) {
        return sendAsync(remoteAddress, packet, waitAckTimeoutMillis, fullSync);
    }

    public <T> ChannelFuture sendAsync(InetSocketAddress remoteAddress, T packet, int waitAckTimeoutMillis, boolean fullSync) throws TimeoutException {
        AckSync as = fullSync ? AckSync.FULL : waitAckTimeoutMillis > 0 ? AckSync.SEMI : AckSync.NONE;
        UdpMessage message = new UdpMessage(generator.increment(), as, waitAckTimeoutMillis, remoteAddress, packet);

        if (message.ack != AckSync.NONE) {
            Context ctx = new Context(message);
            queue.put(message.id, ctx);
            ctx.future = Tasks.setTimeout(() -> {
                channel.writeAndFlush(serialize(remoteAddress, message));
                circuitContinue(++ctx.resend <= maxResend);
            }, waitAckTimeoutMillis / maxResend);
        }
        ChannelFuture future = channel.writeAndFlush(serialize(remoteAddress, message));
        if (message.ack != AckSync.NONE) {
            Context ctx = queue.get(message.id);
            if (ctx == null) {
                //已ack
                return future;
            }
            try {
                ctx.syncRoot.waitOne(waitAckTimeoutMillis);
            } finally {
                queue.remove(message.id);
            }
        }
        return future;
    }
}
