package org.rx.net.support;

import org.junit.jupiter.api.Test;
import org.rx.exception.InvalidException;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class V2RayGeoManagerCrossLoadIsolationTest {
    @Test
    public void compileGeoIpMatcherDoesNotObserveUnrelatedSiteFailure() {
        V2RayGeoManager manager = new V2RayGeoManager(false);
        manager.ipMatcher = new V2RayGeoIpReader().read(V2RayGeoDataTestUtil.geoIpList(
                V2RayGeoDataTestUtil.geoIpEntry("cn", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(1, 2, 0, 0), 16))));
        CompletableFuture<Void> failedSiteTask = new CompletableFuture<Void>();
        failedSiteTask.completeExceptionally(new InvalidException("site load failed"));
        manager.dTask = failedSiteTask;
        manager.dTaskLoadSite = true;

        try {
            V2RayGeoIpMatcher.CodeMatcher matcher = manager.compileGeoIpMatcher("cn");
            assertNotNull(matcher);
            assertTrue(matcher.matches(V2RayGeoDataTestUtil.ip4(1, 2, 3, 4)));
        } finally {
            manager.close();
        }
    }

    @Test
    public void compileGeoSiteMatcherDoesNotObserveUnrelatedIpFailure() {
        V2RayGeoManager manager = new V2RayGeoManager(false);
        manager.siteIndex = new V2RayGeoSiteReader().read(V2RayGeoDataTestUtil.geoSiteList(
                V2RayGeoDataTestUtil.geoSiteEntry("cn",
                        V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN, "baidu.com"))));
        CompletableFuture<Void> failedIpTask = new CompletableFuture<Void>();
        failedIpTask.completeExceptionally(new InvalidException("ip load failed"));
        manager.dTask = failedIpTask;
        manager.dTaskLoadIp = true;

        try {
            GeoSiteMatcher matcher = manager.compileGeoSiteMatcher("cn");
            assertNotNull(matcher);
            assertTrue(matcher.matches("www.baidu.com"));
        } finally {
            manager.close();
        }
    }

    @Test
    public void compileGeoIpMatcherObservesCurrentIpFailureWhenSnapshotReady() {
        V2RayGeoManager manager = new V2RayGeoManager(false);
        manager.ipMatcher = new V2RayGeoIpReader().read(V2RayGeoDataTestUtil.geoIpList(
                V2RayGeoDataTestUtil.geoIpEntry("cn", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(1, 2, 0, 0), 16))));
        CompletableFuture<Void> failedIpTask = new CompletableFuture<Void>();
        failedIpTask.completeExceptionally(new InvalidException("ip load failed"));
        manager.dTask = failedIpTask;
        manager.dTaskLoadIp = true;

        try {
            assertThrows(InvalidException.class, () -> manager.compileGeoIpMatcher("cn"));
        } finally {
            manager.close();
        }
    }

    @Test
    public void compileGeoSiteMatcherObservesCurrentSiteFailureWhenSnapshotReady() {
        V2RayGeoManager manager = new V2RayGeoManager(false);
        manager.siteIndex = new V2RayGeoSiteReader().read(V2RayGeoDataTestUtil.geoSiteList(
                V2RayGeoDataTestUtil.geoSiteEntry("cn",
                        V2RayGeoDataTestUtil.domain(V2RayGeoDataReader.DOMAIN_TYPE_ROOT_DOMAIN, "baidu.com"))));
        CompletableFuture<Void> failedSiteTask = new CompletableFuture<Void>();
        failedSiteTask.completeExceptionally(new InvalidException("site load failed"));
        manager.dTask = failedSiteTask;
        manager.dTaskLoadSite = true;

        try {
            assertThrows(InvalidException.class, () -> manager.compileGeoSiteMatcher("cn"));
        } finally {
            manager.close();
        }
    }
}
