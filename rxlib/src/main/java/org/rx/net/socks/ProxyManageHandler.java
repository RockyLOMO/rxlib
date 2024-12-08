package org.rx.net.socks;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.traffic.ChannelTrafficShapingHandler;
import io.netty.handler.traffic.TrafficCounter;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.DateTime;
import org.rx.core.Constants;
import org.rx.core.Sys;
import org.rx.io.Bytes;
import org.rx.net.Sockets;
import org.rx.net.support.SocksSupport;

import java.net.InetSocketAddress;

import static org.rx.core.Extends.tryAs;

@Slf4j
public class ProxyManageHandler extends ChannelTrafficShapingHandler {
    public static ProxyManageHandler get(ChannelHandlerContext ctx) {
        return (ProxyManageHandler) ctx.pipeline().get(ProxyManageHandler.class.getSimpleName());
    }

    private final Authenticator authenticator;
    @Getter
    private SocksUser user = SocksUser.ANONYMOUS;
    private SocksUser.LoginInfo info;
    private long activeTime;

    public ProxyManageHandler(Authenticator authenticator, long checkInterval) {
        super(checkInterval);
        this.authenticator = authenticator;
    }

    public void setUser(@NonNull SocksUser user, ChannelHandlerContext ctx) {
        this.user = user;
        InetSocketAddress realEp = (InetSocketAddress) SocksSupport.ENDPOINT_TRACER.head(ctx.channel());
        info = user.getLoginIps().computeIfAbsent(realEp.getAddress(), SocksUser.LoginInfo::new);
        if (user.getMaxIpCount() != -1 && user.getLoginIps().size() > user.getMaxIpCount()) {
            log.error("SocksUser {} maxIpCount={}\nconnectedIps={} incomingIp={}", user.getUsername(), user.getMaxIpCount(), user.getLoginIps().keySet(), realEp);
            Sockets.closeOnFlushed(ctx.channel());
            return;
        }
        info.refCnt++;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        activeTime = System.nanoTime();
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        long elapsed = (System.nanoTime() - activeTime);
        TrafficCounter trafficCounter = trafficCounter();
        long readBytes = trafficCounter.cumulativeReadBytes();
        long writeBytes = trafficCounter.cumulativeWrittenBytes();

        if (info != null) {
            DateTime now = DateTime.now();
            if (info.latestTime == null || info.latestTime.before(now)) {
                info.latestTime = now;
            }
//            info.refCnt--;
            info.totalActiveSeconds.addAndGet(elapsed / Constants.NANO_TO_MILLIS / 1000);
            //svr write = client read
            info.totalReadBytes.addAndGet(writeBytes);
            info.totalWriteBytes.addAndGet(readBytes);
        }
//        tryAs(authenticator, DbAuthenticator.class, p -> p.save(user));

        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        log.info("usr={} <-> {} elapsed={} readBytes={} writeBytes={}",
                user.getUsername(), remoteAddress, Sys.formatNanosElapsed(elapsed),
                Bytes.readableByteSize(readBytes), Bytes.readableByteSize(writeBytes));
        super.channelInactive(ctx);
    }
}
