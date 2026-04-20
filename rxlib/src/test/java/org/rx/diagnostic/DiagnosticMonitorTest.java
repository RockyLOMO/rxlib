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
    public void diagnosticConfigDefaultsToCurrentDirectory() {
        DiagnosticConfig config = new DiagnosticConfig();
        config.normalize();
        assertEquals(new File(".").getPath(), config.getDiagnosticsDirectory().getPath());
        assertEquals(new File(".", "rx-diagnostic").getPath(), config.getH2File().getPath());
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
            store.recordMetric(new DiagnosticMetric(now, "test.metric", 1D, "k=v", null));
            store.recordStackTrace(123L, "stack", now);
            store.recordThreadCpu(new ThreadCpuSample(now, 1L, "main", "RUNNABLE", 100L, 123L, "stack"), "inc-1");
            store.recordFileIo(now, "target/a.log", DiagnosticFileOperation.WRITE, 10L, 20L, 123L, "inc-1");
            store.recordFileSize(now, "target/a.log", 10L, now, "inc-1");
            store.recordIncident("inc-1", DiagnosticIncidentType.CPU_HIGH, DiagnosticLevel.DIAG, now, now, "summary", "bundle");

            assertTrue(store.flush(5000L));
            assertEquals(1, count(config, "diag_metric_sample"));
            assertEquals(1, count(config, "diag_stack_trace"));
            assertEquals(1, count(config, "diag_thread_cpu_sample"));
            assertEquals(1, count(config, "diag_file_io_sample"));
            assertEquals(1, count(config, "diag_file_size_sample"));
            assertEquals(1, count(config, "diag_incident"));
        } finally {
            store.close();
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
        try (Connection conn = DriverManager.getConnection(config.jdbcUrl());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
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
