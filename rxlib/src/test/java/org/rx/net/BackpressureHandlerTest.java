package org.rx.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class BackpressureHandlerTest {

    @Test
    public void testInstall() {
        EmbeddedChannel inbound = new EmbeddedChannel();
        EmbeddedChannel outbound = new EmbeddedChannel();

        // Ensure WriteBufferWaterMark is set (EmbeddedChannel default might be null or default)
        // EmbeddedChannel uses DefaultChannelConfig which has a default watermark.
        // Let's verify.
        if (outbound.config().getOption(ChannelOption.WRITE_BUFFER_WATER_MARK) == null) {
            outbound.config().setOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WriteBufferWaterMark.DEFAULT);
        }

        BackpressureHandler.install(inbound, outbound);

        assertNotNull(outbound.pipeline().get(BackpressureHandler.class));
    }

    @SneakyThrows
    @Test
    public void testFlow() {
        EmbeddedChannel inbound = new EmbeddedChannel();
        EmbeddedChannel outbound = new EmbeddedChannel();
        // Ensure watermark is present so install works
        outbound.config().setOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WriteBufferWaterMark.DEFAULT);

        BackpressureHandler.install(inbound, outbound);
        BackpressureHandler handler = outbound.pipeline().get(BackpressureHandler.class);
        assertNotNull(handler);

        // Default state
        assertTrue(outbound.isWritable());
        assertFalse(handler.isPaused());
        assertTrue(inbound.config().isAutoRead());

        // 1. Trigger Unwritable on Outbound
        outbound.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
        outbound.pipeline().fireChannelWritabilityChanged();

        assertTrue(handler.isPaused());
        assertFalse(inbound.config().isAutoRead());

        // 2. Wait for cooldown
        Thread.sleep(BackpressureHandler.COOLDOWN_MILLIS + 20);

        // 3. Trigger Writable on Outbound
        outbound.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
        outbound.pipeline().fireChannelWritabilityChanged();

        assertFalse(handler.isPaused());
        assertTrue(inbound.config().isAutoRead());
    }

    @Test
    public void testWritabilityChangedPropagatesWhenTimerExists() {
        EmbeddedChannel inbound = new EmbeddedChannel();
        EmbeddedChannel outbound = new EmbeddedChannel();
        outbound.config().setOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WriteBufferWaterMark.DEFAULT);

        BackpressureHandler.install(inbound, outbound);
        BackpressureHandler handler = outbound.pipeline().get(BackpressureHandler.class);
        AtomicInteger propagated = new AtomicInteger();
        outbound.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
                propagated.incrementAndGet();
                super.channelWritabilityChanged(ctx);
            }
        });

        ScheduledFuture<?> future = outbound.eventLoop().schedule(() -> {
        }, 1, java.util.concurrent.TimeUnit.DAYS);
        handler.timer.set(future);
        outbound.pipeline().fireChannelWritabilityChanged();

        assertEquals(1, propagated.get());
        handler.stopTimer();
        outbound.finishAndReleaseAll();
        inbound.finishAndReleaseAll();
    }

    @Test
    public void testWritabilityChangedPropagatesWhenCooldownSchedules() {
        EmbeddedChannel inbound = new EmbeddedChannel();
        EmbeddedChannel outbound = new EmbeddedChannel();
        outbound.config().setOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WriteBufferWaterMark.DEFAULT);

        BackpressureHandler.install(inbound, outbound);
        BackpressureHandler handler = outbound.pipeline().get(BackpressureHandler.class);
        handler.lastEventTs = System.nanoTime();
        AtomicInteger propagated = new AtomicInteger();
        outbound.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
                propagated.incrementAndGet();
                super.channelWritabilityChanged(ctx);
            }
        });

        outbound.pipeline().fireChannelWritabilityChanged();

        assertEquals(1, propagated.get());
        assertNotNull(handler.timer.get());
        handler.stopTimer();
        outbound.finishAndReleaseAll();
        inbound.finishAndReleaseAll();
    }

    @Test
    public void testChannelActiveRestoresInboundAutoRead() {
        EmbeddedChannel inbound = new EmbeddedChannel();
        EmbeddedChannel outbound = new EmbeddedChannel();
        outbound.config().setOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WriteBufferWaterMark.DEFAULT);
        inbound.config().setAutoRead(false);
        outbound.config().setAutoRead(false);

        BackpressureHandler.install(inbound, outbound);
        outbound.pipeline().fireChannelActive();

        assertTrue(inbound.config().isAutoRead());
        assertFalse(outbound.config().isAutoRead());
        outbound.finishAndReleaseAll();
        inbound.finishAndReleaseAll();
    }

    @SneakyThrows
    @Test
    public void testCustomCallbacks() {
        EmbeddedChannel inbound = new EmbeddedChannel();
        EmbeddedChannel outbound = new EmbeddedChannel();
        outbound.config().setOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WriteBufferWaterMark.DEFAULT);

        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean ended = new AtomicBoolean();

        BackpressureHandler.install(inbound, outbound,
                (in, out) -> started.set(true),
                (in, out, e) -> ended.set(true));

        BackpressureHandler handler = outbound.pipeline().get(BackpressureHandler.class);

        // Trigger Unwritable
        outbound.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
        outbound.pipeline().fireChannelWritabilityChanged();

        assertTrue(handler.isPaused());
        assertTrue(started.get());

        // Reset
        Thread.sleep(BackpressureHandler.COOLDOWN_MILLIS + 20);

        // Trigger Writable
        outbound.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
        outbound.pipeline().fireChannelWritabilityChanged();

        assertFalse(handler.isPaused());
        assertTrue(ended.get());
    }

    @Test
    public void testExceptionCaught() {
        EmbeddedChannel inbound = new EmbeddedChannel();
        EmbeddedChannel outbound = new EmbeddedChannel();
        outbound.config().setOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WriteBufferWaterMark.DEFAULT);

        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        BackpressureHandler.install(inbound, outbound,
                (in, out) -> {
                },
                (in, out, e) -> errorRef.set(e));
        BackpressureHandler handler = outbound.pipeline().get(BackpressureHandler.class);

        // Manually pause
        outbound.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
        outbound.pipeline().fireChannelWritabilityChanged();
        assertTrue(handler.isPaused());

        // Exception
        RuntimeException ex = new RuntimeException("test");
        outbound.pipeline().fireExceptionCaught(ex);

        // Should recover
        assertFalse(handler.isPaused());
        assertSame(ex, errorRef.get());
    }

    @Test
    public void testChannelInactiveEndsPausedBackpressure() {
        EmbeddedChannel inbound = new EmbeddedChannel();
        EmbeddedChannel outbound = new EmbeddedChannel();
        outbound.config().setOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WriteBufferWaterMark.DEFAULT);

        AtomicInteger ended = new AtomicInteger();
        BackpressureHandler.install(inbound, outbound,
                (in, out) -> Sockets.disableAutoRead(in),
                (in, out, e) -> {
                    ended.incrementAndGet();
                    Sockets.enableAutoRead(in);
                });
        BackpressureHandler handler = outbound.pipeline().get(BackpressureHandler.class);

        outbound.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
        outbound.pipeline().fireChannelWritabilityChanged();
        assertTrue(handler.isPaused());
        assertFalse(inbound.config().isAutoRead());

        outbound.pipeline().fireChannelInactive();

        assertFalse(handler.isPaused());
        assertEquals(1, ended.get());
        assertTrue(inbound.config().isAutoRead());
        outbound.finishAndReleaseAll();
        inbound.finishAndReleaseAll();
    }

    @Test
    public void testChannelInactiveEndsPausedBackpressureWhenInboundClosed() {
        EmbeddedChannel inbound = new EmbeddedChannel();
        EmbeddedChannel outbound = new EmbeddedChannel();
        outbound.config().setOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WriteBufferWaterMark.DEFAULT);

        AtomicInteger ended = new AtomicInteger();
        AtomicReference<Channel> callbackInbound = new AtomicReference<>();
        BackpressureHandler.install(inbound, outbound,
                (in, out) -> {
                },
                (in, out, e) -> {
                    callbackInbound.set(in);
                    ended.incrementAndGet();
                });
        BackpressureHandler handler = outbound.pipeline().get(BackpressureHandler.class);

        outbound.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
        outbound.pipeline().fireChannelWritabilityChanged();
        assertTrue(handler.isPaused());

        inbound.close().syncUninterruptibly();
        outbound.pipeline().fireChannelInactive();

        assertFalse(handler.isPaused());
        assertEquals(1, ended.get());
        assertSame(inbound, callbackInbound.get());
        outbound.finishAndReleaseAll();
        inbound.finishAndReleaseAll();
    }
}
