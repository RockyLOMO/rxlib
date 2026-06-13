package org.rx.core;

import com.alibaba.fastjson2.JSONFactory;
import io.netty.util.internal.SystemPropertyUtil;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ClassUtils;
import org.rx.annotation.Metadata;
import org.rx.bean.IntWaterMark;
import org.rx.bean.LogStrategy;
import org.rx.bean.TrieMatcher;
import org.rx.diagnostic.DiagnosticLevel;
import org.rx.net.NetworkFlowControl;
import org.rx.net.NetworkTrafficConfig;
import org.rx.net.Sockets;
import org.rx.net.http.HttpServer;
import org.rx.util.function.BiFunc;
import org.springframework.core.env.Environment;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.rx.core.Extends.eq;
import static org.rx.core.Extends.newConcurrentList;

@Metadata(topicClass = RxConfig.class)
@Slf4j
@Getter
@Setter
@ToString
public final class RxConfig {
    public interface ConfigNames {
        String TRACE_KEEP_DAYS = "app.trace.keepDays";
        String TRACE_ERROR_MESSAGE_SIZE = "app.trace.errorMessageSize";
        String TRACE_SLOW_METHOD_ELAPSED_MICROS = "app.trace.slowMethodElapsedMicros";
        String TRACE_WATCH_THREAD_FLAGS = "app.trace.watchThreadFlags";
        String TRACE_SAMPLING_CPU_PERIOD = "app.trace.samplingCpuPeriod";
        String THREAD_POOL_CPU_LOAD_WARNING = "app.threadPool.cpuLoadWarningThreshold";
        String THREAD_POOL_INIT_SIZE = "app.threadPool.initSize";
        String THREAD_POOL_KEEP_ALIVE_SECONDS = "app.threadPool.keepAliveSeconds";
        String THREAD_POOL_QUEUE_CAPACITY = "app.threadPool.queueCapacity";
        String THREAD_POOL_CPU_WATER_MARK_LOW = "app.threadPool.cpuWaterMark.low";
        String THREAD_POOL_CPU_WATER_MARK_HIGH = "app.threadPool.cpuWaterMark.high";
        String THREAD_POOL_WATCH_SYSTEM_CPU = "app.threadPool.watchSystemCpu";
        String THREAD_POOL_REPLICAS = "app.threadPool.replicas";
        String THREAD_POOL_TRACE_NAME = "app.threadPool.traceName";
        String THREAD_POOL_SLOW_METHOD_SAMPLING_PERCENT = "app.threadPool.slowMethodSamplingPercent";
        String THREAD_POOL_MAX_TRACE_DEPTH = "app.threadPool.maxTraceDepth";
        String THREAD_POOL_SAMPLING_PERIOD = "app.threadPool.samplingPeriod";
        String THREAD_POOL_SAMPLING_TIMES = "app.threadPool.samplingTimes";
        String THREAD_POOL_MIN_IDLE_SIZE = "app.threadPool.minIdleSize";
        String THREAD_POOL_MAX_POOL_SIZE = "app.threadPool.maxPoolSize";
        String THREAD_POOL_RESIZE_STEP = "app.threadPool.resizeStep";
        String THREAD_POOL_QUEUE_OFFER_MODE = "app.threadPool.queueOfferMode";
        String THREAD_POOL_QUEUE_OFFER_TIMEOUT_MILLIS = "app.threadPool.queueOfferTimeoutMillis";
        String THREAD_POOL_SERIAL_QUEUE_CAPACITY = "app.threadPool.serialQueueCapacity";
        String THREAD_POOL_SERIAL_QUEUE_HARD_LIMIT = "app.threadPool.serialQueueHardLimit";
        String THREAD_POOL_PATCH_COMPLETABLE_FUTURE_ASYNC_POOL = "app.threadPool.patchCompletableFutureAsyncPool";
        String THREAD_POOL_RESIZE_COOLDOWN_MILLIS = "app.threadPool.resizeCooldownMillis";

        String PHYSICAL_MEMORY_USAGE_WARNING = "app.cache.physicalMemoryUsageWarningThreshold";
        String CACHE_PROVIDER = "app.cache.provider";
        String CACHE_SLIDING_SECONDS = "app.cache.slidingSeconds";
        String CACHE_MAX_ITEM_SIZE = "app.cache.maxItemSize";

        String STORAGE_USAGE_WARNING = "app.storage.diskUsageWarningThreshold";
        String STORAGE_H2_SETTINGS = "app.storage.h2Settings";
        String STORAGE_H2_DB_PATH = "app.storage.h2DbPath";
        String STORAGE_H2_MAX_CONNECTIONS = "app.storage.h2MaxConnections";
        String STORAGE_ENTITY_DATABASE_ROLL_PERIOD = "app.storage.entityDatabaseRollPeriod";

        String DIAGNOSTIC_ENABLED = "app.diagnostic.enabled";
        String DIAGNOSTIC_LEVEL = "app.diagnostic.level";
        String DIAGNOSTIC_RETENTION_DAYS = "app.diagnostic.retentionDays";
        String DIAGNOSTIC_SAMPLE_INTERVAL_MILLIS = "app.diagnostic.sample.intervalMillis";
        String DIAGNOSTIC_RING_BUFFER_MAX_SAMPLES = "app.diagnostic.ringBuffer.maxSamples";
        String DIAGNOSTIC_H2_ENABLED = "app.diagnostic.h2.enabled";
        String DIAGNOSTIC_H2_JDBC_URL = "app.diagnostic.h2.jdbcUrl";
        String DIAGNOSTIC_H2_PATH = "app.diagnostic.h2.path";
        String DIAGNOSTIC_H2_SETTINGS = "app.diagnostic.h2Settings";
        String DIAGNOSTIC_H2_MAX_CONNECTIONS = "app.diagnostic.h2MaxConnections";
        String DIAGNOSTIC_H2_BATCH_SIZE = "app.diagnostic.h2.batchSize";
        String DIAGNOSTIC_H2_QUEUE_SIZE = "app.diagnostic.h2.queueSize";
        String DIAGNOSTIC_H2_FLUSH_INTERVAL_MILLIS = "app.diagnostic.h2.flushIntervalMillis";
        String DIAGNOSTIC_H2_TTL_MILLIS = "app.diagnostic.h2.ttlMillis";
        String DIAGNOSTIC_H2_MAX_BYTES = "app.diagnostic.h2.maxBytes";
        String DIAGNOSTIC_H2_FAILURE_DEGRADE_MILLIS = "app.diagnostic.h2.failureDegradeMillis";
        String DIAGNOSTIC_DIR = "app.diagnostic.dir";
        String DIAGNOSTIC_DIR_MAX_BYTES = "app.diagnostic.dir.maxBytes";
        String DIAGNOSTIC_DIR_TTL_MILLIS = "app.diagnostic.dir.ttlMillis";
        String DIAGNOSTIC_EVIDENCE_MIN_FREE_BYTES = "app.diagnostic.evidence.minFreeBytes";
        String DIAGNOSTIC_EVIDENCE_HEAVY_COOLDOWN_MILLIS = "app.diagnostic.evidence.heavyCooldownMillis";
        String DIAGNOSTIC_INCIDENT_COOLDOWN_MILLIS = "app.diagnostic.incident.cooldownMillis";
        String DIAGNOSTIC_DIAG_DURATION_MILLIS = "app.diagnostic.diagDurationMillis";
        String DIAGNOSTIC_CPU_THRESHOLD_PERCENT = "app.diagnostic.cpu.thresholdPercent";
        String DIAGNOSTIC_CPU_SUSTAIN_MILLIS = "app.diagnostic.cpu.sustainMillis";
        String DIAGNOSTIC_CPU_TOP_THREADS = "app.diagnostic.cpu.topThreads";
        String DIAGNOSTIC_CPU_EVIDENCE_SAMPLES = "app.diagnostic.cpu.evidenceSamples";
        String DIAGNOSTIC_CPU_EVIDENCE_INTERVAL_MILLIS = "app.diagnostic.cpu.evidenceIntervalMillis";
        String DIAGNOSTIC_STACK_MAX_FRAMES = "app.diagnostic.stack.maxFrames";
        String DIAGNOSTIC_MEMORY_HEAP_THRESHOLD_PERCENT = "app.diagnostic.memory.heapThresholdPercent";
        String DIAGNOSTIC_MEMORY_DIRECT_THRESHOLD_PERCENT = "app.diagnostic.memory.directThresholdPercent";
        String DIAGNOSTIC_MEMORY_METASPACE_THRESHOLD_PERCENT = "app.diagnostic.memory.metaspaceThresholdPercent";
        String DIAGNOSTIC_DISK_FREE_PERCENT_THRESHOLD = "app.diagnostic.disk.freePercentThreshold";
        String DIAGNOSTIC_DISK_MIN_FREE_BYTES = "app.diagnostic.disk.minFreeBytes";
        String DIAGNOSTIC_DISK_IO_BYTES_PER_SECOND_THRESHOLD = "app.diagnostic.disk.ioBytesPerSecondThreshold";
        String DIAGNOSTIC_DISK_IO_SUSTAIN_MILLIS = "app.diagnostic.disk.ioSustainMillis";
        String DIAGNOSTIC_DISK_SCAN_ENABLED = "app.diagnostic.disk.scan.enabled";
        String DIAGNOSTIC_DISK_SCAN_MAX_DEPTH = "app.diagnostic.disk.scan.maxDepth";
        String DIAGNOSTIC_DISK_SCAN_MAX_FILES = "app.diagnostic.disk.scan.maxFiles";
        String DIAGNOSTIC_DISK_SCAN_TOP_FILES = "app.diagnostic.disk.scan.topFiles";
        String DIAGNOSTIC_DISK_SCAN_TIMEOUT_MILLIS = "app.diagnostic.disk.scan.timeoutMillis";
        String DIAGNOSTIC_DISK_SCAN_ROOTS = "app.diagnostic.disk.scan.roots";
        String DIAGNOSTIC_FILE_IO_STACK_SAMPLE_RATE = "app.diagnostic.fileIo.stackSampleRate";
        String DIAGNOSTIC_FILE_IO_DIAG_STACK_SAMPLE_RATE = "app.diagnostic.fileIo.diagStackSampleRate";
        String DIAGNOSTIC_NET_IO_BYTES_PER_SECOND_THRESHOLD = "app.diagnostic.net.ioBytesPerSecondThreshold";
        String DIAGNOSTIC_NET_IO_BANDWIDTH_BYTES_PER_SECOND = "app.diagnostic.net.ioBandwidthBytesPerSecond";
        String DIAGNOSTIC_NET_IO_BANDWIDTH_THRESHOLD_PERCENT = "app.diagnostic.net.ioBandwidthThresholdPercent";
        String DIAGNOSTIC_NET_IO_SUSTAIN_MILLIS = "app.diagnostic.net.ioSustainMillis";
        String DIAGNOSTIC_NET_IO_STACK_SAMPLE_RATE = "app.diagnostic.netIo.stackSampleRate";
        String DIAGNOSTIC_NET_IO_DIAG_STACK_SAMPLE_RATE = "app.diagnostic.netIo.diagStackSampleRate";
        String DIAGNOSTIC_THREAD_STATE_ENABLED = "app.diagnostic.thread.state.enabled";
        String DIAGNOSTIC_THREAD_STATE_SUSTAIN_MILLIS = "app.diagnostic.thread.state.sustainMillis";
        String DIAGNOSTIC_THREAD_BLOCKED_THRESHOLD_COUNT = "app.diagnostic.thread.blocked.thresholdCount";
        String DIAGNOSTIC_THREAD_WAITING_THRESHOLD_COUNT = "app.diagnostic.thread.waiting.thresholdCount";
        String DIAGNOSTIC_THREAD_STATE_TOP_THREADS = "app.diagnostic.thread.state.topThreads";
        String DIAGNOSTIC_HEAP_DUMP_ENABLED = "app.diagnostic.heapDump.enabled";
        String DIAGNOSTIC_HEAP_DUMP_MIN_FREE_BYTES = "app.diagnostic.heapDump.minFreeBytes";
        String DIAGNOSTIC_JFR_MODE = "app.diagnostic.jfr.mode";
        String DIAGNOSTIC_JFR_SETTINGS = "app.diagnostic.jfr.settings";
        String DIAGNOSTIC_JFR_DURATION_SECONDS = "app.diagnostic.jfr.durationSeconds";
        String DIAGNOSTIC_JFR_MIN_FREE_BYTES = "app.diagnostic.jfr.minFreeBytes";
        String DIAGNOSTIC_NMT_ENABLED = "app.diagnostic.nmt.enabled";

