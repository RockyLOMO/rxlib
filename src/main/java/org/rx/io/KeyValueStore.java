package org.rx.io;

import com.google.common.collect.AbstractSequentialIterator;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.IteratorUtils;
import org.rx.bean.$;
import org.rx.bean.AbstractMap;
import org.rx.core.Disposable;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.rx.bean.$.$;
import static org.rx.core.App.*;

/**
 * meta
 * logPosition + size
 *
 * <p>index
 * key.hashCode(4) + pos(8)
 *
 * <p>log
 * key + value + size(4)
 *
 * <p>减少文件，二分查找
 */
@Slf4j
public class KeyValueStore<TK, TV> extends Disposable implements AbstractMap<TK, TV>, Iterable<Map.Entry<TK, TV>> {
    @AllArgsConstructor
    @EqualsAndHashCode
    @ToString
    @Getter
    static class Entry<TK, TV> implements Map.Entry<TK, TV>, Compressible {
        private static final long serialVersionUID = -2218602651671401557L;

        private void writeObject(ObjectOutputStream out) throws IOException {
            out.writeObject(key);
            out.writeObject(value);
        }

        private void readObject(ObjectInputStream in) throws IOException {
            try {
                key = (TK) in.readObject();
                value = (TV) in.readObject();
            } catch (ClassNotFoundException e) {
                log.error("readObject {}", e.getMessage());
            }
        }

        TK key;
        TV value;

        @Override
        public TV setValue(TV value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean enableCompress() {
            Compressible k = as(key, Compressible.class);
            Compressible v = as(value, Compressible.class);
            return (k != null && k.enableCompress()) || (v != null && v.enableCompress());
        }
    }

    @RequiredArgsConstructor
    enum EntryStatus {
        NORMAL((byte) 0),
        DELETE((byte) 1);

        final byte value;
    }

    @RequiredArgsConstructor
    class IteratorContext {
        long logPos = wal.meta.getLogPosition();
        final BloomFilter<Integer> filter;
        final Entry<TK, TV>[] buf;
        int writePos;
        int readPos;
        int remaining;
    }

    static final String LOG_FILE = "RxKv.log";
    static final int TOMB_MARK = -1;
    static final int DEFAULT_ITERATOR_SIZE = 50;
    @Getter(lazy = true)
    private static final KeyValueStore instance = new KeyValueStore<>();

    final KeyValueStoreConfig config;
    final File parentDirectory;
    final WALFileStream wal;
    final HashFileIndexer<TK> indexer;
    final Serializer serializer;
    final WriteBehindQueue<TK, TV> queue;

    File getParentDirectory() {
        parentDirectory.mkdirs();
        return parentDirectory;
    }

    File getIndexDirectory() {
        File dir = new File(parentDirectory, "index");
        dir.mkdirs();
        return dir;
    }

    private KeyValueStore() {
        this(KeyValueStoreConfig.defaultConfig(), Serializer.DEFAULT);
    }

    public KeyValueStore(KeyValueStoreConfig config) {
        this(config, Serializer.DEFAULT);
    }

    public KeyValueStore(@NonNull KeyValueStoreConfig config, @NonNull Serializer serializer) {
        this.config = config;

        parentDirectory = new File(config.getDirectoryPath());
        this.serializer = serializer;

        File logFile = new File(getParentDirectory(), LOG_FILE);
        wal = new WALFileStream(logFile, config.getLogGrowSize(), config.getLogReaderCount(), serializer);

        indexer = new HashFileIndexer<>(getIndexDirectory(), config.getIndexSlotSize(), config.getIndexGrowSize());

        long pos = wal.meta.getLogPosition();
        log.debug("init logPos={}", pos);
//        pos = WALFileStream.HEADER_SIZE;
        Entry<TK, TV> val;
        $<Long> endPos = $();
        while ((val = findValue(pos, null, endPos)) != null) {
            boolean incr = false;
            TK k = val.key;
            HashFileIndexer.KeyData<TK> key = indexer.findKey(k);
            if (key == null) {
                key = new HashFileIndexer.KeyData<>(k, k.hashCode());
                incr = true;
            }
            if (key.logPosition == TOMB_MARK) {
                incr = true;
            }
            synchronized (this) {
                key.logPosition = val.value == null ? TOMB_MARK : pos;
                wal.meta.setLogPosition(endPos.v);

                indexer.saveKey(key);
            }
            if (incr) {
                wal.meta.incrementSize();
            }
            log.debug("recover {}", key);
            pos = endPos.v;
        }

        if (wal.meta.extra == null) {
            wal.meta.extra = new AtomicInteger();
        }

        queue = new WriteBehindQueue<>(config.getWriteBehindDelayed(), config.getWriteBehindHighWaterMark());
    }

