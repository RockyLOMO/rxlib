package org.rx.net.support;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GeoSiteMatcherTest {
    @Test
    public void testMatchesRulesCaseInsensitively() {
        GeoSiteMatcher matcher = new GeoSiteMatcher(Arrays.asList(
                "google.com",
                "full:Example.COM",
                "keyword:GoOgle",
                "regexp:.*\\.TeSt$").iterator());

        assertTrue(matcher.matches("mail.Google.com"));
        assertTrue(matcher.matches("EXAMPLE.com"));
        assertTrue(matcher.matches("fooGOOGLEbar.net"));
        assertTrue(matcher.matches("demo.Test"));
        assertFalse(matcher.matches("demo.example.org"));
        assertFalse(matcher.matches(null));
    }

    @Test
    public void testFullRuleOnlyMatchesExactHost() {
        GeoSiteMatcher matcher = new GeoSiteMatcher(Collections.singletonList("full:Example.COM").iterator());

        assertTrue(matcher.matches("example.com"));
        assertFalse(matcher.matches("a.example.com"));
    }
}
