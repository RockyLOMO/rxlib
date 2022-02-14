package org.rx.core.config;

public interface ConfigNames {
    String THREAD_POOL_INIT_SIZE = "app.threadPool.initSize";
    String THREAD_POOL_KEEP_ALIVE_SECONDS = "app.threadPool.keepAliveSeconds";
    String THREAD_POOL_QUEUE_CAPACITY = "app.threadPool.queueCapacity";
    String THREAD_POOL_LOW_CPU_WATER_MARK = "app.threadPool.lowCpuWaterMark";
    String THREAD_POOL_HIGH_CPU_WATER_MARK = "app.threadPool.highCpuWaterMark";
    String THREAD_POOL_RESIZE_QUANTITY = "app.threadPool.resizeQuantity";
    String THREAD_POOL_SCHEDULE_INIT_SIZE = "app.threadPool.scheduleInitSize";

    String NET_REACTOR_THREAD_AMOUNT = "app.net.reactorThreadAmount";
    String NET_ENABLE_LOG = "app.net.enableLog";
    String NET_CONNECT_TIMEOUT_MILLIS = "app.net.connectTimeoutMillis";
    String NET_POOL_MAX_SIZE = "app.net.poolMaxSize";
    String NET_POOL_KEEP_ALIVE_SECONDS = "app.net.poolKeepAliveSeconds";
    String NET_USER_AGENT = "app.net.userAgent";

    String MAIN_CACHE = "app.mainCache";
    String JSON_SKIP_TYPES = "app.jsonSkipTypes";
    String AES_KEY = "app.aesKey";
    String OMEGA = "app.omega";
}
