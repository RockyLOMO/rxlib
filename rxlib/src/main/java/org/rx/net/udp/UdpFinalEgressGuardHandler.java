package org.rx.net.udp;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.NetworkFlowControl;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * UDP 最终出口 guard，必须位于所有自定义 UDP header handler 之后。
 */
@Slf4j
public final class UdpFinalEgressGuardHandler extends ChannelDuplexHandler {
    private final SocketConfig config;
    private final boolean forceBackpressure;

    public UdpFinalEgressGuardHandler(SocketConfig config, boolean forceBackpressure) {
        this.config = config;
        this.forceBackpressure = forceBackpressure;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (!(msg instanceof DatagramPacket)) {
            ctx.write(msg, promise);
            return;
        }

        writeDatagram(ctx, (DatagramPacket) msg, promise, false);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof DatagramPacket)) {
            ctx.fireChannelRead(msg);
            return;
        }

        DatagramPacket packet = (DatagramPacket) msg;
        InetSocketAddress sender = packet.sender();
        if (sender == null || !sender.isUnresolved()) {
            ctx.fireChannelRead(msg);
            return;
        }

        SocketConfig effectiveConfig = Sockets.udpEffectiveConfig(ctx.channel(), config);
        Sockets.resolveUdpEndpointAsync(sender, effectiveConfig)
                .whenComplete((resolved, error) -> executeResolvedRead(ctx, packet, sender, resolved, error));
    }

    private void writeDatagram(ChannelHandlerContext ctx, DatagramPacket packet, ChannelPromise promise, boolean forceFlush) {
        Channel channel = ctx.channel();
        SocketConfig effectiveConfig = Sockets.udpEffectiveConfig(channel, config);
        String metricPrefix = Sockets.udpFinalMetricPrefix(effectiveConfig);
        String tags = "path=final-egress";
        int bytes = packet.content().readableBytes();

        if (!channel.isActive()) {
            Sockets.releaseUdpPacket(packet, metricPrefix, tags, "final-inactive", 0, 0);
            completeDroppedWrite(promise);
            return;
        }

        InetSocketAddress recipient = packet.recipient();
        int limitBytes = Sockets.udpWriteLimitBytes(channel);
        if (recipient != null && recipient.isUnresolved()) {
            Sockets.resolveUdpEndpointAsync(recipient, effectiveConfig)
                    .whenComplete((resolved, error) -> executeResolvedWrite(ctx, packet,
                            recipient, resolved, error, promise, metricPrefix, tags, limitBytes));
            return;
        }

        int udpMtu = effectiveConfig != null ? Math.max(0, effectiveConfig.getUdpMtu()) : 0;
        if (!(packet instanceof UdpMtuProbeDatagramPacket) && udpMtu > 0 && bytes > udpMtu) {
            Sockets.releaseUdpPacketByMtu(packet, metricPrefix, tags, bytes, udpMtu);
            completeDroppedWrite(promise);
            return;
        }

        UdpBackpressurePolicy udpPolicy = NetworkFlowControl.DEFAULT.udpBackpressurePolicy();
        boolean trackBackpressure = udpPolicy.isEnabled()
                && (forceBackpressure || udpMtu > 0 || udpPolicy.hasConfiguredPendingLimit());
        if (!trackBackpressure) {
            writeToTransport(ctx, packet, promise, forceFlush);
            return;
        }

        AtomicInteger pendingBytes = Sockets.udpPendingWriteBytesState(channel);
        AtomicInteger pendingPackets = Sockets.udpPendingWritePacketsState(channel);
        int limitPackets = udpPolicy.writeLimitPackets(channel);
        UdpBackpressureDecision decision = udpPolicy.reserve(channel, bytes, pendingBytes, pendingPackets,
                limitBytes, limitPackets, "final-");
        if (!decision.accepted) {
            Sockets.releaseUdpPacket(packet, metricPrefix, tags, decision.reason, decision.queuedBytes,
                    decision.limitBytes, decision.queuedPackets, decision.limitPackets);
            completeDroppedWrite(promise);
            return;
        }

        ChannelPromise writePromise = promise.isVoid() ? ctx.newPromise() : promise;
        writePromise.addListener((ChannelFutureListener) f -> {
            if (decision.tracked) {
                udpPolicy.release(bytes, pendingBytes, pendingPackets, limitPackets);
            }
            if (!f.isSuccess()) {
                Sockets.recordUdpMetric(metricPrefix, "drop.count",
                        Sockets.appendUdpMetricTags(tags,
                                "reason=final-write-fail,limitBucket=" + Sockets.udpLimitBucket(limitBytes)));
                log.warn("UDP final write fail channel={} recipient={}", channel, packet.recipient(), f.cause());
            }
        });
        try {
            writeToTransport(ctx, packet, writePromise, forceFlush);
        } catch (Throwable e) {
            ReferenceCountUtil.release(packet);
            if (!writePromise.tryFailure(e) && decision.tracked) {
                udpPolicy.release(bytes, pendingBytes, pendingPackets, limitPackets);
            }
            Sockets.recordUdpMetric(metricPrefix, "drop.count",
                    Sockets.appendUdpMetricTags(tags,
                            "reason=final-write-throw,limitBucket=" + Sockets.udpLimitBucket(limitBytes)));
            log.warn("UDP final write throw channel={} recipient={}", channel, packet.recipient(), e);
        }
    }

    private void executeResolvedWrite(ChannelHandlerContext ctx, DatagramPacket packet,
                                      InetSocketAddress originalRecipient, InetSocketAddress resolved,
                                      Throwable error, ChannelPromise promise,
                                      String metricPrefix, String tags, int limitBytes) {
        try {
            ctx.executor().execute(() -> completeResolvedWrite(ctx, packet, originalRecipient, resolved, error,
                    promise, metricPrefix, tags, limitBytes));
        } catch (Throwable e) {
            Sockets.releaseUdpPacket(packet, metricPrefix, tags, "final-unresolved-recipient", 0, limitBytes);
            failPromise(promise, e);
        }
    }

    private void completeResolvedWrite(ChannelHandlerContext ctx, DatagramPacket packet,
                                       InetSocketAddress originalRecipient, InetSocketAddress resolved,
                                       Throwable error, ChannelPromise promise,
                                       String metricPrefix, String tags, int limitBytes) {
        if (error != null || resolved == null || resolved.isUnresolved()) {
            Sockets.releaseUdpPacket(packet, metricPrefix, tags, "final-unresolved-recipient", 0, limitBytes);
            log.warn("UDP final resolve recipient fail channel={} recipient={}",
                    ctx.channel(), originalRecipient, resolveError(originalRecipient, error));
            completeDroppedWrite(promise);
            return;
        }

        DatagramPacket next = packet.sender() == null
                ? new DatagramPacket(packet.content().retain(), resolved)
                : new DatagramPacket(packet.content().retain(), resolved, packet.sender());
        ReferenceCountUtil.release(packet);
        writeDatagram(ctx, next, promise, true);
    }

    private void executeResolvedRead(ChannelHandlerContext ctx, DatagramPacket packet,
                                     InetSocketAddress originalSender, InetSocketAddress resolved,
                                     Throwable error) {
        try {
            ctx.executor().execute(() -> completeResolvedRead(ctx, packet, originalSender, resolved, error));
        } catch (Throwable e) {
            ReferenceCountUtil.release(packet);
            ctx.fireExceptionCaught(e);
        }
    }

    private void completeResolvedRead(ChannelHandlerContext ctx, DatagramPacket packet,
                                      InetSocketAddress originalSender, InetSocketAddress resolved,
                                      Throwable error) {
        if (error != null || resolved == null || resolved.isUnresolved()) {
            ReferenceCountUtil.release(packet);
            ctx.fireExceptionCaught(resolveError(originalSender, error));
            return;
        }

        DatagramPacket next = packet.recipient() == null
                ? new DatagramPacket(packet.content().retain(), resolved)
                : new DatagramPacket(packet.content().retain(), packet.recipient(), resolved);
        ReferenceCountUtil.release(packet);
        ctx.fireChannelRead(next);
    }

    private static void writeToTransport(ChannelHandlerContext ctx, DatagramPacket packet,
                                         ChannelPromise promise, boolean forceFlush) {
        if (forceFlush) {
            ctx.writeAndFlush(packet, promise);
        } else {
            ctx.write(packet, promise);
        }
    }

    private static Throwable resolveError(InetSocketAddress endpoint, Throwable error) {
        if (error != null) {
            return error;
        }
        return new UnknownHostException(endpoint == null ? null : endpoint.getHostString());
    }

    private static void failPromise(ChannelPromise promise, Throwable error) {
        if (promise != null && !promise.isVoid()) {
            promise.tryFailure(error);
        }
    }

    private static void completeDroppedWrite(ChannelPromise promise) {
        // Final egress drops are metrics-observable; write futures stay successful so UDP callers
        // that only use completion for pending cleanup are not forced onto an exception path.
        if (!promise.isVoid()) {
            promise.trySuccess();
        }
    }
}
