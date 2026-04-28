package org.rx.net.rpc;

import org.junit.jupiter.api.Test;
import org.rx.net.rpc.protocol.EventFlag;
import org.rx.net.rpc.protocol.EventMessage;
import org.rx.net.rpc.protocol.MethodMessage;
import org.rx.net.transport.hybrid.HybridSendOptions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemotingHybridRouteOptionsTest {
    @Test
    void controlEventsForceTcp() {
        assertSame(RemotingHybridOptions.CONTROL, RemotingHybridOptions.event(new EventMessage("e", EventFlag.SUBSCRIBE)));
        assertSame(RemotingHybridOptions.CONTROL, RemotingHybridOptions.event(new EventMessage("e", EventFlag.UNSUBSCRIBE)));
        assertSame(RemotingHybridOptions.CONTROL, RemotingHybridOptions.event(new EventMessage("e", EventFlag.COMPUTE_ARGS)));
    }

    @Test
    void broadcastAndPublishStayAutoRoute() {
        HybridSendOptions publish = RemotingHybridOptions.event(new EventMessage("e", EventFlag.PUBLISH));
        HybridSendOptions broadcast = RemotingHybridOptions.event(new EventMessage("e", EventFlag.BROADCAST));
        HybridSendOptions fallback = RemotingHybridOptions.event(null);

        assertSame(RemotingHybridOptions.EVENT, publish);
        assertSame(RemotingHybridOptions.EVENT, broadcast);
        assertSame(RemotingHybridOptions.EVENT, fallback);
        assertFalse(publish.isForceTcp());
        assertFalse(broadcast.isForceTcp());
        assertFalse(fallback.isForceTcp());
        assertTrue(publish.isFallbackToTcpOnUdpFailure());
    }

    @Test
    void errorResponsesForceTcp() {
        MethodMessage success = new MethodMessage(1, "ok", new Object[0], "trace");
        MethodMessage failure = new MethodMessage(2, "fail", new Object[0], "trace");
        failure.errorMessage = "boom";

        assertSame(RemotingHybridOptions.RESPONSE, RemotingHybridOptions.response(success));
        assertSame(RemotingHybridOptions.CONTROL, RemotingHybridOptions.response(failure));
    }
}
