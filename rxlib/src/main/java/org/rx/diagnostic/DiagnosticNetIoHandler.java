package org.rx.diagnostic;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.FileRegion;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.AddressedEnvelope;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

@ChannelHandler.Sharable
public final class DiagnosticNetIoHandler extends ChannelDuplexHandler {
    private static final String HANDLER_NAME = "rx-diagnostic-net-io";

    private final String component;

    public DiagnosticNetIoHandler(String component) {
        this.component = component == null || component.length() == 0 ? "net" : component;
    }

    public static void install(ChannelPipeline pipeline, String component) {
        if (pipeline != null && pipeline.get(HANDLER_NAME) == null) {
            pipeline.addFirst(HANDLER_NAME, new DiagnosticNetIoHandler(component));
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        Channel channel = ctx.channel();
        if (channel.isActive()) {
            DiagnosticNetMetrics.register(channel, component);
        }
        super.handlerAdded(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        DiagnosticNetMetrics.register(ctx.channel(), component);
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        DiagnosticNetMetrics.unregister(ctx.channel());
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        long bytes = readableBytes(msg);
        if (bytes > 0L && DiagnosticMetrics.isEnabled()) {
            SocketAddress endpoint = endpointAddress(ctx, msg, true);
            if (!isLoopback(endpoint)) {
                DiagnosticNetMetrics.recordInbound(component, bytes);
                if (DiagnosticNetIo.isEnabled()) {
                    DiagnosticNetIo.recordInbound(endpoint(ctx, endpoint), bytes);
                }
            }
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        long bytes = readableBytes(msg);
        if (bytes > 0L && DiagnosticMetrics.isEnabled()) {
            SocketAddress endpoint = endpointAddress(ctx, msg, false);
            if (!isLoopback(endpoint)) {
                DiagnosticNetMetrics.recordOutbound(component, bytes);
                if (DiagnosticNetIo.isEnabled()) {
                    DiagnosticNetIo.recordOutbound(endpoint(ctx, endpoint), bytes);
                }
            }
        }
        super.write(ctx, msg, promise);
    }

    private static long readableBytes(Object msg) {
        if (msg instanceof ByteBuf) {
            return ((ByteBuf) msg).readableBytes();
        }
        if (msg instanceof ByteBufHolder) {
            return ((ByteBufHolder) msg).content().readableBytes();
        }
        if (msg instanceof FileRegion) {
            return ((FileRegion) msg).count();
        }
        if (msg instanceof DatagramPacket) {
            return ((DatagramPacket) msg).content().readableBytes();
        }
        if (msg instanceof AddressedEnvelope) {
            Object content = ((AddressedEnvelope) msg).content();
            if (content instanceof ByteBuf) {
                return ((ByteBuf) content).readableBytes();
            }
        }
        return 0L;
    }

    private static SocketAddress endpointAddress(ChannelHandlerContext ctx, Object msg, boolean inbound) {
        if (msg instanceof AddressedEnvelope) {
            AddressedEnvelope envelope = (AddressedEnvelope) msg;
            SocketAddress address = inbound ? envelope.sender() : envelope.recipient();
            if (address != null) {
                return address;
            }
        }
        SocketAddress remote = ctx.channel().remoteAddress();
        if (remote != null) {
            return remote;
        }
        return ctx.channel().localAddress();
    }

    private static boolean isLoopback(SocketAddress address) {
        if (address instanceof InetSocketAddress) {
            InetAddress inetAddress = ((InetSocketAddress) address).getAddress();
            if (inetAddress != null) {
                return inetAddress.isLoopbackAddress();
            }
            String host = ((InetSocketAddress) address).getHostString();
            return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
        }
        return false;
    }

    private static String endpoint(ChannelHandlerContext ctx, SocketAddress endpoint) {
        if (endpoint != null) {
            return endpoint.toString();
        }
        SocketAddress local = ctx.channel().localAddress();
        return local == null ? "unknown" : local.toString();
    }
}
