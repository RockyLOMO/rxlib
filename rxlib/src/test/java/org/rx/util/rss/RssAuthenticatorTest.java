package org.rx.util.rss;

import org.junit.jupiter.api.Test;
import org.rx.exception.InvalidException;
import org.rx.net.socks.AuthResult;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    }

    @Test
    public void authenticateRejectsInvalidShadowUserConfig() {
        ShadowUser invalid = new ShadowUser();
        invalid.setUsername("ss-invalid");
        invalid.setSsPort(1081);

        assertThrows(InvalidException.class, () -> new RssAuthenticator(Arrays.asList(invalid), "inner-pwd"));
    }
}
