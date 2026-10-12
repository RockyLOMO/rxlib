package org.rx.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;

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

    @Test
    public void freshOverloadBypassesCacheAndPublishesSuccessfulRefresh() {
        Sockets.PublicIpSnapshot old = Sockets.publicIpSnapshot;
        try (org.mockito.MockedStatic<PublicIpObserver> observer = mockStatic(PublicIpObserver.class)) {
            Sockets.publicIpSnapshot = new Sockets.PublicIpSnapshot("58.209.44.49", System.nanoTime());
            observer.when(() -> PublicIpObserver.observe(org.mockito.ArgumentMatchers.any(String[].class))).thenReturn("58.209.44.50");
            assertEquals("58.209.44.49", Sockets.getPublicIp());
            assertEquals("58.209.44.50", Sockets.getPublicIp(false));
            assertEquals("58.209.44.50", Sockets.getPublicIp());
            observer.verify(() -> PublicIpObserver.observe(org.mockito.ArgumentMatchers.any(String[].class)), org.mockito.Mockito.times(1));
        } finally { Sockets.publicIpSnapshot = old; }
    }
}
