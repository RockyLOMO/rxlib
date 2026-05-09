package org.rx.net.support;

import org.junit.jupiter.api.Test;

import java.util.Collections;

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

        manager.siteIndex = index;
        manager.directSiteMatcher = index.matcher("cn", null);
        manager.directSiteExtraMatcher = new GeoSiteMatcher(Collections.singletonList("full:intranet.example").iterator());

        assertTrue(manager.matchSiteDirect("www.baidu.com"));
        assertTrue(manager.matchSiteDirect("intranet.example"));
        assertFalse(manager.matchSiteDirect("www.google.com"));

        GeoSiteMatcher matcher = manager.siteMatcher("cn");
        assertNotNull(matcher);
        assertTrue(matcher.matches("www.baidu.com"));
        assertTrue(manager.matchGeoSite("geosite:cn", "www.baidu.com"));
    }
}
