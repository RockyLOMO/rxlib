package org.rx.fl.service.command;

import lombok.Data;

@Data
public class HandleResult<T> {
    public static <T> HandleResult<T> of(T value) {
        return of(value, null);
    }

    public static <T> HandleResult<T> of(T value, Command next) {
        HandleResult<T> result = new HandleResult<>();
        result.setValue(value);
        result.setNext(next);
        return result;
    }

    private T value;
    private Command next;
}
