package org.rx.net.support;

import com.maxmind.geoip2.DatabaseReader;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GeoManagerTest {
    @Test
    public void testGetPublicIpDoesNotInitializeSiteMatcher() throws Exception {
        GeoManager manager = newManager();
        manager.ipSearcher = new GeoIPSearcher((DatabaseReader) null) {
            @Override
            public String getPublicIp() {
                return "1.2.3.4";
            }
        };

        assertEquals("1.2.3.4", manager.getPublicIp());
        assertNull(manager.siteMatcher);
    }

    @Test
    public void testMatchSiteDirectLazilyLoadsMatcher() throws Exception {
        Path siteFile = Files.createTempFile("geosite-direct", ".txt");
        Files.write(siteFile, Collections.singletonList("google.com"), StandardCharsets.UTF_8);

        GeoManager manager = newManager();
        manager.geoSiteDirectFile = siteFile.toString();

        assertNull(manager.siteMatcher);
        assertTrue(manager.matchSiteDirect("mail.google.com"));
        assertNotNull(manager.siteMatcher);
        assertNull(manager.ipSearcher);
    }

    private static GeoManager newManager() throws Exception {
        Constructor<GeoManager> ctor = GeoManager.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }
}
