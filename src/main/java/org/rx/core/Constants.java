package org.rx.core;

public interface Constants {
    String THREAD_POOL_MIN_SIZE = "app.threadPool.minSize";
    String THREAD_POOL_MAX_SIZE = "app.threadPool.maxSize";
    String THREAD_POOL_QUEUE_CAPACITY = "app.threadPool.queueCapacity";
    String CPU_LOW_WATER_MARK = "app.cpu.lowWaterMark";
    String CPU_HIGH_WATER_MARK = "app.cpu.highWaterMark";
    String THREAD_POOL_RESIZE_QUANTITY = "app.threadPool.resizeQuantity";
    String REACTOR_THREAD_AMOUNT = "app.reactor.threadAmount";
    String CACHE_DEFAULT_SLIDING_SECONDS = "app.cache.defaultSlidingSeconds";
    String CACHE_DEFAULT_MAX_SIZE = "app.cache.defaultMaxSize";
    String CACHE_MULTI_EXPIRATION = "app.cache.enableMultiExpiration";

    String NON_UNCHECKED = "unchecked";
    String NON_RAW_TYPES = "unchecked,rawtypes";

    String CACHE_KEY_SUFFIX = ":";
    int ONE_DAY_EXPIRE_SECONDS = 60 * 60 * 24;

    int DEFAULT_INTERVAL = 500;
    int IO_EOF = -1;
    int TIMEOUT_INFINITE = -1;

    double PERCENT = 100.0D;
    int KB = 1024, MB = KB * 1024, GB = MB * 1024;
    long TB = GB * 1024L;
    int SIZE_4K = KB * 4;
}
