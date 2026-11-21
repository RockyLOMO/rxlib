package org.rx.net.socks;

import org.rx.net.Sockets;
import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import org.slf4j.LoggerFactory;

import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

class SSTcpProxyHandlerTest {
    @Test
    void frontendCloseReleasesPendingFirstPacket() {
        EmbeddedChannel frontend =
                new EmbeddedChannel(SocksTcpFrontendRelayHandler.DEFAULT);
        EmbeddedChannel backend = new EmbeddedChannel();
        ChannelPromise connecting = backend.newPromise();
        ByteBuf payload = Unpooled.buffer().writeInt(42);
        try {
            SocksContext.markCtx(frontend, connecting, SocksContext.getCtx(null, null));
            frontend.writeInbound(payload);
            assertFalse(frontend.config().isAutoRead());
            frontend.close();
            assertEquals(0, payload.refCnt());
            connecting.trySuccess();
            // closeOnFlushed writes an empty buffer to move the close behind pending writes.
            ByteBuf flushed = backend.readOutbound();
            if (flushed != null) {
                try {
                    assertFalse(flushed.isReadable());
                } finally {
                    flushed.release();
                }
            }
            assertNull(backend.readOutbound());
        } finally {
            frontend.finishAndReleaseAll();
            backend.finishAndReleaseAll();
        }
    }

    @Test
    void logConnectFailure_WarnKeepsSourceAndCauseWithoutStack() {
        Logger logger = (Logger) LoggerFactory.getLogger(SSTcpProxyHandler.class);
        Level oldLevel = logger.getLevel();
        boolean oldAdditive = logger.isAdditive();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        logger.setAdditive(false);
        try {
            SSTcpProxyHandler.logConnectFailure(
                    Sockets.newUnresolvedEndpoint("missing.fly.dev", 443),
                    Sockets.newUnresolvedEndpoint("missing.fly.dev", 443),
                    new UnknownHostException("missing.fly.dev"));

            assertEquals(1, appender.list.size());
            ILoggingEvent event = appender.list.get(0);
            assertEquals(Level.WARN, event.getLevel());
            assertNull(event.getThrowableProxy());

            String message = event.getFormattedMessage();
            assertTrue(message.contains("source=org.rx.net.socks.SSTcpProxyHandler"));
            assertTrue(message.contains("cause=java.net.UnknownHostException"));
            assertTrue(message.contains("missing.fly.dev"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(oldLevel);
            logger.setAdditive(oldAdditive);
        }
    }
}
