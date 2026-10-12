package org.rx.net;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PublicIpObserverTest {
    @Test void domesticAgreementStopsBeforeForeignEndpoints() {
        String[] endpoints = {"https://myip.ipip.net/json", "https://ip.3322.net/", "https://api.ipify.org/"};
        List<String> called = new ArrayList<>();
        String ip = PublicIpObserver.observe(endpoints, endpoint -> {
            called.add(endpoint); return endpoint.contains("ipip") ? "{\"data\":{\"ip\":\"58.209.44.49\"}}" : "58.209.44.49\n";
        });
        assertEquals("58.209.44.49", ip); assertEquals(2, called.size());
    }
    @Test void errorsAndDisagreementFallBackWithoutAcceptingOneVote() {
        String[] endpoints = {"https://a.example/", "https://b.example/", "https://c.example/", "https://d.example/"};
        assertEquals("58.209.44.49", PublicIpObserver.observe(endpoints, endpoint -> {
            if (endpoint.contains("a.example")) throw new IllegalStateException("fixture");
            return endpoint.contains("b.example") ? "58.209.44.50" : "58.209.44.49";
        }));
        assertThrows(IllegalStateException.class, () -> PublicIpObserver.observe(new String[]{"https://same.example/a", "https://same.example/b"}, endpoint -> "58.209.44.49"));
    }
    @Test void errorPagesPrivateIpv6AndCredentialsAreRejected() {
        for (String body : new String[]{"Error: server 58.209.44.49 failed", "<html>58.209.44.49</html>", "192.168.31.4", "::1", "58.209.44.49 58.209.44.50", "{\"ip\":\"58.209.44.49\"}"})
            assertThrows(IllegalArgumentException.class, () -> PublicIpObserver.parse(URI.create("https://ip.3322.net/"), body));
        assertThrows(IllegalStateException.class, () -> PublicIpObserver.observe(new String[]{"http://a.example/", "https://secret@b.example/"}, endpoint -> "58.209.44.49"));
        assertThrows(IllegalArgumentException.class, () -> PublicIpObserver.parse(URI.create("https://myip.ipip.net/json"), "{\"data\":{\"ip\":\"10.1.1.1\"}}"));
    }
}
