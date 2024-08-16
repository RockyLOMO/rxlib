package org.rx.core;

import org.rx.bean.FlagsEnum;

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

    String[] DEFAULT_CONFIG_FILES = {"rx.yml", "application.yml", "bootstrap.yml"};
    int CPU_THREADS = Runtime.getRuntime().availableProcessors();
    String DEFAULT_TRACE_NAME = "rx-traceId";

    String CONFIG_KEY_SPLITS = ".";
    String CACHE_KEY_SUFFIX = ":";
    int ONE_DAY_TOTAL_SECONDS = 60 * 60 * 24;
    int HEAP_BUF_SIZE = 256;
    int ARRAY_BUF_SIZE = HEAP_BUF_SIZE / 2;
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
    String ADVICE_SHARE_KEY = "";
    int ADVICE_SHARE_LEN = 2;
    int ADVICE_SHARE_TIME_INDEX = 0;
    int ADVICE_SHARE_FORK_JOIN_FUNC_INDEX = 1;

    String ENABLE_FLAG = "1";

    String CACHE_REGION_ERROR_CODE = "ERR";
    String CACHE_REGION_BEAN_PROPERTIES = "PROP";
    String CACHE_REGION_INTERFACE_METHOD = "IM";
    String CACHE_REGION_SKIP_SERIALIZE = "SS";

    String STACK_TRACE_FLAG = "\n\tat ";

    String TYPED_JSON_KEY = "$rxType";

    /**
     * do not edit
     */
    FlagsEnum<TimeoutFlag> TIMER_PERIOD_FLAG = TimeoutFlag.PERIOD.flags();
    FlagsEnum<TimeoutFlag> TIMER_SINGLE_FLAG = TimeoutFlag.SINGLE.flags();
    FlagsEnum<TimeoutFlag> TIMER_REPLACE_FLAG = TimeoutFlag.REPLACE.flags();
}
