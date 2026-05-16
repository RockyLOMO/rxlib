package org.rx.net.support;

import com.maxmind.geoip2.DatabaseReader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class GeoIPSearcherTest {
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

    @Test
    public void testIpGeolocationCarriesCityField() {
        IpGeolocation geolocation = new IpGeolocation("China", "CN", "Shanghai", "CN");

        assertEquals("Shanghai", geolocation.getCity());
    }
}
