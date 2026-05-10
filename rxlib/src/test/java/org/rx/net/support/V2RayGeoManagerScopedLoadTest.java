package org.rx.net.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rx.exception.InvalidException;

import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class V2RayGeoManagerScopedLoadTest {
    @TempDir
    Path tempDir;

    @Test
    public void compileGeoIpMatcherIgnoresInvalidSiteFileDuringScopedLoad() throws Exception {
        Path ipFile = tempDir.resolve("geoip.dat");
        Path siteFile = tempDir.resolve("geosite.dat");
        java.nio.file.Files.write(ipFile, V2RayGeoDataTestUtil.geoIpList(
                V2RayGeoDataTestUtil.geoIpEntry("cn", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(1, 2, 0, 0), 16))));
        java.nio.file.Files.write(siteFile, new byte[]{0});

        V2RayGeoManager manager = new V2RayGeoManager(false);
        manager.geoIpFileUrl = null;
        manager.geoSiteFileUrl = null;
        manager.geoIpFile = ipFile.toString();
        manager.geoSiteFile = siteFile.toString();

        try {
            V2RayGeoIpMatcher.CodeMatcher matcher = manager.compileGeoIpMatcher("cn");
            assertNotNull(matcher);
            assertTrue(matcher.matches(V2RayGeoDataTestUtil.ip4(1, 2, 3, 4)));
        } finally {
            manager.close();
        }
    }

    @Test
    public void compileGeoSiteMatcherIgnoresInvalidIpFileDuringScopedLoad() throws Exception {
        Path ipFile = tempDir.resolve("geoip.dat");
        Path siteFile = tempDir.resolve("geosite.dat");
        java.nio.file.Files.write(ipFile, new byte[]{0});
        java.nio.file.Files.write(siteFile, V2RayGeoDataTestUtil.geoSiteList(
                V2RayGeoDataTestUtil.geoSiteEntry("cn",
                        V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN, "baidu.com"))));

        V2RayGeoManager manager = new V2RayGeoManager(false);
        manager.geoIpFileUrl = null;
        manager.geoSiteFileUrl = null;
        manager.geoIpFile = ipFile.toString();
        manager.geoSiteFile = siteFile.toString();

        try {
            GeoSiteMatcher matcher = manager.compileGeoSiteMatcher("cn");
            assertNotNull(matcher);
            assertTrue(matcher.matches("www.baidu.com"));
        } finally {
            manager.close();
        }
    }

    @Test
    public void reloadPublishesIpSideWhenSiteSideInvalid() throws Exception {
        Path ipFile = tempDir.resolve("geoip.dat");
        Path siteFile = tempDir.resolve("geosite.dat");
        java.nio.file.Files.write(ipFile, V2RayGeoDataTestUtil.geoIpList(
                V2RayGeoDataTestUtil.geoIpEntry("cn", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(1, 2, 0, 0), 16))));
        java.nio.file.Files.write(siteFile, new byte[]{0});

        V2RayGeoManager manager = new V2RayGeoManager(false);
        manager.geoIpFileUrl = null;
        manager.geoSiteFileUrl = null;
        manager.geoIpFile = ipFile.toString();
        manager.geoSiteFile = siteFile.toString();

        try {
            assertThrows(InvalidException.class, () -> manager.reload());
            V2RayGeoIpMatcher.CodeMatcher matcher = manager.tryCompileGeoIpMatcher("cn");
            assertNotNull(matcher);
            assertTrue(matcher.matches(V2RayGeoDataTestUtil.ip4(1, 2, 3, 4)));
        } finally {
            manager.close();
        }
    }

    @Test
    public void reloadPublishesSiteSideWhenIpSideInvalid() throws Exception {
        Path ipFile = tempDir.resolve("geoip.dat");
        Path siteFile = tempDir.resolve("geosite.dat");
        java.nio.file.Files.write(ipFile, new byte[]{0});
        java.nio.file.Files.write(siteFile, V2RayGeoDataTestUtil.geoSiteList(
                V2RayGeoDataTestUtil.geoSiteEntry("cn",
                        V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN, "baidu.com"))));

        V2RayGeoManager manager = new V2RayGeoManager(false);
        manager.geoIpFileUrl = null;
        manager.geoSiteFileUrl = null;
        manager.geoIpFile = ipFile.toString();
        manager.geoSiteFile = siteFile.toString();

        try {
            assertThrows(InvalidException.class, () -> manager.reload());
            GeoSiteMatcher matcher = manager.tryCompileGeoSiteMatcher("cn");
            assertNotNull(matcher);
            assertTrue(matcher.matches("www.baidu.com"));
        } finally {
            manager.close();
        }
    }

    @Test
    public void reloadKeepsOldSiteSnapshotWhenSiteRefreshFails() throws Exception {
        Path ipFile = tempDir.resolve("geoip.dat");
        Path siteFile = tempDir.resolve("geosite.dat");
        java.nio.file.Files.write(ipFile, V2RayGeoDataTestUtil.geoIpList(
                V2RayGeoDataTestUtil.geoIpEntry("cn", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(1, 2, 0, 0), 16))));
        java.nio.file.Files.write(siteFile, new byte[]{0});

        V2RayGeoManager manager = new V2RayGeoManager(false);
        manager.geoIpFileUrl = null;
        manager.geoSiteFileUrl = null;
        manager.geoIpFile = ipFile.toString();
        manager.geoSiteFile = siteFile.toString();
        manager.siteIndex = new V2RayGeoSiteReader().read(V2RayGeoDataTestUtil.geoSiteList(
                V2RayGeoDataTestUtil.geoSiteEntry("cn",
                        V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN, "old.example"))));

        try {
            assertThrows(InvalidException.class, () -> manager.reload());
            GeoSiteMatcher siteMatcher = manager.tryCompileGeoSiteMatcher("cn");
            assertNotNull(siteMatcher);
            assertTrue(siteMatcher.matches("www.old.example"));

            V2RayGeoIpMatcher.CodeMatcher ipMatcher = manager.tryCompileGeoIpMatcher("cn");
            assertNotNull(ipMatcher);
            assertTrue(ipMatcher.matches(V2RayGeoDataTestUtil.ip4(1, 2, 3, 4)));
        } finally {
            manager.close();
        }
    }

    @Test
    public void reloadKeepsOldIpSnapshotWhenIpRefreshFails() throws Exception {
        Path ipFile = tempDir.resolve("geoip.dat");
        Path siteFile = tempDir.resolve("geosite.dat");
        java.nio.file.Files.write(ipFile, new byte[]{0});
        java.nio.file.Files.write(siteFile, V2RayGeoDataTestUtil.geoSiteList(
                V2RayGeoDataTestUtil.geoSiteEntry("cn",
                        V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN, "baidu.com"))));

        V2RayGeoManager manager = new V2RayGeoManager(false);
        manager.geoIpFileUrl = null;
        manager.geoSiteFileUrl = null;
        manager.geoIpFile = ipFile.toString();
        manager.geoSiteFile = siteFile.toString();
        manager.ipMatcher = new V2RayGeoIpReader().read(V2RayGeoDataTestUtil.geoIpList(
                V2RayGeoDataTestUtil.geoIpEntry("cn", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(9, 9, 0, 0), 16))));

        try {
            assertThrows(InvalidException.class, () -> manager.reload());
            V2RayGeoIpMatcher.CodeMatcher ipMatcher = manager.tryCompileGeoIpMatcher("cn");
            assertNotNull(ipMatcher);
            assertTrue(ipMatcher.matches(V2RayGeoDataTestUtil.ip4(9, 9, 1, 1)));

            GeoSiteMatcher siteMatcher = manager.tryCompileGeoSiteMatcher("cn");
            assertNotNull(siteMatcher);
            assertTrue(siteMatcher.matches("www.baidu.com"));
        } finally {
            manager.close();
        }
    }

    @Test
    public void reloadAsyncRefreshesValidSideWhenOtherSideInvalid() throws Exception {
        Path ipFile = tempDir.resolve("geoip.dat");
        Path siteFile = tempDir.resolve("geosite.dat");
        java.nio.file.Files.write(ipFile, V2RayGeoDataTestUtil.geoIpList(
                V2RayGeoDataTestUtil.geoIpEntry("cn", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(1, 2, 0, 0), 16))));
        java.nio.file.Files.write(siteFile, new byte[]{0});

        V2RayGeoManager manager = new V2RayGeoManager(false);
        manager.geoIpFileUrl = null;
        manager.geoSiteFileUrl = null;
        manager.geoIpFile = ipFile.toString();
        manager.geoSiteFile = siteFile.toString();

        try {
            manager.reloadAsync();
            Future<Void> ip = manager.ipTask;
            Future<Void> site = manager.siteTask;
            ip.get(manager.timeoutMillis, TimeUnit.MILLISECONDS);
            assertThrows(ExecutionException.class, () -> site.get(manager.timeoutMillis, TimeUnit.MILLISECONDS));

            V2RayGeoIpMatcher.CodeMatcher matcher = manager.tryCompileGeoIpMatcher("cn");
            assertNotNull(matcher);
            assertTrue(matcher.matches(V2RayGeoDataTestUtil.ip4(1, 2, 3, 4)));
        } finally {
            manager.close();
        }
    }
}
