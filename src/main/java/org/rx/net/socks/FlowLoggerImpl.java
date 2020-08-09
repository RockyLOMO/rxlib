package org.rx.net.socks;

import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.DateTime;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;

import static org.rx.core.Contract.isNull;

@Slf4j
public class FlowLoggerImpl implements FlowLogger {
    public void log(ChannelHandlerContext ctx) {
        ProxyChannelManageHandler trafficShapingHandler = ProxyChannelManageHandler.get(ctx);
        InetSocketAddress localAddress = (InetSocketAddress) ctx.channel().localAddress();
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();

        long readByte = trafficShapingHandler.trafficCounter().cumulativeReadBytes();
        long writeByte = trafficShapingHandler.trafficCounter().cumulativeWrittenBytes();

        log.info("User={} Elapsed={}s\tlocal={}:{} remote={}\treadBytes={} writeBytes={}",
                trafficShapingHandler.getUsername(),
                isNull(trafficShapingHandler.getEndTime(), DateTime.now()).subtract(trafficShapingHandler.getBeginTime()).getTotalSeconds(),
                Sockets.getLocalAddress(), localAddress.getPort(),
                remoteAddress,
                readByte,
                writeByte);
    }
}
