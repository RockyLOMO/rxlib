package org.rx.core;

import lombok.NonNull;
import lombok.SneakyThrows;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.rx.annotation.Description;
import org.rx.annotation.ErrorCode;
import org.rx.exception.ApplicationException;
import org.rx.exception.TraceHandler;
import org.rx.io.Serializer;
import org.rx.util.function.Action;
import org.rx.util.function.BiAction;
import org.rx.util.function.Func;

import java.io.Serializable;
import java.lang.reflect.AnnotatedElement;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

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
        //CopyOnWriteArrayList 写性能差
        return readMore ? new CopyOnWriteArrayList<>() : new Vector<>(initialCapacity);
    }

    //todo checkerframework
    @ErrorCode("test")
    static void require(Object arg, boolean testResult) {
        if (!testResult) {
            throw new ApplicationException("test", values(arg));
        }
    }

    static String description(@NonNull AnnotatedElement annotatedElement) {
        Description desc = annotatedElement.getAnnotation(Description.class);
        if (desc == null) {
            return null;
        }
        return desc.value();
    }

    static boolean sneakyInvoke(Action action) {
        return sneakyInvoke(action, 1);
    }

    static boolean sneakyInvoke(@NonNull Action action, int retryCount) {
        Throwable last = null;
        for (int i = 0; i < retryCount; i++) {
            try {
                action.invoke();
                return true;
            } catch (Throwable e) {
                if (last != null) {
                    TraceHandler.INSTANCE.log("sneakyInvoke retry={}", i, e);
                }
                last = e;
            }
        }
        if (last != null) {
            ExceptionUtils.rethrow(last);
        }
        return false;
    }

    static <T> T sneakyInvoke(Func<T> action) {
        return sneakyInvoke(action, 1);
    }

    static <T> T sneakyInvoke(@NonNull Func<T> action, int retryCount) {
        Throwable last = null;
        for (int i = 0; i < retryCount; i++) {
            try {
                return action.invoke();
            } catch (Throwable e) {
                if (last != null) {
                    TraceHandler.INSTANCE.log("sneakyInvoke retry={}/{}", i, retryCount, e);
                }
                last = e;
            }
        }
        if (last != null) {
            ExceptionUtils.rethrow(last);
        }
        return null;
    }

    static <T> void eachQuietly(Object array, BiAction<T> fn) {
        eachQuietly(Linq.asList(array, true), fn);
    }

    static <T> void eachQuietly(Iterable<T> iterable, BiAction<T> fn) {
        if (iterable == null) {
            return;
        }

        asyncEach(iterable, t -> {
            try {
                fn.invoke(t);
            } catch (Throwable e) {
                TraceHandler.INSTANCE.log("eachQuietly", e);
            }
        });
    }

    static boolean quietly(@NonNull Action action) {
        try {
            action.invoke();
            return true;
        } catch (Throwable e) {
            TraceHandler.INSTANCE.log("quietly", e);
        }
        return false;
    }

    static <T> T quietly(Func<T> action) {
        return quietly(action, null);
    }

    static <T> T quietly(@NonNull Func<T> action, Func<T> defaultValue) {
        try {
            return action.invoke();
        } catch (Throwable e) {
            TraceHandler.INSTANCE.log("quietly", e);
        }
        if (defaultValue != null) {
            try {
                return defaultValue.invoke();
            } catch (Throwable e) {
                ExceptionUtils.rethrow(e);
            }
        }
        return null;
    }

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
    static <T> boolean tryAs(Object obj, Class<T> type, BiAction<T> action) {
        T t = as(obj, type);
        if (t == null) {
            return false;
        }
        if (action != null) {
            action.invoke(t);
        }
        return true;
    }

    @SneakyThrows
    static <T> void asyncEach(Iterable<T> iterable, BiAction<T> fn) {
        if (iterable == null) {
            return;
        }

        for (T t : iterable) {
            fn.invoke(t);
            if (!ThreadPool.asyncContinueFlag(true)) {
                break;
            }
        }
    }

    static void asyncContinue(boolean flag) {
        ThreadPool.ASYNC_CONTINUE.set(flag);
    }

//    static CircuitBreakingException asyncBreak() {
//        throw new CircuitBreakingException();
//    }

    static <T> T as(Object obj, Class<T> type) {
        if (!TypeUtils.isInstance(obj, type)) {
            return null;
        }
        return (T) obj;
    }

    static <T> T ifNull(T value, T defaultVal) {
        return value != null ? value : defaultVal;
    }

    static <T> T ifNull(T value, Supplier<T> supplier) {
        if (value == null) {
            if (supplier != null) {
                value = supplier.get();
            }
        }
        return value;
    }

    static <T> boolean eq(T a, T b) {
        //Objects.equals() 有坑
        return a == b || (a != null && a.equals(b));
    }

    static Object[] values(Object... args) {
        return args;
    }

    @SneakyThrows
    static void sleep(long millis) {
        Thread.sleep(millis);
    }
    //endregion

    static <TK, TV> Map<TK, TV> weakMap(Object ref) {
        return Container.<Object, Map<TK, TV>>weakMap().computeIfAbsent(ref, k -> new ConcurrentHashMap<>(8));
    }

    default <TK, TV> TV attr(TK key) {
        Map<TK, TV> attrMap = Container.<Object, Map<TK, TV>>weakMap().get(this);
        if (attrMap == null) {
            return null;
        }
        return attrMap.get(key);
    }

    default <TK, TV> void attr(TK key, TV value) {
        weakMap(this).put(key, value);
    }

    default <T> T deepClone() {
        return Serializer.DEFAULT.deserialize(Serializer.DEFAULT.serialize(this));
    }
}
