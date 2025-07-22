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
import org.rx.bean.TriePrefixMatcher;
import org.rx.net.Sockets;
import org.springframework.core.env.Environment;

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
        String CACHE_MAIN_INSTANCE = "app.cache.mainInstance";
        String CACHE_SLIDING_SECONDS = "app.cache.slidingSeconds";
        String CACHE_MAX_ITEM_SIZE = "app.cache.maxItemSize";

        String DISK_H2_SETTINGS = "app.disk.h2Settings";
        String DISK_USAGE_WARNING = "app.disk.diskUsageWarningThreshold";
        String DISK_ENTITY_DATABASE_ROLL_PERIOD = "app.disk.entityDatabaseRollPeriod";

        String NET_REACTOR_THREAD_AMOUNT = "app.net.reactorThreadAmount";
        String NET_ENABLE_LOG = "app.net.enableLog";
        String NET_CONNECT_TIMEOUT_MILLIS = "app.net.connectTimeoutMillis";
        String NET_READ_WRITE_TIMEOUT_MILLIS = "app.net.readWriteTimeoutMillis";
        String NET_POOL_MAX_SIZE = "app.net.poolMaxSize";
        String NET_POOL_KEEP_ALIVE_SECONDS = "app.net.poolKeepAliveSeconds";
        String NET_USER_AGENT = "app.net.userAgent";
        String NET_BYPASS_HOSTS = "app.net.bypassHosts";
        String NET_CIPHERS_KEY = "app.net.ciphers";
        String NTP_ENABLE_FLAGS = "app.net.ntp.enableFlags";
        String NTP_SYNC_PERIOD = "app.net.ntp.syncPeriod";
        String NTP_TIMEOUT_MILLIS = "app.net.ntp.timeoutMillis";
        String NTP_SERVERS = "app.net.ntp.servers";
        String DNS_INLAND_SERVERS = "app.net.dns.inlandServers";
        String DNS_OUTLAND_SERVERS = "app.net.dns.outlandServers";
        String REST_LOG_MODE = "app.rest.logMode";
        String REST_BLACK_LIST = "app.rest.blackList";
        String REST_WHITE_LIST = "app.rest.whiteList";
        String REST_FORWARDS = "app.rest.forwards";

        String APP_ID = "app.id";
        String MX_SAMPLING_PERIOD = "app.mxSamplingPeriod";
        String DATE_FORMAT = "app.dateFormat";
        String LOG_STRATEGY = "app.logStrategy";
        String JSON_SKIP_TYPES = "app.jsonSkipTypes";
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

        Class<? extends Cache> mainInstance;
        int slidingSeconds;
        int maxItemSize;
    }

    @Getter
    @Setter
    @ToString
    public static class DiskConfig {
        int diskUsageWarningThreshold;
        String h2Settings;
        int entityDatabaseRollPeriod;
    }

    @Getter
    @Setter
    @ToString
    public static class NetConfig {
        int reactorThreadAmount;
        boolean enableLog;
        int connectTimeoutMillis;
        int readWriteTimeoutMillis;
        int poolMaxSize;
        int poolKeepAliveSeconds;
        String userAgent;
        final List<String> bypassHosts = newConcurrentList(true);
        final List<String> ciphers = newConcurrentList(true);
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

    @Getter
    @Setter
    @ToString
    public static class RestConfig {
        //0 disable, 1 blackList, 2 whiteList
        int logMode;
        final List<String> blackList = newConcurrentList(true);
        final List<String> whiteList = newConcurrentList(true);
        //key1: controller, key2: method, value: url
        final Map<String, Map<String, String>> forwards = new ConcurrentHashMap<>(8);
        private TriePrefixMatcher blackMatcher, whiteMatcher;

        public TriePrefixMatcher getBlackMatcher() {
            if (blackMatcher == null) {
                blackMatcher = new TriePrefixMatcher(blackList);
            }
            return blackMatcher;
        }

        public TriePrefixMatcher getWhiteMatcher() {
            if (whiteMatcher == null) {
                whiteMatcher = new TriePrefixMatcher(whiteList);
            }
            return whiteMatcher;
        }
    }

    public static final RxConfig INSTANCE;

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
    final Set<String> logTypeWhitelist = ConcurrentHashMap.newKeySet();
    String omega;
    String mxpwd;
    long mxSamplingPeriod;
    TraceConfig trace = new TraceConfig();
    ThreadPoolConfig threadPool = new ThreadPoolConfig();
    CacheConfig cache = new CacheConfig();
    DiskConfig disk = new DiskConfig();
    NetConfig net = new NetConfig();
    RestConfig rest = new RestConfig();

    public int getIntId() {
        Integer v = Integer.getInteger(id);
        if (v != null) {
            return v;
        }
        return id.hashCode();
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

        afterSet();
    }

    void afterSet() {
        threadPool.replicas = Math.max(1, threadPool.replicas);
        if (net.poolMaxSize <= 0) {
            net.poolMaxSize = Math.max(10, Constants.CPU_THREADS * 2);
        }
        if (id == null) {
            id = Sockets.getLocalAddress().getHostAddress() + "-" + Strings.randomValue(99);
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
        threadPool.traceName = SystemPropertyUtil.get(ConfigNames.THREAD_POOL_TRACE_NAME);
        threadPool.maxTraceDepth = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_MAX_TRACE_DEPTH, threadPool.maxTraceDepth);
        threadPool.slowMethodSamplingPercent = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_SLOW_METHOD_SAMPLING_PERCENT, threadPool.slowMethodSamplingPercent);
        threadPool.cpuLoadWarningThreshold = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_CPU_LOAD_WARNING, threadPool.cpuLoadWarningThreshold);
        threadPool.samplingPeriod = SystemPropertyUtil.getLong(ConfigNames.THREAD_POOL_SAMPLING_PERIOD, threadPool.samplingPeriod);
        threadPool.samplingTimes = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_SAMPLING_TIMES, threadPool.samplingTimes);
        threadPool.minDynamicSize = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_MIN_DYNAMIC_SIZE, threadPool.minDynamicSize);
        threadPool.maxDynamicSize = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_MAX_DYNAMIC_SIZE, threadPool.maxDynamicSize);
        threadPool.resizeQuantity = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_RESIZE_QUANTITY, threadPool.resizeQuantity);

        cache.physicalMemoryUsageWarningThreshold = SystemPropertyUtil.getInt(ConfigNames.PHYSICAL_MEMORY_USAGE_WARNING, cache.physicalMemoryUsageWarningThreshold);
        String v = SystemPropertyUtil.get(ConfigNames.CACHE_MAIN_INSTANCE);
        if (v != null) {
            cache.mainInstance = (Class<? extends Cache>) ClassUtils.getClass(v, true);
        }
        cache.slidingSeconds = SystemPropertyUtil.getInt(ConfigNames.CACHE_SLIDING_SECONDS, cache.slidingSeconds);
        cache.maxItemSize = SystemPropertyUtil.getInt(ConfigNames.CACHE_MAX_ITEM_SIZE, cache.maxItemSize);

        disk.h2Settings = SystemPropertyUtil.get(ConfigNames.DISK_H2_SETTINGS, disk.h2Settings);
        disk.diskUsageWarningThreshold = SystemPropertyUtil.getInt(ConfigNames.DISK_USAGE_WARNING, disk.diskUsageWarningThreshold);
        disk.entityDatabaseRollPeriod = SystemPropertyUtil.getInt(ConfigNames.DISK_ENTITY_DATABASE_ROLL_PERIOD, disk.entityDatabaseRollPeriod);

        net.reactorThreadAmount = SystemPropertyUtil.getInt(ConfigNames.NET_REACTOR_THREAD_AMOUNT, net.reactorThreadAmount);
        net.enableLog = SystemPropertyUtil.getBoolean(ConfigNames.NET_ENABLE_LOG, net.enableLog);
        net.connectTimeoutMillis = SystemPropertyUtil.getInt(ConfigNames.NET_CONNECT_TIMEOUT_MILLIS, net.connectTimeoutMillis);
        net.readWriteTimeoutMillis = SystemPropertyUtil.getInt(ConfigNames.NET_READ_WRITE_TIMEOUT_MILLIS, net.readWriteTimeoutMillis);
        net.poolMaxSize = SystemPropertyUtil.getInt(ConfigNames.NET_POOL_MAX_SIZE, net.poolMaxSize);
        net.poolKeepAliveSeconds = SystemPropertyUtil.getInt(ConfigNames.NET_POOL_KEEP_ALIVE_SECONDS, net.poolKeepAliveSeconds);
        net.userAgent = SystemPropertyUtil.get(ConfigNames.NET_USER_AGENT, net.userAgent);
        reset(net.bypassHosts, ConfigNames.NET_BYPASS_HOSTS);
        reset(net.ciphers, ConfigNames.NET_CIPHERS_KEY);

        net.ntp.enableFlags = SystemPropertyUtil.getInt(ConfigNames.NTP_ENABLE_FLAGS, net.ntp.enableFlags);
        net.ntp.syncPeriod = SystemPropertyUtil.getLong(ConfigNames.NTP_SYNC_PERIOD, net.ntp.syncPeriod);
        net.ntp.timeoutMillis = SystemPropertyUtil.getLong(ConfigNames.NTP_TIMEOUT_MILLIS, net.ntp.timeoutMillis);
        reset(net.ntp.servers, ConfigNames.NTP_SERVERS);

        reset(net.dns.inlandServers, ConfigNames.DNS_INLAND_SERVERS);
        reset(net.dns.outlandServers, ConfigNames.DNS_OUTLAND_SERVERS);

        rest.logMode = SystemPropertyUtil.getInt(ConfigNames.REST_LOG_MODE, rest.logMode);
        reset(rest.blackList, ConfigNames.REST_BLACK_LIST);
        reset(rest.whiteList, ConfigNames.REST_WHITE_LIST);

        id = SystemPropertyUtil.get(ConfigNames.APP_ID, id);
        omega = SystemPropertyUtil.get(ConfigNames.OMEGA, omega);
        mxpwd = SystemPropertyUtil.get(ConfigNames.MXPWD, mxpwd);
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
}
