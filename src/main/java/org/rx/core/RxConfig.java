package org.rx.core;

import io.netty.util.internal.SystemPropertyUtil;
import lombok.Data;
import lombok.SneakyThrows;
import org.rx.bean.LogStrategy;
import org.rx.core.config.ConfigNames;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Data
public final class RxConfig {
    @Data
    public static class ThreadPoolConfig {
        int initSize;
        int keepAliveSeconds;
        int queueCapacity;
        int lowCpuWaterMark;
        int highCpuWaterMark;
        int resizeQuantity;
        int scheduleInitSize;
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

    public static final RxConfig INSTANCE = new RxConfig();
//    static final String prefix = "app";

    final ThreadPoolConfig threadPool = new ThreadPoolConfig();
    final NetConfig net = new NetConfig();
    Class mainCache;
    LogStrategy logStrategy;
    final Set<String> logTypeWhitelist = ConcurrentHashMap.newKeySet();
    final Set<Class<?>> jsonSkipTypes = ConcurrentHashMap.newKeySet();
    String aesKey;
    String omega;

    private RxConfig() {
        refreshFromSystemProperty();
    }

    //        Container.register(RxConfig.class, readSetting("app", RxConfig.class), true);
    @SneakyThrows
    public void refreshFromSystemProperty() {
        threadPool.initSize = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_INIT_SIZE, 0);
        threadPool.keepAliveSeconds = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_KEEP_ALIVE_SECONDS, 600);
        threadPool.queueCapacity = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_QUEUE_CAPACITY, 0);
        threadPool.lowCpuWaterMark = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_LOW_CPU_WATER_MARK, 40);
        threadPool.highCpuWaterMark = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_HIGH_CPU_WATER_MARK, 70);
        threadPool.resizeQuantity = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_RESIZE_QUANTITY, 2);
        threadPool.scheduleInitSize = SystemPropertyUtil.getInt(ConfigNames.THREAD_POOL_SCHEDULE_INIT_SIZE, 1);

        net.reactorThreadAmount = SystemPropertyUtil.getInt(ConfigNames.NET_REACTOR_THREAD_AMOUNT, 0);
        net.enableLog = SystemPropertyUtil.getBoolean(ConfigNames.NET_ENABLE_LOG, false);
        net.connectTimeoutMillis = SystemPropertyUtil.getInt(ConfigNames.NET_CONNECT_TIMEOUT_MILLIS, 16000);
        net.poolMaxSize = SystemPropertyUtil.getInt(ConfigNames.NET_POOL_MAX_SIZE, 0);
        if (net.poolMaxSize <= 0) {
            net.poolMaxSize = ThreadPool.CPU_THREADS * 2;
        }
        net.poolKeepAliveSeconds = SystemPropertyUtil.getInt(ConfigNames.NET_POOL_KEEP_ALIVE_SECONDS, 120);
        net.userAgent = SystemPropertyUtil.get(ConfigNames.NET_USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/53.0.2785.116 Safari/537.36 QBCore/4.0.1301.400 QQBrowser/9.0.2524.400 Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/53.0.2875.116 Safari/537.36 NetType/WIFI MicroMessenger/7.0.5 WindowsWechat");

        String mc = SystemPropertyUtil.get(ConfigNames.MAIN_CACHE);
        if (mc != null) {
            mainCache = Class.forName(mc);
        } else {
            mainCache = Cache.MEMORY_CACHE;
        }
        Container.register(Cache.class, Container.<Cache>get(mainCache));

        jsonSkipTypes.clear();
        String v = SystemPropertyUtil.get(ConfigNames.JSON_SKIP_TYPES);
        if (v != null) {
            jsonSkipTypes.addAll(NQuery.of(Strings.split(v, ",")).select(Class::forName).toSet());
        }

        aesKey = SystemPropertyUtil.get(ConfigNames.AES_KEY, "℞FREEDOM");
        omega = SystemPropertyUtil.get(ConfigNames.OMEGA);
    }
}
