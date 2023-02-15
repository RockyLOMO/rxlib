package org.rx.core;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rx.exception.InvalidException;
import org.rx.util.function.BiAction;
import org.rx.util.function.BiFunc;
import org.rx.util.function.PredicateFunc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.rx.core.Constants.TIMEOUT_INFINITE;
import static org.rx.core.Extends.*;

@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class FluentWait implements WaitHandle {
    public static FluentWait polling(long timeoutMillis) {
        return polling(timeoutMillis, Constants.DEFAULT_INTERVAL);
    }

    public static FluentWait polling(long timeoutMillis, long intervalMillis) {
        return polling(timeoutMillis, intervalMillis, null);
    }

    public static <T> FluentWait polling(long timeoutMillis, long intervalMillis, BiFunc<FluentWait, T> resultFunc) {
        require(timeoutMillis, timeoutMillis > TIMEOUT_INFINITE);
        require(intervalMillis, intervalMillis > TIMEOUT_INFINITE);

        FluentWait wait = new FluentWait(timeoutMillis, intervalMillis);
        wait.resultFunc = (BiFunc<FluentWait, Object>) resultFunc;
        return wait;
    }

    final long timeout;
    final long interval;
    private BiFunc<FluentWait, Object> resultFunc;
    private List<Class<? extends Throwable>> ignoredExceptions;
    private String message;
    private long retryMillis = TIMEOUT_INFINITE;
    private BiAction<FluentWait> retryFunc;
    private boolean retryOnStart;
    @Getter
    private int evaluatedCount;
    volatile boolean doBreak;

    @SafeVarargs
    public synchronized final FluentWait ignoreExceptions(Class<? extends Throwable>... exceptions) {
        if (ignoredExceptions == null) {
            ignoredExceptions = new ArrayList<>();
        }
        ignoredExceptions.addAll(Arrays.toList(exceptions));
        return this;
    }

    public synchronized FluentWait withMessage(String message) {
        this.message = message;
        return this;
    }

    public FluentWait retryEvery(long interval, BiAction<FluentWait> retryFunc) {
        return retryEvery(interval, retryFunc, false);
    }

    public synchronized FluentWait retryEvery(long interval, BiAction<FluentWait> retryFunc, boolean retryOnStart) {
        require(interval, interval >= TIMEOUT_INFINITE);
        this.retryMillis = interval;
        this.retryFunc = retryFunc;
        this.retryOnStart = retryOnStart;
        return this;
    }

    private Throwable propagateIfNotIgnored(Throwable e) {
        if (ignoredExceptions != null) {
            for (Class<? extends Throwable> ignoredException : ignoredExceptions) {
                if (ignoredException.isInstance(e)) {
                    return e;
                }
            }
        }
        throw InvalidException.sneaky(e);
    }

    public boolean awaitTrue(PredicateFunc<FluentWait> isTrue) {
        try {
            return ifNull(await(w -> isTrue.invoke(w) ? Boolean.TRUE : null), Boolean.FALSE);
        } catch (TimeoutException e) {
            //ignore
        }
        return false;
    }

    public <T> T await() throws TimeoutException {
        return (T) await(resultFunc);
    }

    /**
     * Repeatedly applies this instance's input value to the given function until one of the following
     * occurs:
     * <ol>
     * <li>the function returns neither null</li>
     * <li>the function throws an unignored exception</li>
     * <li>the timeout expires</li>
     * <li>the current thread is interrupted</li>
     * </ol>
     *
     * @param resultFunc the parameter to pass to the {@link BiFunc}
     * @param <T>        The function's expected return type.
     * @return The function's return value if the function returned something different from null before the timeout expired.
     * @throws TimeoutException If the timeout expires.
     */
    public synchronized <T> T await(@NonNull BiFunc<FluentWait, T> resultFunc) throws TimeoutException {
//        if (deadline != 0) {
//            throw new InvalidException("Not support await nested");
//        }

        doBreak = false;
        long deadline = System.nanoTime() + timeout * Constants.NANO_TO_MILLIS;
        try {
            int retryCount = TIMEOUT_INFINITE;
            if (retryFunc != null) {
                if (retryOnStart) {
                    retryFunc.accept(this);
                }
                if (retryMillis > TIMEOUT_INFINITE) {
                    retryCount = interval > 0 ? (int) Math.floor((double) retryMillis / interval) : 0;
                }
            }

            boolean doRetry = retryCount > TIMEOUT_INFINITE;
            Throwable cause;
            T result = null;
            do {
                try {
                    if ((result = resultFunc.invoke(this)) != null) {
                        return result;
                    }
                    cause = null;
                } catch (Throwable e) {
                    cause = propagateIfNotIgnored(e);
                } finally {
                    evaluatedCount++;
                }

                if (doRetry && (retryCount == 0 || evaluatedCount % retryCount == 0)) {
                    retryFunc.accept(this);
                }

                if (doBreak) {
                    return result;
                }
                sleep(interval);
            }
            while (System.nanoTime() < deadline);

            String timeoutMessage = String.format("Expected condition failed: %s (tried for %d millisecond(s) with %d milliseconds interval)",
                    message == null ? "waiting for " + resultFunc : message,
                    timeout, interval);
            throw WaitHandle.newTimeoutException(timeoutMessage, cause);
        } finally {
            evaluatedCount = 0;
        }
    }

    @Override
    public boolean await(long timeoutMillis) {
        try {
            polling(timeoutMillis, interval, resultFunc).await();
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    @Override
    public void signalAll() {
        doBreak = true;
    }
}
