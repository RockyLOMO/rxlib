package org.rx.diagnostic;

import org.junit.jupiter.api.Test;
import org.rx.core.RxConfig.DiagnosticConfig;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityDatabaseImpl;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

public class DiagnosticMonitorTest {
    @Test
    public void stackHashIsStable() {
        StackTraceElement[] stack = new StackTraceElement[]{
                new StackTraceElement("a.B", "m1", "B.java", 10),
                new StackTraceElement("a.C", "m2", "C.java", 20)
        };
        long h1 = StackTraceCodec.hash(stack, 64);
        long h2 = StackTraceCodec.hash(stack, 64);
        assertEquals(h1, h2);
        assertTrue(StackTraceCodec.format(stack, 64).contains("a.B.m1"));
    }

    @Test
    public void diagnosticConfigDefaultsToRxDiagnosticDirectory() {
        DiagnosticConfig config = new DiagnosticConfig();
        config.normalize();
        assertEquals(new File(".", "rx-diagnostic").getPath(), config.getDiagnosticsDirectory().getPath());
        assertEquals(new File(".", "rx-diagnostic").getPath(), config.getH2File().getPath());
    }

    @Test
    public void directUsedPercentUsesDirectMaxInsteadOfCurrentCapacity() {
        ResourceSnapshot snapshot = new ResourceSnapshot(System.currentTimeMillis(), 0D, 0D, 1,
                0L, 0L, 0L,
                100L * 1024L * 1024L, 100L * 1024L * 1024L, 2L * 1024L * 1024L * 1024L,
                0L, 0L, 0L, 0L,
                java.util.Collections.<ResourceSnapshot.DiskUsage>emptyList(),
                java.util.Collections.<DiagnosticMetric>emptyList());
        assertEquals(100D, snapshot.directCapacityPercent(), 0.001D);
        assertTrue(snapshot.directUsedPercent() < 5D);
    }

