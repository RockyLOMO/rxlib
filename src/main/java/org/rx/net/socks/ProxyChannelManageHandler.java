package org.rx.net.socks;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.traffic.ChannelTrafficShapingHandler;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.rx.bean.DateTime;

public class ProxyChannelManageHandler extends ChannelTrafficShapingHandler {
    String DEFAULT_USER = "anonymous";

    public static ProxyChannelManageHandler get(ChannelHandlerContext ctx) {
        return (ProxyChannelManageHandler) ctx.pipeline().get(ProxyChannelManageHandler.class.getSimpleName());
    }

    @Getter
    @Setter(AccessLevel.PROTECTED)
    private String username = DEFAULT_USER;
    @Getter
    private DateTime beginTime;
    @Getter
    private DateTime endTime;
    final FlowLogger flowLogger;

    public boolean isAnonymous() {
        return DEFAULT_USER.equals(username);
    }

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
