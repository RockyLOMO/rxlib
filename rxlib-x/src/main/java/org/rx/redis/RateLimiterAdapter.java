package org.rx.redis;

public interface RateLimiterAdapter {
    boolean tryAcquire();
}
