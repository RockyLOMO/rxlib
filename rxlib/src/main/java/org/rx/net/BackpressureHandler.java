package org.rx.net;

import io.netty.channel.*;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.Getter;
import org.rx.core.Constants;
import org.rx.util.function.Action;

import java.util.concurrent.TimeUnit;

public class BackpressureHandler extends ChannelInboundHandlerAdapter {
    static final int MIN_SPAN_MILLIS = 20;
    // 使用一个抖动阈值，防止高低水位频繁震荡
    static final long COOLDOWN_MILLIS = 50 + MIN_SPAN_MILLIS;
    //可以通知队列暂停、暂停 SS/HTTP 上游读取、标记对侧 channel
    Action onBackpressureStart;
    //恢复队列、恢复 SS 上游读取…
    Action onBackpressureEnd;
    volatile long lastEventTs;
    volatile boolean lastWritable;
    volatile ScheduledFuture<?> timer;
    // 是否暂停写入（用于业务层，如队列暂停）
    @Getter
    volatile boolean paused;

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        Channel ch = ctx.channel();

        ScheduledFuture<?> t = timer;
        if (t != null) {
            lastWritable = ch.isWritable();
            return;
        }

        long now = System.nanoTime();
        long spanMs = (now - lastEventTs) / Constants.NANO_TO_MILLIS;
        if (spanMs < COOLDOWN_MILLIS) {
            stopTimer();
            long nextMs = COOLDOWN_MILLIS - spanMs;
            if (nextMs > MIN_SPAN_MILLIS) {
                timer = ctx.executor().schedule(() -> {
                    if (timer == null) {
                        return;
                    }
                    onEvent(ch, System.nanoTime());
                }, nextMs, TimeUnit.MILLISECONDS);
                return;
            }
        }
        onEvent(ch, now);

        super.channelWritabilityChanged(ctx);
    }

    void onEvent(Channel ch, long nowNano) {
        stopTimer();
        lastEventTs = nowNano;
        if (!ch.isWritable()) {
            // ---- 写入过载 ----
            paused = true;

            // 优雅暂停 inbound read，防止数据继续进来
            Sockets.disableAutoRead(ch);
            Action fn = onBackpressureStart;
            if (fn != null) {
                fn.run();
            }
        } else {
            // ---- 恢复 ----
            paused = false;

            Sockets.enableAutoRead(ch);
            Action fn = onBackpressureEnd;
            if (fn != null) {
                fn.run();
            }
        }
    }

    void stopTimer() {
        ScheduledFuture<?> f = timer;
        if (f != null) {
            f.cancel(true);
            timer = null;
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // 确保刚连上就打开读取
        Sockets.enableAutoRead(ctx.channel());
        super.channelActive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        // 任何异常都强制恢复 autoRead，防止通道永久卡死
        Sockets.enableAutoRead(ctx.channel());
        super.exceptionCaught(ctx, cause);
    }
}
