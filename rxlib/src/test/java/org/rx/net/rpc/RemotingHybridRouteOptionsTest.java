package org.rx.net.rpc;

import org.junit.jupiter.api.Test;
import org.rx.net.rpc.protocol.EventFlag;
import org.rx.net.rpc.protocol.EventMessage;
import org.rx.net.rpc.protocol.MethodMessage;

import static org.junit.jupiter.api.Assertions.assertSame;

class RemotingHybridRouteOptionsTest {
    @Test
    void controlEventsForceTcp() {
        assertSame(RemotingHybridOptions.CONTROL, RemotingHybridOptions.event(new EventMessage("e", EventFlag.SUBSCRIBE)));
        assertSame(RemotingHybridOptions.CONTROL, RemotingHybridOptions.event(new EventMessage("e", EventFlag.UNSUBSCRIBE)));
        assertSame(RemotingHybridOptions.CONTROL, RemotingHybridOptions.event(new EventMessage("e", EventFlag.COMPUTE_ARGS)));
    }

    @Test
    void broadcastAndPublishStayAutoRoute() {
        assertSame(RemotingHybridOptions.EVENT, RemotingHybridOptions.event(new EventMessage("e", EventFlag.PUBLISH)));
        assertSame(RemotingHybridOptions.EVENT, RemotingHybridOptions.event(new EventMessage("e", EventFlag.BROADCAST)));
        assertSame(RemotingHybridOptions.EVENT, RemotingHybridOptions.event(null));
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
