package org.rx.net.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