    @Override
    protected void freeObjects() {
        indexer.close();
        wal.close();
    }

    protected void write(TK k, TV v) {
        boolean incr = false;
        HashFileIndexer.KeyData<TK> key = indexer.findKey(k);
        if (key == null) {
            key = new HashFileIndexer.KeyData<>(k, k.hashCode());
            incr = true;
        }
        if (key.logPosition == TOMB_MARK) {
            if (v == null) {
                return;
            }
            incr = true;
        }

        Entry<TK, TV> val = new Entry<>(k, v);
        synchronized (this) {
            saveValue(key, val);
            indexer.saveKey(key);
        }
        if (incr) {
            wal.meta.incrementSize();
        }
    }

    protected TV delete(TK k) {
        HashFileIndexer.KeyData<TK> key = indexer.findKey(k);
        if (key == null) {
            return null;
        }
        Entry<TK, TV> val = findValue(key);
        if (val == null) {
            return null;
        }

        val.value = null;
        synchronized (this) {
            saveValue(key, val);
            indexer.saveKey(key);
        }
        wal.meta.decrementSize();
        return val.value;
    }

    protected TV read(TK k) {
        HashFileIndexer.KeyData<TK> key = indexer.findKey(k);
        if (key == null || key.logPosition == TOMB_MARK) {
            return null;
        }

        Entry<TK, TV> val = findValue(key);
        return val != null ? val.value : null;
    }

    private void saveValue(HashFileIndexer.KeyData<TK> key, Entry<TK, TV> value) {
        checkNotClosed();
        require(key, !(key.logPosition == TOMB_MARK && value.value == null));

        key.logPosition = wal.meta.getLogPosition();
        serializer.serialize(value, wal);
        int size = (int) (wal.meta.getLogPosition() - key.logPosition);
        wal.writeInt(size);
        if (value.value == null) {
            key.logPosition = TOMB_MARK;
        }
        log.debug("saveValue {} {}", key, value);
    }

    private Entry<TK, TV> findValue(HashFileIndexer.KeyData<TK> key) {
        require(key, key.logPosition >= 0);
        if (key.logPosition > wal.meta.getLogPosition()) {
            key.logPosition = TOMB_MARK;
            indexer.saveKey(key);
            return null;
        }

        return findValue(key.logPosition, key.key, null);
    }

    @SneakyThrows
    private Entry<TK, TV> findValue(long logPosition, TK k, $<Long> position) {
        Object kStr = k == null ? "NULL" : k.hashCode();
        log.debug("findValue {} {}", kStr, logPosition);
        Entry<TK, TV> val = null;
        try {
            wal.setReaderPosition(logPosition);
            val = serializer.deserialize(wal, true);
        } catch (Exception e) {
            if (e instanceof StreamCorruptedException) {
                log.error("findValue {} {}", kStr, logPosition, e);
            } else {
                throw e;
            }
        } finally {
            long readerPosition = wal.getReaderPosition(true);
            if (position != null) {
                position.v = readerPosition;
            }
        }
        if (val == null || val.value == null) {
            return null;
        }
        if (k != null && !k.equals(val.key)) {
            AtomicInteger counter = (AtomicInteger) wal.meta.extra;
            int total = counter == null ? -1 : counter.incrementAndGet();
            log.warn("Hash collision {} {} total={}", k.hashCode(), k, total);
            return null;
        }
        return val;
    }

    @Override
    public Iterator<Map.Entry<TK, TV>> iterator() {
        return iterator(0, DEFAULT_ITERATOR_SIZE);
    }

