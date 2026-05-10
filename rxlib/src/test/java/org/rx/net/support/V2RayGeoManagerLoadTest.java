package org.rx.net.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class V2RayGeoManagerLoadTest {
    @TempDir
    Path tempDir;

    @Test
    public void siteApiLoadsSiteSideWhenIpSnapshotAlreadyExists() throws Exception {
        Path siteFile = tempDir.resolve("geosite.dat");
        java.nio.file.Files.write(siteFile, V2RayGeoDataTestUtil.geoSiteList(
                V2RayGeoDataTestUtil.geoSiteEntry("cn",
                        V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN, "baidu.com"))));

        V2RayGeoManager manager = new V2RayGeoManager(false);
        manager.geoIpFileUrl = null;
        manager.geoSiteFileUrl = null;
        manager.geoIpFile = tempDir.resolve("missing-geoip.dat").toString();
        manager.geoSiteFile = siteFile.toString();
        manager.ipMatcher = new V2RayGeoIpReader().read(V2RayGeoDataTestUtil.geoIpList());

        try {
            GeoSiteMatcher matcher = manager.compileGeoSiteMatcher("cn");
            assertNotNull(matcher);
            assertTrue(matcher.matches("www.baidu.com"));
            assertTrue(manager.dailyScheduled);
        } finally {
            manager.close();
        }
    }

    @Test
    public void ipApiLoadsIpSideWhenSiteSnapshotAlreadyExists() throws Exception {
        Path ipFile = tempDir.resolve("geoip.dat");
        java.nio.file.Files.write(ipFile, V2RayGeoDataTestUtil.geoIpList(
                V2RayGeoDataTestUtil.geoIpEntry("cn", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(1, 2, 0, 0), 16))));

        V2RayGeoManager manager = new V2RayGeoManager(false);
        manager.geoIpFileUrl = null;
        manager.geoSiteFileUrl = null;
        manager.geoIpFile = ipFile.toString();
        manager.geoSiteFile = tempDir.resolve("missing-geosite.dat").toString();
        manager.siteIndex = new V2RayGeoSiteReader().read(V2RayGeoDataTestUtil.geoSiteList());

        try {
            V2RayGeoIpMatcher.CodeMatcher matcher = manager.compileGeoIpMatcher("cn");
            assertNotNull(matcher);
            assertTrue(matcher.matches(V2RayGeoDataTestUtil.ip4(1, 2, 3, 4)));
            assertTrue(manager.dailyScheduled);
        } finally {
            manager.close();
        }
    }
}
