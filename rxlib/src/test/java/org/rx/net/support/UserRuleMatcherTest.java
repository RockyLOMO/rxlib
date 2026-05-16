package org.rx.net.support;

import org.junit.jupiter.api.Test;
import org.rx.util.rss.RouteAction;
import org.rx.util.rss.UserRule;
import org.rx.util.rss.UserRuleMatcher;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class UserRuleMatcherTest {
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

            UserRule rule = new UserRule();
            rule.setRules(Arrays.asList(
                    "full:www.baidu.com block",
                    "geosite:cn direct",
                    "geoip:cn proxy"));
            UserRuleMatcher matcher = UserRuleMatcher.compile(rule, manager, "ss-rocky");

            assertEquals(RouteAction.BLOCK, matcher.match("www.baidu.com"));
            assertEquals(RouteAction.DIRECT, matcher.match("map.baidu.com"));
            assertEquals(RouteAction.PROXY, matcher.match("1.2.3.4"));
            assertEquals(RouteAction.PROXY, matcher.match("unknown.example"));
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

            UserRule directFirst = new UserRule();
            directFirst.setRules(Arrays.asList(
                    "192.168.31.1 direct",
                    "geoip:cn proxy"));
            assertEquals(RouteAction.DIRECT,
                    UserRuleMatcher.compile(directFirst, manager, "ss-rocky").match("192.168.31.1"));

            UserRule proxyFirst = new UserRule();
            proxyFirst.setRules(Arrays.asList(
                    "geoip:cn proxy",
                    "192.168.31.1 direct"));
            assertEquals(RouteAction.PROXY,
                    UserRuleMatcher.compile(proxyFirst, manager, "ss-rocky").match("192.168.31.1"));
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

            UserRuleMatcher matcher = UserRuleMatcher.compileDefaultRouteRules(null, manager);

            assertEquals(RouteAction.DIRECT, matcher.match("map.baidu.com"));
            assertEquals(RouteAction.DIRECT, matcher.match("1.2.3.4"));
            assertEquals(RouteAction.PROXY, matcher.match("unknown.example"));
        } finally {
            manager.close();
        }
    }

    @Test
    public void compileDisabledOrEmptyRouteReturnsNull() {
        UserRule rule = new UserRule();
        rule.setEnabled(Boolean.FALSE);

        assertNull(UserRuleMatcher.compile(rule, null, "ss-rocky"));

        rule.setEnabled(null);
        rule.setRules(Collections.<String>emptyList());
        assertNull(UserRuleMatcher.compile(rule, null, "ss-rocky"));
    }

    @Test
    public void matchDefaultKeywordControlsUnmatchedRoute() {
        UserRule rule = new UserRule();
        rule.setRules(Collections.singletonList("default direct"));

        UserRuleMatcher matcher = UserRuleMatcher.compile(rule, null, "ss-rocky");

        assertEquals(RouteAction.DIRECT, matcher.match("unknown.example"));
    }

    @Test
    public void matchEndpointIpRulesUseRouteContextAndOrder() {
        UserRule rule = new UserRule();
        rule.setRules(Arrays.asList(
                "srcIp 192.168.31.7 direct",
                "dstIp 8.8.8.8 proxy",
                "default block"));
        UserRuleMatcher matcher = UserRuleMatcher.compile(rule, null, "ss-rocky");

        assertEquals(RouteAction.DIRECT,
                matcher.match("8.8.8.8", 53, new InetSocketAddress("192.168.31.7", 41000)));
        assertEquals(RouteAction.PROXY,
                matcher.match("8.8.8.8", 53, new InetSocketAddress("192.168.31.8", 41000)));
        assertEquals(RouteAction.BLOCK,
                matcher.match("1.1.1.1", 53, new InetSocketAddress("192.168.31.8", 41000)));
    }

    @Test
    public void matchEndpointPortRulesSupportRanges() {
        UserRule rule = new UserRule();
        rule.setRules(Arrays.asList(
                "srcPort 40000-50000 block",
                "dstPort 443 direct",
                "default proxy"));
        UserRuleMatcher matcher = UserRuleMatcher.compile(rule, null, "ss-rocky");

        assertEquals(RouteAction.BLOCK,
                matcher.match("api.example.com", 443, new InetSocketAddress("192.168.31.7", 41000)));
        assertEquals(RouteAction.DIRECT,
                matcher.match("api.example.com", 443, new InetSocketAddress("192.168.31.7", 30000)));
        assertEquals(RouteAction.PROXY,
                matcher.match("api.example.com", 80, new InetSocketAddress("192.168.31.7", 30000)));
    }

    @Test
    public void matchGeoIpRequiresIpBytesForDomainTargets() {
        V2RayGeoManager manager = new V2RayGeoManager(false);
        try {
            manager.ipMatcher = new V2RayGeoIpReader().read(V2RayGeoDataTestUtil.geoIpList(
                    V2RayGeoDataTestUtil.geoIpEntry("cn", false,
                            V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(1, 2, 0, 0), 16))));

            UserRule rule = new UserRule();
            rule.setRules(Arrays.asList(
                    "geoip:cn direct",
                    "default proxy"));
            UserRuleMatcher matcher = UserRuleMatcher.compile(rule, manager, "ss-rocky");

            assertEquals(RouteAction.PROXY, matcher.match("api.example.com"));
            assertEquals(RouteAction.DIRECT, matcher.match("1.2.3.4"));
            assertEquals(RouteAction.DIRECT,
                    matcher.match("api.example.com", V2RayGeoDataTestUtil.ip4(1, 2, 3, 4)));
        } finally {
            manager.close();
        }
    }

    @Test
    public void matchDstIpRulesUseDestinationIpBytes() {
        UserRule rule = new UserRule();
        rule.setRules(Arrays.asList(
                "dstIp 8.8.8.8 direct",
                "dstIp 10.1.0.0/16 block",
                "default proxy"));
        UserRuleMatcher matcher = UserRuleMatcher.compile(rule, null, "ss-rocky");

        assertEquals(RouteAction.PROXY, matcher.match("dns.google"));
        assertEquals(RouteAction.DIRECT, matcher.match("8.8.8.8"));
        assertEquals(RouteAction.DIRECT,
                matcher.match("dns.google", V2RayGeoDataTestUtil.ip4(8, 8, 8, 8), 53, null));
        assertEquals(RouteAction.BLOCK,
                matcher.match("internal.example", V2RayGeoDataTestUtil.ip4(10, 1, 2, 3), 443, null));
    }

    @Test
    public void matchSrcIpUnresolvedSourceDoesNotMatchOrThrow() {
        UserRule rule = new UserRule();
        rule.setRules(Arrays.asList(
                "srcIp 192.168.31.7 direct",
                "default proxy"));
        UserRuleMatcher matcher = UserRuleMatcher.compile(rule, null, "ss-rocky");

        assertEquals(RouteAction.PROXY,
                matcher.match("1.1.1.1", 53, InetSocketAddress.createUnresolved("192.168.31.7", 41000)));
    }
}
