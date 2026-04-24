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
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.io.Bytes;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;

@Slf4j
public class ProxyManageHandler extends ChannelTrafficShapingHandler {
    public static ProxyManageHandler get(ChannelHandlerContext ctx) {
        return (ProxyManageHandler) ctx.pipeline().get(ProxyManageHandler.class.getSimpleName());
    }

    @Getter
    private SocksUser user = SocksUser.ANONYMOUS;
    @Getter
    private TrafficUser trafficUser = TrafficUser.ANONYMOUS;
    @Getter
    private TrafficLoginInfo info;
    private long activeTime;

    public ProxyManageHandler(long checkInterval) {
        super(checkInterval);
    }

    public void setUser(@NonNull SocksUser user, TrafficUser trafficUser, ChannelHandlerContext ctx) {
        this.user = user;
        this.trafficUser = trafficUser != null ? trafficUser : TrafficUser.ANONYMOUS;
        if (this.trafficUser == null || this.trafficUser.isAnonymous()) {
            SocksUserTraffic.bind(ctx.channel(), TrafficUser.ANONYMOUS, null);
            return;
        }
        InetSocketAddress realEp = Sockets.getOriginRemoteAddress(ctx.channel());
        info = this.trafficUser.getLoginIps().computeIfAbsent(realEp.getAddress(), ip -> new TrafficLoginInfo());
        if (this.trafficUser.getIpLimit() != -1 && this.trafficUser.getLoginIps().size() > this.trafficUser.getIpLimit()) {
            log.error("TrafficUser {} maxIpCount={}\nconnectedIps={} incomingIp={}",
                    this.trafficUser.getUsername(), this.trafficUser.getIpLimit(), this.trafficUser.getLoginIps().keySet(), realEp);
            Sockets.closeOnFlushed(ctx.channel());
            return;
        }
        info.getRefCnt().incrementAndGet();
        SocksUserTraffic.bind(ctx.channel(), this.trafficUser, info);
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
            if (info.getLatestTime() == null || info.getLatestTime().before(now)) {
                info.setLatestTime(now);
            }
            info.getRefCnt().decrementAndGet();
            info.getTotalActiveSeconds().addAndGet(elapsed / Constants.NANO_TO_MILLIS / 1000);
        }

        InetSocketAddress remoteAddress = Sockets.getOriginRemoteAddress(ctx.channel());
        String tagsUser = trafficUser != null && !trafficUser.isAnonymous() ? trafficUser.getUsername() : user.getUsername();
        if (trafficUser != null && !trafficUser.isAnonymous()) {
            SocksUserTraffic.recordSession(trafficUser, remoteAddress, SocksUserTraffic.PROTOCOL_TCP,
                    elapsed / Constants.NANO_TO_MILLIS / 1000);
        }
        if (DiagnosticMetrics.isEnabled()) {
            String tags = "user=" + tagsUser + ",remote=" + remoteAddress;
            DiagnosticMetrics.record("socks.session.active.millis", elapsed / Constants.NANO_TO_MILLIS, tags);
            DiagnosticMetrics.record("socks.session.inbound.bytes", readBytes, tags);
            DiagnosticMetrics.record("socks.session.outbound.bytes", writeBytes, tags);
        }
        log.info("usr={} <-> {} elapsed={} readBytes={} writeBytes={}",
                tagsUser, remoteAddress, Sys.formatNanosElapsed(elapsed),
                Bytes.readableByteSize(readBytes), Bytes.readableByteSize(writeBytes));
        super.channelInactive(ctx);
    }
}