    @Test
    public void entityDatabaseSupportsDirectJdbcUrlAndBatch() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:diag_entity_db_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL";
        EntityDatabase db = new EntityDatabaseImpl(jdbcUrl, null, 1, true);
        try {
            db.withConnection(conn -> {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE TABLE diag_batch_test(id INT PRIMARY KEY, name VARCHAR(32))");
                }
            });
            int[] rs = db.executeBatch("INSERT INTO diag_batch_test(id, name) VALUES (?, ?)",
                    Arrays.asList(Arrays.<Object>asList(1, "a"), Arrays.<Object>asList(2, "b")));
            assertEquals(2, rs.length);
            Integer count = db.withConnection(conn -> {
                try (Statement stmt = conn.createStatement();
                     ResultSet query = stmt.executeQuery("SELECT COUNT(*) FROM diag_batch_test")) {
                    assertTrue(query.next());
                    return query.getInt(1);
                }
            });
            assertEquals(Integer.valueOf(2), count);
        } finally {
            db.close();
        }
    }

    @Test
    public void h2StorePersistsRecords() throws Exception {
        DiagnosticConfig config = memConfig("diag_store");
        H2DiagnosticStore store = new H2DiagnosticStore(config);
        store.start();
        try {
            long now = System.currentTimeMillis();
            store.recordMetric(new DiagnosticMetric(now, "test.metric", 1D, "k=v", null, 123L));
            store.recordStackTrace(123L, "stack", now);
            store.recordThreadCpu(new ThreadCpuSample(now, 1L, "main", "RUNNABLE", 100L, 123L, "stack"), "inc-1");
            store.recordFileIo(now, "target/a.log", DiagnosticFileOperation.WRITE, 10L, 20L, 123L, "inc-1");
            store.recordNetIo(now, "127.0.0.1:8080", DiagnosticNetOperation.OUTBOUND, 11L, 123L, "inc-1");
            store.recordThreadState(new ThreadStateSample(now, 1L, "main", "BLOCKED", 2L, 3L, 4L,
                    "lock", 2L, "owner", 123L, "stack"), "inc-1");
            store.recordFileSize(now, "target/a.log", 10L, now, "inc-1");
            store.recordIncident("inc-1", DiagnosticIncidentType.CPU_HIGH, DiagnosticLevel.DIAG, now, now, "summary", "bundle");

            assertTrue(store.flush(5000L));
            assertEquals(1, count(config, "diag_metric_sample"));
            assertEquals(1, countWhere(config, "diag_metric_sample", "stack_hash=123"));
            assertEquals(1, count(config, "diag_stack_trace"));
            assertEquals(1, count(config, "diag_thread_cpu_sample"));
            assertEquals(1, count(config, "diag_file_io_sample"));
            assertEquals(1, count(config, "diag_net_io_sample"));
            assertEquals(1, count(config, "diag_thread_state_sample"));
            assertEquals(1, count(config, "diag_file_size_sample"));
            assertEquals(1, count(config, "diag_incident"));
        } finally {
            store.close();
        }
    }

    @Test
    public void diagnosticStoreDatabaseUsesRelaxedSlowSqlThreshold() throws Exception {
        DiagnosticConfig config = memConfig("diag_store_slow_threshold");
        config.setH2FlushIntervalMillis(1000L);
        EntityDatabase db = H2DiagnosticStore.createDatabase(config);
        try {
            assertTrue(db instanceof EntityDatabaseImpl);
            java.lang.reflect.Field field = EntityDatabaseImpl.class.getDeclaredField("slowSqlElapsed");
            field.setAccessible(true);
            assertEquals(8000, field.getInt(db));
        } finally {
            db.close();
        }
    }

    @Test
    public void diagnosticMetricsRecordLightweightMetric() throws Exception {
        DiagnosticConfig config = memConfig("diag_metric_bridge");
        config.setSampleIntervalMillis(60000L);
        DiagnosticMonitor monitor = new DiagnosticMonitor(config);
        monitor.start();
        try {
            DiagnosticMetrics.record("bridge.metric", 1D, "k=v");
            assertTrue(monitor.getStore().flush(5000L));
            assertEquals(1, countWhere(config, "diag_metric_sample", "metric='bridge.metric' AND tags='k=v'"));
        } finally {
            monitor.close();
        }
    }

    @Test
    public void manualThreadCapturePersistsSamples() throws Exception {
        DiagnosticConfig config = memConfig("diag_manual_thread_capture");
        config.setSampleIntervalMillis(60000L);
        config.setCpuThresholdPercent(10000D);
        final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicLong sink = new AtomicLong();
        final CountDownLatch started = new CountDownLatch(1);
        Thread busyThread = new Thread(new Runnable() {
            @Override
            public void run() {
                started.countDown();
                long value = 0L;
                while (running.get()) {
                    value++;
                    if ((value & 4095L) == 0L) {
                        sink.lazySet(value);
                    }
                }
                sink.set(value);
            }
        }, "rx-diagnostic-busy-test");
        busyThread.setDaemon(true);

        DiagnosticMonitor monitor = new DiagnosticMonitor(config);
        monitor.start();
        busyThread.start();
        try {
            assertTrue(started.await(1000L, TimeUnit.MILLISECONDS));
            assertTrue(monitor.captureThreadCpu(1L, 32) >= 1);
            assertTrue(monitor.captureThreadState(1L, 32) >= 1);
            assertTrue(monitor.getStore().flush(5000L));
            assertTrue(count(config, "diag_thread_cpu_sample") >= 1);
            assertTrue(count(config, "diag_thread_state_sample") >= 1);
            assertTrue(count(config, "diag_stack_trace") >= 1);
        } finally {
            running.set(false);
            busyThread.join(2000L);
            monitor.close();
        }
    }

    @Test
    public void fileIoFacadePersistsSampleWhenEnabled() throws Exception {
        DiagnosticConfig config = memConfig("diag_file_io");
        config.setSampleIntervalMillis(60000L);
        config.setFileIoSampleRate(1D);
        config.setFileIoDiagSampleRate(1D);
        config.setDiskIoBytesPerSecondThreshold(0L);
        DiagnosticMonitor monitor = new DiagnosticMonitor(config);
        monitor.start();
        try {
            DiagnosticFileIo.recordWrite("target/diag-file.log", 128L, 1000L);
            assertTrue(monitor.getStore().flush(5000L));
            assertTrue(count(config, "diag_file_io_sample") >= 1);
            assertTrue(count(config, "diag_stack_trace") >= 1);
        } finally {
            monitor.close();
        }
    }

    @Test
    public void fileIoHighThroughputOpensIncident() throws Exception {
        DiagnosticConfig config = memConfig("diag_file_io_incident");
        config.setSampleIntervalMillis(60000L);
        config.setFileIoSampleRate(1D);
        config.setFileIoDiagSampleRate(1D);
        config.setDiskIoBytesPerSecondThreshold(1L);
        config.setDiskIoSustainMillis(0L);
        config.setIncidentCooldownMillis(0L);
        config.setJfrMode("off");
        DiagnosticMonitor monitor = new DiagnosticMonitor(config);
        monitor.start();
        try {
            DiagnosticFileIo.recordWrite("target/diag-file.log", 1024L, 1000L);
            Thread.sleep(1100L);
            DiagnosticFileIo.recordWrite("target/diag-file.log", 1024L, 1000L);
            assertTrue(monitor.getStore().flush(5000L));
            assertTrue(count(config, "diag_incident") >= 1);
        } finally {
            monitor.close();
        }
    }

    @Test
    public void h2StoreDegradesAfterWriteFailure() throws Exception {
        DiagnosticConfig config = memConfig("diag_h2_degrade");
        config.setH2FailureDegradeMillis(60000L);
        H2DiagnosticStore store = new H2DiagnosticStore(config);
        store.start();
        try (Connection conn = DriverManager.getConnection(config.jdbcUrl());
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE diag_metric_sample");
            store.recordMetric(new DiagnosticMetric(System.currentTimeMillis(), "broken.metric", 1D, null, null));
            assertTrue(store.flush(5000L));
            waitUntilWriteFailure(store);
            assertTrue(store.writeFailures() >= 1L);
            assertTrue(store.isDegraded());
            long dropped = store.droppedRecords();
            store.recordMetric(new DiagnosticMetric(System.currentTimeMillis(), "dropped.metric", 1D, null, null));
            assertTrue(store.droppedRecords() > dropped);
        } finally {
            store.close();
        }
    }

    @Test
    public void incidentBundleSkipsWhenNoDiskBudget() {
        DiagnosticConfig config = new DiagnosticConfig();
        config.setDiagnosticsDirectory(new File("target/diagnostics-budget-test"));
        config.setEvidenceMinFreeBytes(Long.MAX_VALUE);
        IncidentBundleWriter writer = new IncidentBundleWriter(config);
        assertNull(writer.createBundleDir("no-space", DiagnosticIncidentType.CPU_HIGH));
    }

    @Test
    public void incidentBundleCleanupDeletesExpiredBundles() {
        DiagnosticConfig config = new DiagnosticConfig();
        File root = new File("target/diagnostics-cleanup-test-" + System.nanoTime());
        config.setDiagnosticsDirectory(root);
        config.setDiagnosticsTtlMillis(1000L);
        config.setDiagnosticsMaxBytes(0L);
        config.setEvidenceMinFreeBytes(0L);
        IncidentBundleWriter writer = new IncidentBundleWriter(config);

        long now = System.currentTimeMillis();
        File oldDir = writer.createBundleDir("1000-cpu_high-1", DiagnosticIncidentType.CPU_HIGH);
        assertNotNull(oldDir);
        assertTrue(oldDir.exists());
        assertTrue(oldDir.setLastModified(now - 10000L));

        File currentDir = writer.createBundleDir(now + "-cpu_high-2", DiagnosticIncidentType.CPU_HIGH);
        assertNotNull(currentDir);
        assertTrue(currentDir.exists());

        writer.cleanup(now);
        assertFalse(oldDir.exists());
        assertTrue(currentDir.exists());
    }

    private DiagnosticConfig memConfig(String name) {
        DiagnosticConfig config = new DiagnosticConfig();
        config.setH2JdbcUrl("jdbc:h2:mem:" + name + "_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL");
        config.setH2QueueSize(64);
        config.setH2BatchSize(16);
        config.setH2FlushIntervalMillis(50L);
        config.setH2TtlMillis(0L);
        config.setDiagnosticsDirectory(new java.io.File("target/diagnostics-test"));
        config.setDiagnosticsMaxBytes(0L);
        config.setEvidenceMinFreeBytes(0L);
        config.setJfrMinFreeBytes(0L);
        config.setHeapDumpMinFreeBytes(0L);
        config.setHeavyEvidenceCooldownMillis(0L);
        return config;
    }

    private int count(DiagnosticConfig config, String table) throws Exception {
        return countWhere(config, table, "1=1");
    }

    @Test
    public void netIoFacadePersistsSampleWhenEnabled() throws Exception {
        DiagnosticConfig config = memConfig("diag_net_io");
        config.setSampleIntervalMillis(60000L);
        config.setNetIoSampleRate(1D);
        config.setNetIoDiagSampleRate(1D);
        config.setNetIoBytesPerSecondThreshold(0L);
        DiagnosticMonitor monitor = new DiagnosticMonitor(config);
        monitor.start();
        try {
            DiagnosticNetIo.recordInbound("127.0.0.1:8080", 512L);
            assertTrue(monitor.getStore().flush(5000L));
            assertTrue(count(config, "diag_net_io_sample") >= 1);
            assertTrue(countWhere(config, "diag_metric_sample", "metric='net.io.bytes'") >= 1);
            assertTrue(count(config, "diag_stack_trace") >= 1);
        } finally {
            monitor.close();
        }
    }

    @Test
    public void incidentAsyncEventRaised() throws Exception {
        DiagnosticConfig config = memConfig("diag_incident_event");
        config.setSampleIntervalMillis(60000L);
        config.setCpuThresholdPercent(-1D);
        config.setCpuSustainMillis(0L);
        config.setIncidentCooldownMillis(0L);
        config.setCpuEvidenceSamples(1);
        config.setCpuEvidenceIntervalMillis(1L);
        config.setJfrMode("off");
        CountDownLatch latch = new CountDownLatch(1);
        DiagnosticMonitor monitor = new DiagnosticMonitor(config);
        monitor.onIncident.combine((sender, event) -> {
            if (event.getType() == DiagnosticIncidentType.CPU_HIGH) {
                latch.countDown();
            }
        });
        monitor.start();
        try {
            monitor.sampleOnce();
            assertTrue(latch.await(5000L, TimeUnit.MILLISECONDS));
        } finally {
            monitor.close();
        }
    }

    private int countWhere(DiagnosticConfig config, String table, String where) throws Exception {
        try (Connection conn = DriverManager.getConnection(config.jdbcUrl());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + where)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private void waitUntilWriteFailure(H2DiagnosticStore store) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000L;
        while (store.writeFailures() == 0L && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
    }
}
