package org.rx.io;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.$;
import org.rx.bean.AbstractMap;
import org.rx.core.Disposable;

import java.io.*;
import java.util.Map;
import java.util.Set;

import static org.rx.bean.$.$;
import static org.rx.core.App.as;
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
 * <p>
 * 减少文件，二分查找
 */
@Slf4j
public class KeyValueStore<TK, TV> extends Disposable implements AbstractMap<TK, TV> {
    @AllArgsConstructor
    @EqualsAndHashCode
    @ToString
    private static class Entry<TK, TV> implements Compressible {
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

    static final String LOG_FILE = "RxKv.log";
    static final int TOMB_MARK = -1;
    @Getter(lazy = true)
    private static final KeyValueStore instance = new KeyValueStore<>();

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
            log.warn("Hash collision {} {}", k.hashCode(), k);
            return null;
        }
        return val;
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
    public TV remove(Object key) {
        return delete((TK) key);
    }

    @Override
    public synchronized void clear() {
        indexer.clear();
        wal.clear();
    }

    @Override
    public Set<Map.Entry<TK, TV>> entrySet() {
        throw new UnsupportedOperationException();
    }
}
