package org.rx.net.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class V2RayGeoIpLookupInverseTest {
    @Test
    public void lookupCodeRespectsNormalAndInversePriority() {
        V2RayGeoIpMatcher matcher = new V2RayGeoIpReader().read(V2RayGeoDataTestUtil.geoIpList(
                V2RayGeoDataTestUtil.geoIpEntry("normal", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(10, 1, 2, 0), 24)),
                V2RayGeoDataTestUtil.geoIpEntry("inverse-a", true,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(10, 0, 0, 0), 8)),
                V2RayGeoDataTestUtil.geoIpEntry("inverse-b", true,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(192, 168, 0, 0), 16))));

        assertEquals("normal", matcher.lookupCode(V2RayGeoDataTestUtil.ip4(10, 1, 2, 3)));
        assertEquals("inverse-a", matcher.lookupCode(V2RayGeoDataTestUtil.ip4(8, 8, 8, 8)));
        assertEquals("inverse-b", matcher.lookupCode(V2RayGeoDataTestUtil.ip4(10, 2, 3, 4)));
    }

    @Test
    public void lookupCodeRespectsIpv6InversePriority() {
        V2RayGeoIpMatcher matcher = new V2RayGeoIpReader().read(V2RayGeoDataTestUtil.geoIpList(
                V2RayGeoDataTestUtil.geoIpEntry("normal-v6", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip6("2001:db8:1::"), 48)),
                V2RayGeoDataTestUtil.geoIpEntry("inverse-v6-a", true,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip6("2001:db8::"), 32)),
                V2RayGeoDataTestUtil.geoIpEntry("inverse-v6-b", true,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip6("2001:db9::"), 32))));

        assertEquals("normal-v6", matcher.lookupCode(V2RayGeoDataTestUtil.ip6("2001:db8:1::1")));
        assertEquals("inverse-v6-a", matcher.lookupCode(V2RayGeoDataTestUtil.ip6("2001:db7::1")));
        assertEquals("inverse-v6-b", matcher.lookupCode(V2RayGeoDataTestUtil.ip6("2001:db8:2::1")));
    }

    @Test
    public void lookupCodeReturnsNullForFullInverseComplement() {
        V2RayGeoIpMatcher matcher = new V2RayGeoIpReader().read(V2RayGeoDataTestUtil.geoIpList(
                V2RayGeoDataTestUtil.geoIpEntry("inverse-all", true,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(0, 0, 0, 0), 0),
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip6("::"), 0))));

        assertNull(matcher.lookupCode(V2RayGeoDataTestUtil.ip4(8, 8, 8, 8)));
        assertNull(matcher.lookupCode(V2RayGeoDataTestUtil.ip6("2001:db8::1")));
    }
}
