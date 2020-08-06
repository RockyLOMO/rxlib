package org.rx.net.socks;

import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;

@Slf4j
public class FlowLoggerImpl implements FlowLogger {
    public void log(ChannelHandlerContext ctx) {
        ProxyChannelTrafficShapingHandler trafficShapingHandler = ProxyChannelTrafficShapingHandler.get(ctx);
        InetSocketAddress localAddress = (InetSocketAddress) ctx.channel().localAddress();
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();

        long readByte = trafficShapingHandler.trafficCounter().cumulativeReadBytes();
        long writeByte = trafficShapingHandler.trafficCounter().cumulativeWrittenBytes();

        log.info("{},{},{},{}:{},{}:{},{},{},{}",
                trafficShapingHandler.getUsername(),
                trafficShapingHandler.getBeginTime(),
                trafficShapingHandler.getEndTime(),
                Sockets.getLocalAddress(),
                localAddress.getPort(),
                remoteAddress.getAddress().getHostAddress(),
                remoteAddress.getPort(),
                readByte,
                writeByte,
                (readByte + writeByte));
    }
}
