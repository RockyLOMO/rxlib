package org.rx.core;

public interface Constants {
    enum MetricName {
        THREAD_QUEUE_SIZE_ERROR,
        OBJECT_POOL_LEAK,
        DEAD_EVENT,
        OBJECT_TRACK_OVERFLOW
    }

    double PERCENT = 100.0D;
    int KB = 1024, MB = KB * 1024, GB = MB * 1024;
    long TB = GB * 1024L;
    int SIZE_4K = KB * 4;

    String DEFAULT_CONFIG_FILE = "rx.yml";
    int CPU_THREADS = Runtime.getRuntime().availableProcessors();
    String DEFAULT_TRACE_NAME = "rx-traceId";

    String CONFIG_KEY_SPLITS = ".";
    String CACHE_KEY_SUFFIX = ":";
    int ONE_DAY_TOTAL_SECONDS = 60 * 60 * 24;
    int HEAP_BUF_SIZE = 256;
    int MAX_HEAP_BUF_SIZE = MB * 16;

    int NON_BUF = 0;
    int SMALL_BUF = 1024;
    int MEDIUM_BUF = SIZE_4K;
    int LARGE_BUF = 1 << 16; //64K buffer

    int DEFAULT_INTERVAL = 500;
    int IO_EOF = -1;
    int TIMEOUT_INFINITE = -1;
    long NANO_TO_MILLIS = 1000000L;

    String NON_UNCHECKED = "unchecked";
    String NON_RAW_TYPES = "unchecked,rawtypes";
}