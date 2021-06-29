package org.rx.io;

import org.rx.core.exception.ApplicationException;
import org.rx.util.function.BiFunc;

import java.io.File;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public class KeyValueStore<TK, TV> implements ConcurrentMap<TK, TV> {
    static class MetaData implements Serializable {
        private static final long serialVersionUID = -4204525178919466203L;

        final AtomicInteger size = new AtomicInteger();
    }

    static final String META_FILE = "meta.db";
    static final int HASH_BITS = 0x7fffffff;

    static final int spread(int h) {
        return (h ^ (h >>> 16)) & HASH_BITS;
    }

    final MetaData metaData;
    final FileStream value;
    final FileStream[] indexes;

    public KeyValueStore(String dirPath, int indexFileCount) {
        File parent = new File(dirPath);
        parent.mkdir();

        File metaFile = new File(parent, META_FILE);
        metaData = metaFile.exists() ? IOStream.deserialize(new FileStream(metaFile)) : new MetaData();

        value = new FileStream(new File(parent, "RxKv.db"), BufferedRandomAccessFile.FileMode.READ_WRITE, BufferedRandomAccessFile.BufSize.LARGE_DATA);
        indexes = new FileStream[indexFileCount];
        parent = new File(parent, "index");
        parent.mkdir();
        for (int i = 0; i < indexes.length; i++) {
            indexes[i] = new FileStream(new File(parent, String.format("%s.db", i)), BufferedRandomAccessFile.FileMode.READ_WRITE, BufferedRandomAccessFile.BufSize.SMALL_DATA);
        }
    }

    private void xxx(TK k){
        int hash = spread(k.hashCode());
        
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public int size() {
        return metaData.size.get();
    }

    @Override
    public boolean containsKey(Object key) {
        return cache.getIfPresent(key) != null;
    }

    @Override
    public boolean containsValue(Object value) {
        return cache.asMap().containsValue(value);
    }

    @Override
    public TV get(Object key) {
        return cache.getIfPresent(key);
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
        return cache.asMap().put(key, value);
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
        return cache.asMap().remove(key);
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
    public Set<Entry<TK, TV>> entrySet() {
        return cache.asMap().entrySet();
    }
}