        String NET_REACTOR_THREAD_AMOUNT = "app.net.reactorThreadAmount";
        String NET_ENABLE_LOG = "app.net.enableLog";
        /** 全局默认 HttpClient Cookie 实现：memory（内存）或 storage（H2 持久化，同 HttpClientCookieJar.h2） */
        String NET_HTTP_CLIENT_COOKIE_JAR = "app.net.http.clientCookieJar";
        String NET_CONNECT_TIMEOUT_MILLIS = "app.net.connectTimeoutMillis";
        String NET_READ_WRITE_TIMEOUT_MILLIS = "app.net.readWriteTimeoutMillis";
        String NET_POOL_MAX_SIZE = "app.net.poolMaxSize";
        String NET_POOL_KEEP_ALIVE_SECONDS = "app.net.poolKeepAliveSeconds";
        String NET_REUSE_PORT_BIND_COUNT = "app.net.reusePortBindCount";
        String NET_GLOBAL_TRAFFIC_ENABLED = "app.net.globalTraffic.enabled";
        String NET_GLOBAL_TRAFFIC_UPLOAD_KILOBYTES_PER_SECOND = "app.net.globalTraffic.uploadKilobytesPerSecond";
        String NET_GLOBAL_TRAFFIC_DOWNLOAD_KILOBYTES_PER_SECOND = "app.net.globalTraffic.downloadKilobytesPerSecond";
        String NET_GLOBAL_TRAFFIC_CHECK_INTERVAL_MILLIS = "app.net.globalTraffic.checkIntervalMillis";
        String NET_GLOBAL_TRAFFIC_MAX_DELAY_MILLIS = "app.net.globalTraffic.maxDelayMillis";
        String NET_GLOBAL_TRAFFIC_TCP_BACKPRESSURE_ENABLED = "app.net.globalTraffic.tcpBackpressureEnabled";
        String NET_GLOBAL_TRAFFIC_UDP_BACKPRESSURE_ENABLED = "app.net.globalTraffic.udpBackpressureEnabled";
        String NET_GLOBAL_TRAFFIC_UDP_MAX_PENDING_BYTES = "app.net.globalTraffic.udpMaxPendingBytes";
        String NET_GLOBAL_TRAFFIC_UDP_MAX_PENDING_PACKETS = "app.net.globalTraffic.udpMaxPendingPackets";
        String NET_HTTP_SERVER_PORT = "app.net.http.serverPort";
        String NET_HTTP_SERVER_TLS = "app.net.http.serverTls";
        String NET_HTTP_SERVER_CERTIFICATE_PATH = "app.net.http.serverCertificatePath";
        String NET_HTTP_SERVER_CERTIFICATE_PASSWORD = "app.net.http.serverCertificatePassword";
        String NET_USER_AGENT = "app.net.userAgent";
        String NET_BYPASS_HOSTS = "app.net.bypassHosts";
        String NET_CIPHERS_KEY = "app.net.ciphers";
        String NTP_SYNC_MODE = "app.net.ntp.syncMode";
        String NTP_SYNC_PERIOD = "app.net.ntp.syncPeriod";
        String NTP_TIMEOUT_MILLIS = "app.net.ntp.timeoutMillis";
        String NTP_SERVERS = "app.net.ntp.servers";
        String DNS_DIRECT_SERVERS = "app.net.dns.directServers";
        String DNS_REMOTE_SERVERS = "app.net.dns.remoteServers";
        String DNS_LOCAL_SYSTEM_FALLBACK = "app.net.dns.localSystemFallback";
        String DNS_DOH_ENABLED = "app.net.dns.dohEnabled";
        String DNS_DOH_PATH = "app.net.dns.dohPath";
        String DNS_DOH_ALLOW_PLAIN_HTTP = "app.net.dns.dohAllowPlainHttp";
        String DNS_DOH_MAX_MESSAGE_BYTES = "app.net.dns.dohMaxMessageBytes";
        String DNS_DOH_TIMEOUT_MILLIS = "app.net.dns.dohTimeoutMillis";
        String DNS_DOH_MAX_IN_FLIGHT = "app.net.dns.dohMaxInFlight";
        String DNS_DOH_ENDPOINTS = "app.net.dns.dohEndpoints";
        String DNS_CACHE_PREFETCH = "app.net.dns.prefetch";
        String DNS_CACHE_PREFETCH_THRESHOLD_PERCENT = "app.net.dns.prefetchThresholdPercent";
        String DNS_CACHE_SERVE_EXPIRED = "app.net.dns.serveExpired";
        String DNS_CACHE_SERVE_EXPIRED_TTL_SECONDS = "app.net.dns.serveExpiredTtlSeconds";
        String DNS_CACHE_SERVE_EXPIRED_REPLY_TTL_SECONDS = "app.net.dns.serveExpiredReplyTtlSeconds";
        String DNS_CACHE_SERVE_EXPIRED_CLIENT_TIMEOUT_MILLIS = "app.net.dns.serveExpiredClientTimeoutMillis";
        String DNS_CACHE_STORAGE = "app.net.dns.cacheStorage";
        String DNS_CACHE_MAXIMUM_SIZE = "app.net.dns.cacheMaximumSize";
        String DNS_CACHE_MAXIMUM_BYTES = "app.net.dns.cacheMaximumBytes";
        String REST_LOG_MODE = "app.rest.logMode";
        String REST_LOG_NAME_LIST = "app.rest.logNameList";
        String REST_FORWARDS = "app.rest.forwards";

