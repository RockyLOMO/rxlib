package org.rx.net.support;

import org.junit.jupiter.api.Test;
import org.rx.util.rss.V2RayRouteAction;
import org.rx.util.rss.V2RayUserRule;
import org.rx.util.rss.V2RayUserRuleMatcher;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class V2RayUserRuleMatcherTest {
    @Test
    public void matchAppliesUserRouteOrderAndDefaultProxy() {
        V2RayGeoManager manager = new V2RayGeoManager(false);
        try {
            manager.siteIndex = new V2RayGeoSiteReader().read(V2RayGeoDataTestUtil.geoSiteList(
                    V2RayGeoDataTestUtil.geoSiteEntry("cn",
                            V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN, "baidu.com"))));
            manager.ipMatcher = new V2RayGeoIpReader().read(V2RayGeoDataTestUtil.geoIpList(
                    V2RayGeoDataTestUtil.geoIpEntry("cn", false,
                            V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(1, 2, 0, 0), 16))));

            V2RayUserRule rule = new V2RayUserRule();
            rule.setRules(Arrays.asList(
                    "full:www.baidu.com block",
                    "geosite:cn direct",
                    "geoip:cn proxy"));
            V2RayUserRuleMatcher matcher = V2RayUserRuleMatcher.compile(rule, manager, "ss-rocky");

            assertEquals(V2RayRouteAction.BLOCK, matcher.match("www.baidu.com"));
            assertEquals(V2RayRouteAction.DIRECT, matcher.match("map.baidu.com"));
            assertEquals(V2RayRouteAction.PROXY, matcher.match("1.2.3.4"));
            assertEquals(V2RayRouteAction.PROXY, matcher.match("unknown.example"));
        } finally {
            manager.close();
        }
    }

    @Test
    public void matchFirstOrderedIpRuleWinsOverLaterGeoIpRule() {
        V2RayGeoManager manager = new V2RayGeoManager(false);
        try {
            manager.ipMatcher = new V2RayGeoIpReader().read(V2RayGeoDataTestUtil.geoIpList(
                    V2RayGeoDataTestUtil.geoIpEntry("cn", false,
                            V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(192, 168, 0, 0), 16))));

            V2RayUserRule directFirst = new V2RayUserRule();
            directFirst.setRules(Arrays.asList(
                    "192.168.31.1 direct",
                    "geoip:cn proxy"));
            assertEquals(V2RayRouteAction.DIRECT,
                    V2RayUserRuleMatcher.compile(directFirst, manager, "ss-rocky").match("192.168.31.1"));

            V2RayUserRule proxyFirst = new V2RayUserRule();
            proxyFirst.setRules(Arrays.asList(
                    "geoip:cn proxy",
                    "192.168.31.1 direct"));
            assertEquals(V2RayRouteAction.PROXY,
                    V2RayUserRuleMatcher.compile(proxyFirst, manager, "ss-rocky").match("192.168.31.1"));
        } finally {
            manager.close();
        }
    }

    @Test
    public void matchEmptyRuleDefaultsCnDirectAndOthersProxy() {
        V2RayGeoManager manager = new V2RayGeoManager(false);
        try {
            manager.siteIndex = new V2RayGeoSiteReader().read(V2RayGeoDataTestUtil.geoSiteList(
                    V2RayGeoDataTestUtil.geoSiteEntry("cn",
                            V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN, "baidu.com"))));
            manager.ipMatcher = new V2RayGeoIpReader().read(V2RayGeoDataTestUtil.geoIpList(
                    V2RayGeoDataTestUtil.geoIpEntry("cn", false,
                            V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(1, 2, 0, 0), 16))));

            V2RayUserRuleMatcher matcher = V2RayUserRuleMatcher.compileDefaultRouteRules(null, manager);

            assertEquals(V2RayRouteAction.DIRECT, matcher.match("map.baidu.com"));
            assertEquals(V2RayRouteAction.DIRECT, matcher.match("1.2.3.4"));
            assertEquals(V2RayRouteAction.PROXY, matcher.match("unknown.example"));
        } finally {
            manager.close();
        }
    }

    @Test
    public void compileDisabledOrEmptyRouteReturnsNull() {
        V2RayUserRule rule = new V2RayUserRule();
        rule.setEnabled(Boolean.FALSE);

        assertNull(V2RayUserRuleMatcher.compile(rule, null, "ss-rocky"));

        rule.setEnabled(null);
        rule.setRules(Collections.<String>emptyList());
        assertNull(V2RayUserRuleMatcher.compile(rule, null, "ss-rocky"));
    }

    @Test
    public void matchDefaultKeywordControlsUnmatchedRoute() {
        V2RayUserRule rule = new V2RayUserRule();
        rule.setRules(Collections.singletonList("default direct"));

        V2RayUserRuleMatcher matcher = V2RayUserRuleMatcher.compile(rule, null, "ss-rocky");

        assertEquals(V2RayRouteAction.DIRECT, matcher.match("unknown.example"));
    }
}
