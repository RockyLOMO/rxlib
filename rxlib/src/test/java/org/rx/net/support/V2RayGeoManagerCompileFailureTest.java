package org.rx.net.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rx.exception.InvalidException;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class V2RayGeoManagerCompileFailureTest {
    @TempDir
    Path tempDir;

    @Test
    public void compileGeoIpMatcherPropagatesInvalidLoad() throws Exception {
        Path ipFile = tempDir.resolve("geoip.dat");
        java.nio.file.Files.write(ipFile, new byte[]{0});

        V2RayGeoManager manager = new V2RayGeoManager(false);
        manager.geoIpFileUrl = null;
        manager.geoSiteFileUrl = null;
        manager.geoIpFile = ipFile.toString();
        manager.geoSiteFile = tempDir.resolve("missing-geosite.dat").toString();

        try {
            assertThrows(InvalidException.class, () -> manager.compileGeoIpMatcher("cn"));
        } finally {
            manager.close();
        }
    }

    @Test
    public void compileGeoSiteMatcherPropagatesInvalidLoad() throws Exception {
        Path siteFile = tempDir.resolve("geosite.dat");
        java.nio.file.Files.write(siteFile, new byte[]{0});

        V2RayGeoManager manager = new V2RayGeoManager(false);
        manager.geoIpFileUrl = null;
        manager.geoSiteFileUrl = null;
        manager.geoIpFile = tempDir.resolve("missing-geoip.dat").toString();
        manager.geoSiteFile = siteFile.toString();

        try {
            assertThrows(InvalidException.class, () -> manager.compileGeoSiteMatcher("cn"));
        } finally {
            manager.close();
        }
    }
}
