package org.rx.net.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PublicSuffixMatcherTest {
    @Test
    public void testExactAndPrivateRules() {
        PublicSuffixMatcher matcher = PublicSuffixMatcher.DEFAULT;

        assertTrue(matcher.isPublicSuffix("com"));
        assertTrue(matcher.isPublicSuffix("co.uk"));
        assertFalse(matcher.isPublicSuffix("example.co.uk"));
        assertEquals("example.co.uk", matcher.registrableDomain("www.example.co.uk"));

        assertTrue(matcher.isPublicSuffix("github.io"));
        assertFalse(matcher.isPublicSuffix("app.github.io"));
        assertEquals("app.github.io", matcher.registrableDomain("deep.app.github.io"));
    }

    @Test
    public void testWildcardAndExceptionRules() {
        PublicSuffixMatcher matcher = PublicSuffixMatcher.DEFAULT;

        assertTrue(matcher.isPublicSuffix("test.ck"));
        assertFalse(matcher.isPublicSuffix("a.test.ck"));
        assertEquals("a.test.ck", matcher.registrableDomain("www.a.test.ck"));

        assertFalse(matcher.isPublicSuffix("www.ck"));
        assertEquals("www.ck", matcher.registrableDomain("a.www.ck"));
    }
}
