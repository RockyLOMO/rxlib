package org.rx.net.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class V2RayGeoIpMatcherTest {
    @Test
    public void matchesIpv4Ipv6BoundariesAndInverseRule() {
        V2RayGeoIpMatcher matcher = new V2RayGeoIpReader().read(V2RayGeoDataTestUtil.geoIpList(
                V2RayGeoDataTestUtil.geoIpEntry("cn", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(1, 2, 0, 0), 16),
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip6("2001:db8::"), 32)),
                V2RayGeoDataTestUtil.geoIpEntry("us", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(8, 8, 8, 0), 24)),
                V2RayGeoDataTestUtil.geoIpEntry("geolocation-!cn", true,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(1, 2, 0, 0), 16))));

        assertTrue(matcher.matches("geoip:cn", "1.2.0.0"));
        assertTrue(matcher.matches("cn", "1.2.255.255"));
        assertFalse(matcher.matches("cn", "1.3.0.0"));
        assertTrue(matcher.matches("cn", "2001:db8::1"));
        assertFalse(matcher.matches("cn", "2001:db9::1"));

        assertFalse(matcher.matches("geolocation-!cn", "1.2.3.4"));
        assertTrue(matcher.matches("geolocation-!cn", "9.9.9.9"));
        assertFalse(matcher.matches("cn", "example.com"));

        assertEquals("cn", matcher.lookupCode("1.2.3.4"));
        assertEquals("us", matcher.lookupCode("8.8.8.8"));
        assertEquals("geolocation-!cn", matcher.lookupCode("9.9.9.9"));
        assertNull(matcher.lookupCode("example.com"));
    }

    @Test
    public void mergesAdjacentIpv4RangesPerEntry() {
        V2RayGeoIpMatcher matcher = new V2RayGeoIpReader().read(V2RayGeoDataTestUtil.geoIpList(
                V2RayGeoDataTestUtil.geoIpEntry("merge", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(10, 0, 0, 0), 25),
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(10, 0, 0, 128), 25))));

        assertTrue(matcher.matches("merge", "10.0.0.255"));
        assertEquals(1, matcher.index.codeMatchers.get("merge").entries[0].ipv4.starts.length);
    }

    @Test
    public void lookupCodeKeepsFirstEntryPriorityForOverlappingRanges() {
        V2RayGeoIpMatcher matcher = new V2RayGeoIpReader().read(V2RayGeoDataTestUtil.geoIpList(
                V2RayGeoDataTestUtil.geoIpEntry("first", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(10, 0, 0, 0), 8),
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip6("2001:db8::"), 32)),
                V2RayGeoDataTestUtil.geoIpEntry("second", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(10, 1, 0, 0), 16),
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(11, 0, 0, 0), 8),
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip6("2001:db8:1::"), 48))));

        assertEquals("first", matcher.lookupCode("10.1.2.3"));
        assertEquals("second", matcher.lookupCode("11.1.2.3"));
        assertEquals("first", matcher.lookupCode("2001:db8:1::1"));
    }
}
