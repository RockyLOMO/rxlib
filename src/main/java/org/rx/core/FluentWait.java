package org.rx.core;

import com.google.common.base.Throwables;
import lombok.*;
import org.rx.bean.DateTime;
import org.rx.exception.InvalidException;
import org.rx.util.function.BiFunc;
import org.rx.util.function.PredicateFunc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.rx.core.Constants.TIMEOUT_INFINITE;
import static org.rx.core.App.require;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class FluentWait {
    @Data
    public static class UntilState {
        private final DateTime endTime;
        private int invokedCount;
    }

    public static UntilState emptyState() {
        return new UntilState(DateTime.now());
    }

    public static FluentWait newInstance(long timeoutMillis) {
        return newInstance(timeoutMillis, Constants.DEFAULT_INTERVAL);
    }

    public static FluentWait newInstance(long timeoutMillis, long intervalMillis) {
        require(timeoutMillis, timeoutMillis > TIMEOUT_INFINITE);
        require(intervalMillis, intervalMillis > TIMEOUT_INFINITE);

        return new FluentWait(timeoutMillis, intervalMillis);
    }

    @Getter
    private final long timeout;
    @Getter
    private final long interval;
    private long retryMillis = TIMEOUT_INFINITE;
    private boolean retryCallFirst;
    private boolean throwOnFail = true;
    private String message;
    private final List<Class<? extends Throwable>> ignoredExceptions = new ArrayList<>();

    public FluentWait retryMillis(long retryMillis) {
        require(retryMillis, retryMillis >= TIMEOUT_INFINITE);
        this.retryMillis = retryMillis;
        return this;
    }

    public FluentWait retryCallFirst(boolean retryFirstCall) {
        this.retryCallFirst = retryFirstCall;
        return this;
    }

    public FluentWait throwOnFail(boolean throwOnFail) {
        this.throwOnFail = throwOnFail;
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

    public FluentWait sleep() {
        if (interval > 0) {
            App.sleep(interval);
        }
        return this;
    }

    public <T> T until(BiFunc<UntilState, T> func) throws TimeoutException {
        return until(func, null);
    }

    /**
     * until
     *
     * @param func      renew
     * @param retryFunc return true continue, false break
     * @param <T>       result
     * @return result
     * @throws TimeoutException timeout
     */
    @SneakyThrows
    public <T> T until(@NonNull BiFunc<UntilState, T> func, PredicateFunc<UntilState> retryFunc) throws TimeoutException {
        Throwable lastException;
        T lastResult = null;
        UntilState state = new UntilState(DateTime.now().addMilliseconds((int) timeout));
        if (retryCallFirst && retryFunc != null) {
            retryFunc.invoke(state);
        }

        int retryCount = retryMillis == TIMEOUT_INFINITE ? TIMEOUT_INFINITE : (int) (interval > 0 ? Math.floor((double) retryMillis / interval) : timeout);
        do {
            try {
                lastResult = func.invoke(state);
                if (lastResult != null && (!(lastResult instanceof Boolean) || Boolean.TRUE.equals(lastResult))) {
                    return lastResult;
                }
                lastException = null;
            } catch (Throwable e) {
                lastException = propagateIfNotIgnored(e);
            } finally {
                state.invokedCount++;
            }

            sleep();

            if (retryMillis > TIMEOUT_INFINITE && (retryCount == 0 || state.invokedCount % retryCount == 0)) {
                if (retryFunc != null && !retryFunc.invoke(state)) {
                    break;
                }
            }
        }
        while (DateTime.now().before(state.endTime));
        if (!throwOnFail) {
            return lastResult;
        }

        String timeoutMessage = String.format("Expected condition failed: %s (tried for %d millisecond(s) with %d milliseconds interval%s)",
                message == null ? "waiting for " + func : message,
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
