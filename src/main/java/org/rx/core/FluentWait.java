package org.rx.core;

import com.google.common.base.Throwables;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.rx.beans.DateTime;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static org.rx.core.Contract.require;

public class FluentWait {
    @Data
    public static class UntilState {
        private DateTime endTime;
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

    @SneakyThrows
    public <T> T until(Function<UntilState, T> supplier) {
        require(supplier);

        Throwable lastException;
        UntilState state = new UntilState();
        state.endTime = DateTime.now().addSeconds(timeoutSeconds);
        do {
            try {
                T val = supplier.apply(state);
                if (val != null && (Boolean.class != val.getClass() || Boolean.TRUE.equals(val))) {
                    return val;
                }
                lastException = null;
            } catch (Throwable e) {
                lastException = propagateIfNotIgnored(e);
            } finally {
                state.checkCount++;
            }

            sleep();
        }
        while (state.endTime.before(DateTime.now()));
        if (!throwOnFail) {
            return null;
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
