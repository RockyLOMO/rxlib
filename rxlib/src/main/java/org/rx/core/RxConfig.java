package org.rx.core;

import com.alibaba.fastjson2.JSONFactory;
import io.netty.util.internal.SystemPropertyUtil;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.rx.annotation.Metadata;
import org.rx.bean.LogStrategy;
import org.rx.net.Sockets;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
        String THREAD_POOL_LOW_CPU_WATER_MARK = "app.threadPool.lowCpuWaterMark";
        String THREAD_POOL_HIGH_CPU_WATER_MARK = "app.threadPool.highCpuWaterMark";
        String THREAD_POOL_REPLICAS = "app.threadPool.replicas";
        String THREAD_POOL_TRACE_NAME = "app.threadPool.traceName";
        String THREAD_POOL_MAX_TRACE_DEPTH = "app.threadPool.maxTraceDepth";
        String THREAD_POOL_SAMPLING_PERIOD = "app.threadPool.samplingPeriod";
        String THREAD_POOL_SAMPLING_TIMES = "app.threadPool.samplingTimes";
        String THREAD_POOL_MIN_DYNAMIC_SIZE = "app.threadPool.minDynamicSize";
        String THREAD_POOL_MAX_DYNAMIC_SIZE = "app.threadPool.maxDynamicSize";
        String THREAD_POOL_RESIZE_QUANTITY = "app.threadPool.resizeQuantity";

        String PHYSICAL_MEMORY_USAGE_WARNING = "app.cache.physicalMemoryUsageWarningThreshold";
        String CACHE_MAIN_INSTANCE = "app.cache.mainInstance";
        String CACHE_SLIDING_SECONDS = "app.cache.slidingSeconds";
        String CACHE_MAX_ITEM_SIZE = "app.cache.maxItemSize";

        String DISK_USAGE_WARNING = "app.disk.diskUsageWarningThreshold";
        String DISK_ENTITY_DATABASE_ROLL_PERIOD = "app.disk.entityDatabaseRollPeriod";

        String NET_REACTOR_THREAD_AMOUNT = "app.net.reactorThreadAmount";
        String NET_ENABLE_LOG = "app.net.enableLog";
        String NET_CONNECT_TIMEOUT_MILLIS = "app.net.connectTimeoutMillis";
        String NET_POOL_MAX_SIZE = "app.net.poolMaxSize";
        String NET_POOL_KEEP_ALIVE_SECONDS = "app.net.poolKeepAliveSeconds";
        String NET_USER_AGENT = "app.net.userAgent";
        String NET_LAN_IPS = "app.net.lanIps";
        String NTP_ENABLE_FLAGS = "app.net.ntp.enableFlags";
        String NTP_SYNC_PERIOD = "app.net.ntp.syncPeriod";
        String NTP_TIMEOUT_MILLIS = "app.net.ntp.timeoutMillis";
        String NTP_SERVERS = "app.net.ntp.servers";
        String DNS_INLAND_SERVERS = "app.net.dns.inlandServers";
        String DNS_OUTLAND_SERVERS = "app.net.dns.outlandServers";

        String APP_ID = "app.id";
        String MX_SAMPLING_PERIOD = "app.mxSamplingPeriod";
        String DATE_FORMAT = "app.dateFormat";
        String LOG_STRATEGY = "app.logStrategy";
        String JSON_SKIP_TYPES = "app.jsonSkipTypes";
        String AES_KEY = "app.aesKey";
        String OMEGA = "app.omega";
        String MXPWD = "app.mxpwd";

        static String getWithoutPrefix(String name) {
            return name.substring(4);
        }
    }

    @Getter
    @Setter
    @ToString
    public static class TraceConfig {
        int keepDays;
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
        int lowCpuWaterMark;
        int highCpuWaterMark;
        int replicas;
        String traceName;
        int maxTraceDepth;

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

        Class<? extends Cache> mainCache;
        int slidingSeconds;
        int maxItemSize;
    }

    @Getter
    @Setter
    @ToString
    public static class DiskConfig {
        int diskUsageWarningThreshold;
        int entityDatabaseRollPeriod;
    }

    @Getter
    @Setter
    @ToString
    public static class NetConfig {
        int reactorThreadAmount;
        boolean enableLog;
        int connectTimeoutMillis;
        int poolMaxSize;
        int poolKeepAliveSeconds;
        String userAgent;
        final List<String> lanIps = newConcurrentList(true);
        NtpConfig ntp = new NtpConfig();
        DnsConfig dns = new DnsConfig();
    }

    @Getter
    @Setter
    @ToString
    public static class NtpConfig {
        //1 syncTask, 2 injectJdkTime
        int enableFlags;
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

    public static final RxConfig INSTANCE;

    static {
        JSONFactory.getDefaultObjectReaderProvider().addAutoTypeAccept("org.springframework");
        RxConfig temp;
        try {
            temp = YamlConfiguration.RX_CONF.readAs("app", RxConfig.class);
        } catch (Throwable e) {
            log.error("RxMeta init error", e);
            temp = new RxConfig();
        }
        temp.refreshFromSystemProperty();
        INSTANCE = temp;
    }

    String id;
    String omega;
    String aesKey;
    String mxpwd;
    long mxSamplingPeriod;
    String dateFormat;
    final Set<Class<?>> jsonSkipTypes = ConcurrentHashMap.newKeySet();
    LogStrategy logStrategy;
    final Set<String> logTypeWhitelist = ConcurrentHashMap.newKeySet();
    TraceConfig trace = new TraceConfig();
    ThreadPoolConfig threadPool = new ThreadPoolConfig();
    CacheConfig cache = new CacheConfig();
    DiskConfig disk = new DiskConfig();
    NetConfig net = new NetConfig();
    //key1: controller, key2: method, value: url
    Map<Class<?>, Map<String, String>> httpForwards = new ConcurrentHashMap<>(8);

    public int getIntId() {
        Integer v = Integer.getInteger(id);
        if (v != null) {
            return v;
        }
        return id.hashCode();
    }

    private RxConfig() {
//        refreshFromSystemProperty();
    }

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
        threadPool.lowCpuWaterMark = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_LOW_CPU_WATER_MARK, threadPool.lowCpuWaterMark);
        threadPool.highCpuWaterMark = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_HIGH_CPU_WATER_MARK, threadPool.highCpuWaterMark);
        threadPool.replicas = Math.max(1, SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_REPLICAS, threadPool.replicas));
        threadPool.traceName = SystemPropertyUtil.get(ConfigNames.THREAD_POOL_TRACE_NAME);
        threadPool.maxTraceDepth = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_MAX_TRACE_DEPTH, threadPool.maxTraceDepth);
        threadPool.cpuLoadWarningThreshold = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_CPU_LOAD_WARNING, threadPool.cpuLoadWarningThreshold);
        threadPool.samplingPeriod = SystemPropertyUtil.getLong(ConfigNames.THREAD_POOL_SAMPLING_PERIOD, threadPool.samplingPeriod);
        threadPool.samplingTimes = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_SAMPLING_TIMES, threadPool.samplingTimes);
        threadPool.minDynamicSize = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_MIN_DYNAMIC_SIZE, threadPool.minDynamicSize);
        threadPool.maxDynamicSize = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_MAX_DYNAMIC_SIZE, threadPool.maxDynamicSize);
        threadPool.resizeQuantity = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_RESIZE_QUANTITY, threadPool.resizeQuantity);

        cache.physicalMemoryUsageWarningThreshold = SystemPropertyUtil.getInt(ConfigNames.PHYSICAL_MEMORY_USAGE_WARNING, cache.physicalMemoryUsageWarningThreshold);
        String mc = SystemPropertyUtil.get(ConfigNames.CACHE_MAIN_INSTANCE);
        if (mc != null) {
            cache.mainCache = (Class<? extends Cache>) Class.forName(mc);
        }
        cache.slidingSeconds = SystemPropertyUtil.getInt(ConfigNames.CACHE_SLIDING_SECONDS, cache.slidingSeconds);
        cache.maxItemSize = SystemPropertyUtil.getInt(ConfigNames.CACHE_MAX_ITEM_SIZE, cache.maxItemSize);

        disk.diskUsageWarningThreshold = SystemPropertyUtil.getInt(ConfigNames.DISK_USAGE_WARNING, disk.diskUsageWarningThreshold);
        disk.entityDatabaseRollPeriod = SystemPropertyUtil.getInt(ConfigNames.DISK_ENTITY_DATABASE_ROLL_PERIOD, disk.entityDatabaseRollPeriod);

        net.reactorThreadAmount = SystemPropertyUtil.getInt(ConfigNames.NET_REACTOR_THREAD_AMOUNT, net.reactorThreadAmount);
        net.enableLog = SystemPropertyUtil.getBoolean(ConfigNames.NET_ENABLE_LOG, net.enableLog);
        net.connectTimeoutMillis = SystemPropertyUtil.getInt(ConfigNames.NET_CONNECT_TIMEOUT_MILLIS, net.connectTimeoutMillis);
        net.poolMaxSize = SystemPropertyUtil.getInt(ConfigNames.NET_POOL_MAX_SIZE, net.poolMaxSize);
        if (net.poolMaxSize <= 0) {
            net.poolMaxSize = Constants.CPU_THREADS * 2;
        }
        net.poolKeepAliveSeconds = SystemPropertyUtil.getInt(ConfigNames.NET_POOL_KEEP_ALIVE_SECONDS, net.poolKeepAliveSeconds);
        net.userAgent = SystemPropertyUtil.get(ConfigNames.NET_USER_AGENT, net.userAgent);
        reset(net.lanIps, ConfigNames.NET_LAN_IPS);

        net.ntp.enableFlags = SystemPropertyUtil.getInt(ConfigNames.NTP_ENABLE_FLAGS, net.ntp.enableFlags);
        net.ntp.syncPeriod = SystemPropertyUtil.getLong(ConfigNames.NTP_SYNC_PERIOD, net.ntp.syncPeriod);
        net.ntp.timeoutMillis = SystemPropertyUtil.getLong(ConfigNames.NTP_TIMEOUT_MILLIS, net.ntp.timeoutMillis);
        reset(net.ntp.servers, ConfigNames.NTP_SERVERS);

        reset(net.dns.inlandServers, ConfigNames.DNS_INLAND_SERVERS);
        reset(net.dns.outlandServers, ConfigNames.DNS_OUTLAND_SERVERS);

        id = SystemPropertyUtil.get(ConfigNames.APP_ID, id);
        if (id == null) {
            id = Sockets.getLocalAddress().getHostAddress() + "-" + Strings.randomValue(99);
        }
        omega = SystemPropertyUtil.get(ConfigNames.OMEGA, omega);
        aesKey = SystemPropertyUtil.get(ConfigNames.AES_KEY, aesKey);
        mxpwd = SystemPropertyUtil.get(ConfigNames.MXPWD, mxpwd);
        mxSamplingPeriod = SystemPropertyUtil.getLong(ConfigNames.MX_SAMPLING_PERIOD, mxSamplingPeriod);
        dateFormat = SystemPropertyUtil.get(ConfigNames.DATE_FORMAT, dateFormat);
        String v = SystemPropertyUtil.get(ConfigNames.JSON_SKIP_TYPES);
        if (v != null) {
            jsonSkipTypes.clear();
            //method ref will match multi methods
            jsonSkipTypes.addAll(Linq.from(Strings.split(v, ",")).select(p -> Class.forName(p)).toSet());
        }
        v = SystemPropertyUtil.get(ConfigNames.LOG_STRATEGY);
        if (v != null) {
            logStrategy = LogStrategy.valueOf(v);
        }
    }

    void reset(Collection<String> conf, String propName) {
        String v = SystemPropertyUtil.get(propName);
        if (v == null) {
            return;
        }
        conf.clear();
        conf.addAll(Linq.from(Strings.split(v, ",")).toSet());
    }
}
