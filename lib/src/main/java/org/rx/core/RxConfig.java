package org.rx.core;

import com.alibaba.fastjson2.JSONFactory;
import io.netty.util.internal.SystemPropertyUtil;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.LogStrategy;
import org.rx.net.Sockets;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Extends.newConcurrentList;

@Slf4j
@Data
public final class RxConfig {
    public interface ConfigNames {
        String THREAD_POOL_CPU_LOAD_WARNING = "app.threadPool.cpuLoadWarningThreshold";
        String THREAD_POOL_INIT_SIZE = "app.threadPool.initSize";
        String THREAD_POOL_KEEP_ALIVE_SECONDS = "app.threadPool.keepAliveSeconds";
        String THREAD_POOL_QUEUE_CAPACITY = "app.threadPool.queueCapacity";
        String THREAD_POOL_LOW_CPU_WATER_MARK = "app.threadPool.lowCpuWaterMark";
        String THREAD_POOL_HIGH_CPU_WATER_MARK = "app.threadPool.highCpuWaterMark";
        String THREAD_POOL_REPLICAS = "app.threadPool.replicas";
        String THREAD_POOL_TRACE_NAME = "app.threadPool.traceName";
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
        String TRACE_KEEP_DAYS = "app.traceKeepDays";
        String TRACE_ERROR_MESSAGE_SIZE = "app.traceErrorMessageSize";
        String TRACE_SLOW_ELAPSED_MICROS = "app.traceSlowElapsedMicros";
        String LOG_STRATEGY = "app.logStrategy";
        String JSON_SKIP_TYPES = "app.jsonSkipTypes";
        String AES_KEY = "app.aesKey";
        String OMEGA = "app.omega";
        String MXPWD = "app.mxpwd";

        static String getWithoutPrefix(String name) {
            return name.substring(4);
        }
    }

    @Data
    public static class ThreadPoolConfig {
        int initSize;
        int keepAliveSeconds;
        int queueCapacity;
        int lowCpuWaterMark;
        int highCpuWaterMark;
        int replicas;
        String traceName;

        int cpuLoadWarningThreshold;
        long samplingPeriod;
        int samplingTimes;
        int minDynamicSize;
        int maxDynamicSize;
        int resizeQuantity;
    }

    @Data
    public static class CacheConfig {
        int physicalMemoryUsageWarningThreshold;

        Class<? extends Cache> mainCache;
        int slidingSeconds;
        int maxItemSize;
    }

    @Data
    public static class DiskConfig {
        int diskUsageWarningThreshold;
        int entityDatabaseRollPeriod;
    }

    @Data
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

    @Data
    public static class NtpConfig {
        //1 syncTask, 2 injectJdkTime
        int enableFlags;
        long syncPeriod;
        long timeoutMillis;
        final List<String> servers = newConcurrentList(true);
    }

