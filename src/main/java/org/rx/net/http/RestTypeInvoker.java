package org.rx.net.http;

import lombok.SneakyThrows;
import org.rx.util.function.Action;
import org.rx.util.function.Func;

import java.lang.reflect.Type;

public interface RestTypeInvoker {
    @SneakyThrows
    default void invoke(Action action, Type type) {
        RestClient.RESULT_TYPE.set(type);
        try {
            action.invoke();
        } finally {
            RestClient.RESULT_TYPE.remove();
        }
    }

    @SneakyThrows
    default <T> T invoke(Func<T> action, Type type) {
        RestClient.RESULT_TYPE.set(type);
        try {
            return action.invoke();
        } finally {
            RestClient.RESULT_TYPE.remove();
        }
    }
}
