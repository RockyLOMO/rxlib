package org.rx.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.rx.core.Constants;
import org.rx.util.function.BiAction;
import org.rx.util.function.TripleAction;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RequiredArgsConstructor
public class BackpressureHandler extends ChannelInboundHandlerAdapter {
    static final int MIN_SPAN_MILLIS = 20;
    // 使用一个抖动阈值，防止高低水位频繁震荡
    static final long COOLDOWN_MILLIS = 60 + MIN_SPAN_MILLIS;
    final AtomicReference<ScheduledFuture<?>> timer = new AtomicReference<>();
    final boolean disableSelfAutoRead;
    //可以通知队列暂停、暂停 SS/HTTP 上游读取、标记对侧 channel
    @Setter
    BiAction<Channel> onBackpressureStart;
    //恢复队列、恢复 SS 上游读取…
    @Setter
    TripleAction<Channel, Throwable> onBackpressureEnd;
    volatile long lastEventTs;
    // 是否暂停写入（用于业务层，如队列暂停）
    @Getter
    volatile boolean paused;

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        Channel ch = ctx.channel();

        ScheduledFuture<?> t = timer.get();
        if (t != null
//                && !t.isDone()
        ) {
            return;
        }

        long now = System.nanoTime();
        long spanMs = (now - lastEventTs) / Constants.NANO_TO_MILLIS, nextMs;
        if (spanMs < COOLDOWN_MILLIS
                && (nextMs = COOLDOWN_MILLIS - spanMs) > MIN_SPAN_MILLIS) {
            ScheduledFuture<?> schedule = ctx.executor().schedule(() -> {
                try {
                    if (!ch.isActive()) {
                        return;
                    }
                    onEvent(ch, System.nanoTime());
                } finally {
                    timer.lazySet(null);
                }
            }, nextMs, TimeUnit.MILLISECONDS);
            // 如果 CAS 失败（意味着别处刚写入 timer），我们仍然要把 scheduled 取消，
            // 并把 pendingWritable 保持（因为我们已经 set 了）
            if (!timer.compareAndSet(null, schedule)) {
                schedule.cancel(false);
            }
            return;
        }

        onEvent(ch, now);

        super.channelWritabilityChanged(ctx);
    }

    void onEvent(Channel ch, long nowNano) {
        if (!ch.isWritable()) {
            if (paused) {
                return;
            }
            // ---- 写入过载 ----
            paused = true;
            if (disableSelfAutoRead) {
                // 优雅暂停 inbound read，防止数据继续进来
                Sockets.disableAutoRead(ch);
            }
            lastEventTs = nowNano;
            BiAction<Channel> fn = onBackpressureStart;
            if (fn != null) {
                fn.accept(ch);
            }
        } else {
            handleRecovery(ch, nowNano, null);
        }
    }

    void handleRecovery(Channel ch, long nowNano, Throwable cause) {
        if (!paused) {
            return;
        }
        // ---- 恢复 ----
        paused = false;
        if (disableSelfAutoRead) {
            Sockets.enableAutoRead(ch);
        }
        lastEventTs = nowNano;
        TripleAction<Channel, Throwable> fn = onBackpressureEnd;
        if (fn != null) {
            fn.accept(ch, cause);
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
