package org.rx.core;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.rx.bean.WeakIdentityMap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class Locker {
    public static final Locker INSTANCE = new Locker();
    //key1: ref, key2: key
    final Map<Object, Map<Object, ReentrantLock>> hold = new WeakIdentityMap<>();

    public ReentrantLock getLock(Object ref, Object key) {
        return hold.computeIfAbsent(ref, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(key, k -> new ReentrantLock());
    }
}
