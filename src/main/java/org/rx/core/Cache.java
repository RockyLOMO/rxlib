package org.rx.core;

import org.rx.core.cache.CacheFactory;
import org.rx.util.function.BiFunc;

import java.util.*;

import static org.rx.core.Contract.require;

public interface Cache<TK, TV> {
    static <TK, TV> TV getOrStore(TK key, BiFunc<TK, TV> supplier) {
        return getOrStore(key, supplier, null);
    }

    static <TK, TV> TV getOrStore(TK key, BiFunc<TK, TV> supplier, CacheKind kind) {
        require(key, supplier);
        if (kind == null) {
            kind = CacheKind.LruCache;
        }

        return Cache.<TK, TV>getInstance(kind).get(key, supplier);
    }

    static <TK, TV> Cache<TK, TV> getInstance(CacheKind kind) {
        require(kind);

        return CacheFactory.getInstance().get(kind.name());
    }

    int size();

    Set<TK> keySet();

    void put(TK key, TV val);

    default void remove(TK key) {
        remove(key, true);
    }

    void remove(TK key, boolean destroy);

    void clear();

    TV get(TK key);

    TV get(TK key, BiFunc<TK, TV> supplier);
}
