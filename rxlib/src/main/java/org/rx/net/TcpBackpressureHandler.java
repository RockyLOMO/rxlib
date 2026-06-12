package org.rx.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Constants;
import org.rx.core.RxConfig;
import org.rx.util.function.QuadraAction;
import org.rx.util.function.TripleAction;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@RequiredArgsConstructor
public class TcpBackpressureHandler extends ChannelInboundHandlerAdapter {
    static final int MIN_SPAN_MILLIS = 20;
    // 使用一个抖动阈值，防止高低水位频繁震荡
    static final long COOLDOWN_MILLIS = 50;

    public static void install(Channel inbound, Channel outbound) {
        install(inbound, outbound, (in, out) -> {
            Sockets.disableAutoRead(in);
        }, (in, out, e) -> {
            Sockets.enableAutoRead(in);
        });
    }

    public static void install(Channel inbound, Channel outbound, TripleAction<Channel, Channel> onBackpressureStart, QuadraAction<Channel, Channel, Throwable> onBackpressureEnd) {
        if (outbound == null) {
            return;
        }
        NetworkTrafficConfig config = RxConfig.INSTANCE.getNet().getGlobalTraffic();
        if (config != null && !config.isTcpBackpressureEnabled()) {
            return;
        }
        WriteBufferWaterMark waterMark = outbound.config().getOption(ChannelOption.WRITE_BUFFER_WATER_MARK);
        if (waterMark == null) {
            log.warn("TcpBackpressureHandler not installed: WriteBufferWaterMark not set");
            return;
        }

        ChannelPipeline p = outbound.pipeline();
        TcpBackpressureHandler handler = p.get(TcpBackpressureHandler.class);
        if (handler != null) {
            throw new IllegalStateException("TcpBackpressureHandler already installed");
        }
        p.addLast(new TcpBackpressureHandler(inbound, onBackpressureStart, onBackpressureEnd));
    }

    final AtomicReference<ScheduledFuture<?>> timer = new AtomicReference<>();
    final Channel inbound;
    // 通知暂停队列、暂停上游读取
    final TripleAction<Channel, Channel> onBackpressureStart;
    // 通知恢复队列、恢复上游读取
    final QuadraAction<Channel, Channel, Throwable> onBackpressureEnd;
    volatile long lastEventTs;
    volatile long pauseStartNano;
    // 是否暂停写入（用于业务层，如队列暂停）
    @Getter
    volatile boolean paused;

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        try {
            Channel outbound = ctx.channel();

            ScheduledFuture<?> t = timer.get();
            if (t != null
            // && !t.isDone()
            ) {
                return;
            }

            long now = System.nanoTime();
            long spanMs = (now - lastEventTs) / Constants.NANO_TO_MILLIS, nextMs;
            if (spanMs < COOLDOWN_MILLIS
                    && (nextMs = COOLDOWN_MILLIS - spanMs) > MIN_SPAN_MILLIS) {
                ScheduledFuture<?> schedule = ctx.executor().schedule(() -> {
                    try {
                        if (!outbound.isActive()) {
                            return;
                        }
                        onEvent(outbound, System.nanoTime());
                    } finally {
                        timer.lazySet(null);
                    }
                }, nextMs, TimeUnit.MILLISECONDS);
                // 如果 CAS 失败（意味着别处刚写入 timer），我们仍然要把 scheduled 取消
                if (!timer.compareAndSet(null, schedule)) {
                    schedule.cancel(false);
                }
                NetworkFlowDiagnostics.recordTcpBackpressureTimeout();
                log.debug("Backpressure setTimeout[{}]", nextMs);
                return;
            }

            onEvent(outbound, now);
        } finally {
            super.channelWritabilityChanged(ctx);
        }
    }

    void onEvent(Channel outbound, long nowNano) {
        if (!outbound.isWritable()) {
            if (paused) {
                return;
            }
            // ---- 写入过载 ----
            paused = true;
            lastEventTs = nowNano;
            pauseStartNano = nowNano;
            NetworkFlowDiagnostics.recordTcpBackpressureStart();
            log.debug("Channel {} Backpressure start[notWritable]", outbound);
            TripleAction<Channel, Channel> fn = onBackpressureStart;
            if (fn != null) {
                fn.accept(inbound, outbound);
            }
        } else {
            handleRecovery(outbound, nowNano, null);
        }
    }

    void handleRecovery(Channel outbound, long nowNano, Throwable cause) {
        if (!paused) {
            return;
        }
        // ---- 恢复 ----
        paused = false;
        lastEventTs = nowNano;
        long startNano = pauseStartNano;
        pauseStartNano = 0L;
        NetworkFlowDiagnostics.recordTcpBackpressureEnd(startNano > 0L && nowNano > startNano
                ? (nowNano - startNano) / Constants.NANO_TO_MILLIS : 0L);
        log.debug("Channel {} Backpressure end[writable]", outbound);
        QuadraAction<Channel, Channel, Throwable> fn = onBackpressureEnd;
        if (fn != null) {
            fn.accept(inbound, outbound, cause);
        }
    }

    void stopTimer() {
        ScheduledFuture<?> t = timer.getAndSet(null);
        if (t != null) {
            t.cancel(false);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // outbound 刚连上时恢复 captured inbound 读取
        if (inbound != null && inbound.isOpen()) {
            Sockets.enableAutoRead(inbound);
        }
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        stopTimer();
        // outbound 关闭时也通知恢复，避免外部队列/限流状态停在 paused。
        handleRecovery(ctx.channel(), 0, null);
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        // 异常时也尝试清理定时器，并通知外部系统恢复
        stopTimer();
        // 任何异常都强制恢复 autoRead，防止通道永久卡死
        handleRecovery(ctx.channel(), 0, cause);
        super.exceptionCaught(ctx, cause);
    }
}
