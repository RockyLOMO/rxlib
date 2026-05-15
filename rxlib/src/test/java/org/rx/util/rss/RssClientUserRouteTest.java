package org.rx.util.rss;

import org.junit.jupiter.api.Test;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.socks.TrafficUser;

import java.net.InetSocketAddress;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RssClientUserRouteTest {
    @Test
    public void normalizeBuildsUserMatcherForRoute() {
        RssClientConf oldConf = RssClient.rssConf;
        RssClientConf conf = validRssConf();
        V2RayUserRule rule = new V2RayUserRule();
        rule.setRules(Collections.singletonList("example.com direct"));
        conf.shadowUsers.get(0).setRoute(rule);

        try {
            assertTrue(RssClient.normalizeAndValidateRssConfig(conf));
            RssClient.rssConf = conf;

            ShadowUser user = conf.shadowUsers.get(0);
            assertEquals(V2RayRouteAction.DIRECT, RssClient.matchUserRoute(user, "api.example.com"));
            assertEquals(V2RayRouteAction.PROXY, RssClient.matchUserRoute(TrafficUser.ANONYMOUS, "api.example.com"));
        } finally {
            RssClient.rssConf = oldConf;
        }
    }

    @Test
    public void normalizeFallsBackToDefaultRouteRulesWhenUserRouteDisabled() {
        RssClientConf oldConf = RssClient.rssConf;
        RssClientConf conf = validRssConf();
        conf.defaultRouteRules = Collections.singletonList("default block");
        V2RayUserRule route = new V2RayUserRule();
        route.setEnabled(Boolean.FALSE);
        conf.shadowUsers.get(0).setRoute(route);

        try {
            assertTrue(RssClient.normalizeAndValidateRssConfig(conf));
            RssClient.rssConf = conf;

            assertEquals(V2RayRouteAction.BLOCK,
                    RssClient.matchUserRoute(conf.shadowUsers.get(0), "unknown.example"));
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
