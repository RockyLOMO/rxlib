package org.rx.core;

import com.google.common.base.Throwables;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.exception.InvalidException;
import org.rx.util.function.BiFunc;
import org.rx.util.function.PredicateFunc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.rx.core.Constants.TIMEOUT_INFINITE;
import static org.rx.core.Extends.require;

@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class FluentWait {
    public static FluentWait newInstance(long timeoutMillis) {
        return newInstance(timeoutMillis, Constants.DEFAULT_INTERVAL);
    }

    public static FluentWait newInstance(long timeoutMillis, long intervalMillis) {
        require(timeoutMillis, timeoutMillis > TIMEOUT_INFINITE);
        require(intervalMillis, intervalMillis > TIMEOUT_INFINITE);

        return new FluentWait(timeoutMillis, intervalMillis);
    }

    @Getter
    final long timeout;
    @Getter
    final long interval;
    private long retryMillis = TIMEOUT_INFINITE;
    private boolean doRetryFirst;
    private boolean throwOnTimeout = true;
    private String message;
    private List<Class<? extends Throwable>> ignoredExceptions;
    @Getter
    private long endTime;
    @Getter
    private int evaluatedCount;

    public FluentWait retryEvery(long interval) {
        return retryEvery(interval, false);
    }

    public synchronized FluentWait retryEvery(long interval, boolean doRetryFirst) {
        require(interval, interval >= TIMEOUT_INFINITE);
        this.retryMillis = interval;
        this.doRetryFirst = doRetryFirst;
        return this;
    }

    public FluentWait throwOnTimeout(boolean throwOnTimeout) {
        this.throwOnTimeout = throwOnTimeout;
        return this;
    }

    public FluentWait withMessage(String message) {
        this.message = message;
        return this;
    }

    @SafeVarargs
    public final FluentWait ignoreExceptions(Class<? extends Throwable>... exceptions) {
        if (ignoredExceptions == null) {
            ignoredExceptions = new ArrayList<>();
        }
        ignoredExceptions.addAll(Arrays.toList(exceptions));
        return this;
    }

    public FluentWait sleep() {
        if (interval > 0) {
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw InvalidException.wrap(e);
            }
        }
        return this;
    }

    public <T> T until(BiFunc<FluentWait, T> isTrue) throws TimeoutException {
        return until(isTrue, null);
    }

    /**
     * Repeatedly applies this instance's input value to the given function until one of the following
     * occurs:
     * <ol>
     * <li>the function returns neither null nor false</li>
     * <li>the function throws an unignored exception</li>
     * <li>the timeout expires</li>
     * <li>the current thread is interrupted</li>
     * </ol>
     *
     * @param isTrue the parameter to pass to the {@link BiFunc}
     * @param <T>    The function's expected return type.
     * @return The function's return value if the function returned something different
     * from null or false before the timeout expired.
     * @throws TimeoutException If the timeout expires.
     */
    public synchronized <T> T until(@NonNull BiFunc<FluentWait, T> isTrue, PredicateFunc<FluentWait> retryCondition) throws TimeoutException {
        if (retryCondition != null && retryMillis == TIMEOUT_INFINITE) {
            log.warn("Not call retryEvery() before until()");
        }

        endTime = System.currentTimeMillis() + timeout;
        evaluatedCount = 0;
        int retryCount = TIMEOUT_INFINITE;
        if (retryCondition != null) {
            if (doRetryFirst) {
                try {
                    retryCondition.invoke(this);
                } catch (Throwable e) {
                    throw InvalidException.sneaky(e);
                }
            }
            if (retryMillis > TIMEOUT_INFINITE) {
                retryCount = (int) (interval > 0 ? Math.floor((double) retryMillis / interval) : timeout);
            }
        }

        Throwable lastException;
        T lastResult = null;
        do {
            try {
                lastResult = isTrue.invoke(this);
                if (lastResult != null && (!(lastResult instanceof Boolean) || Boolean.TRUE.equals(lastResult))) {
                    return lastResult;
                }
                lastException = null;
            } catch (Throwable e) {
                lastException = propagateIfNotIgnored(e);
            } finally {
                evaluatedCount++;
            }

            sleep();

            if (retryCount > TIMEOUT_INFINITE && (retryCount == 0 || evaluatedCount % retryCount == 0)) {
                try {
                    if (!retryCondition.invoke(this)) {
                        break;
                    }
                } catch (Throwable e) {
                    throw InvalidException.sneaky(e);
                }
            }
        }
        while (endTime > System.currentTimeMillis());
        if (!throwOnTimeout) {
            return lastResult;
        }

        String timeoutMessage = String.format("Expected condition failed: %s (tried for %d millisecond(s) with %d milliseconds interval)",
                message == null ? "waiting for " + isTrue : message,
                timeout, interval);
        throw ResetEventWait.newTimeoutException(timeoutMessage, lastException);
    }

    private Throwable propagateIfNotIgnored(Throwable e) {
        if (ignoredExceptions != null) {
            for (Class<? extends Throwable> ignoredException : ignoredExceptions) {
                if (ignoredException.isInstance(e)) {
                    return e;
                }
            }
        }
        Throwables.throwIfUnchecked(e);
        throw new RuntimeException(e);
    }
}
