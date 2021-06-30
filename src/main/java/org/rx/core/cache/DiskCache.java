//package org.rx.core.cache;
//
//import com.github.benmanes.caffeine.cache.Caffeine;
//import com.github.benmanes.caffeine.cache.RemovalCause;
//import com.github.benmanes.caffeine.cache.RemovalListener;
//import com.github.benmanes.caffeine.cache.Scheduler;
//import com.google.common.cache.CacheBuilder;
//import lombok.SneakyThrows;
//import org.checkerframework.checker.nullness.qual.NonNull;
//import org.checkerframework.checker.nullness.qual.Nullable;
//import org.rx.bean.DateTime;
//import org.rx.core.Cache;
//import org.rx.core.Tasks;
//import org.rx.util.function.BiFunc;
//
//import java.util.Collection;
//import java.util.Map;
//import java.util.Set;
//import java.util.concurrent.TimeUnit;
//
//import static org.rx.core.App.*;
//
//public class DiskCache<TK, TV> implements Cache<TK, TV> {
//    static class Entry<TV> {
//        TV value;
//        DateTime expireTime;
//    }
//
//    static class BinaryEntry{
//        int id;
//    }
//
//    final com.github.benmanes.caffeine.cache.Cache<TK, Entry<TV>> cache = Caffeine.newBuilder().executor(Tasks.pool()).scheduler(Scheduler.disabledScheduler())
//            .softValues().removalListener((k, v, r) -> {
////                if (r == RemovalCause.EXPIRED) {
////                    return;
////                }
////                Integer.MAX_VALUE
//            }).build();
//
//    @Override
//    public Map<TK, TV> getAll(Iterable<TK> keys, BiFunc<Set<TK>, Map<TK, TV>> loadingFunc) {
//        return null;
//    }
//
//    @Override
//    public Map<TK, TV> getAll(Iterable<TK> keys) {
//        return null;
//    }
//
//    @Override
//    public void removeAll(Iterable<TK> keys) {
//
//    }
//
//    @Override
//    public int size() {
//        return 0;
//    }
//
//    @Override
//    public boolean isEmpty() {
//        return false;
//    }
//
//    @Override
//    public boolean containsKey(Object key) {
//        return false;
//    }
//
//    @Override
//    public boolean containsValue(Object value) {
//        return false;
//    }
//
//    @Override
//    public TV get(Object key) {
//        return null;
//    }
//
//    @org.jetbrains.annotations.Nullable
//    @Override
//    public TV put(TK key, TV value) {
//        return null;
//    }
//
//    @Override
//    public TV remove(Object key) {
//        return null;
//    }
//
//    @Override
//    public void putAll(Map<? extends TK, ? extends TV> m) {
//
//    }
//
//    @Override
//    public void clear() {
//
//    }
//
//
//    @Override
//    public Set<TK> keySet() {
//        return null;
//    }
//
//
//    @Override
//    public Collection<TV> values() {
//        return null;
//    }
//
//
//    @Override
//    public Set<Entry<TK, TV>> entrySet() {
//        return null;
//    }
//
//    @Override
//    public TV putIfAbsent(TK key, TV value) {
//        return null;
//    }
//
//    @Override
//    public boolean remove(Object key, Object value) {
//        return false;
//    }
//
//    @Override
//    public boolean replace(TK key, TV oldValue, TV newValue) {
//        return false;
//    }
//
//    @Override
//    public TV replace(TK key, TV value) {
//        return null;
//    }
//
////    public DiskCache() {
////        this(1);
////    }
////
////    public DiskCache(int expireMinutes) {
////        cache = CacheBuilder.newBuilder().maximumSize(Short.MAX_VALUE)
////                .expireAfterAccess(expireMinutes, TimeUnit.MINUTES).build(); //expireAfterAccess 包含expireAfterWrite
////    }
////
////    @Override
////    public long size() {
////        return cache.size();
////    }
////
////    @Override
////    public Set<TK> keySet() {
////        return cache.asMap().keySet();
////    }
////
////    @Override
////    public synchronized TV put(TK key, TV val) {
////        TV v = cache.getIfPresent(key);
////        if (v == null) {
////            return null;
////        }
////        cache.put(key, val);
////        return v;
////    }
////
////    @Override
////    public synchronized TV remove(TK key) {
////        TV v = cache.getIfPresent(key);
////        if (v == null) {
////            return null;
////        }
////        cache.invalidate(key);
////        return v;
////    }
////
////    @Override
////    public void clear() {
////        cache.invalidateAll();
////    }
////
////    @Override
////    public TV get(TK key) {
////        return cache.getIfPresent(key);
////    }
////
////    @SneakyThrows
////    @Override
////    public TV get(TK key, BiFunc<TK, TV> supplier) {
////        return cache.get(key, () -> sneakyInvoke(() -> supplier.invoke(key)));
////    }
//}
