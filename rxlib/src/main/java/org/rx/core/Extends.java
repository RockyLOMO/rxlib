package org.rx.core;

import lombok.NonNull;
import lombok.SneakyThrows;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.rx.annotation.Metadata;
import org.rx.annotation.ErrorCode;
import org.rx.exception.ApplicationException;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.util.function.Action;
import org.rx.util.function.BiAction;
import org.rx.util.function.Func;

import java.io.Serializable;
import java.lang.reflect.AnnotatedElement;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.CopyOnWriteArrayList;

@SuppressWarnings(Constants.NON_UNCHECKED)
public interface Extends extends Serializable {
    //region extend
    static <T> List<T> newConcurrentList(boolean readMore) {
        return readMore ? new CopyOnWriteArrayList<>() : new Vector<>();
    }

    static <T> List<T> newConcurrentList(int initialCapacity) {
        return newConcurrentList(initialCapacity, false);
    }

    static <T> List<T> newConcurrentList(int initialCapacity, boolean readMore) {
        return readMore ? new CopyOnWriteArrayList<>() : new Vector<>(initialCapacity);
    }

    static <T> T require(T arg) {
        if (arg == null) {
            throw new IllegalArgumentException("The object requires non null");
        }
        return arg;
    }

    @ErrorCode("test")
    static <T> void require(T arg, boolean testResult) {
        require(arg);
        if (!testResult) {
            throw new ApplicationException("test", values(arg));
        }
    }

    static boolean quietly(Action fn) {
        return quietly(fn, 1, false);
    }

    static boolean quietly(Action fn, int retryCount) {
        return quietly(fn, retryCount, false);
    }

    static boolean quietly(@NonNull Action fn, int retryCount, boolean throwOnLast) {
        Throwable last = null;
        for (int i = 0; i < retryCount; i++) {
            try {
                fn.invoke();
                return true;
            } catch (Throwable e) {
                TraceHandler.INSTANCE.log("quietly retry={}/{}", i, retryCount, e);
                last = e;
            }
        }
        if (last != null && throwOnLast) {
            throw InvalidException.sneaky(last);
        }
        return false;
    }

    static <T> T quietly(Func<T> fn) {
        return quietly(fn, 1, (Func<T>) Func.EMPTY);
    }

    static <T> T quietly(Func<T> fn, int retryCount) {
        return quietly(fn, retryCount, null);
    }

    static <T> T quietly(Func<T> fn, Func<T> defaultValue) {
        return quietly(fn, 1, defaultValue);
    }

    static <T> T quietly(@NonNull Func<T> fn, int retryCount, Func<T> defaultValue) {
        Throwable last = null;
        for (int i = 0; i < retryCount; i++) {
            try {
                return fn.invoke();
            } catch (Throwable e) {
                TraceHandler.INSTANCE.log("quietly retry={}/{}", i, retryCount, e);
                last = e;
            }
        }
        if (last != null && defaultValue == null) {
            throw InvalidException.sneaky(last);
        }
        if (defaultValue != null) {
            return defaultValue.get();
        }
        return null;
    }

    //region each
    static <T> void eachQuietly(Object array, BiAction<T> fn) {
        eachQuietly(Linq.fromIterable(array), fn);
    }

    static <T> void eachQuietly(Iterable<T> iterable, BiAction<T> fn) {
        each(iterable, fn, false, Constants.TIMEOUT_INFINITE);
    }

    static <T> void eachQuietly(Iterable<T> iterable, BiAction<T> fn, long interruptedFlag) {
        each(iterable, fn, false, interruptedFlag);
    }

    static <T> void each(Object array, BiAction<T> fn) {
        each(Linq.fromIterable(array), fn);
    }

    static <T> void each(Iterable<T> iterable, BiAction<T> fn) {
        each(iterable, fn, true, Constants.TIMEOUT_INFINITE);
    }

    static <T> void each(Iterable<T> iterable, BiAction<T> fn, boolean throwOnNext, long interruptedFlag) {
        if (iterable == null || fn == null) {
            return;
        }

        for (T t : iterable) {
            try {
                fn.invoke(t);
            } catch (Throwable e) {
                if (throwOnNext) {
                    throw InvalidException.sneaky(e);
                }
                TraceHandler.INSTANCE.log("each", e);
            }
            if (!ThreadPool.asyncContinueFlag(true)) {
                break;
            }
            if (interruptedFlag < 0) {
                if (Thread.interrupted()) {
                    break;
                }
            } else {
                try {
                    Thread.sleep(interruptedFlag);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    //CircuitBreakingException
    static void circuitContinue(boolean flag) {
        ThreadPool.ASYNC_CONTINUE.set(flag);
    }

    @SneakyThrows
    static void sleep(long millis) {
        Thread.sleep(millis);
    }
    //endregion

    static boolean tryClose(Object obj) {
        return tryAs(obj, AutoCloseable.class, p -> quietly(p::close));
    }

    static boolean tryClose(AutoCloseable obj) {
        if (obj == null) {
            return false;
        }
        quietly(obj::close);
        return true;
    }

    static <T> boolean tryAs(Object obj, Class<T> type) {
        return tryAs(obj, type, null);
    }

    @SneakyThrows
    static <T> boolean tryAs(Object obj, Class<T> type, BiAction<T> fn) {
        T t = as(obj, type);
        if (t == null) {
            return false;
        }
        if (fn != null) {
            fn.invoke(t);
        }
        return true;
    }

    static String metadata(@NonNull AnnotatedElement annotatedElement) {
        Metadata m = annotatedElement.getAnnotation(Metadata.class);
        if (m == null) {
            return null;
        }
        return m.value();
    }

    static Object[] values(Object... args) {
        return args;
    }

    static <T> T ifNull(T value, T defaultVal) {
        return value != null ? value : defaultVal;
    }

    @SneakyThrows
    static <T> T ifNull(T value, Func<T> fn) {
        if (value == null) {
            if (fn != null) {
                value = fn.invoke();
            }
        }
        return value;
    }

    static <T> T as(Object obj, Class<T> type) {
        if (!TypeUtils.isInstance(obj, type)) {
            return null;
        }
        return (T) obj;
    }

    static <T> boolean eq(T a, T b) {
        //Objects.equals() poisonous
        return a == b || (a != null && a.equals(b));
    }
    //endregion

    default <TK, TV> TV attr(TK key) {
        Map<TK, TV> attrMap = IOC.weakIdentityMap(this);
        if (attrMap == null) {
            return null;
        }
        return attrMap.get(key);
    }

    default <TK, TV> void attr(TK key, TV value) {
        IOC.weakIdentityMap(this).put(key, value);
    }
}
