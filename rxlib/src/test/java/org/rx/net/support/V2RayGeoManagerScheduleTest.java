package org.rx.net.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class V2RayGeoManagerScheduleTest {
    @TempDir
    Path tempDir;

    @Test
    public void setDailyDownloadTimeReschedulesAfterFirstLoad() throws Exception {
        Path ipFile = tempDir.resolve("geoip.dat");
        java.nio.file.Files.write(ipFile, V2RayGeoDataTestUtil.geoIpList(
                V2RayGeoDataTestUtil.geoIpEntry("cn", false,
                        V2RayGeoDataTestUtil.cidr(V2RayGeoDataTestUtil.ip4(1, 2, 0, 0), 16))));

        V2RayGeoManager manager = new V2RayGeoManager(false);
        manager.geoIpFileUrl = null;
        manager.geoSiteFileUrl = null;
        manager.geoIpFile = ipFile.toString();
        manager.geoSiteFile = tempDir.resolve("missing-geosite.dat").toString();

        try {
            assertNotNull(manager.compileGeoIpMatcher("cn"));
            List<? extends ScheduledFuture<?>> first = manager.dailyTasks;
            assertNotNull(first);
            assertFalse(first.isEmpty());

            manager.setDailyDownloadTime("23:59:59");

            List<? extends ScheduledFuture<?>> second = manager.dailyTasks;
            assertNotNull(second);
            assertFalse(second.isEmpty());
            assertNotSame(first, second);
            assertTrue(first.get(0).isCancelled());
        } finally {
            manager.close();
        }
    }
}
