package org.rx.net.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class V2RayGeoSiteMatcherTest {
    @Test
    public void matchesAllV2rayDomainTypes() {
        V2RayGeoSiteIndex index = new V2RayGeoSiteReader().read(V2RayGeoDataTestUtil.geoSiteList(
                V2RayGeoDataTestUtil.geoSiteEntry("google",
                        V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN, "Google.COM"),
                        V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_FULL, "Exact.Example.COM"),
                        V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_PLAIN, "GMail"),
                        V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_REGEX, ".*\\.test$"))));

        assertTrue(index.matches("google", "mail.google.com"));
        assertTrue(index.matches("google", "EXACT.example.com"));
        assertFalse(index.matches("google", "a.exact.example.com"));
        assertTrue(index.matches("google", "fooGMAILbar.net"));
        assertTrue(index.matches("google", "demo.test"));
        assertFalse(index.matches("google", "example.org"));
    }

    @Test
    public void matchesAttributeIncludeExcludeAndGeositeSyntax() {
        V2RayGeoSiteIndex index = new V2RayGeoSiteReader().read(V2RayGeoDataTestUtil.geoSiteList(
                V2RayGeoDataTestUtil.geoSiteEntry("google",
                        V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN, "ads.google.com", "ads"),
                        V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN, "mail.google.com", "mail"))));

        assertTrue(index.matches("google", "ads", "www.ads.google.com"));
        assertFalse(index.matches("google", "ads", "www.mail.google.com"));
        assertTrue(index.matches("google", "!ads", "www.mail.google.com"));
        assertFalse(index.matches("google", "!ads", "www.ads.google.com"));
        assertTrue(index.matches("geosite:google@ads", "www.ads.google.com"));
    }

    @Test
    public void usesCountryCodeWhenCodeMissing() {
        V2RayGeoSiteIndex index = new V2RayGeoSiteReader().read(V2RayGeoDataTestUtil.geoSiteList(
                V2RayGeoDataTestUtil.geoSiteEntryByCountry("CN",
                        V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN, "baidu.com"))));

        assertTrue(index.matches("cn", "www.baidu.com"));
        assertTrue(index.matches("geosite:CN", "www.baidu.com"));
    }
}
