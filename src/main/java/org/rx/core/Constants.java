package org.rx.core;

public interface Constants {
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