    @Data
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
        INSTANCE = temp;
    }

    ThreadPoolConfig threadPool = new ThreadPoolConfig();
    CacheConfig cache = new CacheConfig();
    DiskConfig disk = new DiskConfig();
    NetConfig net = new NetConfig();
    String id;
    long mxSamplingPeriod;
    int traceKeepDays;
    int traceErrorMessageSize;
    long traceSlowElapsedMicros;
    LogStrategy logStrategy;
    final Set<String> logTypeWhitelist = ConcurrentHashMap.newKeySet();
    final Set<Class<?>> jsonSkipTypes = ConcurrentHashMap.newKeySet();
    String aesKey;
    String omega;
    String mxpwd;

    public int getIntId() {
        Integer v = Integer.getInteger(id);
        if (v != null) {
            return v;
        }
        return id.hashCode();
    }

    private RxConfig() {
        refreshFromSystemProperty();
    }

    @SneakyThrows
    public void refreshFromSystemProperty() {
        threadPool.initSize = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_INIT_SIZE, 0);
        threadPool.keepAliveSeconds = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_KEEP_ALIVE_SECONDS, 600);
        threadPool.queueCapacity = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_QUEUE_CAPACITY, 0);
        threadPool.lowCpuWaterMark = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_LOW_CPU_WATER_MARK, 40);
        threadPool.highCpuWaterMark = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_HIGH_CPU_WATER_MARK, 70);
        threadPool.replicas = Math.max(1, SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_REPLICAS, 2));
        threadPool.traceName = SystemPropertyUtil.get(ConfigNames.THREAD_POOL_TRACE_NAME);
        threadPool.cpuLoadWarningThreshold = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_CPU_LOAD_WARNING, 80);
        threadPool.samplingPeriod = SystemPropertyUtil.getLong(ConfigNames.THREAD_POOL_SAMPLING_PERIOD, 3000);
        threadPool.samplingTimes = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_SAMPLING_TIMES, 2);
        threadPool.minDynamicSize = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_MIN_DYNAMIC_SIZE, 2);
        threadPool.maxDynamicSize = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_MAX_DYNAMIC_SIZE, 1000);
        threadPool.resizeQuantity = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_RESIZE_QUANTITY, 2);

        cache.physicalMemoryUsageWarningThreshold = SystemPropertyUtil.getInt(ConfigNames.PHYSICAL_MEMORY_USAGE_WARNING, 95);
        String mc = SystemPropertyUtil.get(ConfigNames.CACHE_MAIN_INSTANCE);
        if (mc != null) {
            cache.mainCache = (Class<? extends Cache>) Class.forName(mc);
        } else {
            cache.mainCache = Cache.MEMORY_CACHE;
        }
        cache.slidingSeconds = SystemPropertyUtil.getInt(ConfigNames.CACHE_SLIDING_SECONDS, 60);
        cache.maxItemSize = SystemPropertyUtil.getInt(ConfigNames.CACHE_MAX_ITEM_SIZE, 5000);

        disk.diskUsageWarningThreshold = SystemPropertyUtil.getInt(ConfigNames.DISK_USAGE_WARNING, 90);
        disk.entityDatabaseRollPeriod = SystemPropertyUtil.getInt(ConfigNames.DISK_ENTITY_DATABASE_ROLL_PERIOD, 10000);

        net.reactorThreadAmount = SystemPropertyUtil.getInt(ConfigNames.NET_REACTOR_THREAD_AMOUNT, 0);
        net.enableLog = SystemPropertyUtil.getBoolean(ConfigNames.NET_ENABLE_LOG, false);
        net.connectTimeoutMillis = SystemPropertyUtil.getInt(ConfigNames.NET_CONNECT_TIMEOUT_MILLIS, 16000);
        net.poolMaxSize = SystemPropertyUtil.getInt(ConfigNames.NET_POOL_MAX_SIZE, 0);
        if (net.poolMaxSize <= 0) {
            net.poolMaxSize = Constants.CPU_THREADS * 2;
        }
        net.poolKeepAliveSeconds = SystemPropertyUtil.getInt(ConfigNames.NET_POOL_KEEP_ALIVE_SECONDS, 120);
        net.userAgent = SystemPropertyUtil.get(ConfigNames.NET_USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/53.0.2785.116 Safari/537.36 QBCore/4.0.1301.400 QQBrowser/9.0.2524.400 Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/53.0.2875.116 Safari/537.36 NetType/WIFI MicroMessenger/7.0.5 WindowsWechat");
        reset(net.lanIps, ConfigNames.NET_LAN_IPS);

        net.ntp.enableFlags = SystemPropertyUtil.getInt(ConfigNames.NTP_ENABLE_FLAGS, 0);
        net.ntp.syncPeriod = SystemPropertyUtil.getLong(ConfigNames.NTP_SYNC_PERIOD, 128000);
        net.ntp.timeoutMillis = SystemPropertyUtil.getLong(ConfigNames.NTP_TIMEOUT_MILLIS, 2048);
        reset(net.ntp.servers, ConfigNames.NTP_SERVERS);

        reset(net.dns.inlandServers, ConfigNames.DNS_INLAND_SERVERS);
        reset(net.dns.outlandServers, ConfigNames.DNS_OUTLAND_SERVERS);

        id = SystemPropertyUtil.get(ConfigNames.APP_ID);
        if (id == null) {
            id = Sockets.getLocalAddress().getHostAddress() + "-" + Strings.randomValue(99);
        }
        mxSamplingPeriod = SystemPropertyUtil.getInt(ConfigNames.MX_SAMPLING_PERIOD, 60000);
        traceKeepDays = SystemPropertyUtil.getInt(ConfigNames.TRACE_KEEP_DAYS, 1);
        traceErrorMessageSize = SystemPropertyUtil.getInt(ConfigNames.TRACE_ERROR_MESSAGE_SIZE, 10);
        traceSlowElapsedMicros = SystemPropertyUtil.getLong(ConfigNames.TRACE_SLOW_ELAPSED_MICROS, 50000);
        String v = SystemPropertyUtil.get(ConfigNames.LOG_STRATEGY);
        if (v != null) {
            logStrategy = LogStrategy.valueOf(v);
        }

        jsonSkipTypes.clear();
        v = SystemPropertyUtil.get(ConfigNames.JSON_SKIP_TYPES);
        if (v != null) {
            //method ref will match multi methods
            jsonSkipTypes.addAll(Linq.from(Strings.split(v, ",")).select(p -> Class.forName(p)).toSet());
        }
        aesKey = SystemPropertyUtil.get(ConfigNames.AES_KEY, "℞FREEDOM");
        omega = SystemPropertyUtil.get(ConfigNames.OMEGA);
        mxpwd = SystemPropertyUtil.get(ConfigNames.MXPWD);
    }

    void reset(Collection<String> conf, String propName) {
        conf.clear();
        String v = SystemPropertyUtil.get(propName);
        if (v == null) {
            return;
        }
        conf.addAll(Linq.from(Strings.split(v, ",")).toSet());
    }
}
