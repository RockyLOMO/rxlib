package org.rx.net.socks;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.traffic.ChannelTrafficShapingHandler;
import lombok.Getter;

public class ProxyChannelTrafficShapingHandler extends ChannelTrafficShapingHandler {
    public static final String PROXY_TRAFFIC = "ProxyChannelTrafficShapingHandler";

    public static ProxyChannelTrafficShapingHandler get(ChannelHandlerContext ctx) {
        return (ProxyChannelTrafficShapingHandler) ctx.pipeline().get(PROXY_TRAFFIC);
    }

    public static void username(ChannelHandlerContext ctx, String username) {
        get(ctx).username = username;
    }

    @Getter
    private long beginTime;
    @Getter
    private long endTime;
    @Getter
    private String username = "anonymous";
    private FlowLogger flowLogger;
    private ChannelListener channelListener;

    public ProxyChannelTrafficShapingHandler(long checkInterval, FlowLogger flowLogger, ChannelListener channelListener) {
        super(checkInterval);
        this.flowLogger = flowLogger;
        this.channelListener = channelListener;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        beginTime = System.currentTimeMillis();
        if (channelListener != null) {
            channelListener.active(ctx);
        }
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        endTime = System.currentTimeMillis();
        if (channelListener != null) {
            channelListener.inactive(ctx);
        }
        flowLogger.log(ctx);
        super.channelInactive(ctx);
    }
}
