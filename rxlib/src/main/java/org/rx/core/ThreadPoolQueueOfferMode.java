package org.rx.core;

import lombok.extern.slf4j.Slf4j;
import org.rx.diagnostic.DiagnosticMetrics;

@Slf4j
public enum ThreadPoolQueueOfferMode {
    BLOCK,
    TIMEOUT_REJECT,
    CALLER_RUNS;

    public static ThreadPoolQueueOfferMode parse(String value, ThreadPoolQueueOfferMode defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String normalized = value.trim();
        if (normalized.length() == 0) {
            return defaultValue;
        }
        normalized = normalized.replace('-', '_').replace(' ', '_');
        for (ThreadPoolQueueOfferMode mode : values()) {
            if (mode.name().equalsIgnoreCase(normalized)) {
                return mode;
            }
        }
        log.warn("Invalid thread pool queueOfferMode {}, use {}", value, defaultValue);
        DiagnosticMetrics.record("rx.thread_pool.config.invalid.count", 1D,
                "name=queueOfferMode,value=" + sanitizeMetricTag(value));
        return defaultValue;
    }

    private static String sanitizeMetricTag(String value) {
        return value.replace(',', '_').replace('\r', ' ').replace('\n', ' ');
    }
}
