package org.rx.diagnostic;

import lombok.extern.slf4j.Slf4j;
import org.rx.core.RxConfig.DiagnosticConfig;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityDatabaseImpl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class H2DiagnosticStore implements DiagnosticStore {
    private static final int TYPE_METRIC = 1;
    private static final int TYPE_STACK = 2;
    private static final int TYPE_THREAD_CPU = 3;
    private static final int TYPE_FILE_IO = 4;
    private static final int TYPE_FILE_SIZE = 5;
    private static final int TYPE_INCIDENT = 6;
    private static final int TYPE_FLUSH = 7;

    private final DiagnosticConfig config;
    private final EntityDatabase db;
    private final ArrayBlockingQueue<Record> queue;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong droppedRecords = new AtomicLong();
    private final AtomicLong writeFailures = new AtomicLong();
    private volatile Thread worker;
    private volatile long lastCleanupMillis;
    private volatile long degradedUntilMillis;

    public H2DiagnosticStore(DiagnosticConfig config) {
        this(config, createDatabase(config));
    }

    H2DiagnosticStore(DiagnosticConfig config, EntityDatabase db) {
        this.config = config;
        this.config.normalize();
        this.db = db;
        this.queue = new ArrayBlockingQueue<>(config.getH2QueueSize());
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            initSchema();
        } catch (Throwable e) {
            running.set(false);
            throw new IllegalStateException("init diagnostic h2 store failed", e);
        }
        worker = new Thread(this::runWorker, "rx-diagnostic-h2-writer");
        worker.setDaemon(true);
        worker.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
        worker.start();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void recordMetric(DiagnosticMetric metric) {
        if (metric == null) {
            return;
        }
        Record record = new Record(TYPE_METRIC);
        record.ts = metric.getTimestampMillis();
        record.s1 = metric.getName();
        record.d1 = metric.getValue();
        record.s2 = metric.getTags();
        record.s3 = metric.getIncidentId();
        offer(record);
    }

    @Override
    public void recordStackTrace(long stackHash, String stackTrace, long timestampMillis) {
        if (stackHash == 0L || stackTrace == null || stackTrace.length() == 0) {
            return;
        }
        Record record = new Record(TYPE_STACK);
        record.ts = timestampMillis;
        record.l1 = stackHash;
        record.s1 = stackTrace;
        offer(record);
    }

    @Override
    public void recordThreadCpu(ThreadCpuSample sample, String incidentId) {
        if (sample == null) {
            return;
        }
        Record record = new Record(TYPE_THREAD_CPU);
        record.ts = sample.getTimestampMillis();
        record.l1 = sample.getThreadId();
        record.s1 = sample.getThreadName();
        record.l2 = sample.getCpuDeltaNanos();
        record.s2 = sample.getThreadState();
        record.l3 = sample.getStackHash();
        record.s3 = incidentId;
        offer(record);
    }

    @Override
    public void recordFileIo(long timestampMillis, String path, DiagnosticFileOperation operation, long bytes,
                             long elapsedNanos, long stackHash, String incidentId) {
        if (path == null || operation == null) {
            return;
        }
        Record record = new Record(TYPE_FILE_IO);
        record.ts = timestampMillis;
        record.s1 = path;
        record.l1 = StackTraceCodec.hash(path);
        record.s2 = operation.name();
        record.l2 = bytes;
        record.l3 = elapsedNanos;
        record.l4 = stackHash;
        record.s3 = incidentId;
        offer(record);
    }

    @Override
    public void recordFileSize(long timestampMillis, String path, long sizeBytes, long lastModifiedMillis, String incidentId) {
        if (path == null) {
            return;
        }
        Record record = new Record(TYPE_FILE_SIZE);
        record.ts = timestampMillis;
        record.s1 = path;
        record.l1 = StackTraceCodec.hash(path);
        record.l2 = sizeBytes;
        record.l3 = lastModifiedMillis;
        record.s2 = incidentId;
        offer(record);
    }

    @Override
    public void recordIncident(String incidentId, DiagnosticIncidentType type, DiagnosticLevel level, long startMillis,
                               long endMillis, String summary, String bundlePath) {
        if (incidentId == null || type == null || level == null) {
            return;
        }
        Record record = new Record(TYPE_INCIDENT);
        record.s1 = incidentId;
        record.s2 = type.name();
        record.s3 = level.name();
        record.l1 = startMillis;
        record.l2 = endMillis;
        record.s4 = summary;
        record.s5 = bundlePath;
        offer(record);
    }

    @Override
    public boolean flush(long timeoutMillis) throws InterruptedException {
        if (!running.get() && queue.isEmpty()) {
            return true;
        }
        CountDownLatch latch = new CountDownLatch(1);
        Record record = new Record(TYPE_FLUSH);
        record.latch = latch;
        long deadline = System.currentTimeMillis() + Math.max(1L, timeoutMillis);
        while (!queue.offer(record, Math.min(50L, Math.max(1L, deadline - System.currentTimeMillis())), TimeUnit.MILLISECONDS)) {
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
        }
        long remain = Math.max(1L, deadline - System.currentTimeMillis());
        return latch.await(remain, TimeUnit.MILLISECONDS);
    }

    @Override
    public int pendingRecords() {
        return queue.size();
    }

    @Override
    public long droppedRecords() {
        return droppedRecords.get();
    }

    @Override
    public void close() {
        running.set(false);
        Thread t = worker;
        if (t != null) {
            t.interrupt();
            try {
                t.join(Math.max(1000L, config.getH2FlushIntervalMillis() * 2L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            db.close();
        } catch (Exception e) {
            log.warn("close diagnostic h2 store failed", e);
        }
    }

    private void offer(Record record) {
        if (!running.get()) {
            droppedRecords.incrementAndGet();
            return;
        }
        if (isDegraded() && record.type != TYPE_INCIDENT && record.type != TYPE_FLUSH) {
            droppedRecords.incrementAndGet();
            return;
        }
        if (!queue.offer(record)) {
            droppedRecords.incrementAndGet();
        }
    }

    private void runWorker() {
        List<Record> batch = new ArrayList<>(config.getH2BatchSize());
        while (running.get() || !queue.isEmpty()) {
            try {
                Record first = queue.poll(config.getH2FlushIntervalMillis(), TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, Math.max(0, config.getH2BatchSize() - 1));
                }
                if (!batch.isEmpty()) {
                    writeBatch(batch);
                    batch.clear();
                }
            } catch (InterruptedException e) {
                if (!running.get()) {
                    drainRemaining(batch);
                }
            } catch (Throwable e) {
                log.warn("diagnostic h2 write failed", e);
                onWriteFailure(batch);
                releaseFlushRecords(batch);
                batch.clear();
            }
        }
        drainRemaining(batch);
    }

    private void drainRemaining(List<Record> batch) {
        queue.drainTo(batch);
        if (!batch.isEmpty()) {
            try {
                writeBatch(batch);
            } catch (Throwable e) {
                log.warn("diagnostic h2 final write failed", e);
                onWriteFailure(batch);
                releaseFlushRecords(batch);
            } finally {
                batch.clear();
            }
        }
    }

    private void initSchema() throws SQLException {
        db.withConnection(conn -> {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS diag_metric_sample ("
                        + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "ts BIGINT NOT NULL,"
                        + "metric VARCHAR(256) NOT NULL,"
                        + "metric_value DOUBLE,"
                        + "tags VARCHAR(2048),"
                        + "incident_id VARCHAR(96))");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_diag_metric_ts ON diag_metric_sample(ts)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_diag_metric_name_ts ON diag_metric_sample(metric, ts)");

                stmt.execute("CREATE TABLE IF NOT EXISTS diag_stack_trace ("
                        + "stack_hash BIGINT PRIMARY KEY,"
                        + "stack_text CLOB NOT NULL,"
                        + "first_seen BIGINT NOT NULL,"
                        + "last_seen BIGINT NOT NULL)");

                stmt.execute("CREATE TABLE IF NOT EXISTS diag_thread_cpu_sample ("
                        + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "ts BIGINT NOT NULL,"
                        + "thread_id BIGINT NOT NULL,"
                        + "thread_name VARCHAR(512),"
                        + "cpu_nanos_delta BIGINT,"
                        + "state VARCHAR(64),"
                        + "stack_hash BIGINT,"
                        + "incident_id VARCHAR(96))");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_diag_thread_cpu_incident ON diag_thread_cpu_sample(incident_id, cpu_nanos_delta)");

                stmt.execute("CREATE TABLE IF NOT EXISTS diag_file_io_sample ("
                        + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "ts BIGINT NOT NULL,"
                        + "path_hash BIGINT NOT NULL,"
                        + "path_sample VARCHAR(2048),"
                        + "op VARCHAR(16) NOT NULL,"
                        + "bytes BIGINT,"
                        + "elapsed_nanos BIGINT,"
                        + "stack_hash BIGINT,"
                        + "incident_id VARCHAR(96))");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_diag_file_io_incident ON diag_file_io_sample(incident_id, bytes)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_diag_file_io_path ON diag_file_io_sample(path_hash, ts)");

                stmt.execute("CREATE TABLE IF NOT EXISTS diag_file_size_sample ("
                        + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "ts BIGINT NOT NULL,"
                        + "path_hash BIGINT NOT NULL,"
                        + "path_sample VARCHAR(2048),"
                        + "size_bytes BIGINT,"
                        + "last_modified BIGINT,"
                        + "incident_id VARCHAR(96))");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_diag_file_size_incident ON diag_file_size_sample(incident_id, size_bytes)");

                stmt.execute("CREATE TABLE IF NOT EXISTS diag_incident ("
                        + "incident_id VARCHAR(96) PRIMARY KEY,"
                        + "type VARCHAR(64) NOT NULL,"
                        + "level VARCHAR(32) NOT NULL,"
                        + "start_ts BIGINT NOT NULL,"
                        + "end_ts BIGINT,"
                        + "summary CLOB,"
                        + "bundle_path VARCHAR(2048))");
            }
        });
    }

    private void writeBatch(List<Record> batch) throws SQLException {
        try {
            db.withConnection(conn -> {
                boolean autoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    writeBatch(conn, batch);
                    cleanupExpired(conn, System.currentTimeMillis());
                    enforceStorageBudget(conn);
                    conn.commit();
                    if (hasDataRecords(batch)) {
                        degradedUntilMillis = 0L;
                    }
                } catch (Throwable e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(autoCommit);
                }
            });
        } finally {
            releaseFlushRecords(batch);
        }
    }

    private void writeBatch(Connection conn, List<Record> batch) throws SQLException {
        try (PreparedStatement metricStmt = conn.prepareStatement("INSERT INTO diag_metric_sample(ts, metric, metric_value, tags, incident_id) VALUES (?, ?, ?, ?, ?)");
             PreparedStatement stackStmt = conn.prepareStatement("MERGE INTO diag_stack_trace(stack_hash, stack_text, first_seen, last_seen) KEY(stack_hash) VALUES (?, ?, ?, ?)");
             PreparedStatement threadStmt = conn.prepareStatement("INSERT INTO diag_thread_cpu_sample(ts, thread_id, thread_name, cpu_nanos_delta, state, stack_hash, incident_id) VALUES (?, ?, ?, ?, ?, ?, ?)");
             PreparedStatement fileIoStmt = conn.prepareStatement("INSERT INTO diag_file_io_sample(ts, path_hash, path_sample, op, bytes, elapsed_nanos, stack_hash, incident_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
             PreparedStatement fileSizeStmt = conn.prepareStatement("INSERT INTO diag_file_size_sample(ts, path_hash, path_sample, size_bytes, last_modified, incident_id) VALUES (?, ?, ?, ?, ?, ?)");
             PreparedStatement incidentStmt = conn.prepareStatement("MERGE INTO diag_incident(incident_id, type, level, start_ts, end_ts, summary, bundle_path) KEY(incident_id) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            boolean metric = false;
            boolean stack = false;
            boolean thread = false;
            boolean fileIo = false;
            boolean fileSize = false;
            boolean incident = false;

            for (Record record : batch) {
                switch (record.type) {
                    case TYPE_METRIC:
                        metricStmt.setLong(1, record.ts);
                        metricStmt.setString(2, limit(record.s1, 256));
                        metricStmt.setDouble(3, record.d1);
                        metricStmt.setString(4, limit(record.s2, 2048));
                        metricStmt.setString(5, limit(record.s3, 96));
                        metricStmt.addBatch();
                        metric = true;
                        break;
                    case TYPE_STACK:
                        stackStmt.setLong(1, record.l1);
                        stackStmt.setString(2, record.s1);
                        stackStmt.setLong(3, record.ts);
                        stackStmt.setLong(4, record.ts);
                        stackStmt.addBatch();
                        stack = true;
                        break;
                    case TYPE_THREAD_CPU:
                        threadStmt.setLong(1, record.ts);
                        threadStmt.setLong(2, record.l1);
                        threadStmt.setString(3, limit(record.s1, 512));
                        threadStmt.setLong(4, record.l2);
                        threadStmt.setString(5, limit(record.s2, 64));
                        threadStmt.setLong(6, record.l3);
                        threadStmt.setString(7, limit(record.s3, 96));
                        threadStmt.addBatch();
                        thread = true;
                        break;
                    case TYPE_FILE_IO:
                        fileIoStmt.setLong(1, record.ts);
                        fileIoStmt.setLong(2, record.l1);
                        fileIoStmt.setString(3, limit(record.s1, 2048));
                        fileIoStmt.setString(4, limit(record.s2, 16));
                        fileIoStmt.setLong(5, record.l2);
                        fileIoStmt.setLong(6, record.l3);
                        fileIoStmt.setLong(7, record.l4);
                        fileIoStmt.setString(8, limit(record.s3, 96));
                        fileIoStmt.addBatch();
                        fileIo = true;
                        break;
                    case TYPE_FILE_SIZE:
                        fileSizeStmt.setLong(1, record.ts);
                        fileSizeStmt.setLong(2, record.l1);
                        fileSizeStmt.setString(3, limit(record.s1, 2048));
                        fileSizeStmt.setLong(4, record.l2);
                        fileSizeStmt.setLong(5, record.l3);
                        fileSizeStmt.setString(6, limit(record.s2, 96));
                        fileSizeStmt.addBatch();
                        fileSize = true;
                        break;
                    case TYPE_INCIDENT:
                        incidentStmt.setString(1, limit(record.s1, 96));
                        incidentStmt.setString(2, limit(record.s2, 64));
                        incidentStmt.setString(3, limit(record.s3, 32));
                        incidentStmt.setLong(4, record.l1);
                        incidentStmt.setLong(5, record.l2);
                        incidentStmt.setString(6, record.s4);
                        incidentStmt.setString(7, limit(record.s5, 2048));
                        incidentStmt.addBatch();
                        incident = true;
                        break;
                    case TYPE_FLUSH:
                        break;
                    default:
                        break;
                }
            }

            if (metric) {
                metricStmt.executeBatch();
            }
            if (stack) {
                stackStmt.executeBatch();
            }
            if (thread) {
                threadStmt.executeBatch();
            }
            if (fileIo) {
                fileIoStmt.executeBatch();
            }
            if (fileSize) {
                fileSizeStmt.executeBatch();
            }
            if (incident) {
                incidentStmt.executeBatch();
            }
        }
    }

    private void cleanupExpired(Connection conn, long now) throws SQLException {
        long ttl = config.getH2TtlMillis();
        if (ttl <= 0L || now - lastCleanupMillis < 60000L) {
            return;
        }
        lastCleanupMillis = now;
        long expireBefore = now - ttl;
        try (PreparedStatement metric = conn.prepareStatement("DELETE FROM diag_metric_sample WHERE ts < ?");
             PreparedStatement thread = conn.prepareStatement("DELETE FROM diag_thread_cpu_sample WHERE ts < ?");
             PreparedStatement fileIo = conn.prepareStatement("DELETE FROM diag_file_io_sample WHERE ts < ?");
             PreparedStatement fileSize = conn.prepareStatement("DELETE FROM diag_file_size_sample WHERE ts < ?")) {
            metric.setLong(1, expireBefore);
            metric.executeUpdate();
            thread.setLong(1, expireBefore);
            thread.executeUpdate();
            fileIo.setLong(1, expireBefore);
            fileIo.executeUpdate();
            fileSize.setLong(1, expireBefore);
            fileSize.executeUpdate();
        }
    }

    private void enforceStorageBudget(Connection conn) throws SQLException {
        long maxBytes = config.getH2MaxBytes();
        if (maxBytes <= 0L || !config.isFileH2Storage()) {
            return;
        }
        long bytes = DiagnosticFileSupport.h2StorageBytes(config.getH2File());
        if (bytes >= 0L && bytes <= maxBytes) {
            return;
        }
        int limit = Math.max(config.getH2BatchSize() * 16, 1024);
        purgeOldRows(conn, "diag_metric_sample", "id", "ts", limit);
        purgeOldRows(conn, "diag_file_io_sample", "id", "ts", limit);
        purgeOldRows(conn, "diag_thread_cpu_sample", "id", "ts", limit);
        purgeOldRows(conn, "diag_file_size_sample", "id", "ts", limit);
        purgeOldRows(conn, "diag_stack_trace", "stack_hash", "last_seen", limit);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CHECKPOINT SYNC");
        }
    }

    private void purgeOldRows(Connection conn, String table, String idColumn, String orderColumn, int limit) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM " + table
                + " WHERE " + idColumn + " IN (SELECT " + idColumn + " FROM " + table
                + " ORDER BY " + orderColumn + " ASC LIMIT ?)")) {
            stmt.setInt(1, limit);
            stmt.executeUpdate();
        }
    }

    public boolean isDegraded() {
        return System.currentTimeMillis() < degradedUntilMillis;
    }

    public long writeFailures() {
        return writeFailures.get();
    }

    private void onWriteFailure(List<Record> batch) {
        writeFailures.incrementAndGet();
        degradedUntilMillis = Math.max(degradedUntilMillis, System.currentTimeMillis() + config.getH2FailureDegradeMillis());
        droppedRecords.addAndGet(countDataRecords(batch));
    }

    private static int countDataRecords(List<Record> batch) {
        int count = 0;
        for (Record record : batch) {
            if (record.type != TYPE_FLUSH) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasDataRecords(List<Record> batch) {
        return countDataRecords(batch) > 0;
    }

    private static String limit(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private static void releaseFlushRecords(List<Record> batch) {
        for (Record record : batch) {
            if (record.type == TYPE_FLUSH && record.latch != null) {
                record.latch.countDown();
            }
        }
    }

    static EntityDatabase createDatabase(DiagnosticConfig config) {
        config.normalize();
        int maxConnections = 1;
        if (config.getH2JdbcUrl() != null && config.getH2JdbcUrl().length() != 0) {
            return new EntityDatabaseImpl(config.getH2JdbcUrl(), null, maxConnections, true);
        }
        return new EntityDatabaseImpl(config.getH2File().getPath(), null, maxConnections);
    }

    private static final class Record {
        final int type;
        long ts;
        long l1;
        long l2;
        long l3;
        long l4;
        double d1;
        String s1;
        String s2;
        String s3;
        String s4;
        String s5;
        CountDownLatch latch;

        Record(int type) {
            this.type = type;
        }
    }
}
