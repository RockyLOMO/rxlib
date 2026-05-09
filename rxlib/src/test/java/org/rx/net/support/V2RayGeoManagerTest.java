package org.rx.net.support;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class V2RayGeoManagerTest {
    @Test
    public void matchSiteDirectUsesV2rayCnMatcherAndExtraRules() {
        V2RayGeoManager manager = new V2RayGeoManager(false);
        V2RayGeoSiteIndex index = new V2RayGeoSiteReader().read(V2RayGeoDataTestUtil.geoSiteList(
                V2RayGeoDataTestUtil.geoSiteEntry("cn",
                        V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN, "baidu.com"))));
        V2RayGeoIpMatcher ipMatcher = new V2RayGeoIpReader().read(V2RayGeoDataTestUtil.geoIpList(
                V2RayGeoDataTestUtil.geoIpEntry("cn", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(1, 2, 0, 0), 16))));

        manager.siteIndex = index;
        manager.ipMatcher = ipMatcher;
        manager.directSiteMatcher = index.matcher("cn", null);
        manager.directSiteExtraMatcher = new GeoSiteMatcher(Collections.singletonList("full:intranet.example").iterator());

        assertTrue(manager.matchSiteDirect("www.baidu.com"));
        assertTrue(manager.matchSiteDirect("intranet.example"));
        assertFalse(manager.matchSiteDirect("www.google.com"));

        GeoSiteMatcher matcher = manager.siteMatcher("cn");
        assertNotNull(matcher);
        assertTrue(matcher.matches("www.baidu.com"));
        assertTrue(manager.matchGeoSite("geosite:cn", "www.baidu.com"));
        assertTrue(manager.matchGeoIp("cn", V2RayGeoDataTestUtil.ip4(1, 2, 3, 4)));
        assertEquals("cn", manager.resolveGeoIpCode(V2RayGeoDataTestUtil.ip4(1, 2, 3, 4)));

        V2RayGeoIpMatcher.CodeMatcher geoIpMatcher = manager.compileGeoIpMatcher("cn");
        assertNotNull(geoIpMatcher);
        assertTrue(geoIpMatcher.matches(V2RayGeoDataTestUtil.ip4(1, 2, 3, 4)));
    }
}
