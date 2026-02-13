package org.rx.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class BackpressureHandlerTest {

    /**
     * 测试基本的背压流程：writable -> unwritable -> writable
     * disableSelfAutoRead=true 时，autoRead 会自动开关
     */
    @SneakyThrows
    @Test
    public void testBasicFlow() {
        BackpressureHandler handler = new BackpressureHandler(true);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        assertTrue(channel.isWritable());
        assertFalse(handler.isPaused());
        assertTrue(channel.config().isAutoRead());

        // 1. 触发 unwritable
        channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
        channel.pipeline().fireChannelWritabilityChanged();

        assertTrue(handler.isPaused());
        assertFalse(channel.config().isAutoRead());

        // 2. 等待超过 COOLDOWN_MILLIS，避免防抖
        Thread.sleep(BackpressureHandler.COOLDOWN_MILLIS + 20);

        // 3. 恢复 writable
        channel.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
        channel.pipeline().fireChannelWritabilityChanged();

        assertFalse(handler.isPaused());
        assertTrue(channel.config().isAutoRead());
    }

    /**
     * 测试 disableSelfAutoRead=false 时不影响 autoRead
     */
    @SneakyThrows
    @Test
    public void testWithoutAutoReadControl() {
        BackpressureHandler handler = new BackpressureHandler(false);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        assertTrue(channel.config().isAutoRead());

        // 触发 unwritable
        channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
        channel.pipeline().fireChannelWritabilityChanged();

        assertTrue(handler.isPaused());
        // disableSelfAutoRead=false，autoRead 不应改变
        assertTrue(channel.config().isAutoRead());

        Thread.sleep(BackpressureHandler.COOLDOWN_MILLIS + 20);

        // 恢复
        channel.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
        channel.pipeline().fireChannelWritabilityChanged();

        assertFalse(handler.isPaused());
        assertTrue(channel.config().isAutoRead());
    }

    /**
     * 测试回调 onBackpressureStart / onBackpressureEnd
     */
    @SneakyThrows
    @Test
    public void testCallbacks() {
        BackpressureHandler handler = new BackpressureHandler(false);
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean ended = new AtomicBoolean();
        AtomicReference<Throwable> endCause = new AtomicReference<>();

        handler.setOnBackpressureStart(ch -> started.set(true));
        handler.setOnBackpressureEnd((ch, cause) -> {
            ended.set(true);
            endCause.set(cause);
        });

        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // unwritable → onBackpressureStart
        channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
        channel.pipeline().fireChannelWritabilityChanged();

        assertTrue(started.get());
        assertFalse(ended.get());

        Thread.sleep(BackpressureHandler.COOLDOWN_MILLIS + 20);

        // writable → onBackpressureEnd
        channel.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
        channel.pipeline().fireChannelWritabilityChanged();

        assertTrue(ended.get());
        assertNull(endCause.get());
    }

    /**
     * 测试 onEvent 直接调用：not writable 时 paused=true
     */
    @Test
    public void testOnEventDirectly() {
        BackpressureHandler handler = new BackpressureHandler(true);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // 直接调用 onEvent，模拟 not writable
        channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
        handler.onEvent(channel, System.nanoTime());
        assertTrue(handler.isPaused());
        assertFalse(channel.config().isAutoRead());

        // 重复调用 onEvent(not writable)，不应重复触发
        AtomicBoolean called = new AtomicBoolean();
        handler.setOnBackpressureStart(ch -> called.set(true));
        handler.onEvent(channel, System.nanoTime());
        assertFalse(called.get(), "paused 状态下不应再触发 onBackpressureStart");
    }

    /**
     * 测试 handleRecovery：已恢复状态下不会重复触发
     */
    @Test
    public void testHandleRecoveryIdempotent() {
        BackpressureHandler handler = new BackpressureHandler(false);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // 未 paused 时调用 handleRecovery 无效
        AtomicBoolean called = new AtomicBoolean();
        handler.setOnBackpressureEnd((ch, e) -> called.set(true));
        handler.handleRecovery(channel, System.nanoTime(), null);
        assertFalse(called.get());

        // paused → 恢复
        handler.paused = true;
        handler.handleRecovery(channel, System.nanoTime(), null);
        assertTrue(called.get());
        assertFalse(handler.isPaused());

        // 再次调用无效
        called.set(false);
        handler.handleRecovery(channel, System.nanoTime(), null);
        assertFalse(called.get());
    }

    /**
     * 测试异常时 exceptionCaught 恢复
     */
    @Test
    public void testExceptionCaughtRecovery() {
        BackpressureHandler handler = new BackpressureHandler(true);
        AtomicReference<Throwable> caught = new AtomicReference<>();
        handler.setOnBackpressureEnd((ch, cause) -> caught.set(cause));

        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // 先进入 paused 状态
        handler.paused = true;
        channel.config().setAutoRead(false);

        // 触发异常
        RuntimeException ex = new RuntimeException("test error");
        channel.pipeline().fireExceptionCaught(ex);

        assertFalse(handler.isPaused(), "异常后应自动恢复");
        assertTrue(channel.config().isAutoRead());
        assertSame(ex, caught.get());
    }

    /**
     * 测试 channelInactive 时清理状态
     */
    @Test
    public void testChannelInactiveCleanup() {
        BackpressureHandler handler = new BackpressureHandler(false);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        handler.paused = true;

        // 关闭 channel 触发 channelInactive
        channel.close();

        assertFalse(handler.isPaused(), "channelInactive 后 paused 应被重置");
        assertNull(handler.timer.get());
    }

    /**
     * 测试 stopTimer 清理定时器
     */
    @Test
    public void testStopTimer() {
        BackpressureHandler handler = new BackpressureHandler(false);
        assertNull(handler.timer.get());

        handler.stopTimer();
        assertNull(handler.timer.get(), "空 timer 调用 stop 不应异常");
    }

    /**
     * 测试防抖逻辑：快速切换时会被延迟
     */
    @SneakyThrows
    @Test
    public void testDebounce() {
        BackpressureHandler handler = new BackpressureHandler(true);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // 1. 触发 unwritable
        channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
        channel.pipeline().fireChannelWritabilityChanged();
        assertTrue(handler.isPaused());

        // 2. 立即恢复 writable (在 COOLDOWN 内)
        channel.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
        channel.pipeline().fireChannelWritabilityChanged();

        // 因为在 COOLDOWN 内，应被 debounce（timer 不为 null）
        assertTrue(handler.isPaused(), "应保持 paused（被防抖延迟）");
        assertNotNull(handler.timer.get());

        // 3. 等待 timer 到期
        Thread.sleep(BackpressureHandler.COOLDOWN_MILLIS + 30);
        channel.runScheduledPendingTasks();

        assertFalse(handler.isPaused(), "timer 到期后应恢复");
    }
}