        String APP_ID = "app.id";
        String MX_SAMPLING_PERIOD = "app.mxSamplingPeriod";
        String DATE_FORMAT = "app.dateFormat";
        String LOG_STRATEGY = "app.logStrategy";
        String LOG_NAME_LIST = "app.logNameList";
        String JSON_SKIP_TYPES = "app.jsonSkipTypes";
        String OMEGA = "app.omega";
        String RTOKEN = "app.rtoken";

        static String getWithoutPrefix(String name) {
            return name.substring(4);
        }
    }

    @Getter
    @Setter
    @ToString
    public static class TraceConfig {
        int keepDays;
        int writeQueueLength;
        int flushQueuePeriod;
        int errorMessageSize;
        long slowMethodElapsedMicros;

        //1 Lock, 2 UserTime
        int watchThreadFlags;
        long samplingCpuPeriod;
    }

    @Getter
    @Setter
    @ToString
    public static class ThreadPoolConfig {
        int initSize;
        int keepAliveSeconds;
        int queueCapacity;
        IntWaterMark cpuWaterMark = new IntWaterMark();
        boolean watchSystemCpu;
        int replicas;
        String traceName;
        int maxTraceDepth;
        int slowMethodSamplingPercent;
        final List<String> slowMethodAutoSampleTime = newConcurrentList(true);

        int cpuLoadWarningThreshold;
        long samplingPeriod;
        int samplingTimes;
        int minIdleSize;
        int maxPoolSize;
        int resizeStep;
        ThreadPoolQueueOfferMode queueOfferMode = ThreadPoolQueueOfferMode.BLOCK;
        long queueOfferTimeoutMillis;
        int serialQueueCapacity = 4096;
        int serialQueueHardLimit = 100000;
        boolean patchCompletableFutureAsyncPool;
        long resizeCooldownMillis = 1000L;
    }

    @Getter
    @Setter
    @ToString
    public static class CacheConfig {
        int physicalMemoryUsageWarningThreshold;

        Class<? extends Cache> provider;
        int slidingSeconds;
        int maxItemSize;
    }

    @Getter
    @Setter
    @ToString
    public static class StorageConfig {
        int diskUsageWarningThreshold;
        String h2Settings;
        String h2DbPath;
        int h2MaxConnections;
        int entityDatabaseRollPeriod;
    }

    @Getter
    @Setter
    @ToString
    public static class DiagnosticConfig {
        boolean enabled = true;
        DiagnosticLevel level = DiagnosticLevel.LIGHT;
        int retentionDays = 2;
        long sampleIntervalMillis = 15000L;
        int ringBufferMaxSamples = 4096;

        boolean h2Enabled = true;
        String h2JdbcUrl;
        File h2File = new File(".", "rx-diagnostic");
        String h2Settings = "CACHE_SIZE=2048;MAX_MEMORY_ROWS=512;MAX_OPERATION_MEMORY=1024;WRITE_DELAY=500";
        int h2MaxConnections = 2;
        int h2BatchSize = 128;
        int h2QueueSize = 8192;
        long h2FlushIntervalMillis = 1000L;
        long h2TtlMillis;
        long h2MaxBytes = 256L * 1024L * 1024L;
        long h2FailureDegradeMillis = 60000L;

        File diagnosticsDirectory = new File(".", "rx-diagnostic");
        long diagnosticsMaxBytes = 1024L * 1024L * 1024L;
        long diagnosticsTtlMillis;
        long evidenceMinFreeBytes = 64L * 1024L * 1024L;
        long jfrMinFreeBytes = 256L * 1024L * 1024L;
        long heapDumpMinFreeBytes = 2L * 1024L * 1024L * 1024L;
        long heavyEvidenceCooldownMillis = 300000L;
        long incidentCooldownMillis = 300000L;
        long diagDurationMillis = 60000L;

        double cpuThresholdPercent = 80D;
        long cpuSustainMillis = 30000L;
        int cpuTopThreads = 10;
        int cpuEvidenceSamples = 5;
        long cpuEvidenceIntervalMillis = 300L;
        int maxStackFrames = 64;

        double heapUsedThresholdPercent = 85D;
        double directUsedThresholdPercent = 80D;
        double metaspaceUsedThresholdPercent = 85D;

        double diskFreePercentThreshold = 15D;
        long diskMinFreeBytes = 5L * 1024L * 1024L * 1024L;
        long diskIoBytesPerSecondThreshold = 100L * 1024L * 1024L;
        long diskIoSustainMillis = 30000L;
        boolean diskScanEnabled = true;
        int diskScanMaxDepth = 4;
        int diskScanMaxFiles = 10000;
        int diskScanTopFiles = 50;
        long diskScanTimeoutMillis = 5000L;
        final List<File> diskScanRoots = newConcurrentList(true);

        double fileIoSampleRate = 0.001D;
        double fileIoDiagSampleRate = 0.1D;
        long netIoBytesPerSecondThreshold = 100L * 1024L * 1024L;
        long netIoBandwidthBytesPerSecond;
        double netIoBandwidthThresholdPercent = 80D;
        long netIoSustainMillis = 30000L;
        double netIoSampleRate = 0D;
        double netIoDiagSampleRate = 0.1D;

        boolean threadStateEnabled = true;
        long threadStateSustainMillis = 30000L;
        int threadBlockedThresholdCount = 8;
        int threadWaitingThresholdCount = 128;
        int threadStateTopThreads = 16;

        boolean heapDumpEnabled;
        String jfrMode = "auto";
        String jfrSettings = "profile";
        int jfrDurationSeconds = 60;
        boolean nativeMemoryTrackingEnabled = true;

        public void normalize() {
            retentionDays = Math.max(1, retentionDays);
            sampleIntervalMillis = positive(sampleIntervalMillis, 15000L);
            ringBufferMaxSamples = Math.max(16, ringBufferMaxSamples);
            if (h2Settings == null) {
                h2Settings = "";
            }
            h2MaxConnections = Math.max(1, h2MaxConnections);
            h2BatchSize = Math.max(1, h2BatchSize);
            h2QueueSize = Math.max(h2BatchSize, h2QueueSize);
            h2FlushIntervalMillis = positive(h2FlushIntervalMillis, 1000L);
            long retentionMillis = TimeUnit.DAYS.toMillis(retentionDays);
            h2TtlMillis = h2TtlMillis > 0L ? h2TtlMillis : retentionMillis;
            h2MaxBytes = Math.max(0L, h2MaxBytes);
            h2FailureDegradeMillis = Math.max(0L, h2FailureDegradeMillis);
            if (diagnosticsDirectory == null) {
                diagnosticsDirectory = new File(".", "rx-diagnostic");
            }
            if (h2File == null) {
                h2File = new File(diagnosticsDirectory, "rx-diagnostic");
            }
            diagnosticsMaxBytes = Math.max(0L, diagnosticsMaxBytes);
            diagnosticsTtlMillis = diagnosticsTtlMillis > 0L ? diagnosticsTtlMillis : retentionMillis;
            evidenceMinFreeBytes = Math.max(0L, evidenceMinFreeBytes);
            jfrMinFreeBytes = Math.max(0L, jfrMinFreeBytes);
            heapDumpMinFreeBytes = Math.max(0L, heapDumpMinFreeBytes);
            heavyEvidenceCooldownMillis = Math.max(0L, heavyEvidenceCooldownMillis);
            incidentCooldownMillis = Math.max(0L, incidentCooldownMillis);
            diagDurationMillis = positive(diagDurationMillis, 60000L);
            cpuEvidenceSamples = Math.max(1, cpuEvidenceSamples);
            cpuEvidenceIntervalMillis = positive(cpuEvidenceIntervalMillis, 300L);
            cpuTopThreads = Math.max(1, cpuTopThreads);
            maxStackFrames = Math.max(1, maxStackFrames);
            diskScanMaxDepth = Math.max(0, diskScanMaxDepth);
            diskIoBytesPerSecondThreshold = Math.max(0L, diskIoBytesPerSecondThreshold);
            diskIoSustainMillis = Math.max(0L, diskIoSustainMillis);
            diskScanMaxFiles = Math.max(1, diskScanMaxFiles);
            diskScanTopFiles = Math.max(1, diskScanTopFiles);
            diskScanTimeoutMillis = positive(diskScanTimeoutMillis, 5000L);
            fileIoSampleRate = clampRate(fileIoSampleRate);
            fileIoDiagSampleRate = clampRate(fileIoDiagSampleRate);
            netIoBytesPerSecondThreshold = Math.max(0L, netIoBytesPerSecondThreshold);
            netIoBandwidthBytesPerSecond = Math.max(0L, netIoBandwidthBytesPerSecond);
            netIoBandwidthThresholdPercent = Math.max(0D, netIoBandwidthThresholdPercent);
            netIoSustainMillis = Math.max(0L, netIoSustainMillis);
            netIoSampleRate = clampRate(netIoSampleRate);
            netIoDiagSampleRate = clampRate(netIoDiagSampleRate);
            threadStateSustainMillis = Math.max(0L, threadStateSustainMillis);
            threadBlockedThresholdCount = Math.max(0, threadBlockedThresholdCount);
            threadWaitingThresholdCount = Math.max(0, threadWaitingThresholdCount);
            threadStateTopThreads = Math.max(1, threadStateTopThreads);
        }

