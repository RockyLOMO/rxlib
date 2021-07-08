package org.rx.core.cache;

import com.alibaba.fastjson.JSON;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.cache.Scheduler;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.rx.bean.DateTime;
import org.rx.core.Cache;
import org.rx.core.CacheExpirations;
import org.rx.core.Tasks;
import org.rx.core.exception.ApplicationException;
import org.rx.io.*;
import org.rx.util.function.BiFunc;

import java.io.File;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.rx.core.App.*;

@Slf4j
public class PersistentCache<TK, TV> implements Cache<TK, TV> {
    @AllArgsConstructor
    static class Entry<TV> implements Serializable {
        private static final long serialVersionUID = -7742074465897857966L;
        TV value;
        DateTime expire;
        int slidingMinutes;
    }

    public static Cache DEFAULT = new PersistentCache<>("RxCache.db");

    final Cache<TK, Entry<TV>> cache;
    final KeyValueStore<TK, Entry<TV>> store;

    public PersistentCache(String kvDirectoryPath) {
        cache = new CaffeineCache<>(Caffeine.newBuilder().executor(Tasks.pool()).scheduler(Scheduler.disabledScheduler())
                .softValues().expireAfterAccess(1, TimeUnit.MINUTES).maximumSize(Short.MAX_VALUE)
                .removalListener(this::onRemoval).build());
        store = new KeyValueStore<>(kvDirectoryPath);
    }

    private void onRemoval(@Nullable TK tk, @Nullable Entry<TV> tvEntry, @NonNull RemovalCause removalCause) {
        if (tk == null || tvEntry == null || removalCause == RemovalCause.EXPIRED) {
            return;
        }
        if (!(tk instanceof Serializable && tvEntry.value instanceof Serializable)) {
            return;
        }
        store.put(tk, tvEntry);
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public int size() {
        return cache.size() + store.size();
    }

    @Override
    public boolean containsKey(Object key) {
        return cache.containsKey(key) || store.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return cache.containsValue(value) || store.containsValue(value);
    }

    @Override
    public TV get(Object key) {
        Entry<TV> entry = cache.get(key);
        if (entry == null) {
            entry = store.get(key);
        }
        return unwrap((TK) key, entry);
    }

    private TV unwrap(TK key, Entry<TV> entry) {
        if (entry == null) {
            return null;
        }
        DateTime utc = DateTime.utcNow();
        if (entry.expire.before(utc)) {
            cache.remove(key);
            return null;
        }
        if (entry.slidingMinutes > 0) {
            entry.expire = utc.addMinutes(entry.slidingMinutes);
            cache.put(key, entry);
        }
        return entry.value;
    }

    @Override
    public Map<TK, TV> getAll(Iterable<TK> keys) {
        return cache.getAllPresent(keys);
    }

    @Override
    public Map<TK, TV> getAll(Iterable<TK> keys, BiFunc<Set<TK>, Map<TK, TV>> loadingFunc) {
        return cache.getAll(keys, ks -> {
            try {
                return loadingFunc.invoke((Set<TK>) ks);
            } catch (Throwable e) {
                throw ApplicationException.sneaky(e);
            }
        });
    }

    @Override
    public TV put(TK key, TV value) {
        return cache.put(key, new Entry<>(value,) );
    }

    @Override
    public void putAll(Map<? extends TK, ? extends TV> m) {
        cache.putAll(m);
    }

    @Override
    public TV putIfAbsent(TK key, TV value) {
        return cache.asMap().putIfAbsent(key, value);
    }

    @Override
    public boolean replace(TK key, TV oldValue, TV newValue) {
        return cache.asMap().replace(key, oldValue, newValue);
    }

    @Override
    public TV replace(TK key, TV value) {
        return cache.asMap().replace(key, value);
    }

    @Override
    public TV remove(Object key) {
        Entry<TV> remove = cache.remove(key);
        if (remove == null) {
            remove = store.remove(key);
        }
        return remove == null ? null : remove.value;
    }

    @Override
    public boolean remove(Object key, Object value) {
        return cache.asMap().remove(key, value);
    }

    @Override
    public void removeAll(Iterable<TK> keys) {
        cache.invalidateAll(keys);
    }

    @Override
    public void clear() {
        cache.invalidateAll();
    }

    @Override
    public Set<TK> keySet() {
        return cache.asMap().keySet();
    }

    @Override
    public Collection<TV> values() {
        return cache.asMap().values();
    }

    @Override
    public Set<Map.Entry<TK, TV>> entrySet() {
        return cache.asMap().entrySet();
    }
}
