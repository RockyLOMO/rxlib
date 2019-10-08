package org.rx.core;

import com.google.common.base.Throwables;
import lombok.*;
import org.rx.beans.DateTime;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.rx.core.Contract.require;

public class FluentWait {
    @Data
    @RequiredArgsConstructor
    public static class UntilState {
        private final DateTime endTime;
        private int checkCount;
    }

    @Getter
    @Setter
    private int timeoutSeconds;
    @Getter
    private long interval = 500L;
    private String message;
    private List<Class<? extends Throwable>> ignoredExceptions = new ArrayList<>();
    private boolean throwOnFail = true;

    public FluentWait(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public FluentWait interval(long interval) {
        this.interval = interval;
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
        App.sleep(interval);
        return this;
    }

    public <T> T until(Function<UntilState, T> supplier) {
        return until(supplier, App.TimeoutInfinite, null, false);
    }

    @SneakyThrows
    public <T> T until(Function<UntilState, T> supplier, long retryMills, Predicate<UntilState> retryFunc, boolean retryFirstCall) {
        require(supplier);
        require(retryMills, retryMills >= App.TimeoutInfinite);

        Throwable lastException;
        T lastResult = null;
        UntilState state = new UntilState(DateTime.now().addSeconds(timeoutSeconds));
        if (retryFirstCall && retryFunc != null) {
            retryFunc.test(state);
        }

        int retryCount = retryMills == App.TimeoutInfinite ? App.TimeoutInfinite : (int) Math.floor((double) retryMills / interval);
        do {
            try {
                lastResult = supplier.apply(state);
                if (lastResult != null && (Boolean.class != lastResult.getClass() || Boolean.TRUE.equals(lastResult))) {
                    return lastResult;
                }
                lastException = null;
            } catch (Throwable e) {
                lastException = propagateIfNotIgnored(e);
            } finally {
                state.checkCount++;
            }

            sleep();

            if (retryMills > App.TimeoutInfinite && state.checkCount % retryCount == 0) {
                if (retryFunc != null && !retryFunc.test(state)) {
                    break;
                }
            }
        }
        while (state.endTime.before(DateTime.now()));
        if (!throwOnFail) {
            return lastResult;
        }

        String timeoutMessage = String.format("Expected condition failed: %s (tried for %d second(s) with %d milliseconds interval%s)",
                message == null ? "waiting for " + supplier : message,
                timeoutSeconds, interval, lastException == null ? "" : " with ignoredException " + lastException);
        throw new TimeoutException(timeoutMessage);
    }

    private Throwable propagateIfNotIgnored(Throwable e) {
        Iterator tor = this.ignoredExceptions.iterator();
        Class ignoredException;
        do {
            if (!tor.hasNext()) {
                Throwables.throwIfUnchecked(e);
                throw new RuntimeException(e);
            }
            ignoredException = (Class) tor.next();
        } while (!ignoredException.isInstance(e));
        return e;
    }
}
