package org.rx.net;

import io.netty.channel.ConnectTimeoutException;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.UnresolvedAddressException;

import static org.junit.jupiter.api.Assertions.*;

public class GlobalChannelHandlerTest {
    @Test
    public void testExpectedConnectFailures() {
        assertTrue(GlobalChannelHandler.isExpectedConnectFailure(new ConnectTimeoutException("timeout")));
        assertTrue(GlobalChannelHandler.isExpectedConnectFailure(new ConnectException("refused")));
        assertTrue(GlobalChannelHandler.isExpectedConnectFailure(new NoRouteToHostException("noroute")));
        assertTrue(GlobalChannelHandler.isExpectedConnectFailure(new UnknownHostException("unknown")));
        assertTrue(GlobalChannelHandler.isExpectedConnectFailure(new UnresolvedAddressException()));
    }

    @Test
    public void testExpectedConnectFailuresFromWrappedCause() {
        assertTrue(GlobalChannelHandler.isExpectedConnectFailure(new RuntimeException(new NoRouteToHostException("noroute"))));
    }

    @Test
    public void testUnexpectedConnectFailure() {
        assertFalse(GlobalChannelHandler.isExpectedConnectFailure(new IllegalStateException("boom")));
    }

    @Test
    public void testConnectFailureSummary() {
        assertEquals("NoRouteToHostException: noroute",
                GlobalChannelHandler.connectFailureSummary(new RuntimeException(new NoRouteToHostException("noroute"))));
        assertEquals("UnresolvedAddressException",
                GlobalChannelHandler.connectFailureSummary(new UnresolvedAddressException()));
    }

    @Test
    public void testExpectedChannelExceptions() {
        assertTrue(GlobalChannelHandler.isExpectedChannelException(new ClosedChannelException()));
        assertTrue(GlobalChannelHandler.isExpectedChannelException(new NotYetConnectedException()));
        assertTrue(GlobalChannelHandler.isExpectedChannelException(new RuntimeException(new NotYetConnectedException())));
        assertFalse(GlobalChannelHandler.isExpectedChannelException(new IllegalStateException("boom")));
    }
}
