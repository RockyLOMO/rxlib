package org.rx.core;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.SneakyThrows;
import org.rx.util.function.Func;

import java.lang.reflect.Type;

public interface JsonTypeInvoker {
    FastThreadLocal<Type> JSON_TYPE = new FastThreadLocal<>();

    @SneakyThrows
    default <T> T typeInvoke(Type type, Func<T> action) {
        JSON_TYPE.set(type);
        try {
            return action.invoke();
        } finally {
            JSON_TYPE.remove();
        }
    }
}
