package org.rx.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
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
}
