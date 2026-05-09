package org.rx.net.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class V2RayGeoSiteHotPathTest {
    @Test
    public void compiledMatcherCanBeReusedWithoutSelectorParsing() {
        V2RayGeoSiteIndex index = new V2RayGeoSiteReader().read(V2RayGeoDataTestUtil.geoSiteList(
                V2RayGeoDataTestUtil.geoSiteEntry("google",
                        V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN,
                                "ads.google.com", "ads"))));

        GeoSiteMatcher matcher = index.matcher("geosite:google@ads");
        GeoSiteMatcher cached = index.matcher("google", "ads");

        assertNotNull(matcher);
        assertSame(matcher, cached);
        assertTrue(matcher.matches("www.ads.google.com"));
    }
}
