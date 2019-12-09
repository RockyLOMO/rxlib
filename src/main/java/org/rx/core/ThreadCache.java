package org.rx.core;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.rx.util.function.BiFunc;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.rx.core.Contract.tryClose;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ThreadCache<TK, TV> implements MemoryCache<TK, TV> {
    private static final ThreadCache cache = new ThreadCache<>();

    public static <TK, TV> ThreadCache<TK, TV> getInstance() {
        return cache;
    }

    private final ThreadLocal<Map<TK, TV>> local = ThreadLocal.withInitial(HashMap::new);

    @Override
    public int size() {
        return local.get().size();
    }

    @Override
    public Set<TK> keySet() {
        return local.get().keySet();
    }

    @Override
    public void add(TK key, TV val) {
        local.get().put(key, val);
    }

    @Override
    public void remove(TK key, boolean destroy) {
        TV val = local.get().remove(key);
        if (destroy) {
            tryClose(val);
        }
    }

    @Override
    public void clear() {
        //local.remove();
        local.get().clear();
    }

    @Override
    public TV get(TK key) {
        return local.get().get(key);
    }

    @Override
    public TV get(TK key, BiFunc<TK, TV> supplier) {
        return local.get().computeIfAbsent(key, supplier.toFunction());
    }
}
