package org.rx.net.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class V2RayGeoSiteAttributeTest {
    @Test
    public void ignoresFalseBooleanAttributeValues() {
        V2RayGeoSiteIndex index = new V2RayGeoSiteReader().read(V2RayGeoDataTestUtil.geoSiteList(
                V2RayGeoDataTestUtil.geoSiteEntry("google",
                        V2RayGeoDataTestUtil.domainWithAttributeMessages(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN,
                                "ads.google.com", V2RayGeoDataTestUtil.attribute("ads", true)),
                        V2RayGeoDataTestUtil.domainWithAttributeMessages(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN,
                                "mail.google.com", V2RayGeoDataTestUtil.attribute("ads", false)),
                        V2RayGeoDataTestUtil.domainWithAttributeMessages(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN,
                                "cdn.google.com", V2RayGeoDataTestUtil.attribute("cn", 7L)))));

        assertTrue(index.matches("google", "ads", "www.ads.google.com"));
        assertFalse(index.matches("google", "ads", "www.mail.google.com"));
        assertTrue(index.matches("google", "!ads", "www.mail.google.com"));
        assertTrue(index.matches("google", "cn", "www.cdn.google.com"));
    }
}
