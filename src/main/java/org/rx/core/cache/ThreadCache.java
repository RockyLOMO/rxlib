//package org.rx.core.cache;
//
//import io.netty.util.concurrent.FastThreadLocal;
//import org.rx.core.Cache;
//import org.rx.util.function.BiFunc;
//
//import java.util.Map;
//import java.util.Set;
//import java.util.concurrent.ConcurrentHashMap;
//
//public final class ThreadCache<TK, TV> implements Cache<TK, TV> {
//    //Java 11 HashMap.computeIfAbsent java.util.ConcurrentModificationException
//    private final FastThreadLocal<Map<TK, TV>> local = new FastThreadLocal<Map<TK, TV>>() {
//        @Override
//        protected Map<TK, TV> initialValue() throws Exception {
//            return new ConcurrentHashMap<>();
//        }
//    };
//
//    @Override
//    public long size() {
//        return local.get().size();
//    }
//
//    @Override
//    public Set<TK> keySet() {
//        return local.get().keySet();
//    }
//
//    @Override
//    public TV put(TK key, TV val) {
//        return local.get().put(key, val);
//    }
//
//    @Override
//    public TV remove(TK key) {
//        return local.get().remove(key);
//    }
//
//    @Override
//    public void clear() {
//        local.get().clear();
//    }
//
//    @Override
//    public TV get(TK key) {
//        return local.get().get(key);
//    }
//
//    @Override
//    public TV get(TK key, BiFunc<TK, TV> supplier) {
//        return local.get().computeIfAbsent(key, supplier.toFunction());
//    }
//}
