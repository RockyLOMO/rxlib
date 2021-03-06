package org.rx.core;

import com.google.common.base.Throwables;
import lombok.*;
import org.rx.bean.DateTime;
import org.rx.core.exception.InvalidException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.rx.core.App.TIMEOUT_INFINITE;
import static org.rx.core.App.require;

public class FluentWait {
    @Data
    @RequiredArgsConstructor
    public static class UntilState {
        private final DateTime endTime;
        private int checkCount;
    }

    private static final long defaultTimeout = 500L;

    public static UntilState StateNull() {
        return new UntilState(DateTime.now());
    }

    public static FluentWait newInstance(long timeoutMillis) {
        return newInstance(timeoutMillis, defaultTimeout);
    }

    public static FluentWait newInstance(long timeoutMillis, long intervalMillis) {
        return new FluentWait().timeout(timeoutMillis).interval(intervalMillis);
    }

    @Getter
    private long timeout = defaultTimeout;
    @Getter
    private long interval = defaultTimeout;
    private String message;
    private List<Class<? extends Throwable>> ignoredExceptions = new ArrayList<>();
    private boolean throwOnFail = true;
    private long retryMillis = TIMEOUT_INFINITE;
    private boolean retryFirstCall;

    private FluentWait() {
    }

    public FluentWait timeout(long timeoutMillis) {
        require(timeoutMillis, timeoutMillis > TIMEOUT_INFINITE);
        this.timeout = timeoutMillis;
        return this;
    }

    public FluentWait interval(long intervalMillis) {
        require(intervalMillis, intervalMillis > TIMEOUT_INFINITE);
        this.interval = intervalMillis;
        return this;
    }

    public FluentWait retryMillis(long retryMillis) {
        require(retryMillis, retryMillis >= TIMEOUT_INFINITE);
        this.retryMillis = retryMillis;
        return this;
    }

    public FluentWait retryFirstCall(boolean retryFirstCall) {
        this.retryFirstCall = retryFirstCall;
        return this;
    }

    public FluentWait message(String message) {
        this.message = message;
        return this;
    }

    @SafeVarargs
    public final FluentWait ignoredExceptions(Class<? extends Throwable>... exceptions) {
        ignoredExceptions.addAll(Arrays.toList(exceptions));
        return this;
    }

    public FluentWait throwOnFail(boolean throwOnFail) {
        this.throwOnFail = throwOnFail;
        return this;
    }

    public FluentWait sleep() {
        if (interval > 0) {
            App.sleep(interval);
        }
        return this;
    }

    public <T> T until(Function<UntilState, T> supplier) throws TimeoutException {
        return until(supplier, null);
    }

    /**
     * until
     *
     * @param supplier  renew
     * @param retryFunc return true continue, false break
     * @param <T>       result
     * @return result
     * @throws TimeoutException timeout
     */
    public <T> T until(@NonNull Function<UntilState, T> supplier, Predicate<UntilState> retryFunc) throws TimeoutException {
        Throwable lastException;
        T lastResult = null;
        UntilState state = new UntilState(DateTime.now().addMilliseconds((int) timeout));
        if (retryFirstCall && retryFunc != null) {
            retryFunc.test(state);
        }

        int retryCount = retryMillis == TIMEOUT_INFINITE ? TIMEOUT_INFINITE : (int) (interval > 0 ? Math.floor((double) retryMillis / interval) : timeout);
        do {
            try {
                lastResult = supplier.apply(state);
                if (lastResult != null && (!(lastResult instanceof Boolean) || Boolean.TRUE.equals(lastResult))) {
                    return lastResult;
                }
                lastException = null;
            } catch (Throwable e) {
                lastException = propagateIfNotIgnored(e);
            } finally {
                state.checkCount++;
            }

            sleep();

            if (retryMillis > TIMEOUT_INFINITE && (retryCount == 0 || state.checkCount % retryCount == 0)) {
                if (retryFunc != null && !retryFunc.test(state)) {
                    break;
                }
            }
        }
        while (DateTime.now().before(state.endTime));
        if (!throwOnFail) {
            return lastResult;
        }

        String timeoutMessage = String.format("Expected condition failed: %s (tried for %d millisecond(s) with %d milliseconds interval%s)",
                message == null ? "waiting for " + supplier : message,
                timeout, interval, lastException == null ? "" : " with ignoredException " + lastException);
        throw new TimeoutException(timeoutMessage);
    }

    private Throwable propagateIfNotIgnored(Throwable e) {
        Iterator<Class<? extends Throwable>> tor = this.ignoredExceptions.iterator();
        Class<? extends Throwable> ignoredException;
        do {
            if (!tor.hasNext()) {
                Throwables.throwIfUnchecked(e);
                throw InvalidException.sneaky(e);
            }
            ignoredException = tor.next();
        } while (!ignoredException.isInstance(e));
        return e;
    }
}
