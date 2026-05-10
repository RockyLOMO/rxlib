package org.rx.net.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class V2RayGeoManagerCloseTest {
    @TempDir
    Path tempDir;

    @Test
    public void closeCancelsTasksClearsSnapshotsAndRejectsReload() throws Exception {
        Path ipFile = tempDir.resolve("geoip.dat");
        java.nio.file.Files.write(ipFile, V2RayGeoDataTestUtil.geoIpList(
                V2RayGeoDataTestUtil.geoIpEntry("cn", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(1, 2, 0, 0), 16))));

        V2RayGeoManager manager = new V2RayGeoManager(false);
        manager.geoIpFileUrl = null;
        manager.geoSiteFileUrl = null;
        manager.geoIpFile = ipFile.toString();
        manager.geoSiteFile = tempDir.resolve("missing-geosite.dat").toString();
        manager.compileGeoIpMatcher("cn");
        manager.siteIndex = new V2RayGeoSiteReader().read(V2RayGeoDataTestUtil.geoSiteList());

        CompletableFuture<Void> pendingTask = new CompletableFuture<Void>();
        manager.ipTask = pendingTask;
        manager.siteTask = pendingTask;
        List<? extends ScheduledFuture<?>> dailyTasks = manager.dailyTasks;

        manager.close();

        assertTrue(manager.closed);
        assertTrue(pendingTask.isCancelled());
        assertTrue(dailyTasks.get(0).isCancelled());
        assertNull(manager.ipMatcher);
        assertNull(manager.siteIndex);
        assertThrows(IllegalStateException.class, () -> manager.reload());
    }
}
