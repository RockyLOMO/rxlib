package org.rx.net.socks;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ProxyChannelIdleHandlerTest {
    private static final class IdleEvents extends ChannelInboundHandlerAdapter {
        IdleState state;

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof IdleStateEvent) {
                state = ((IdleStateEvent) evt).state();
            }
        }
    }

    @Test
    void oneWayDownloadSurvivesMultipleIdleWindowsThenClosesWhenSilent() {
        verifyOneWayTransfer(false);
    }

    @Test
    void oneWayUploadSurvivesMultipleIdleWindowsThenClosesWhenSilent() {
        verifyOneWayTransfer(true);
    }

    private void verifyOneWayTransfer(boolean upload) {
        IdleEvents events = new IdleEvents();
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyChannelIdleHandler(0, 0, 120), events);
        channel.freezeTime();
        try {
            for (int i = 0; i < 12; i++) {
                if (upload) {
                    channel.writeInbound(Unpooled.buffer(1).writeByte(1));
                    ReferenceCountUtil.release(channel.readInbound());
                } else {
                    channel.writeOutbound(Unpooled.buffer(1).writeByte(1));
                    ReferenceCountUtil.release(channel.readOutbound());
                }
                channel.advanceTimeBy(30, TimeUnit.SECONDS);
                channel.runScheduledPendingTasks();
                assertTrue(channel.isActive(), "Single-direction activity must keep the relay alive");
                assertNull(events.state);
            }
            channel.advanceTimeBy(121, TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();
            assertEquals(IdleState.ALL_IDLE, events.state);
            assertFalse(channel.isActive());
            assertEquals(-1, channel.runScheduledPendingTasks(), "Close must cancel the idle task");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void completelySilentConnectionClosesAtConfiguredIdleTimeout() {
        IdleEvents events = new IdleEvents();
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyChannelIdleHandler(0, 0, 120), events);
        channel.freezeTime();
        try {
            channel.advanceTimeBy(119, TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();
            assertTrue(channel.isActive());
            channel.advanceTimeBy(1, TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();
            assertEquals(IdleState.ALL_IDLE, events.state);
            assertFalse(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void disabledTimeoutSchedulesNoTask() {
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyChannelIdleHandler(0, 0, 0));
        channel.freezeTime();
        try {
            channel.advanceTimeBy(1000, TimeUnit.SECONDS);
            assertEquals(-1, channel.runScheduledPendingTasks());
            assertTrue(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void legacyDirectionalIdleStillClosesDespiteDownloadActivity() {
        IdleEvents events = new IdleEvents();
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyChannelIdleHandler(120, 0), events);
        channel.freezeTime();
        try {
            for (int i = 0; i < 4; i++) {
                channel.writeOutbound(Unpooled.buffer(1).writeByte(1));
                ReferenceCountUtil.release(channel.readOutbound());
                channel.advanceTimeBy(30, TimeUnit.SECONDS);
                channel.runScheduledPendingTasks();
            }
            assertEquals(IdleState.READER_IDLE, events.state);
            assertFalse(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