        public String jdbcUrl() {
            if (h2JdbcUrl != null && h2JdbcUrl.length() != 0) {
                return h2JdbcUrl;
            }
            File parent = h2File.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            return "jdbc:h2:" + h2File.getAbsolutePath().replace('\\', '/')
                    + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;TRACE_LEVEL_FILE=0;MODE=MySQL;";
        }

        public boolean isFileH2Storage() {
            return h2JdbcUrl == null || h2JdbcUrl.length() == 0
                    || !h2JdbcUrl.toLowerCase(Locale.ENGLISH).startsWith("jdbc:h2:mem:");
        }

        public double effectiveFileIoSampleRate(DiagnosticLevel currentLevel) {
            return currentLevel != null && currentLevel.atLeast(DiagnosticLevel.DIAG) ? fileIoDiagSampleRate : fileIoSampleRate;
        }

        public double effectiveNetIoSampleRate(DiagnosticLevel currentLevel) {
            return currentLevel != null && currentLevel.atLeast(DiagnosticLevel.DIAG) ? netIoDiagSampleRate : netIoSampleRate;
        }

        public long effectiveNetIoBytesPerSecondThreshold() {
            if (netIoBandwidthBytesPerSecond > 0L && netIoBandwidthThresholdPercent > 0D) {
                return Math.max(1L, (long) (netIoBandwidthBytesPerSecond * netIoBandwidthThresholdPercent / 100D));
            }
            return netIoBytesPerSecondThreshold;
        }

        private static long positive(long value, long def) {
            return value <= 0L ? def : value;
        }

        private static double clampRate(double value) {
            if (value < 0D) {
                return 0D;
            }
            if (value > 1D) {
                return 1D;
            }
            return value;
        }
    }

    @Getter
    @Setter
    @ToString
    public static class NetConfig {
        boolean enableLog;
        int reactorThreadAmount;
        int connectTimeoutMillis;
        int readWriteTimeoutMillis;
        int poolMaxSize;
        int poolKeepAliveSeconds;
        int reusePortBindCount = 1;
        NetworkTrafficConfig globalTraffic = new NetworkTrafficConfig();
        HttpConfig http = new HttpConfig();



        String userAgent;
        final List<String> bypassHosts = newConcurrentList(true);
        final List<String> ciphers = newConcurrentList(true);
        NtpConfig ntp = new NtpConfig();
        DnsConfig dns = new DnsConfig();
    }

    @Getter
    @Setter
    @ToString
    public static class HttpConfig {
        /**
         * 默认 {@link org.rx.net.http.HttpClientCookieJar#DEFAULT} 的存储方式：memory（默认）或 storage（H2）。
         */
        String clientCookieJar = "memory";
        int serverPort;
        boolean serverTls;
        String serverCertificatePath;
        String serverCertificatePassword;
    }

    @Getter
    @Setter
    @ToString
    public static class NtpConfig {
        //1 syncTask, 2 injectJdkTime
        int syncMode;
        long syncPeriod;
        long timeoutMillis;
        final List<String> servers = newConcurrentList(true);
    }

    @Getter
    @Setter
    @ToString
    public static class DnsCacheConfig {
        public enum StorageMode {
            MEMORY,
            PERSISTENT,
            HYBRID
        }

        boolean prefetch;
        int prefetchThresholdPercent = 10;
        boolean serveExpired;
        int serveExpiredTtlSeconds = 86400;
        int serveExpiredReplyTtlSeconds = 30;
        int serveExpiredClientTimeoutMillis = 1800;
        StorageMode storage = StorageMode.HYBRID;
        int maximumSize = 4096;
        long maximumBytes;

        public void normalize() {
            if (prefetchThresholdPercent < 1) {
                prefetchThresholdPercent = 1;
            } else if (prefetchThresholdPercent > 100) {
                prefetchThresholdPercent = 100;
            }
            if (serveExpiredTtlSeconds < 0) {
                serveExpiredTtlSeconds = 0;
            }
            if (serveExpiredReplyTtlSeconds < 1) {
                serveExpiredReplyTtlSeconds = 1;
            }
            if (serveExpiredClientTimeoutMillis < 0) {
                serveExpiredClientTimeoutMillis = 0;
            }
            if (maximumSize < 1) {
                maximumSize = 1;
            }
            if (maximumBytes < 0) {
                maximumBytes = 0;
            }
            if (storage == null) {
                storage = StorageMode.HYBRID;
            }
        }
    }

    @Getter
    @Setter
    @ToString
    public static class DnsConfig {
        final List<String> directServers = newConcurrentList(true);
        final List<String> remoteServers = newConcurrentList(true);
        boolean localSystemFallback;
        boolean dohEnabled;
        String dohPath = "/dns-query";
        boolean dohAllowPlainHttp;
        int dohMaxMessageBytes = 65535;
        int dohTimeoutMillis = 5000;
        int dohMaxInFlight = 64;
        final List<String> dohEndpoints = newConcurrentList(true);
        DnsCacheConfig cache = new DnsCacheConfig();
    }

    @Getter
    @Setter
    @ToString
    public static class RestConfig {
        //0 disable, 1 whiteList, 2 blackList
        int logMode;
        final Set<String> logNameList = ConcurrentHashMap.newKeySet();
        //key1: controller, key2: method, value: url
        final Map<String, Map<String, String>> forwards = new ConcurrentHashMap<>(8);
        private volatile TrieMatcher.PrefixMatcher logNameMatcher;

        public TrieMatcher.PrefixMatcher getLogNameMatcher() {
            TrieMatcher.PrefixMatcher matcher = logNameMatcher;
            if (matcher == null) {
                matcher = new TrieMatcher.PrefixMatcher(logMode == 1, logClassNameFn);
                for (String p : logNameList) {
                    matcher.insert(p);
                }
                logNameMatcher = matcher;
            }
            return matcher;
        }
    }

    public static final RxConfig INSTANCE;
    static final BiFunc<String, Object[]> logClassNameFn = className -> Strings.split(className, ".", -1);

    static {
        JSONFactory.getDefaultObjectReaderProvider().addAutoTypeAccept("org.springframework");
        RxConfig t;
        try {
            t = YamlConfiguration.RX_CONF.readAs("app", RxConfig.class);
        } catch (Throwable e) {
            log.error("RxMeta init error", e);
            t = new RxConfig();
        }
        INSTANCE = t;
        INSTANCE.refreshFromSystemProperty();
    }

    String id;
    String dateFormat;
    final Set<Class<?>> jsonSkipTypes = ConcurrentHashMap.newKeySet();
    LogStrategy logStrategy;
    final Set<String> logNameList = ConcurrentHashMap.newKeySet();
    private volatile TrieMatcher.PrefixMatcher logNameMatcher;
    String omega;
    String rtoken;
    long mxSamplingPeriod;
    TraceConfig trace = new TraceConfig();
    ThreadPoolConfig threadPool = new ThreadPoolConfig();
    CacheConfig cache = new CacheConfig();
    StorageConfig storage = new StorageConfig();
    DiagnosticConfig diagnostic = new DiagnosticConfig();
    NetConfig net = new NetConfig();
    RestConfig rest = new RestConfig();

