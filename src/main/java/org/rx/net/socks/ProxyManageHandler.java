package org.rx.net.socks;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.traffic.ChannelTrafficShapingHandler;
import io.netty.handler.traffic.TrafficCounter;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.DateTime;
import org.rx.net.Sockets;
import org.rx.net.support.SocksSupport;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class ProxyManageHandler extends ChannelTrafficShapingHandler {
    public static ProxyManageHandler get(ChannelHandlerContext ctx) {
        return (ProxyManageHandler) ctx.pipeline().get(ProxyManageHandler.class.getSimpleName());
    }

    private final Authenticator authenticator;
    @Getter
    private SocksUser user = SocksUser.ANONYMOUS;
    private DateTime onlineTime;

    public void setUser(@NonNull SocksUser user, ChannelHandlerContext ctx) {
        this.user = user;
        InetSocketAddress remoteEp = (InetSocketAddress) ctx.channel().remoteAddress();
        InetSocketAddress prevEp = remoteEp;
        while ((prevEp = SocksSupport.ipTracer().get(prevEp)) != null) {

        }
        InetSocketAddress realEp = SocksSupport.ipTracer().get(remoteEp);
        log.info("tracer s5 get {} => {}", remoteEp, realEp);
        if (realEp == null) {
            realEp = remoteEp;
        }
        AtomicInteger refCnt = user.getLoginIps().computeIfAbsent(realEp.getAddress(), k -> new AtomicInteger());
        refCnt.incrementAndGet();
    }

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
