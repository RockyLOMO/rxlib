package org.rx.net.socks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class SSUdpProxyHandlerTest {
    @Test
    void defaultHandlerIsLoadable() {
        assertNotNull(SSUdpProxyHandler.DEFAULT);
    }
}
