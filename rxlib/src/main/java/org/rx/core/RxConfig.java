package org.rx.core;

import com.alibaba.fastjson2.JSONFactory;
import io.netty.util.internal.SystemPropertyUtil;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.rx.annotation.Metadata;
import org.rx.bean.LogStrategy;
import org.rx.net.Sockets;

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
        String NET_READ_WRITE_TIMEOUT_MILLIS = "app.net.readWriteTimeoutMillis";
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

        Class<? extends Cache> mainInstance;
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
        int readWriteTimeoutMillis;
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
    String aesKey;
    String dateFormat;
    final Set<Class<?>> jsonSkipTypes = ConcurrentHashMap.newKeySet();
    LogStrategy logStrategy;
    final Set<String> logTypeWhitelist = ConcurrentHashMap.newKeySet();
    String omega;
    String mxpwd;
    long mxSamplingPeriod;
    //key1: controller, key2: method, value: url
    Map<Class<?>, Map<String, String>> mxHttpForwards = new ConcurrentHashMap<>(8);
    TraceConfig trace = new TraceConfig();
    ThreadPoolConfig threadPool = new ThreadPoolConfig();
    CacheConfig cache = new CacheConfig();
    DiskConfig disk = new DiskConfig();
    NetConfig net = new NetConfig();

    public int getIntId() {
        Integer v = Integer.getInteger(id);
        if (v != null) {
            return v;
        }
        return id.hashCode();
    }

    private RxConfig() {
    }

    public void refreshFromSystemProperty() {
        Map<String, Object> sysProps = new HashMap<>((Map) System.getProperties());
        reset(net.lanIps, ConfigNames.NET_LAN_IPS);
        reset(net.ntp.servers, ConfigNames.NTP_SERVERS);
        reset(net.dns.inlandServers, ConfigNames.DNS_INLAND_SERVERS);
        reset(net.dns.outlandServers, ConfigNames.DNS_OUTLAND_SERVERS);
        String v = SystemPropertyUtil.get(ConfigNames.JSON_SKIP_TYPES);
        if (v != null) {
            jsonSkipTypes.clear();
            //method ref will match multi methods
            jsonSkipTypes.addAll(Linq.from(Strings.split(v, ",")).select(p -> Class.forName(p)).toSet());
        }

        sysProps.remove(ConfigNames.NET_LAN_IPS);
        sysProps.remove(ConfigNames.NTP_SERVERS);
        sysProps.remove(ConfigNames.DNS_INLAND_SERVERS);
        sysProps.remove(ConfigNames.DNS_OUTLAND_SERVERS);
        sysProps.remove(ConfigNames.JSON_SKIP_TYPES);

        refreshFrom(sysProps);
    }

    void reset(Collection<String> conf, String propName) {
        String v = SystemPropertyUtil.get(propName);
        if (v == null) {
            return;
        }
        conf.clear();
        conf.addAll(Linq.from(Strings.split(v, ",")).toSet());
    }

    public void refreshFrom(Map<String, Object> props) {
        refreshFrom(props, 0);
    }

    public void refreshFrom(Map<String, Object> props, int flags) {
        Linq.from(Reflects.getFieldMap(ConfigNames.class).values()).select(p -> p.get(null)).join(props.entrySet(), (p, x) -> eq(p, x.getKey()), (p, x) -> {
            Reflects.writeFieldByPath(this, ConfigNames.getWithoutPrefix(x.getKey()), x.getValue(), flags);
            return null;
        });

        threadPool.replicas = Math.max(1, threadPool.replicas);
        if (net.poolMaxSize <= 0) {
            net.poolMaxSize = Math.max(10, Constants.CPU_THREADS * 2);
        }
        if (id == null) {
            id = Sockets.getLocalAddress().getHostAddress() + "-" + Strings.randomValue(99);
        }
    }
}
