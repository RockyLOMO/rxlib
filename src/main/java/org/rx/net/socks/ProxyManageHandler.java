package org.rx.net.socks;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.traffic.ChannelTrafficShapingHandler;
import io.netty.handler.traffic.TrafficCounter;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.DateTime;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class ProxyManageHandler extends ChannelTrafficShapingHandler {
    public static ProxyManageHandler get(ChannelHandlerContext ctx) {
        return (ProxyManageHandler) ctx.pipeline().get(ProxyManageHandler.class.getSimpleName());
    }

    private final Authenticator authenticator;
    @Getter
    @Setter(AccessLevel.PROTECTED)
    private SocksUser user = SocksUser.ANONYMOUS;
    private DateTime onlineTime;

    public ProxyManageHandler(Authenticator authenticator, long checkInterval) {
        super(checkInterval);
        this.authenticator = authenticator;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        onlineTime = DateTime.utcNow();
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        double elapsed = DateTime.utcNow().subtract(onlineTime).getTotalSeconds();

        InetSocketAddress localAddress = (InetSocketAddress) ctx.channel().localAddress();
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();

        TrafficCounter trafficCounter = trafficCounter();
        long readByte = trafficCounter.cumulativeReadBytes();
        long writeByte = trafficCounter.cumulativeWrittenBytes();

        AtomicInteger refCnt = user.getLoginIps().get(remoteAddress.getAddress());
        if (refCnt != null) {
            if (refCnt.decrementAndGet() <= 0) {
                user.getLoginIps().remove(remoteAddress.getAddress());
            }
        }
        user.getTotalReadBytes().addAndGet(readByte);
        user.getTotalWriteBytes().addAndGet(writeByte);

        log.info("user={} elapsed={}s\tlocal={}:{} remote={}\treadBytes={} writeBytes={}",
                user.getUsername(), elapsed,
                Sockets.getLocalAddress(), localAddress.getPort(), remoteAddress,
                readByte, writeByte);
        if (authenticator instanceof DbAuthenticator) {
            ((DbAuthenticator) authenticator).save(user);
        }
        super.channelInactive(ctx);
    }
}
