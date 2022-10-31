package org.rx.core;

public interface Constants {
    String RX_CONFIG_FILE = "rx.yml";
    int CPU_THREADS = Runtime.getRuntime().availableProcessors();

    String CONFIG_KEY_SPLITS = ".";
    String CACHE_KEY_SUFFIX = ":";
    int ONE_DAY_TOTAL_SECONDS = 60 * 60 * 24;
    int HEAP_BUF_SIZE = 256;
    int MAX_HEAP_BUF_SIZE = Constants.MB * 16;

    int DEFAULT_INTERVAL = 500;
    int IO_EOF = -1;
    int TIMEOUT_INFINITE = -1;
    long NANO_TO_MILLIS = 1000000L;

    double PERCENT = 100.0D;
    int KB = 1024, MB = KB * 1024, GB = MB * 1024;
    long TB = GB * 1024L;
    int SIZE_4K = KB * 4;

    String THREAD_POOL_QUEUE = "threadPoolQueue";
    String DEFAULT_TRACE_NAME = "rx-traceId";

    String NON_UNCHECKED = "unchecked";
    String NON_RAW_TYPES = "unchecked,rawtypes";
}