    public int getIntId() {
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            return id.hashCode();
        }
    }

    public TrieMatcher.PrefixMatcher getLogNameMatcher() {
        TrieMatcher.PrefixMatcher matcher = logNameMatcher;
        if (matcher == null) {
            matcher = new TrieMatcher.PrefixMatcher(logStrategy == LogStrategy.WHITELIST, logClassNameFn);
            for (String p : logNameList) {
                matcher.insert(p);
            }
            logNameMatcher = matcher;
        }
        return matcher;
    }

    private RxConfig() {
    }

    public void refreshFrom(Environment env, byte flags) {
        Map<String, Object> rsProps = Linq.from(Reflects.getFieldMap(RxConfig.ConfigNames.class).values()).select(p -> {
            String k = (String) p.get(null);
            return new AbstractMap.SimpleEntry<>(k, env.getProperty(k));
        }).where(p -> p.getValue() != null).toMap();
        refreshFrom(rsProps, flags);
    }

    public void refreshFrom(Map<String, Object> props) {
        refreshFrom(props, (byte) 0);
    }

    public void refreshFrom(Map<String, Object> props, byte flags) {
        Linq.from(Reflects.getFieldMap(ConfigNames.class).values()).select(p -> p.get(null)).join(props.entrySet(), (p, x) -> eq(p, x.getKey()), (p, x) -> {
            if (ConfigNames.THREAD_POOL_QUEUE_OFFER_MODE.equals(x.getKey())) {
                Object value = x.getValue();
                threadPool.queueOfferMode = ThreadPoolQueueOfferMode.parse(value == null ? null : value.toString(), threadPool.queueOfferMode);
            } else {
                Reflects.writeFieldByPath(this, ConfigNames.getWithoutPrefix(x.getKey()), x.getValue(), flags);
            }
            return null;
        });

        invalidateLogNameMatchers();
        afterSet();
    }

    void invalidateLogNameMatchers() {
        logNameMatcher = null;
        if (rest != null) {
            rest.logNameMatcher = null;
        }
    }

    void afterSet() {
        threadPool.replicas = Math.max(1, threadPool.replicas);
        threadPool.minIdleSize = Math.max(1, threadPool.minIdleSize);
        threadPool.maxPoolSize = Math.max(threadPool.minIdleSize, threadPool.maxPoolSize);
        threadPool.resizeStep = Math.max(1, threadPool.resizeStep);
        threadPool.maxTraceDepth = Math.max(1, threadPool.maxTraceDepth);
        if (threadPool.queueOfferMode == null) {
            threadPool.queueOfferMode = ThreadPoolQueueOfferMode.BLOCK;
        }
        threadPool.queueOfferTimeoutMillis = Math.max(0L, threadPool.queueOfferTimeoutMillis);
        threadPool.serialQueueHardLimit = Math.max(1, threadPool.serialQueueHardLimit);
        threadPool.serialQueueCapacity = Math.max(1, Math.min(threadPool.serialQueueCapacity, threadPool.serialQueueHardLimit));
        threadPool.resizeCooldownMillis = Math.max(0L, threadPool.resizeCooldownMillis);
        if (net.poolMaxSize <= 0) {
            net.poolMaxSize = Math.max(10, Constants.CPU_THREADS * 2);
        }
        if (net.globalTraffic == null) {
            net.globalTraffic = new NetworkTrafficConfig();
        }
        net.globalTraffic.normalize();
        NetworkFlowControl.refresh();
        if (id == null) {
            id = Sockets.getLocalAddress().getHostAddress() + "-" + Strings.randomValue(99);
        }
        diagnostic.normalize();
        if (net.http.serverPort > 0) {
            try {
                HttpServer.getDefault();
            } catch (Throwable e) {
                log.warn("init default http server failed, port={}", net.http.serverPort, e);
            }
        }
    }

