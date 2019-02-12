package org.rx.fl.service.command;

import lombok.Data;

import java.util.Collections;
import java.util.List;

@Data
public class HandleResult<T> {
    public static <T> HandleResult<T> ok(T value) {
        return ok(Collections.singletonList(value), null);
    }

    public static <T> HandleResult<T> ok(T value, Command next) {
        return ok(Collections.singletonList(value), next);
    }

    public static <T> HandleResult<T> ok(List<T> values, Command next) {
        HandleResult<T> result = new HandleResult<>();
        result.setOk(true);
        result.setValues(values);
        result.setNext(next);
        return result;
    }

    public static <T> HandleResult<T> fail() {
        return new HandleResult<>();
    }

    private boolean isOk;
    private List<T> values;
    private Command next;
}
