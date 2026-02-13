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
import org.rx.util.function.QuadraAction;
import org.rx.util.function.TripleAction;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@RequiredArgsConstructor
public class BackpressureHandler extends ChannelInboundHandlerAdapter {
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
        WriteBufferWaterMark waterMark = outbound.config().getOption(ChannelOption.WRITE_BUFFER_WATER_MARK);
        if (waterMark == null) {
            log.warn("BackpressureHandler not installed: WriteBufferWaterMark not set");
            return;
        }

        ChannelPipeline p = outbound.pipeline();
        BackpressureHandler handler = p.get(BackpressureHandler.class);
        if (handler != null) {
            throw new IllegalStateException("BackpressureHandler already installed");
        }
        p.addLast(new BackpressureHandler(inbound, onBackpressureStart, onBackpressureEnd));
    }

    final AtomicReference<ScheduledFuture<?>> timer = new AtomicReference<>();
    final Channel inbound;
    // 通知暂停队列、暂停上游读取
    final TripleAction<Channel, Channel> onBackpressureStart;
    // 通知恢复队列、恢复上游读取
    final QuadraAction<Channel, Channel, Throwable> onBackpressureEnd;
    volatile long lastEventTs;
    // 是否暂停写入（用于业务层，如队列暂停）
    @Getter
    volatile boolean paused;

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
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
            log.info("Backpressure setTimeout[{}]", nextMs);
            return;
        }

        onEvent(outbound, now);

        super.channelWritabilityChanged(ctx);
    }

    void onEvent(Channel outbound, long nowNano) {
        if (!outbound.isWritable()) {
            if (paused) {
                return;
            }
            // ---- 写入过载 ----
            paused = true;
            lastEventTs = nowNano;
            log.info("Channel {} Backpressure start[notWritable]", outbound);
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
        log.info("Channel {} Backpressure end[writable]", outbound);
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
        // 确保刚连上就打开读取
        Sockets.enableAutoRead(ctx.channel());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        stopTimer();
        if (paused) {
            paused = false; // 防止外部系统死等
        }
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
