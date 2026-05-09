package org.rx.net.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class V2RayGeoIpMatcherHotPathTest {
    @Test
    public void preboundMatcherReusesNormalizedCodeAndParsedIpBytes() {
        V2RayGeoIpMatcher matcher = new V2RayGeoIpReader().read(V2RayGeoDataTestUtil.geoIpList(
                V2RayGeoDataTestUtil.geoIpEntry("CN", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(1, 2, 0, 0), 16))));

        V2RayGeoIpMatcher.CodeMatcher codeMatcher = matcher.matcher(" geoip:cn ");

        assertNotNull(codeMatcher);
        assertTrue(codeMatcher.matches(V2RayGeoDataTestUtil.ip4(1, 2, 3, 4)));
        assertFalse(codeMatcher.matches(V2RayGeoDataTestUtil.ip4(1, 3, 0, 1)));
        assertFalse(codeMatcher.matches(new byte[]{1, 2, 3}));
    }

    @Test
    public void mergesMultipleNormalEntriesPerCodeForHotPathMatcher() {
        V2RayGeoIpMatcher matcher = new V2RayGeoIpReader().read(V2RayGeoDataTestUtil.geoIpList(
                V2RayGeoDataTestUtil.geoIpEntry("merge", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(10, 0, 0, 0), 24)),
                V2RayGeoDataTestUtil.geoIpEntry("merge", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(10, 0, 1, 0), 24))));

        V2RayGeoIpMatcher.CodeMatcher codeMatcher = matcher.matcher("merge");

        assertNotNull(codeMatcher);
        assertTrue(codeMatcher.matches(V2RayGeoDataTestUtil.ip4(10, 0, 0, 1)));
        assertTrue(codeMatcher.matches(V2RayGeoDataTestUtil.ip4(10, 0, 1, 1)));
        assertFalse(codeMatcher.matches(V2RayGeoDataTestUtil.ip4(10, 0, 2, 1)));
        assertEquals(1, matcher.index.codeMatchers.get("merge").entries.length);
    }

    @Test
    public void matchesIpv4AndIpv6FullRangeBoundaries() {
        V2RayGeoIpMatcher matcher = new V2RayGeoIpReader().read(V2RayGeoDataTestUtil.geoIpList(
                V2RayGeoDataTestUtil.geoIpEntry("all4", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(0, 0, 0, 0), 0)),
                V2RayGeoDataTestUtil.geoIpEntry("max4", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(255, 255, 255, 255), 32)),
                V2RayGeoDataTestUtil.geoIpEntry("all6", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip6("::"), 0)),
                V2RayGeoDataTestUtil.geoIpEntry("max6", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip6(
                                "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"), 128))));

        assertTrue(matcher.matcher("all4").matches(V2RayGeoDataTestUtil.ip4(0, 0, 0, 0)));
        assertTrue(matcher.matcher("all4").matches(V2RayGeoDataTestUtil.ip4(255, 255, 255, 255)));
        assertTrue(matcher.matcher("max4").matches(V2RayGeoDataTestUtil.ip4(255, 255, 255, 255)));
        assertTrue(matcher.matcher("all6").matches(V2RayGeoDataTestUtil.ip6("::1")));
        assertTrue(matcher.matcher("all6").matches(V2RayGeoDataTestUtil.ip6(
                "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")));
        assertTrue(matcher.matcher("max6").matches(V2RayGeoDataTestUtil.ip6(
                "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")));
    }
}
