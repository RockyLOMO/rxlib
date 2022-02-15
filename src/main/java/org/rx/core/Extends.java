package org.rx.core;

import lombok.NonNull;
import lombok.SneakyThrows;
import org.apache.commons.lang3.concurrent.CircuitBreakingException;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.rx.annotation.Description;
import org.rx.annotation.ErrorCode;
import org.rx.exception.ApplicationException;
import org.rx.exception.ExceptionHandler;
import org.rx.io.IOStream;
import org.rx.io.Serializer;
import org.rx.util.function.Action;
import org.rx.util.function.BiAction;
import org.rx.util.function.Func;

import java.io.Serializable;
import java.lang.reflect.AnnotatedElement;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

@SuppressWarnings(Constants.NON_UNCHECKED)
public interface Extends extends Serializable {
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

    //region extend
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
                    ExceptionHandler.INSTANCE.log("sneakyInvoke retry={}", i, e);
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
                    ExceptionHandler.INSTANCE.log("sneakyInvoke retry={}", i, e);
                }
                last = e;
            }
        }
        if (last != null) {
            ExceptionUtils.rethrow(last);
        }
        return null;
    }

    static boolean quietly(@NonNull Action action) {
        try {
            action.invoke();
            return true;
        } catch (Throwable e) {
            ExceptionHandler.INSTANCE.log("quietly", e);
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
            ExceptionHandler.INSTANCE.log("quietly", e);
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

    static <T> void eachQuietly(Iterable<T> iterable, BiAction<T> fn) {
        if (iterable == null) {
            return;
        }

        for (T t : iterable) {
            try {
                fn.invoke(t);
            } catch (Throwable e) {
                if (e instanceof CircuitBreakingException) {
                    break;
                }
                ExceptionHandler.INSTANCE.log("eachQuietly", e);
            }
        }
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

    static <T> T as(Object obj, Class<T> type) {
        if (!TypeUtils.isInstance(obj, type)) {
            return null;
        }
        return (T) obj;
    }

    //todo checkerframework
    @ErrorCode("test")
    static void require(Object arg, boolean testResult) {
        if (!testResult) {
            throw new ApplicationException("test", values(arg));
        }
    }

    static <T> T isNull(T value, T defaultVal) {
        if (value == null) {
            return defaultVal;
        }
        return value;
    }

    static <T> T isNull(T value, Supplier<T> supplier) {
        if (value == null) {
            if (supplier != null) {
                value = supplier.get();
            }
        }
        return value;
    }

    static <T> boolean eq(T a, T b) {
        return (a == b) || (a != null && a.equals(b));
    }

    static String description(@NonNull AnnotatedElement annotatedElement) {
        Description desc = annotatedElement.getAnnotation(Description.class);
        if (desc == null) {
            return null;
        }
        return desc.value();
    }

    static Object[] values(Object... args) {
        return args;
    }

    @SneakyThrows
    static void sleep(long millis) {
        Thread.sleep(millis);
    }
    //endregion

    default <TV> TV attr() {
        return Container.<Object, TV>weakMap().get(this);
    }

    default <TV> TV attr(TV v) {
        return Container.<Object, TV>weakMap().put(this, v);
    }

    default <T> T deepClone() {
        IOStream<?, ?> stream = Serializer.DEFAULT.serialize(this);
        return Serializer.DEFAULT.deserialize(stream.rewind());
    }
}
