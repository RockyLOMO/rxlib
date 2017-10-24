package org.rx;

import org.rx.bean.Const;

import static org.rx.App.CacheContainerKind.*;

public final class $<T> {
    /**
     * Alt+Enter
     * 
     * @param <T>
     * @return
     */
    public static <T> $<T> $() {
        return App.getOrStore($.class, Const.EmptyString, k -> new $<>(), ThreadStatic);
    }

    public T $;

    private $() {
    }
}
