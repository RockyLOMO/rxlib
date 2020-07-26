package org.rx.core;

import org.rx.core.cache.CacheFactory;
import org.rx.util.function.BiFunc;

import java.util.*;

import static org.rx.core.Contract.require;

public interface MemoryCache<TK, TV> {
    static <TK, TV> TV getOrStore(TK key, BiFunc<TK, TV> supplier) {
        return getOrStore(key, supplier, CacheKind.WeakCache);
    }

    static <TK, TV> TV getOrStore(TK key, BiFunc<TK, TV> supplier, CacheKind kind) {
        require(key, supplier, kind);

        return MemoryCache.<TK, TV>getInstance(kind).get(key, supplier);
    }

    static <TK, TV> MemoryCache<TK, TV> getInstance(CacheKind kind) {
        require(kind);

        return getInstance(kind.name());
    }

    static <TK, TV> MemoryCache<TK, TV> getInstance(String name) {
        return CacheFactory.getInstance().get(name);
    }

    int size();

    Set<TK> keySet();

    void add(TK key, TV val);

    default void remove(TK key) {
        remove(key, true);
    }

    void remove(TK key, boolean destroy);

    void clear();

    TV get(TK key);

    TV get(TK key, BiFunc<TK, TV> supplier);
}
