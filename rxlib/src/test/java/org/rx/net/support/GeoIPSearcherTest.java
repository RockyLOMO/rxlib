package org.rx.net.support;

import com.maxmind.geoip2.DatabaseReader;
import org.junit.jupiter.api.Test;
import org.rx.net.http.HttpClient;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class GeoIPSearcherTest {
    static class TestGeoIPSearcher extends GeoIPSearcher {
        final AtomicInteger queryCount = new AtomicInteger();

        TestGeoIPSearcher(String response) {
            super((DatabaseReader) null);
            this.response = response;
        }

        final String response;

        @Override
        HttpClient createPublicIpClient() {
            return new HttpClient();
        }

        @Override
        String[] publicIpServices() {
            return new String[]{"mock://public-ip"};
        }

        @Override
        String queryPublicIp(HttpClient client, String service) {
            queryCount.incrementAndGet();
            return response;
        }
    }

    @Test
    public void testGetPublicIpTrimsAndCachesResult() {
        TestGeoIPSearcher searcher = new TestGeoIPSearcher(" 1.2.3.4 \r\n");

        assertEquals("1.2.3.4", searcher.getPublicIp());
        assertEquals("1.2.3.4", searcher.getPublicIp());
        assertEquals(1, searcher.queryCount.get());
    }

    @Test
    public void testLookupRejectsHostnameWithoutDnsFallback() {
        GeoIPSearcher searcher = new GeoIPSearcher((DatabaseReader) null);

        assertSame(GeoIPSearcher.UNKNOWN_IP, searcher.lookup("example.com"));
        assertSame(GeoIPSearcher.UNKNOWN_IP, searcher.lookup(" example.com "));
    }

    @Test
    public void testLookupRecognizesPrivateIpWithoutDatabase() {
        GeoIPSearcher searcher = new GeoIPSearcher((DatabaseReader) null);

        assertSame(GeoIPSearcher.PRIVATE_IP, searcher.lookup("192.168.31.2"));
        assertSame(GeoIPSearcher.PRIVATE_IP, searcher.lookup("127.0.0.1"));
    }
}
