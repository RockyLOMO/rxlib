package org.rx.net.udp;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.NetworkFlowControl;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * UDP 最终出口 guard，必须位于所有自定义 UDP header handler 之后。
 */
@Slf4j
public final class UdpFinalEgressGuardHandler extends ChannelOutboundHandlerAdapter {
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

        Channel channel = ctx.channel();
        DatagramPacket packet = (DatagramPacket) msg;
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
            Sockets.releaseUdpPacket(packet, metricPrefix, tags, "final-unresolved-recipient", 0, limitBytes);
            completeDroppedWrite(promise);
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
            ctx.write(packet, promise);
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
            ctx.write(packet, writePromise);
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

    private static void completeDroppedWrite(ChannelPromise promise) {
        // Final egress drops are metrics-observable; write futures stay successful so UDP callers
        // that only use completion for pending cleanup are not forced onto an exception path.
        if (!promise.isVoid()) {
            promise.trySuccess();
        }
    }
}
