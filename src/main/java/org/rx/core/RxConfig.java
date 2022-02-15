package org.rx.core;

import io.netty.util.internal.SystemPropertyUtil;
import lombok.Data;
import lombok.SneakyThrows;
import org.rx.bean.LogStrategy;
import org.rx.net.Sockets;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Data
public final class RxConfig {
    public interface ConfigNames {
        String THREAD_POOL_INIT_SIZE = "app.threadPool.initSize";
        String THREAD_POOL_KEEP_ALIVE_SECONDS = "app.threadPool.keepAliveSeconds";
        String THREAD_POOL_QUEUE_CAPACITY = "app.threadPool.queueCapacity";
        String THREAD_POOL_LOW_CPU_WATER_MARK = "app.threadPool.lowCpuWaterMark";
        String THREAD_POOL_HIGH_CPU_WATER_MARK = "app.threadPool.highCpuWaterMark";
        String THREAD_POOL_RESIZE_QUANTITY = "app.threadPool.resizeQuantity";
        String THREAD_POOL_SCHEDULE_INIT_SIZE = "app.threadPool.scheduleInitSize";
        String THREAD_POOL_ENABLE_INHERIT_THREAD_LOCALS = "app.threadPool.enableInheritThreadLocals";
        String THREAD_POOL_REPLICAS = "app.threadPool.replicas";

        String CACHE_MAIN_INSTANCE = "app.cache.mainInstance";
        String CACHE_SLIDING_SECONDS = "app.cache.slidingSeconds";
        String CACHE_MAX_ITEM_SIZE = "app.cache.maxItemSize";

        String NET_REACTOR_THREAD_AMOUNT = "app.net.reactorThreadAmount";
        String NET_ENABLE_LOG = "app.net.enableLog";
        String NET_CONNECT_TIMEOUT_MILLIS = "app.net.connectTimeoutMillis";
        String NET_POOL_MAX_SIZE = "app.net.poolMaxSize";
        String NET_POOL_KEEP_ALIVE_SECONDS = "app.net.poolKeepAliveSeconds";
        String NET_USER_AGENT = "app.net.userAgent";

        String APP_ID = "app.id";
        String TRACE_KEEP_DAYS = "app.traceKeepDays";
        String JSON_SKIP_TYPES = "app.jsonSkipTypes";
        String AES_KEY = "app.aesKey";
        String OMEGA = "app.omega";
    }

    @Data
    public static class ThreadPoolConfig {
        int initSize;
        int keepAliveSeconds;
        int queueCapacity;
        int lowCpuWaterMark;
        int highCpuWaterMark;
        int resizeQuantity;
        int scheduleInitSize;
        boolean enableInheritThreadLocals;
        int replicas;
    }

    @Data
    public static class CacheConfig {
        Class mainInstance;
        int slidingSeconds;
        int maxItemSize;
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

    public static final RxConfig INSTANCE = YamlConfig.RX_CONF.readAs("app", RxConfig.class);
    ThreadPoolConfig threadPool = new ThreadPoolConfig();
    CacheConfig cache = new CacheConfig();
    NetConfig net = new NetConfig();
    String id;
    int traceKeepDays;
    LogStrategy logStrategy;
    final Set<String> logTypeWhitelist = ConcurrentHashMap.newKeySet();
    final Set<Class<?>> jsonSkipTypes = ConcurrentHashMap.newKeySet();
    String aesKey;
    String omega;

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
        threadPool.resizeQuantity = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_RESIZE_QUANTITY, 2);
        threadPool.scheduleInitSize = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_SCHEDULE_INIT_SIZE, 1);
        threadPool.enableInheritThreadLocals = SystemPropertyUtil.getBoolean(ConfigNames.THREAD_POOL_ENABLE_INHERIT_THREAD_LOCALS, false);
        threadPool.replicas = Math.max(1, SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_REPLICAS, 2));

        String mc = SystemPropertyUtil.get(ConfigNames.CACHE_MAIN_INSTANCE);
        if (mc != null) {
            cache.mainInstance = Class.forName(mc);
        } else {
            cache.mainInstance = Cache.MEMORY_CACHE;
        }
        cache.slidingSeconds = SystemPropertyUtil.getInt(ConfigNames.CACHE_SLIDING_SECONDS, 60);
        cache.maxItemSize = SystemPropertyUtil.getInt(ConfigNames.CACHE_MAX_ITEM_SIZE, 10000);

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
            id = Sockets.getLocalAddress().getHostAddress();
        }
        traceKeepDays = SystemPropertyUtil.getInt(ConfigNames.TRACE_KEEP_DAYS, 1);
        jsonSkipTypes.clear();
        String v = SystemPropertyUtil.get(ConfigNames.JSON_SKIP_TYPES);
        if (v != null) {
            jsonSkipTypes.addAll(NQuery.of(Strings.split(v, ",")).select(Class::forName).toSet());
        }
        aesKey = SystemPropertyUtil.get(ConfigNames.AES_KEY, "℞FREEDOM");
        omega = SystemPropertyUtil.get(ConfigNames.OMEGA);
    }
}
