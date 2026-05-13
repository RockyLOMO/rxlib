package org.rx.net.socks;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.rx.net.support.UnresolvedEndpoint;
import org.slf4j.LoggerFactory;

import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

class SSTcpProxyHandlerTest {
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
                    new UnresolvedEndpoint("missing.fly.dev", 443),
                    new UnresolvedEndpoint("missing.fly.dev", 443),
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
