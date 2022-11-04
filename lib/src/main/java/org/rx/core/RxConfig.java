package org.rx.core;

import io.netty.util.internal.SystemPropertyUtil;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.LogStrategy;
import org.rx.net.Sockets;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Extends.newConcurrentList;

@Slf4j
@Data
public final class RxConfig {
    public interface ConfigNames {
        String THREAD_POOL_INIT_SIZE = "app.threadPool.initSize";
        String THREAD_POOL_KEEP_ALIVE_SECONDS = "app.threadPool.keepAliveSeconds";
        String THREAD_POOL_QUEUE_CAPACITY = "app.threadPool.queueCapacity";
        String THREAD_POOL_LOW_CPU_WATER_MARK = "app.threadPool.lowCpuWaterMark";
        String THREAD_POOL_HIGH_CPU_WATER_MARK = "app.threadPool.highCpuWaterMark";
        String THREAD_POOL_MIN_CORE_SIZE = "app.threadPool.minCoreSize";
        String THREAD_POOL_MAX_CORE_SIZE = "app.threadPool.maxCoreSize";
        String THREAD_POOL_RESIZE_QUANTITY = "app.threadPool.resizeQuantity";
        String THREAD_POOL_SCHEDULE_INIT_SIZE = "app.threadPool.scheduleInitSize";
        String THREAD_POOL_TRACE_NAME = "app.threadPool.traceName";
        String THREAD_POOL_REPLICAS = "app.threadPool.replicas";

        String NTP_ENABLE_FLAGS = "app.ntp.enableFlags";
        String NTP_SYNC_PERIOD = "app.ntp.syncPeriod";
        String NTP_SERVERS = "app.ntp.servers";

        String CACHE_MAIN_INSTANCE = "app.cache.mainInstance";
        String CACHE_SLIDING_SECONDS = "app.cache.slidingSeconds";
        String CACHE_MAX_ITEM_SIZE = "app.cache.maxItemSize";

        String DISK_MONITOR_PERIOD = "app.disk.monitorPeriod";
        String DISK_ENTITY_DATABASE_ROLL_PERIOD = "app.disk.entityDatabaseRollPeriod";

        String NET_REACTOR_THREAD_AMOUNT = "app.net.reactorThreadAmount";
        String NET_ENABLE_LOG = "app.net.enableLog";
        String NET_CONNECT_TIMEOUT_MILLIS = "app.net.connectTimeoutMillis";
        String NET_POOL_MAX_SIZE = "app.net.poolMaxSize";
        String NET_POOL_KEEP_ALIVE_SECONDS = "app.net.poolKeepAliveSeconds";
        String NET_USER_AGENT = "app.net.userAgent";

        String APP_ID = "app.id";
        String TRACE_KEEP_DAYS = "app.traceKeepDays";
        String TRACE_ERROR_MESSAGE_SIZE = "app.traceErrorMessageSize";
        String TRACE_SLOW_ELAPSED_MICROS = "app.traceSlowElapsedMicros";
        String LOG_STRATEGY = "app.logStrategy";
        String JSON_SKIP_TYPES = "app.jsonSkipTypes";
        String AES_KEY = "app.aesKey";
        String OMEGA = "app.omega";
        String MXPWD = "app.mxpwd";
    }

    @Data
    public static class ThreadPoolConfig {
        int initSize;
        int keepAliveSeconds;
        int queueCapacity;
        int lowCpuWaterMark;
        int highCpuWaterMark;

        int minCoreSize;
        int maxCoreSize;
        int resizeQuantity;
        int scheduleInitSize;
        String traceName;
        int replicas;
    }

    @Data
    public static class NtpConfig {
        //1 syncTask, 2 injectJdkTime
        int enableFlags;
        long syncPeriod;
        final List<String> servers = newConcurrentList(true);
    }

    @Data
    public static class CacheConfig {
        Class mainInstance;
        int slidingSeconds;
        int maxItemSize;
    }

    @Data
    public static class DiskConfig {
        int monitorPeriod;
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
    }

    public static final RxConfig INSTANCE;

    static {
        RxConfig temp;
        try {
            temp = YamlConfiguration.RX_CONF.readAs("app", RxConfig.class);
        } catch (Throwable e) {
            log.error("rx init error", e);
            temp = new RxConfig();
        }
        INSTANCE = temp;
    }

    ThreadPoolConfig threadPool = new ThreadPoolConfig();
    NtpConfig ntp = new NtpConfig();
    CacheConfig cache = new CacheConfig();
    DiskConfig disk = new DiskConfig();
    NetConfig net = new NetConfig();
    String id;
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
        threadPool.minCoreSize = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_MIN_CORE_SIZE, 2);
        threadPool.maxCoreSize = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_MAX_CORE_SIZE, 1000);
        threadPool.resizeQuantity = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_RESIZE_QUANTITY, 2);
        threadPool.scheduleInitSize = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_SCHEDULE_INIT_SIZE, 1);
        threadPool.traceName = SystemPropertyUtil.get(ConfigNames.THREAD_POOL_TRACE_NAME);
        threadPool.replicas = Math.max(1, SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_REPLICAS, 2));

        ntp.enableFlags = SystemPropertyUtil.getInt(ConfigNames.NTP_ENABLE_FLAGS, 0);
        ntp.syncPeriod = SystemPropertyUtil.getLong(ConfigNames.NTP_SYNC_PERIOD, 128000);
        ntp.servers.clear();
        String v = SystemPropertyUtil.get(ConfigNames.NTP_SERVERS);
        if (v != null) {
            ntp.servers.addAll(Linq.from(Strings.split(v, ",")).toSet());
        }

        String mc = SystemPropertyUtil.get(ConfigNames.CACHE_MAIN_INSTANCE);
        if (mc != null) {
            cache.mainInstance = Class.forName(mc);
        } else {
            cache.mainInstance = Cache.MEMORY_CACHE;
        }
        cache.slidingSeconds = SystemPropertyUtil.getInt(ConfigNames.CACHE_SLIDING_SECONDS, 60);
        cache.maxItemSize = SystemPropertyUtil.getInt(ConfigNames.CACHE_MAX_ITEM_SIZE, 5000);

        disk.monitorPeriod = SystemPropertyUtil.getInt(ConfigNames.DISK_MONITOR_PERIOD, 60000);
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

        id = SystemPropertyUtil.get(ConfigNames.APP_ID);
        if (id == null) {
            id = Sockets.getLocalAddress().getHostAddress() + "-" + Strings.randomValue(99);
        }
        traceKeepDays = SystemPropertyUtil.getInt(ConfigNames.TRACE_KEEP_DAYS, 1);
        traceErrorMessageSize = SystemPropertyUtil.getInt(ConfigNames.TRACE_ERROR_MESSAGE_SIZE, 10);
        traceSlowElapsedMicros = SystemPropertyUtil.getLong(ConfigNames.TRACE_SLOW_ELAPSED_MICROS, 50000);
        v = SystemPropertyUtil.get(ConfigNames.LOG_STRATEGY);
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
}
