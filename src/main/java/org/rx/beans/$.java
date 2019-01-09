package org.rx.beans;

import org.rx.common.App;

import static org.rx.common.App.CacheContainerKind.*;
import static org.rx.common.Contract.EmptyString;

public final class $<T> {
    /**
     * Alt+Enter
     * 
     * @param <T>
     * @return
     */
    public static <T> $<T> $() {
        return App.getOrStore($.class, EmptyString, k -> new $<>(), ThreadStatic);
    }

    public T $;

    private $() {
    }
}