    public Iterator<Map.Entry<TK, TV>> iterator(int offset, int size) {
        require(offset, offset >= 0);
        require(size, size >= 0);

        if (size() <= offset) {
            return IteratorUtils.emptyIterator();
        }

        IteratorContext ctx = new IteratorContext(BloomFilter.create(Funnels.integerFunnel(), offset + size, 0.001), new Entry[config.getIteratorPrefetchCount()]);
        if (offset > 0 && !backwardFindValue(ctx, offset)) {
            return IteratorUtils.emptyIterator();
        }
        backwardFindValue(ctx, ctx.buf.length);
        if (ctx.writePos == 0) {
            return IteratorUtils.emptyIterator();
        }
        ctx.remaining = size - 1;
        return new AbstractSequentialIterator<Map.Entry<TK, TV>>(ctx.buf[ctx.readPos]) {
            @Override
            protected Map.Entry<TK, TV> computeNext(Map.Entry<TK, TV> current) {
                if (--ctx.remaining < 0) {
                    return null;
                }
                if (++ctx.readPos == ctx.buf.length) {
                    backwardFindValue(ctx, Math.min(ctx.buf.length, ctx.remaining));
                    if (ctx.writePos == 0) {
                        return null;
                    }
                }
                return ctx.buf[ctx.readPos];
            }
        };
    }

    private boolean backwardFindValue(IteratorContext ctx, int prefetchCount) {
        wal.setReaderPosition(ctx.logPos); //4 lock
        return wal.backwardReadObject(reader -> {
            ctx.writePos = ctx.readPos = 0;
            for (int i = 0; i < prefetchCount; i++) {
                if (ctx.logPos <= WALFileStream.HEADER_SIZE) {
                    ctx.remaining = 0;
                    return false;
                }

                ctx.logPos -= 4;
                reader.setPosition(ctx.logPos);
                try {
                    int size = reader.readInt();
                    log.debug("contentLength: {}", size);
                    ctx.logPos -= size;
                } catch (Exception e) {
                    if (e instanceof EOFException) {
                        ctx.remaining = 0;
                        return false;
                    }
                    throw e;
                }
                reader.setPosition(ctx.logPos);
                try {
                    Entry<TK, TV> val = serializer.deserialize(reader, true);
                    log.debug("read {}", val);
                    TV v = queue.peek(val.key);
                    if (v != null) {
                        val.value = v;
                    }
                    if (ctx.filter.mightContain(val.key.hashCode())) {
                        log.info("mightContain {}", val.key);
                        continue;
                    }
                    ctx.filter.put(val.key.hashCode());
                    ctx.buf[ctx.writePos++] = val;
                } catch (Exception e) {
                    if (e instanceof StreamCorruptedException) {
                        ctx.remaining = 0;
                        return false;
                    }
                    throw e;
                }
            }
            return true;
        });
    }

    @Override
    public int size() {
        return wal.meta.getSize();
    }

    @Override
    public boolean containsKey(Object key) {
        TK k = (TK) key;
        TV v = queue.peek(k);
        if (v != null) {
            return true;
        }
        return indexer.findKey((TK) key) != null;
    }

    @Override
    public TV get(Object key) {
        TK k = (TK) key;
        TV v = queue.peek(k);
        if (v != null) {
            return v;
        }
        return read(k);
    }

    @Override
    public TV put(TK key, TV value) {
        TV old = read(key);
        if (!eq(old, value)) {
            write(key, value);
        }
        return old;
    }

    public void putBehind(TK key, TV value) {
        queue.offer(key, value, v -> put(key, v));
    }

    @Override
    public TV remove(Object key) {
        TK k = (TK) key;
        queue.replace(k, null);
        return delete(k);
    }

    @Override
    public synchronized void clear() {
        queue.reset();
        indexer.clear();
        wal.clear();
    }

    @Override
    public Set<Map.Entry<TK, TV>> entrySet() {
        return entrySet(0, DEFAULT_ITERATOR_SIZE);
    }

    public Set<Map.Entry<TK, TV>> entrySet(int offset, int size) {
        return new LinkedHashSet<>(IteratorUtils.toList(iterator(offset, size)));
    }
}
