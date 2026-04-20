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
import org.rx.net.Sockets;
import org.rx.net.http.HttpServer;
import org.rx.util.function.BiFunc;
import org.springframework.core.env.Environment;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
        String THREAD_POOL_MIN_DYNAMIC_SIZE = "app.threadPool.minDynamicSize";
        String THREAD_POOL_MAX_DYNAMIC_SIZE = "app.threadPool.maxDynamicSize";
        String THREAD_POOL_RESIZE_QUANTITY = "app.threadPool.resizeQuantity";

        String PHYSICAL_MEMORY_USAGE_WARNING = "app.cache.physicalMemoryUsageWarningThreshold";
        String CACHE_PROVIDER = "app.cache.provider";
        String CACHE_SLIDING_SECONDS = "app.cache.slidingSeconds";
        String CACHE_MAX_ITEM_SIZE = "app.cache.maxItemSize";

        String STORAGE_USAGE_WARNING = "app.storage.diskUsageWarningThreshold";
        String STORAGE_H2_SETTINGS = "app.storage.h2Settings";
        String STORAGE_H2_DB_PATH = "app.storage.h2DbPath";
        String STORAGE_ENTITY_DATABASE_MAX_CONNECTIONS = "app.storage.entityDatabaseMaxConnections";
        String STORAGE_ENTITY_DATABASE_ROLL_PERIOD = "app.storage.entityDatabaseRollPeriod";

        String DIAGNOSTIC_ENABLED = "app.diagnostic.enabled";
        String DIAGNOSTIC_LEVEL = "app.diagnostic.level";
        String DIAGNOSTIC_SAMPLE_INTERVAL_MILLIS = "app.diagnostic.sample.intervalMillis";
        String DIAGNOSTIC_RING_BUFFER_MAX_SAMPLES = "app.diagnostic.ringBuffer.maxSamples";
        String DIAGNOSTIC_H2_ENABLED = "app.diagnostic.h2.enabled";
        String DIAGNOSTIC_H2_JDBC_URL = "app.diagnostic.h2.jdbcUrl";
        String DIAGNOSTIC_H2_PATH = "app.diagnostic.h2.path";
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
        String DIAGNOSTIC_HEAP_DUMP_ENABLED = "app.diagnostic.heapDump.enabled";
        String DIAGNOSTIC_HEAP_DUMP_MIN_FREE_BYTES = "app.diagnostic.heapDump.minFreeBytes";
        String DIAGNOSTIC_JFR_MODE = "app.diagnostic.jfr.mode";
        String DIAGNOSTIC_JFR_SETTINGS = "app.diagnostic.jfr.settings";
        String DIAGNOSTIC_JFR_DURATION_SECONDS = "app.diagnostic.jfr.durationSeconds";
        String DIAGNOSTIC_JFR_MIN_FREE_BYTES = "app.diagnostic.jfr.minFreeBytes";
        String DIAGNOSTIC_NMT_ENABLED = "app.diagnostic.nmt.enabled";

        String NET_REACTOR_THREAD_AMOUNT = "app.net.reactorThreadAmount";
        String NET_ENABLE_LOG = "app.net.enableLog";
        String NET_CONNECT_TIMEOUT_MILLIS = "app.net.connectTimeoutMillis";
        String NET_READ_WRITE_TIMEOUT_MILLIS = "app.net.readWriteTimeoutMillis";
        String NET_POOL_MAX_SIZE = "app.net.poolMaxSize";
        String NET_POOL_KEEP_ALIVE_SECONDS = "app.net.poolKeepAliveSeconds";
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
        String DNS_INLAND_SERVERS = "app.net.dns.inlandServers";
        String DNS_OUTLAND_SERVERS = "app.net.dns.outlandServers";
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
        int minDynamicSize;
        int maxDynamicSize;
        int resizeQuantity;
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
        int entityDatabaseMaxConnections;
        int entityDatabaseRollPeriod;
    }

    @Getter
    @Setter
    @ToString
    public static class DiagnosticConfig {
        boolean enabled = true;
        DiagnosticLevel level = DiagnosticLevel.LIGHT;
        long sampleIntervalMillis = 10000L;
        int ringBufferMaxSamples = 4096;

        boolean h2Enabled = true;
        String h2JdbcUrl;
        File h2File = new File(".", "rx-diagnostic");
        int h2BatchSize = 128;
        int h2QueueSize = 8192;
        long h2FlushIntervalMillis = 1000L;
        long h2TtlMillis = 3L * 24L * 60L * 60L * 1000L;
        long h2MaxBytes = 256L * 1024L * 1024L;
        long h2FailureDegradeMillis = 60000L;

        File diagnosticsDirectory = new File(".", "rx-diagnostic");
        long diagnosticsMaxBytes = 1024L * 1024L * 1024L;
        long diagnosticsTtlMillis = 3L * 24L * 60L * 60L * 1000L;
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

        boolean heapDumpEnabled;
        String jfrMode = "auto";
        String jfrSettings = "profile";
        int jfrDurationSeconds = 60;
        boolean nativeMemoryTrackingEnabled = true;

        public void normalize() {
            sampleIntervalMillis = positive(sampleIntervalMillis, 10000L);
            ringBufferMaxSamples = Math.max(16, ringBufferMaxSamples);
            h2BatchSize = Math.max(1, h2BatchSize);
            h2QueueSize = Math.max(h2BatchSize, h2QueueSize);
            h2FlushIntervalMillis = positive(h2FlushIntervalMillis, 1000L);
            h2MaxBytes = Math.max(0L, h2MaxBytes);
            h2FailureDegradeMillis = Math.max(0L, h2FailureDegradeMillis);
            if (diagnosticsDirectory == null) {
                diagnosticsDirectory = new File(".", "rx-diagnostic");
            }
            if (h2File == null) {
                h2File = new File(diagnosticsDirectory, "rx-diagnostic");
            }
            diagnosticsMaxBytes = Math.max(0L, diagnosticsMaxBytes);
            diagnosticsTtlMillis = Math.max(0L, diagnosticsTtlMillis);
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
    public static class DnsConfig {
        final List<String> inlandServers = newConcurrentList(true);
        final List<String> outlandServers = newConcurrentList(true);
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
            Reflects.writeFieldByPath(this, ConfigNames.getWithoutPrefix(x.getKey()), x.getValue(), flags);
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
        if (net.poolMaxSize <= 0) {
            net.poolMaxSize = Math.max(10, Constants.CPU_THREADS * 2);
        }
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
//        reset(net.dns.inlandServers, ConfigNames.DNS_INLAND_SERVERS);
//        reset(net.dns.outlandServers, ConfigNames.DNS_OUTLAND_SERVERS);
//        String v = SystemPropertyUtil.get(ConfigNames.JSON_SKIP_TYPES);
//        if (v != null) {
//            jsonSkipTypes.clear();
//            //method ref will match multi methods
//            jsonSkipTypes.addAll(Linq.from(Strings.split(v, ",")).select(p -> Class.forName(p)).toSet());
//        }
//
//        sysProps.remove(ConfigNames.NET_LAN_IPS);
//        sysProps.remove(ConfigNames.NTP_SERVERS);
//        sysProps.remove(ConfigNames.DNS_INLAND_SERVERS);
//        sysProps.remove(ConfigNames.DNS_OUTLAND_SERVERS);
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
        threadPool.minDynamicSize = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_MIN_DYNAMIC_SIZE, threadPool.minDynamicSize);
        threadPool.maxDynamicSize = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_MAX_DYNAMIC_SIZE, threadPool.maxDynamicSize);
        threadPool.resizeQuantity = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_RESIZE_QUANTITY, threadPool.resizeQuantity);

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
        storage.entityDatabaseMaxConnections = SystemPropertyUtil.getInt(ConfigNames.STORAGE_ENTITY_DATABASE_MAX_CONNECTIONS, storage.entityDatabaseMaxConnections);
        storage.entityDatabaseRollPeriod = SystemPropertyUtil.getInt(ConfigNames.STORAGE_ENTITY_DATABASE_ROLL_PERIOD, storage.entityDatabaseRollPeriod);

        diagnostic.enabled = SystemPropertyUtil.getBoolean(ConfigNames.DIAGNOSTIC_ENABLED, diagnostic.enabled);
        diagnostic.level = getEnum(ConfigNames.DIAGNOSTIC_LEVEL, diagnostic.level);
        diagnostic.sampleIntervalMillis = SystemPropertyUtil.getLong(ConfigNames.DIAGNOSTIC_SAMPLE_INTERVAL_MILLIS, diagnostic.sampleIntervalMillis);
        diagnostic.ringBufferMaxSamples = SystemPropertyUtil.getInt(ConfigNames.DIAGNOSTIC_RING_BUFFER_MAX_SAMPLES, diagnostic.ringBufferMaxSamples);
        diagnostic.h2Enabled = SystemPropertyUtil.getBoolean(ConfigNames.DIAGNOSTIC_H2_ENABLED, diagnostic.h2Enabled);
        diagnostic.h2JdbcUrl = SystemPropertyUtil.get(ConfigNames.DIAGNOSTIC_H2_JDBC_URL, diagnostic.h2JdbcUrl);
        String diagPath = SystemPropertyUtil.get(ConfigNames.DIAGNOSTIC_H2_PATH);
        if (diagPath != null && diagPath.length() != 0) {
            diagnostic.h2File = new File(diagPath);
        }
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
        net.http.serverPort = SystemPropertyUtil.getInt(ConfigNames.NET_HTTP_SERVER_PORT, net.http.serverPort);
        net.http.serverTls = SystemPropertyUtil.getBoolean(ConfigNames.NET_HTTP_SERVER_TLS, net.http.serverTls);
        net.http.serverCertificatePath = SystemPropertyUtil.get(ConfigNames.NET_HTTP_SERVER_CERTIFICATE_PATH, net.http.serverCertificatePath);
        net.http.serverCertificatePassword = SystemPropertyUtil.get(ConfigNames.NET_HTTP_SERVER_CERTIFICATE_PASSWORD, net.http.serverCertificatePassword);
        net.userAgent = SystemPropertyUtil.get(ConfigNames.NET_USER_AGENT, net.userAgent);
        reset(net.bypassHosts, ConfigNames.NET_BYPASS_HOSTS);
        reset(net.ciphers, ConfigNames.NET_CIPHERS_KEY);

        net.ntp.syncMode = SystemPropertyUtil.getInt(ConfigNames.NTP_SYNC_MODE, net.ntp.syncMode);
        net.ntp.syncPeriod = SystemPropertyUtil.getLong(ConfigNames.NTP_SYNC_PERIOD, net.ntp.syncPeriod);
        net.ntp.timeoutMillis = SystemPropertyUtil.getLong(ConfigNames.NTP_TIMEOUT_MILLIS, net.ntp.timeoutMillis);
        reset(net.ntp.servers, ConfigNames.NTP_SERVERS);

        reset(net.dns.inlandServers, ConfigNames.DNS_INLAND_SERVERS);
        reset(net.dns.outlandServers, ConfigNames.DNS_OUTLAND_SERVERS);

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
