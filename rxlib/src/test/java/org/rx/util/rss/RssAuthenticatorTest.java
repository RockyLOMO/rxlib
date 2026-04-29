package org.rx.util.rss;

import org.junit.jupiter.api.Test;
import org.rx.exception.InvalidException;
import org.rx.net.socks.AuthResult;
import org.rx.net.socks.TrafficLoginInfo;

import java.net.InetAddress;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RssAuthenticatorTest {
    @Test
    public void authenticateMapsSsUsersToSharedSocksUser() {
        ShadowUser first = new ShadowUser();
        first.setUsername("ss-rocky");
        first.setSsPort(1081);
        first.setSsPwd("pwd-rocky");
        first.setSocksUser("shared-socks");
        first.setIpLimit(2);

        ShadowUser second = new ShadowUser();
        second.setUsername("ss-ccy");
        second.setSsPort(1082);
        second.setSsPwd("pwd-ccy");
        second.setSocksUser("shared-socks");
        second.setIpLimit(3);

        RssAuthenticator authenticator = new RssAuthenticator(Arrays.asList(first, second), "inner-pwd");
        AuthResult firstResult = authenticator.loginResult("ss-rocky", "inner-pwd");
        AuthResult secondResult = authenticator.loginResult("ss-ccy", "inner-pwd");

        assertNotNull(firstResult);
        assertNotNull(secondResult);
        assertSame(first, firstResult.getTrafficUser());
        assertSame(second, secondResult.getTrafficUser());
        assertSame(firstResult.getUser(), secondResult.getUser());
        assertEquals("shared-socks", firstResult.getUser().getUsername());
        assertEquals(RssAuthenticator.DEFAULT_MEMORY_RETENTION_HOURS, authenticator.getMemoryRetentionHours());
    }

    @Test
    public void authenticateRejectsInvalidShadowUserConfig() {
        ShadowUser invalid = new ShadowUser();
        invalid.setUsername("ss-invalid");
        invalid.setSsPort(1081);

        assertThrows(InvalidException.class, () -> new RssAuthenticator(Arrays.asList(invalid), "inner-pwd"));
    }

    @Test
    public void resetIpRemovesEntriesOutsideConfiguredMemoryWindow() throws Exception {
        ShadowUser user = new ShadowUser();
        user.setUsername("ss-rocky");
        user.setSsPort(1081);
        user.setSsPwd("pwd-rocky");
        user.setSocksUser("shared-socks");

        TrafficLoginInfo expired = new TrafficLoginInfo();
        expired.setLatestTime(org.rx.bean.DateTime.now().addHours(-25));
        user.getLoginIps().put(InetAddress.getByName("18.12.3.4"), expired);

        TrafficLoginInfo retained = new TrafficLoginInfo();
        retained.setLatestTime(org.rx.bean.DateTime.now().addHours(-1));
        user.getLoginIps().put(InetAddress.getByName("18.12.3.5"), retained);

        RssAuthenticator authenticator = new RssAuthenticator(Arrays.asList(user), "inner-pwd", 24);
        authenticator.resetIp();

        assertEquals(1, user.getLoginIps().size());
        assertTrue(user.getLoginIps().containsKey(InetAddress.getByName("18.12.3.5")));
        assertNotNull(user.getLastResetTime());
    }

    @Test
    public void resetIpUsesCurrentRssConfigMemoryWindow() throws Exception {
        RSSConf oldConf = RssClient.rssConf;
        ShadowUser user = new ShadowUser();
        user.setUsername("ss-rocky");
        user.setSsPort(1081);
        user.setSsPwd("pwd-rocky");
        user.setSocksUser("shared-socks");

        TrafficLoginInfo expired = new TrafficLoginInfo();
        expired.setLatestTime(org.rx.bean.DateTime.now().addHours(-2));
        user.getLoginIps().put(InetAddress.getByName("18.12.3.4"), expired);

        RssAuthenticator authenticator = new RssAuthenticator(Arrays.asList(user), "inner-pwd", 24);
        try {
            RSSConf conf = new RSSConf();
            conf.memoryRetentionHours = 1;
            RssClient.rssConf = conf;

            authenticator.resetIp();

            assertEquals(0, user.getLoginIps().size());
        } finally {
            RssClient.rssConf = oldConf;
        }
    }

    @Test
    public void resetIpSkipsUsersWithoutConnections() {
        ShadowUser user = new ShadowUser();
        user.setUsername("ss-rocky");
        user.setSsPort(1081);
        user.setSsPwd("pwd-rocky");
        user.setSocksUser("shared-socks");

        RssAuthenticator authenticator = new RssAuthenticator(Arrays.asList(user), "inner-pwd",
                RssAuthenticator.DEFAULT_MEMORY_RETENTION_HOURS);
        authenticator.resetIp();

        assertTrue(user.getLoginIps().isEmpty());
        assertTrue(user.getLastResetTime() == null);
    }

    @Test
    public void resetIpUsesFiveHourDefaultWindow() throws Exception {
        ShadowUser user = new ShadowUser();
        user.setUsername("ss-rocky");
        user.setSsPort(1081);
        user.setSsPwd("pwd-rocky");
        user.setSocksUser("shared-socks");

        TrafficLoginInfo expired = new TrafficLoginInfo();
        expired.setLatestTime(org.rx.bean.DateTime.now().addHours(-6));
        user.getLoginIps().put(InetAddress.getByName("18.12.3.4"), expired);

        RssAuthenticator authenticator = new RssAuthenticator(Arrays.asList(user), "inner-pwd");
        authenticator.resetIp();

        assertEquals(0, user.getLoginIps().size());
        assertNotNull(user.getLastResetTime());
    }

    @Test
    public void reloadUpdatesPasswordAndPreservesShadowUserRuntimeState() throws Exception {
        ShadowUser first = new ShadowUser();
        first.setUsername("ss-rocky");
        first.setSsPort(1081);
        first.setSsPwd("pwd-rocky");
        first.setSocksUser("shared-socks");

        TrafficLoginInfo loginInfo = new TrafficLoginInfo();
        loginInfo.setLatestTime(org.rx.bean.DateTime.now());
        first.getLoginIps().put(InetAddress.getByName("18.12.3.4"), loginInfo);
        RssAuthenticator authenticator = new RssAuthenticator(Arrays.asList(first), "old-pwd", 24);

        ShadowUser reloaded = new ShadowUser();
        reloaded.setUsername("ss-rocky");
        reloaded.setSsPort(1081);
        reloaded.setSsPwd("pwd-rocky");
        reloaded.setSocksUser("shared-socks");
        ShadowUser added = new ShadowUser();
        added.setUsername("ss-ccy");
        added.setSsPort(1082);
        added.setSsPwd("pwd-ccy");
        added.setSocksUser("shared-socks");

        authenticator.reload(Arrays.asList(reloaded, added), "new-pwd", 6);

        assertTrue(authenticator.loginResult("ss-rocky", "old-pwd") == null);
        assertNotNull(authenticator.loginResult("ss-rocky", "new-pwd"));
        assertNotNull(authenticator.loginResult("ss-ccy", "new-pwd"));
        assertSame(loginInfo, authenticator.getShadowStore().get("ss-rocky").getLoginIps().get(InetAddress.getByName("18.12.3.4")));
        assertEquals(6, authenticator.getMemoryRetentionHours());
    }
}
