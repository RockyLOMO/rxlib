package org.rx.core.cache;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Cache;
import org.rx.util.function.BiFunc;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import static org.rx.core.Contract.require;

@Slf4j
final class WeakCache<TK, TV> implements Cache<TK, TV> {
    //ReferenceQueue、ConcurrentMap<TK, Reference<TV>> 不准, soft 内存不够时才会回收
    private final Map<TK, TV> container = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public long size() {
        return container.size();
    }

    @Override
    public Set<TK> keySet() {
        return container.keySet();
    }

    @Override
    public TV put(TK key, TV val) {
        return container.put(key, val);
    }

    @Override
    public TV remove(TK key) {
        return container.remove(key);
    }

    @Override
    public void clear() {
        container.clear();
    }

    @Override
    public TV get(TK key) {
        TV val = container.get(key);
        if (val == null) {
            log.debug("get key {} is gc", key);
            remove(key);
            return null;
        }
        return val;
    }

    @SneakyThrows
    @Override
    public TV get(TK key, BiFunc<TK, TV> supplier) {
        require(supplier);

        TV v = get(key);
        if (v == null) {
            put(key, v = supplier.invoke(key));
        }
        return v;
    }
}
