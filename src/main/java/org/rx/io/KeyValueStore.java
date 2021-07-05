package org.rx.io;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.$;
import org.rx.core.Disposable;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.rx.bean.$.$;
import static org.rx.core.App.require;

/**
 * meta
 * logPosition + size
 *
 * <p>index
 * key.hashCode(4) + pos(8)
 *
 * <p>log
 * key + value
 */
@Slf4j
public class KeyValueStore<TK, TV> extends Disposable implements ConcurrentMap<TK, TV> {
    @AllArgsConstructor
    @ToString
    static class Entry<TK, TV> implements Serializable {
        private static final long serialVersionUID = -2218602651671401557L;

        private void writeObject(ObjectOutputStream out) throws IOException {
            out.writeObject(key);
            out.writeObject(value);
        }

        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            key = (TK) in.readObject();
            value = (TV) in.readObject();
        }

        TK key;
        TV value;
    }

    @RequiredArgsConstructor
    enum EntryStatus {
        NORMAL((byte) 0),
        DELETE((byte) 1);

        final byte value;
    }

    @RequiredArgsConstructor
    static class IndexNode {
        final FileStream stream;
        final ReentrantReadWriteLock locker;
    }

    static final String LOG_FILE = "RxKv.log";
    static final int TOMB_MARK = -1;
    static final int KEY_SIZE = 16, READ_BLOCK_SIZE = (int) Math.floor(1024d * 4 / KEY_SIZE) * KEY_SIZE;

    final KeyValueStoreConfig config;
    final File parentDirectory;
    final WALFileStream wal;
    final HashFileIndexer<TK> indexer;
    final Serializer serializer;

    File getParentDirectory() {
        parentDirectory.mkdirs();
        return parentDirectory;
    }

    File getIndexDirectory() {
        File dir = new File(parentDirectory, "index");
        dir.mkdirs();
        return dir;
    }

    public KeyValueStore(@NonNull KeyValueStoreConfig config, Serializer serializer) {
        this.config = config;

        parentDirectory = new File(config.getDirectoryPath());
        this.serializer = serializer;

        File logFile = new File(getParentDirectory(), LOG_FILE);
        wal = new WALFileStream(logFile, config.getLogGrowSize(), config.getLogReaderCount(), serializer);

        indexer = new HashFileIndexer<>(getIndexDirectory(), config.getIndexSlotSize(), config.getIndexBufferSize());

        long pos = wal.meta.getLogPosition();
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
                key.logPosition = pos;
                if (val.value == null) {
                    key.logPosition = TOMB_MARK;
                }
                wal.meta.setLogPosition(endPos.v);

                indexer.saveKey(key);
            }
            if (incr) {
                wal.meta.incrementSize();
            }
            log.debug("recover {}", key);
            pos = endPos.v;
        }
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

//        key.logPosition = TOMB_MARK;
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

        wal.lock.writeInvoke(() -> {
            key.logPosition = wal.meta.getLogPosition();
            serializer.serialize(value, wal);
            wal.flush();
            if (value.value == null) {
                key.logPosition = TOMB_MARK;
            }
        });
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
            log.warn("Hash collision {} {}", k.hashCode(), k);
            return null;
        }
        return val;
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public int size() {
        return wal.meta.getSize();
    }

    @Override
    public boolean containsKey(Object key) {
        return indexer.findKey((TK) key) != null;
    }

    @Override
    public boolean containsValue(Object value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TV get(Object key) {
        return read((TK) key);
    }

    @Override
    public TV put(TK key, TV value) {
        TV old = read(key);
        write(key, value);
        return old;
    }

    @Override
    public void putAll(Map<? extends TK, ? extends TV> m) {
        for (Map.Entry<? extends TK, ? extends TV> entry : m.entrySet()) {
            write(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public TV putIfAbsent(TK key, TV value) {
        TV cur = read(key);
        if (cur == null) {
            write(key, value);
        }
        return cur;
    }

    @Override
    public boolean replace(TK key, TV oldValue, TV newValue) {
        TV curValue = read(key);
        if (!Objects.equals(curValue, oldValue) || curValue == null) {
            return false;
        }
        write(key, newValue);
        return true;
    }

    @Override
    public TV replace(TK key, TV value) {
        TV curValue;
        if ((curValue = read(key)) != null) {
            write(key, value);
        }
        return curValue;
    }

    @Override
    public TV remove(Object key) {
        return delete((TK) key);
    }

    @Override
    public boolean remove(Object key, Object value) {
        Object curValue = get(key);
        if (!Objects.equals(curValue, value) || curValue == null) {
            return false;
        }
        remove(key);
        return true;
    }

    @Override
    public synchronized void clear() {
        indexer.clear();
        wal.clear();
    }

    @Override
    public Set<TK> keySet() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Collection<TV> values() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<Map.Entry<TK, TV>> entrySet() {
        throw new UnsupportedOperationException();
    }
}
