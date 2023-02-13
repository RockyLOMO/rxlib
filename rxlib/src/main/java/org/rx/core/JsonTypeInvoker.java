package org.rx.core;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.SneakyThrows;
import org.rx.util.function.Action;
import org.rx.util.function.Func;

import java.lang.reflect.Type;

public interface JsonTypeInvoker {
    FastThreadLocal<Type> JSON_TYPE = new FastThreadLocal<>();

    @SneakyThrows
    default void invoke(Action action, Type type) {
        JSON_TYPE.set(type);
        try {
            action.invoke();
        } finally {
            JSON_TYPE.remove();
        }
    }

    @SneakyThrows
    default <T> T invoke(Func<T> action, Type type) {
        JSON_TYPE.set(type);
        try {
            return action.invoke();
        } finally {
            JSON_TYPE.remove();
        }
    }
}
