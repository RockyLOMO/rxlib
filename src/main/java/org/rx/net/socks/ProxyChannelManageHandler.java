package org.rx.net.socks;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.traffic.ChannelTrafficShapingHandler;
import lombok.Getter;
import org.rx.bean.DateTime;

public class ProxyChannelManageHandler extends ChannelTrafficShapingHandler {
    public static ProxyChannelManageHandler get(ChannelHandlerContext ctx) {
        return (ProxyChannelManageHandler) ctx.pipeline().get(ProxyChannelManageHandler.class.getSimpleName());
    }

    public static void username(ChannelHandlerContext ctx, String username) {
        get(ctx).username = username;
    }

    @Getter
    private DateTime beginTime;
    @Getter
    private DateTime endTime;
    @Getter
    private String username = "anonymous";
    private FlowLogger flowLogger;

    public ProxyChannelManageHandler(long checkInterval, FlowLogger flowLogger) {
        super(checkInterval);
        this.flowLogger = flowLogger;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        beginTime = DateTime.now();
        flowLogger.log(ctx);
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        endTime = DateTime.now();
        flowLogger.log(ctx);
        super.channelInactive(ctx);
    }
}
