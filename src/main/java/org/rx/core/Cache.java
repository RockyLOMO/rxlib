package org.rx.core;

import org.rx.bean.RxConfig;
import org.rx.core.cache.LocalCache;
import org.rx.core.cache.ThreadCache;
import org.rx.util.function.BiFunc;

import java.util.*;

import static org.rx.core.Contract.*;

public interface Cache<TK, TV> {
    int NON_EXPIRE_MINUTES = 0;
    String LRU_CACHE = "LruCache";
    String LOCAL_CACHE = "localCache";
    String THREAD_CACHE = "ThreadCache";

    static <TK, TV> TV getOrSet(TK key, BiFunc<TK, TV> supplier) {
        return getOrSet(key, supplier, NON_EXPIRE_MINUTES);
    }

    static <TK, TV> TV getOrSet(TK key, BiFunc<TK, TV> supplier, int expireMinutes) {
        return getOrSet(key, supplier, expireMinutes, App.getConfig().getDefaultCache());
    }

    static <TK, TV> TV getOrSet(TK key, BiFunc<TK, TV> supplier, String cacheName) {
        return getOrSet(key, supplier, NON_EXPIRE_MINUTES, cacheName);
    }

    static <TK, TV> TV getOrSet(TK key, BiFunc<TK, TV> supplier, int expireMinutes, String cacheName) {
        require(key, supplier);

        return Cache.<TK, TV>getInstance(cacheName).get(key, supplier, expireMinutes);
    }

    static <TK, TV> Cache<TK, TV> getInstance() {
        return getInstance(App.getConfig().getDefaultCache());
    }

    static <TK, TV> Cache<TK, TV> getInstance(String name) {
        return Container.getInstance().getOrRegister(name, () -> {
            switch (name) {
                case THREAD_CACHE:
                    return new ThreadCache<>();
                case LOCAL_CACHE:
                    return new LocalCache<>(RxConfig.KEEPALIVE_MINUTES * 2);
                default:
                    return new LocalCache<>();
            }
        });
    }

    long size();

    Set<TK> keySet();

    TV put(TK key, TV val);

    default TV put(TK key, TV value, int expireMinutes) {
        return put(key, value);
    }

    TV remove(TK key);

    default void remove(TK key, boolean destroy) {
        TV v = remove(key);
        if (destroy) {
            tryClose(v);
        }
    }

    void clear();

    TV get(TK key);

    TV get(TK key, BiFunc<TK, TV> supplier);

    default TV get(TK key, BiFunc<TK, TV> supplier, int expireMinutes) {
        return get(key, supplier);
    }
}
