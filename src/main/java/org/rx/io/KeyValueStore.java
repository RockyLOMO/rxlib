package org.rx.io;

import lombok.extern.slf4j.Slf4j;
import org.rx.core.exception.ApplicationException;
import org.rx.core.exception.InvalidException;
import org.rx.util.function.BiFunc;

import java.io.File;
import java.io.Serializable;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class KeyValueStore<TK, TV> implements ConcurrentMap<TK, TV> {
    static class MetaData implements Serializable {
        private static final long serialVersionUID = -4204525178919466203L;

        final AtomicInteger size = new AtomicInteger();
    }

    static class KeyData {
        private long position, length;
    }

    static final String VALUE_FILE = "RxKv.db";
    static final int HEADER_LENGTH = 2 * 1024;
    static final int HASH_BITS = 0x7fffffff;

    static final int spread(int h) {
        return (h ^ (h >>> 16)) & HASH_BITS;
    }

    final MetaData metaData;
    final FileStream reader, writer;
    final FileStream[] indexes;

    public KeyValueStore(String dirPath, int indexFileCount) {
        File parent = new File(dirPath);
        parent.mkdir();

        File valFile = new File(parent, VALUE_FILE);
        writer = new FileStream(valFile, BufferedRandomAccessFile.FileMode.READ_WRITE, BufferedRandomAccessFile.BufSize.LARGE_DATA);
        reader = new FileStream(valFile, BufferedRandomAccessFile.FileMode.READ_ONLY, BufferedRandomAccessFile.BufSize.LARGE_DATA);
        if (writer.getLength() == 0) {
            saveMetaData(metaData = new MetaData());
            writer.setPosition(HEADER_LENGTH);
        } else {
            metaData = loadMetaData();
        }

        indexes = new FileStream[indexFileCount];
        parent = new File(parent, "index");
        parent.mkdir();
        for (int i = 0; i < indexes.length; i++) {
            indexes[i] = new FileStream(new File(parent, String.format("%s.db", i)), BufferedRandomAccessFile.FileMode.READ_WRITE, BufferedRandomAccessFile.BufSize.SMALL_DATA);
        }
    }

    private void saveMetaData(MetaData metaData) {
        writer.lockWrite(0, HEADER_LENGTH);
        try {
            writer.write(Bytes.getBytes(0));
            IOStream.serializeTo(writer, metaData);
            writer.setPosition(0);
            writer.write(Bytes.getBytes(writer.getLength() - 4));
        } finally {
            writer.unlock(0, HEADER_LENGTH);
        }
    }

    private MetaData loadMetaData() {
        reader.lockRead(0, HEADER_LENGTH);
        try {
            byte[] buf = new byte[4];
            if (reader.read(buf) != buf.length) {
                log.error("Invalid header");
                return new MetaData();
            }
            return IOStream.deserialize(reader, true);
        } finally {
            reader.unlock(0, HEADER_LENGTH);
        }
    }

    private void xxx(TK k) {
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
        spread(key.hashCode());
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
