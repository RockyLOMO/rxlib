package org.rx.fl.service.command;

import lombok.Data;

@Data
public class HandleResult<T> {
    public static <T> HandleResult<T> ok(T value) {
        return ok(value, null);
    }

    public static <T> HandleResult<T> ok(T value, Command next) {
        HandleResult<T> result = new HandleResult<>();
        result.setOk(true);
        result.setValue(value);
        result.setNext(next);
        return result;
    }

    public static <T> HandleResult<T> fail() {
        return new HandleResult<>();
    }

    private boolean isOk;
    private T value;
    private Command next;
}
