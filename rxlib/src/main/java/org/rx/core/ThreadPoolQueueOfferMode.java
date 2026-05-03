package org.rx.core;

public enum ThreadPoolQueueOfferMode {
    BLOCK,
    TIMEOUT_REJECT,
    CALLER_RUNS;

    public static ThreadPoolQueueOfferMode parse(String value, ThreadPoolQueueOfferMode defaultValue) {
        if (value == null || value.length() == 0) {
            return defaultValue;
        }
        for (ThreadPoolQueueOfferMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return defaultValue;
    }
}
