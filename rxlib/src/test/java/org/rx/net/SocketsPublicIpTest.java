package org.rx.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SocketsPublicIpTest {
    @Test
    public void getPublicIpUsesCachedSnapshot() {
        Sockets.PublicIpSnapshot old = Sockets.publicIpSnapshot;
        try {
            Sockets.publicIpSnapshot = new Sockets.PublicIpSnapshot("1.2.3.4", System.nanoTime());

            assertEquals("1.2.3.4", Sockets.getPublicIp());
        } finally {
            Sockets.publicIpSnapshot = old;
        }
    }
}
