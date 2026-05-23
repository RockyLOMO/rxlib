package org.rx.net.udp;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * UDP unresolved 地址兜底解析，避免直发路径把 unresolved 交给 transport。
 */
@Slf4j
public final class UdpUnresolvedEndpointResolveHandler extends ChannelDuplexHandler {
    private final SocketConfig config;

    public UdpUnresolvedEndpointResolveHandler(SocketConfig config) {
        this.config = config;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (!(msg instanceof DatagramPacket)) {
            ctx.write(msg, promise);
            return;
        }

        DatagramPacket packet = (DatagramPacket) msg;
        InetSocketAddress recipient = packet.recipient();
        if (recipient == null || !recipient.isUnresolved()) {
            ctx.write(msg, promise);
            return;
        }

        SocketConfig effectiveConfig = Sockets.udpEffectiveConfig(ctx.channel(), config);
        Sockets.resolveUdpEndpointAsync(recipient, effectiveConfig)
                .whenComplete((resolved, error) -> executeResolvedWrite(ctx, packet, recipient, resolved, error, promise));
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

    private void executeResolvedWrite(ChannelHandlerContext ctx, DatagramPacket packet,
                                      InetSocketAddress originalRecipient, InetSocketAddress resolved,
                                      Throwable error, ChannelPromise promise) {
        try {
            ctx.executor().execute(() -> completeResolvedWrite(ctx, packet, originalRecipient, resolved, error, promise));
        } catch (Throwable e) {
            ReferenceCountUtil.release(packet);
            failPromise(promise, e);
        }
    }

    private void completeResolvedWrite(ChannelHandlerContext ctx, DatagramPacket packet,
                                       InetSocketAddress originalRecipient, InetSocketAddress resolved,
                                       Throwable error, ChannelPromise promise) {
        if (error != null || resolved == null || resolved.isUnresolved()) {
            ReferenceCountUtil.release(packet);
            failPromise(promise, resolveError(originalRecipient, error));
            return;
        }

        DatagramPacket next = packet.sender() == null
                ? new DatagramPacket(packet.content().retain(), resolved)
                : new DatagramPacket(packet.content().retain(), resolved, packet.sender());
        ReferenceCountUtil.release(packet);
        try {
            ctx.writeAndFlush(next, promise);
        } catch (Throwable e) {
            ReferenceCountUtil.release(next);
            failPromise(promise, e);
        }
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
}
