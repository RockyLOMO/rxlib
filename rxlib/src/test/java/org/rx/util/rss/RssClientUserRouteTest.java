package org.rx.util.rss;

import org.junit.jupiter.api.Test;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.socks.TrafficUser;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RssClientUserRouteTest {
    @Test
    public void normalizeBuildsUserMatcherForRoute() {
        RssClientConf oldConf = RssClient.rssConf;
        RssClientConf conf = validRssConf();
        UserRule rule = new UserRule();
        rule.setRules(Collections.singletonList("example.com direct"));
        conf.shadowUsers.get(0).setRoute(rule);

        try {
            assertTrue(RssClient.normalizeAndValidateRssConfig(conf));
            RssClient.rssConf = conf;

            ShadowUser user = conf.shadowUsers.get(0);
            assertEquals(RouteAction.DIRECT, RssClient.matchUserRoute(user, "api.example.com"));
            assertEquals(RouteAction.PROXY, RssClient.matchUserRoute(TrafficUser.ANONYMOUS, "api.example.com"));
        } finally {
            RssClient.rssConf = oldConf;
        }
    }

    @Test
    public void normalizeFallsBackToDefaultRouteWhenUserRouteDisabled() {
        RssClientConf oldConf = RssClient.rssConf;
        RssClientConf conf = validRssConf();
        UserRule defaultRoute = new UserRule();
        defaultRoute.setRules(Arrays.asList(
                "srcIp 192.168.31.7 block",
                "default proxy"));
        conf.defaultRoute = defaultRoute;
        UserRule route = new UserRule();
        route.setEnabled(Boolean.FALSE);
        conf.shadowUsers.get(0).setRoute(route);

        try {
            assertTrue(RssClient.normalizeAndValidateRssConfig(conf));
            RssClient.rssConf = conf;

            assertEquals(RouteAction.BLOCK,
                    RssClient.matchUserRoute(conf.shadowUsers.get(0), "unknown.example", 443,
                            new InetSocketAddress("192.168.31.7", 41000)));
            assertEquals(RouteAction.PROXY,
                    RssClient.matchUserRoute(conf.shadowUsers.get(0), "unknown.example", 443,
                            new InetSocketAddress("192.168.31.8", 41000)));
        } finally {
            RssClient.rssConf = oldConf;
        }
    }

    @Test
    public void normalizeBuildsDefaultRouteWhenMissingRules() {
        RssClientConf oldConf = RssClient.rssConf;
        RssClientConf conf = validRssConf();

        try {
            assertTrue(RssClient.normalizeAndValidateRssConfig(conf));
            RssClient.rssConf = conf;

            assertEquals(Boolean.TRUE, conf.defaultRoute.getEnabled());
            assertEquals(RouteAction.PROXY, RssClient.matchUserRoute(TrafficUser.ANONYMOUS, "unknown.example"));
        } finally {
            RssClient.rssConf = oldConf;
        }
    }

    @Test
    public void sourceSteeringTtlReadsShadowUserRouteAndHonorsDisabledRoute() {
        RssClientConf oldConf = RssClient.rssConf;
        RssClientConf conf = new RssClientConf();
        UserRule defaultRoute = new UserRule();
        defaultRoute.setEnabled(Boolean.TRUE);
        defaultRoute.setSrcSteeringTTL(30);
        conf.defaultRoute = defaultRoute;
        RssClient.rssConf = conf;
        ShadowUser user = new ShadowUser();
        UserRule route = new UserRule();
        route.setSrcSteeringTTL(60);
        user.setRoute(route);

        try {
            assertEquals(60, RssClient.sourceSteeringTtl(user));

            route.setEnabled(Boolean.FALSE);
            assertEquals(30, RssClient.sourceSteeringTtl(user));
            assertEquals(30, RssClient.sourceSteeringTtl(TrafficUser.ANONYMOUS));
        } finally {
            RssClient.rssConf = oldConf;
        }
    }

    @Test
    public void sourceSteeringTtlCanUseReloadingConfBeforeGlobalSwap() {
        RssClientConf oldConf = RssClient.rssConf;
        RssClientConf oldRuntimeConf = new RssClientConf();
        UserRule oldDefaultRoute = new UserRule();
        oldDefaultRoute.setSrcSteeringTTL(10);
        oldRuntimeConf.defaultRoute = oldDefaultRoute;
        RssClient.rssConf = oldRuntimeConf;

        RssClientConf newReloadingConf = new RssClientConf();
        UserRule newDefaultRoute = new UserRule();
        newDefaultRoute.setSrcSteeringTTL(60);
        newReloadingConf.defaultRoute = newDefaultRoute;
        ShadowUser user = new ShadowUser();

        try {
            assertEquals(60, RssClient.sourceSteeringTtl(user, newReloadingConf));
            assertEquals(10, RssClient.sourceSteeringTtl(user));
        } finally {
            RssClient.rssConf = oldConf;
        }
    }

    private static RssClientConf validRssConf() {
        RssClientConf conf = new RssClientConf();
        ShadowUser user = new ShadowUser();
        user.setUsername("ss-rocky");
        user.setSsPort(2081);
        user.setSsPwd("pwd");
        user.setSocksUser("inner");
        conf.shadowUsers = Collections.singletonList(user);
        conf.socksPwd = "socks-pwd";
        AuthenticEndpoint endpoint = new AuthenticEndpoint(new InetSocketAddress("127.0.0.1", 1080), "u", "p");
        conf.socksServers = Collections.singletonList(new RssClientConf.SocksServer("primary", 1, endpoint));
        return conf;
    }
}