//    public void refreshFromSystemProperty() {
//        Map<String, Object> sysProps = new HashMap<>((Map) System.getProperties());
//        reset(net.lanIps, ConfigNames.NET_LAN_IPS);
//        reset(net.ntp.servers, ConfigNames.NTP_SERVERS);
//        reset(net.dns.directServers, ConfigNames.DNS_DIRECT_SERVERS);
//        reset(net.dns.remoteServers, ConfigNames.DNS_REMOTE_SERVERS);
//        String v = SystemPropertyUtil.get(ConfigNames.JSON_SKIP_TYPES);
//        if (v != null) {
//            jsonSkipTypes.clear();
//            //method ref will match multi methods
//            jsonSkipTypes.addAll(Linq.from(Strings.split(v, ",")).select(p -> Class.forName(p)).toSet());
//        }
//
//        sysProps.remove(ConfigNames.NET_LAN_IPS);
//        sysProps.remove(ConfigNames.NTP_SERVERS);
//        sysProps.remove(ConfigNames.DNS_DIRECT_SERVERS);
//        sysProps.remove(ConfigNames.DNS_REMOTE_SERVERS);
//        sysProps.remove(ConfigNames.JSON_SKIP_TYPES);
//
//        refreshFrom(sysProps);
//    }

    //初始化减少依赖其他类
    @SneakyThrows
    public void refreshFromSystemProperty() {
        trace.keepDays = SystemPropertyUtil.getInt(ConfigNames.TRACE_KEEP_DAYS, trace.keepDays);
        trace.errorMessageSize = SystemPropertyUtil.getInt(ConfigNames.TRACE_ERROR_MESSAGE_SIZE, trace.errorMessageSize);
        trace.slowMethodElapsedMicros = SystemPropertyUtil.getLong(ConfigNames.TRACE_SLOW_METHOD_ELAPSED_MICROS, trace.slowMethodElapsedMicros);
        trace.watchThreadFlags = SystemPropertyUtil.getInt(ConfigNames.TRACE_WATCH_THREAD_FLAGS, trace.watchThreadFlags);
        trace.samplingCpuPeriod = SystemPropertyUtil.getLong(ConfigNames.TRACE_SAMPLING_CPU_PERIOD, trace.samplingCpuPeriod);
        threadPool.initSize = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_INIT_SIZE, threadPool.initSize);
        threadPool.keepAliveSeconds = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_KEEP_ALIVE_SECONDS, threadPool.keepAliveSeconds);
        threadPool.queueCapacity = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_QUEUE_CAPACITY, threadPool.queueCapacity);
        threadPool.cpuWaterMark.setLow(SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_CPU_WATER_MARK_LOW, threadPool.cpuWaterMark.getLow()));
        threadPool.cpuWaterMark.setHigh(SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_CPU_WATER_MARK_HIGH, threadPool.cpuWaterMark.getHigh()));
        threadPool.watchSystemCpu = SystemPropertyUtil.getBoolean(ConfigNames.THREAD_POOL_WATCH_SYSTEM_CPU, threadPool.watchSystemCpu);
        threadPool.replicas = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_REPLICAS, threadPool.replicas);
        threadPool.traceName = SystemPropertyUtil.get(ConfigNames.THREAD_POOL_TRACE_NAME, threadPool.traceName);
        threadPool.maxTraceDepth = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_MAX_TRACE_DEPTH, threadPool.maxTraceDepth);
        threadPool.slowMethodSamplingPercent = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_SLOW_METHOD_SAMPLING_PERCENT, threadPool.slowMethodSamplingPercent);
        threadPool.cpuLoadWarningThreshold = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_CPU_LOAD_WARNING, threadPool.cpuLoadWarningThreshold);
        threadPool.samplingPeriod = SystemPropertyUtil.getLong(ConfigNames.THREAD_POOL_SAMPLING_PERIOD, threadPool.samplingPeriod);
        threadPool.samplingTimes = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_SAMPLING_TIMES, threadPool.samplingTimes);
        threadPool.minIdleSize = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_MIN_IDLE_SIZE, threadPool.minIdleSize);
        threadPool.maxPoolSize = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_MAX_POOL_SIZE, threadPool.maxPoolSize);
        threadPool.resizeStep = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_RESIZE_STEP, threadPool.resizeStep);
        threadPool.queueOfferMode = ThreadPoolQueueOfferMode.parse(
                SystemPropertyUtil.get(ConfigNames.THREAD_POOL_QUEUE_OFFER_MODE), threadPool.queueOfferMode);
        threadPool.queueOfferTimeoutMillis = SystemPropertyUtil.getLong(
                ConfigNames.THREAD_POOL_QUEUE_OFFER_TIMEOUT_MILLIS, threadPool.queueOfferTimeoutMillis);
        threadPool.serialQueueCapacity = SystemPropertyUtil.getInt(
                ConfigNames.THREAD_POOL_SERIAL_QUEUE_CAPACITY, threadPool.serialQueueCapacity);
        threadPool.serialQueueHardLimit = SystemPropertyUtil.getInt(
                ConfigNames.THREAD_POOL_SERIAL_QUEUE_HARD_LIMIT, threadPool.serialQueueHardLimit);
        threadPool.patchCompletableFutureAsyncPool = SystemPropertyUtil.getBoolean(
                ConfigNames.THREAD_POOL_PATCH_COMPLETABLE_FUTURE_ASYNC_POOL, threadPool.patchCompletableFutureAsyncPool);
        threadPool.resizeCooldownMillis = SystemPropertyUtil.getLong(
                ConfigNames.THREAD_POOL_RESIZE_COOLDOWN_MILLIS, threadPool.resizeCooldownMillis);

        cache.physicalMemoryUsageWarningThreshold = SystemPropertyUtil.getInt(ConfigNames.PHYSICAL_MEMORY_USAGE_WARNING, cache.physicalMemoryUsageWarningThreshold);
        String v = SystemPropertyUtil.get(ConfigNames.CACHE_PROVIDER);
        if (v != null) {
            cache.provider = (Class<? extends Cache>) ClassUtils.getClass(v, true);
        }
        cache.slidingSeconds = SystemPropertyUtil.getInt(ConfigNames.CACHE_SLIDING_SECONDS, cache.slidingSeconds);
        cache.maxItemSize = SystemPropertyUtil.getInt(ConfigNames.CACHE_MAX_ITEM_SIZE, cache.maxItemSize);

        storage.diskUsageWarningThreshold = SystemPropertyUtil.getInt(ConfigNames.STORAGE_USAGE_WARNING, storage.diskUsageWarningThreshold);
        storage.h2Settings = SystemPropertyUtil.get(ConfigNames.STORAGE_H2_SETTINGS, storage.h2Settings);
        storage.h2DbPath = SystemPropertyUtil.get(ConfigNames.STORAGE_H2_DB_PATH, storage.h2DbPath);
        storage.h2MaxConnections = SystemPropertyUtil.getInt(ConfigNames.STORAGE_H2_MAX_CONNECTIONS, storage.h2MaxConnections);
        storage.entityDatabaseRollPeriod = SystemPropertyUtil.getInt(ConfigNames.STORAGE_ENTITY_DATABASE_ROLL_PERIOD, storage.entityDatabaseRollPeriod);

        diagnostic.enabled = SystemPropertyUtil.getBoolean(ConfigNames.DIAGNOSTIC_ENABLED, diagnostic.enabled);
        diagnostic.level = getEnum(ConfigNames.DIAGNOSTIC_LEVEL, diagnostic.level);
        diagnostic.retentionDays = SystemPropertyUtil.getInt(ConfigNames.DIAGNOSTIC_RETENTION_DAYS, diagnostic.retentionDays);
        diagnostic.sampleIntervalMillis = SystemPropertyUtil.getLong(ConfigNames.DIAGNOSTIC_SAMPLE_INTERVAL_MILLIS, diagnostic.sampleIntervalMillis);
        diagnostic.ringBufferMaxSamples = SystemPropertyUtil.getInt(ConfigNames.DIAGNOSTIC_RING_BUFFER_MAX_SAMPLES, diagnostic.ringBufferMaxSamples);
        diagnostic.h2Enabled = SystemPropertyUtil.getBoolean(ConfigNames.DIAGNOSTIC_H2_ENABLED, diagnostic.h2Enabled);
        diagnostic.h2JdbcUrl = SystemPropertyUtil.get(ConfigNames.DIAGNOSTIC_H2_JDBC_URL, diagnostic.h2JdbcUrl);
        String diagPath = SystemPropertyUtil.get(ConfigNames.DIAGNOSTIC_H2_PATH);
        if (diagPath != null && diagPath.length() != 0) {
            diagnostic.h2File = new File(diagPath);
        }
        diagnostic.h2Settings = SystemPropertyUtil.get(ConfigNames.DIAGNOSTIC_H2_SETTINGS, diagnostic.h2Settings);
        diagnostic.h2MaxConnections = SystemPropertyUtil.getInt(ConfigNames.DIAGNOSTIC_H2_MAX_CONNECTIONS, diagnostic.h2MaxConnections);
        diagnostic.h2BatchSize = SystemPropertyUtil.getInt(ConfigNames.DIAGNOSTIC_H2_BATCH_SIZE, diagnostic.h2BatchSize);
        diagnostic.h2QueueSize = SystemPropertyUtil.getInt(ConfigNames.DIAGNOSTIC_H2_QUEUE_SIZE, diagnostic.h2QueueSize);
        diagnostic.h2FlushIntervalMillis = SystemPropertyUtil.getLong(ConfigNames.DIAGNOSTIC_H2_FLUSH_INTERVAL_MILLIS, diagnostic.h2FlushIntervalMillis);
        diagnostic.h2TtlMillis = SystemPropertyUtil.getLong(ConfigNames.DIAGNOSTIC_H2_TTL_MILLIS, diagnostic.h2TtlMillis);
        diagnostic.h2MaxBytes = SystemPropertyUtil.getLong(ConfigNames.DIAGNOSTIC_H2_MAX_BYTES, diagnostic.h2MaxBytes);
        diagnostic.h2FailureDegradeMillis = SystemPropertyUtil.getLong(ConfigNames.DIAGNOSTIC_H2_FAILURE_DEGRADE_MILLIS, diagnostic.h2FailureDegradeMillis);
        diagPath = SystemPropertyUtil.get(ConfigNames.DIAGNOSTIC_DIR);
        if (diagPath != null && diagPath.length() != 0) {
            diagnostic.diagnosticsDirectory = new File(diagPath);
        }
        diagnostic.diagnosticsMaxBytes = SystemPropertyUtil.getLong(ConfigNames.DIAGNOSTIC_DIR_MAX_BYTES, diagnostic.diagnosticsMaxBytes);
        diagnostic.diagnosticsTtlMillis = SystemPropertyUtil.getLong(ConfigNames.DIAGNOSTIC_DIR_TTL_MILLIS, diagnostic.diagnosticsTtlMillis);
        diagnostic.evidenceMinFreeBytes = SystemPropertyUtil.getLong(ConfigNames.DIAGNOSTIC_EVIDENCE_MIN_FREE_BYTES, diagnostic.evidenceMinFreeBytes);
        diagnostic.heavyEvidenceCooldownMillis = SystemPropertyUtil.getLong(ConfigNames.DIAGNOSTIC_EVIDENCE_HEAVY_COOLDOWN_MILLIS, diagnostic.heavyEvidenceCooldownMillis);
        diagnostic.incidentCooldownMillis = SystemPropertyUtil.getLong(ConfigNames.DIAGNOSTIC_INCIDENT_COOLDOWN_MILLIS, diagnostic.incidentCooldownMillis);
        diagnostic.diagDurationMillis = SystemPropertyUtil.getLong(ConfigNames.DIAGNOSTIC_DIAG_DURATION_MILLIS, diagnostic.diagDurationMillis);
        diagnostic.cpuThresholdPercent = getDouble(ConfigNames.DIAGNOSTIC_CPU_THRESHOLD_PERCENT, diagnostic.cpuThresholdPercent);
        diagnostic.cpuSustainMillis = SystemPropertyUtil.getLong(ConfigNames.DIAGNOSTIC_CPU_SUSTAIN_MILLIS, diagnostic.cpuSustainMillis);
        diagnostic.cpuTopThreads = SystemPropertyUtil.getInt(ConfigNames.DIAGNOSTIC_CPU_TOP_THREADS, diagnostic.cpuTopThreads);
        diagnostic.cpuEvidenceSamples = SystemPropertyUtil.getInt(ConfigNames.DIAGNOSTIC_CPU_EVIDENCE_SAMPLES, diagnostic.cpuEvidenceSamples);
        diagnostic.cpuEvidenceIntervalMillis = SystemPropertyUtil.getLong(ConfigNames.DIAGNOSTIC_CPU_EVIDENCE_INTERVAL_MILLIS, diagnostic.cpuEvidenceIntervalMillis);
        diagnostic.maxStackFrames = SystemPropertyUtil.getInt(ConfigNames.DIAGNOSTIC_STACK_MAX_FRAMES, diagnostic.maxStackFrames);
        diagnostic.heapUsedThresholdPercent = getDouble(ConfigNames.DIAGNOSTIC_MEMORY_HEAP_THRESHOLD_PERCENT, diagnostic.heapUsedThresholdPercent);
        diagnostic.directUsedThresholdPercent = getDouble(ConfigNames.DIAGNOSTIC_MEMORY_DIRECT_THRESHOLD_PERCENT, diagnostic.directUsedThresholdPercent);
        diagnostic.metaspaceUsedThresholdPercent = getDouble(ConfigNames.DIAGNOSTIC_MEMORY_METASPACE_THRESHOLD_PERCENT, diagnostic.metaspaceUsedThresholdPercent);
        diagnostic.diskFreePercentThreshold = getDouble(ConfigNames.DIAGNOSTIC_DISK_FREE_PERCENT_THRESHOLD, diagnostic.diskFreePercentThreshold);
        diagnostic.diskMinFreeBytes = SystemPropertyUtil.getLong(ConfigNames.DIAGNOSTIC_DISK_MIN_FREE_BYTES, diagnostic.diskMinFreeBytes);
        diagnostic.diskIoBytesPerSecondThreshold = SystemPropertyUtil.getLong(ConfigNames.DIAGNOSTIC_DISK_IO_BYTES_PER_SECOND_THRESHOLD, diagnostic.diskIoBytesPerSecondThreshold);
        diagnostic.diskIoSustainMillis = SystemPropertyUtil.getLong(ConfigNames.DIAGNOSTIC_DISK_IO_SUSTAIN_MILLIS, diagnostic.diskIoSustainMillis);
        diagnostic.diskScanEnabled = SystemPropertyUtil.getBoolean(ConfigNames.DIAGNOSTIC_DISK_SCAN_ENABLED, diagnostic.diskScanEnabled);
        diagnostic.diskScanMaxDepth = SystemPropertyUtil.getInt(ConfigNames.DIAGNOSTIC_DISK_SCAN_MAX_DEPTH, diagnostic.diskScanMaxDepth);
        diagnostic.diskScanMaxFiles = SystemPropertyUtil.getInt(ConfigNames.DIAGNOSTIC_DISK_SCAN_MAX_FILES, diagnostic.diskScanMaxFiles);
        diagnostic.diskScanTopFiles = SystemPropertyUtil.getInt(ConfigNames.DIAGNOSTIC_DISK_SCAN_TOP_FILES, diagnostic.diskScanTopFiles);
        diagnostic.diskScanTimeoutMillis = SystemPropertyUtil.getLong(ConfigNames.DIAGNOSTIC_DISK_SCAN_TIMEOUT_MILLIS, diagnostic.diskScanTimeoutMillis);
        resetFiles(diagnostic.diskScanRoots, ConfigNames.DIAGNOSTIC_DISK_SCAN_ROOTS);
        diagnostic.fileIoSampleRate = getDouble(ConfigNames.DIAGNOSTIC_FILE_IO_STACK_SAMPLE_RATE, diagnostic.fileIoSampleRate);
        diagnostic.fileIoDiagSampleRate = getDouble(ConfigNames.DIAGNOSTIC_FILE_IO_DIAG_STACK_SAMPLE_RATE, diagnostic.fileIoDiagSampleRate);
        diagnostic.netIoBytesPerSecondThreshold = SystemPropertyUtil.getLong(ConfigNames.DIAGNOSTIC_NET_IO_BYTES_PER_SECOND_THRESHOLD, diagnostic.netIoBytesPerSecondThreshold);
        diagnostic.netIoBandwidthBytesPerSecond = SystemPropertyUtil.getLong(ConfigNames.DIAGNOSTIC_NET_IO_BANDWIDTH_BYTES_PER_SECOND, diagnostic.netIoBandwidthBytesPerSecond);
        diagnostic.netIoBandwidthThresholdPercent = getDouble(ConfigNames.DIAGNOSTIC_NET_IO_BANDWIDTH_THRESHOLD_PERCENT, diagnostic.netIoBandwidthThresholdPercent);
        diagnostic.netIoSustainMillis = SystemPropertyUtil.getLong(ConfigNames.DIAGNOSTIC_NET_IO_SUSTAIN_MILLIS, diagnostic.netIoSustainMillis);
        diagnostic.netIoSampleRate = getDouble(ConfigNames.DIAGNOSTIC_NET_IO_STACK_SAMPLE_RATE, diagnostic.netIoSampleRate);
        diagnostic.netIoDiagSampleRate = getDouble(ConfigNames.DIAGNOSTIC_NET_IO_DIAG_STACK_SAMPLE_RATE, diagnostic.netIoDiagSampleRate);
        diagnostic.threadStateEnabled = SystemPropertyUtil.getBoolean(ConfigNames.DIAGNOSTIC_THREAD_STATE_ENABLED, diagnostic.threadStateEnabled);
        diagnostic.threadStateSustainMillis = SystemPropertyUtil.getLong(ConfigNames.DIAGNOSTIC_THREAD_STATE_SUSTAIN_MILLIS, diagnostic.threadStateSustainMillis);
        diagnostic.threadBlockedThresholdCount = SystemPropertyUtil.getInt(ConfigNames.DIAGNOSTIC_THREAD_BLOCKED_THRESHOLD_COUNT, diagnostic.threadBlockedThresholdCount);
        diagnostic.threadWaitingThresholdCount = SystemPropertyUtil.getInt(ConfigNames.DIAGNOSTIC_THREAD_WAITING_THRESHOLD_COUNT, diagnostic.threadWaitingThresholdCount);
        diagnostic.threadStateTopThreads = SystemPropertyUtil.getInt(ConfigNames.DIAGNOSTIC_THREAD_STATE_TOP_THREADS, diagnostic.threadStateTopThreads);
        diagnostic.heapDumpEnabled = SystemPropertyUtil.getBoolean(ConfigNames.DIAGNOSTIC_HEAP_DUMP_ENABLED, diagnostic.heapDumpEnabled);
        diagnostic.heapDumpMinFreeBytes = SystemPropertyUtil.getLong(ConfigNames.DIAGNOSTIC_HEAP_DUMP_MIN_FREE_BYTES, diagnostic.heapDumpMinFreeBytes);
        diagnostic.jfrMode = SystemPropertyUtil.get(ConfigNames.DIAGNOSTIC_JFR_MODE, diagnostic.jfrMode);
        diagnostic.jfrSettings = SystemPropertyUtil.get(ConfigNames.DIAGNOSTIC_JFR_SETTINGS, diagnostic.jfrSettings);
        diagnostic.jfrDurationSeconds = SystemPropertyUtil.getInt(ConfigNames.DIAGNOSTIC_JFR_DURATION_SECONDS, diagnostic.jfrDurationSeconds);
        diagnostic.jfrMinFreeBytes = SystemPropertyUtil.getLong(ConfigNames.DIAGNOSTIC_JFR_MIN_FREE_BYTES, diagnostic.jfrMinFreeBytes);
        diagnostic.nativeMemoryTrackingEnabled = SystemPropertyUtil.getBoolean(ConfigNames.DIAGNOSTIC_NMT_ENABLED, diagnostic.nativeMemoryTrackingEnabled);

        net.reactorThreadAmount = SystemPropertyUtil.getInt(ConfigNames.NET_REACTOR_THREAD_AMOUNT, net.reactorThreadAmount);
        net.enableLog = SystemPropertyUtil.getBoolean(ConfigNames.NET_ENABLE_LOG, net.enableLog);
        net.connectTimeoutMillis = SystemPropertyUtil.getInt(ConfigNames.NET_CONNECT_TIMEOUT_MILLIS, net.connectTimeoutMillis);
        net.readWriteTimeoutMillis = SystemPropertyUtil.getInt(ConfigNames.NET_READ_WRITE_TIMEOUT_MILLIS, net.readWriteTimeoutMillis);
        net.poolMaxSize = SystemPropertyUtil.getInt(ConfigNames.NET_POOL_MAX_SIZE, net.poolMaxSize);
        net.poolKeepAliveSeconds = SystemPropertyUtil.getInt(ConfigNames.NET_POOL_KEEP_ALIVE_SECONDS, net.poolKeepAliveSeconds);
        net.reusePortBindCount = SystemPropertyUtil.getInt(ConfigNames.NET_REUSE_PORT_BIND_COUNT, net.reusePortBindCount);
        if (net.globalTraffic == null) {
            net.globalTraffic = new NetworkTrafficConfig();
        }
        net.globalTraffic.setEnabled(SystemPropertyUtil.getBoolean(ConfigNames.NET_GLOBAL_TRAFFIC_ENABLED, net.globalTraffic.isEnabled()));
        net.globalTraffic.setUploadKilobytesPerSecond(SystemPropertyUtil.getLong(
                ConfigNames.NET_GLOBAL_TRAFFIC_UPLOAD_KILOBYTES_PER_SECOND, net.globalTraffic.getUploadKilobytesPerSecond()));
        net.globalTraffic.setDownloadKilobytesPerSecond(SystemPropertyUtil.getLong(
                ConfigNames.NET_GLOBAL_TRAFFIC_DOWNLOAD_KILOBYTES_PER_SECOND, net.globalTraffic.getDownloadKilobytesPerSecond()));
        net.globalTraffic.setCheckIntervalMillis(SystemPropertyUtil.getLong(
                ConfigNames.NET_GLOBAL_TRAFFIC_CHECK_INTERVAL_MILLIS, net.globalTraffic.getCheckIntervalMillis()));
        net.globalTraffic.setMaxDelayMillis(SystemPropertyUtil.getLong(
                ConfigNames.NET_GLOBAL_TRAFFIC_MAX_DELAY_MILLIS, net.globalTraffic.getMaxDelayMillis()));
        net.globalTraffic.setTcpBackpressureEnabled(SystemPropertyUtil.getBoolean(
                ConfigNames.NET_GLOBAL_TRAFFIC_TCP_BACKPRESSURE_ENABLED, net.globalTraffic.isTcpBackpressureEnabled()));
        net.globalTraffic.setUdpBackpressureEnabled(SystemPropertyUtil.getBoolean(
                ConfigNames.NET_GLOBAL_TRAFFIC_UDP_BACKPRESSURE_ENABLED, net.globalTraffic.isUdpBackpressureEnabled()));
        net.globalTraffic.setUdpMaxPendingBytes(SystemPropertyUtil.getInt(
                ConfigNames.NET_GLOBAL_TRAFFIC_UDP_MAX_PENDING_BYTES, net.globalTraffic.getUdpMaxPendingBytes()));
        net.globalTraffic.setUdpMaxPendingPackets(SystemPropertyUtil.getInt(
                ConfigNames.NET_GLOBAL_TRAFFIC_UDP_MAX_PENDING_PACKETS, net.globalTraffic.getUdpMaxPendingPackets()));
        net.http.serverPort = SystemPropertyUtil.getInt(ConfigNames.NET_HTTP_SERVER_PORT, net.http.serverPort);
        net.http.serverTls = SystemPropertyUtil.getBoolean(ConfigNames.NET_HTTP_SERVER_TLS, net.http.serverTls);
        net.http.serverCertificatePath = SystemPropertyUtil.get(ConfigNames.NET_HTTP_SERVER_CERTIFICATE_PATH, net.http.serverCertificatePath);
        net.http.serverCertificatePassword = SystemPropertyUtil.get(ConfigNames.NET_HTTP_SERVER_CERTIFICATE_PASSWORD, net.http.serverCertificatePassword);
        net.http.clientCookieJar = SystemPropertyUtil.get(ConfigNames.NET_HTTP_CLIENT_COOKIE_JAR, net.http.clientCookieJar);
        net.userAgent = SystemPropertyUtil.get(ConfigNames.NET_USER_AGENT, net.userAgent);
        reset(net.bypassHosts, ConfigNames.NET_BYPASS_HOSTS);
        reset(net.ciphers, ConfigNames.NET_CIPHERS_KEY);

        net.ntp.syncMode = SystemPropertyUtil.getInt(ConfigNames.NTP_SYNC_MODE, net.ntp.syncMode);
        net.ntp.syncPeriod = SystemPropertyUtil.getLong(ConfigNames.NTP_SYNC_PERIOD, net.ntp.syncPeriod);
        net.ntp.timeoutMillis = SystemPropertyUtil.getLong(ConfigNames.NTP_TIMEOUT_MILLIS, net.ntp.timeoutMillis);
        reset(net.ntp.servers, ConfigNames.NTP_SERVERS);

        reset(net.dns.directServers, ConfigNames.DNS_DIRECT_SERVERS);
        reset(net.dns.remoteServers, ConfigNames.DNS_REMOTE_SERVERS);
        net.dns.localSystemFallback = SystemPropertyUtil.getBoolean(ConfigNames.DNS_LOCAL_SYSTEM_FALLBACK, net.dns.localSystemFallback);
        net.dns.dohEnabled = SystemPropertyUtil.getBoolean(ConfigNames.DNS_DOH_ENABLED, net.dns.dohEnabled);
        net.dns.dohPath = SystemPropertyUtil.get(ConfigNames.DNS_DOH_PATH, net.dns.dohPath);
        net.dns.dohAllowPlainHttp = SystemPropertyUtil.getBoolean(ConfigNames.DNS_DOH_ALLOW_PLAIN_HTTP, net.dns.dohAllowPlainHttp);
        net.dns.dohMaxMessageBytes = SystemPropertyUtil.getInt(ConfigNames.DNS_DOH_MAX_MESSAGE_BYTES, net.dns.dohMaxMessageBytes);
        net.dns.dohTimeoutMillis = SystemPropertyUtil.getInt(ConfigNames.DNS_DOH_TIMEOUT_MILLIS, net.dns.dohTimeoutMillis);
        net.dns.dohMaxInFlight = SystemPropertyUtil.getInt(ConfigNames.DNS_DOH_MAX_IN_FLIGHT, net.dns.dohMaxInFlight);
        reset(net.dns.dohEndpoints, ConfigNames.DNS_DOH_ENDPOINTS);
        net.dns.cache.prefetch = SystemPropertyUtil.getBoolean(ConfigNames.DNS_CACHE_PREFETCH, net.dns.cache.prefetch);
        net.dns.cache.prefetchThresholdPercent = SystemPropertyUtil.getInt(
                ConfigNames.DNS_CACHE_PREFETCH_THRESHOLD_PERCENT, net.dns.cache.prefetchThresholdPercent);
        net.dns.cache.serveExpired = SystemPropertyUtil.getBoolean(ConfigNames.DNS_CACHE_SERVE_EXPIRED, net.dns.cache.serveExpired);
        net.dns.cache.serveExpiredTtlSeconds = SystemPropertyUtil.getInt(
                ConfigNames.DNS_CACHE_SERVE_EXPIRED_TTL_SECONDS, net.dns.cache.serveExpiredTtlSeconds);
        net.dns.cache.serveExpiredReplyTtlSeconds = SystemPropertyUtil.getInt(
                ConfigNames.DNS_CACHE_SERVE_EXPIRED_REPLY_TTL_SECONDS, net.dns.cache.serveExpiredReplyTtlSeconds);
        net.dns.cache.serveExpiredClientTimeoutMillis = SystemPropertyUtil.getInt(
                ConfigNames.DNS_CACHE_SERVE_EXPIRED_CLIENT_TIMEOUT_MILLIS, net.dns.cache.serveExpiredClientTimeoutMillis);
        net.dns.cache.storage = getEnum(ConfigNames.DNS_CACHE_STORAGE, net.dns.cache.storage);
        net.dns.cache.maximumSize = SystemPropertyUtil.getInt(ConfigNames.DNS_CACHE_MAXIMUM_SIZE, net.dns.cache.maximumSize);
        net.dns.cache.maximumBytes = SystemPropertyUtil.getLong(ConfigNames.DNS_CACHE_MAXIMUM_BYTES, net.dns.cache.maximumBytes);
        net.dns.cache.normalize();

        rest.logMode = SystemPropertyUtil.getInt(ConfigNames.REST_LOG_MODE, rest.logMode);
        reset(rest.logNameList, ConfigNames.REST_LOG_NAME_LIST);
        rest.logNameMatcher = null;

        id = SystemPropertyUtil.get(ConfigNames.APP_ID, id);
        omega = SystemPropertyUtil.get(ConfigNames.OMEGA, omega);
        rtoken = SystemPropertyUtil.get(ConfigNames.RTOKEN, rtoken);
        mxSamplingPeriod = SystemPropertyUtil.getLong(ConfigNames.MX_SAMPLING_PERIOD, mxSamplingPeriod);
        dateFormat = SystemPropertyUtil.get(ConfigNames.DATE_FORMAT, dateFormat);
        v = SystemPropertyUtil.get(ConfigNames.JSON_SKIP_TYPES);
        if (v != null) {
            jsonSkipTypes.clear();
            //method ref will match multi methods
            jsonSkipTypes.addAll(Linq.from(Strings.split(v, ",")).select(p -> ClassUtils.getClass(p, false)).toSet());
        }
        v = SystemPropertyUtil.get(ConfigNames.LOG_STRATEGY);
        if (v != null) {
            logStrategy = LogStrategy.valueOf(v);
        }
        reset(logNameList, ConfigNames.LOG_NAME_LIST);
        logNameMatcher = null;

        afterSet();
    }

    void reset(Collection<String> conf, String propName) {
        String v = SystemPropertyUtil.get(propName);
        if (v == null) {
            return;
        }
        conf.clear();
        conf.addAll(Linq.from(Strings.split(v, ",")).toSet());
    }

    void resetFiles(Collection<File> conf, String propName) {
        String v = SystemPropertyUtil.get(propName);
        if (v == null) {
            return;
        }
        conf.clear();
        for (String path : Strings.split(v, ",")) {
            if (path != null && path.trim().length() != 0) {
                conf.add(new File(path.trim()));
            }
        }
    }

    static <T extends Enum<T>> T getEnum(String propName, T def) {
        String v = SystemPropertyUtil.get(propName);
        if (v == null || v.length() == 0) {
            return def;
        }
        try {
            return Enum.valueOf(def.getDeclaringClass(), v.trim().toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    static double getDouble(String propName, double def) {
        String v = SystemPropertyUtil.get(propName);
        if (v == null || v.length() == 0) {
            return def;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
