package org.rx.util;

public interface RateLimiterAdapter {
    boolean tryAcquire();
}
