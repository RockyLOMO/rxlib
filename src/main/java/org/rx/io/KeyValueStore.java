package org.rx.io;

import io.netty.buffer.ByteBuf;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.$;
import org.rx.core.Disposable;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.rx.core.App.require;

/**
 * meta
 * size + logPosition
 *
 * <p>index
 * key.hashCode(4) + pos(8) + size(4)
 *
 * <p>log
 * status(1) + key + value
 * <p>
 * delay relative sequential write
 */
@Slf4j
public class KeyValueStore<TK, TV> extends Disposable implements ConcurrentMap<TK, TV> {
    @RequiredArgsConstructor
    @ToString
    static class KeyData<TK> {
        final TK key;
        long position = -1;

        final int hashCode;
        long logPosition;
        int logSize;
    }

    @AllArgsConstructor
    @ToString
    static class Entry<TK, TV> implements Serializable {
        private static final long serialVersionUID = -2218602651671401557L;

        private void writeObject(ObjectOutputStream out) throws IOException {
            out.writeByte(status.value);
            out.writeObject(key);
            out.writeObject(value);
        }

        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            status = in.readByte() == EntryStatus.NORMAL.value ? EntryStatus.NORMAL : EntryStatus.DELETE;
            key = (TK) in.readObject();
            value = (TV) in.readObject();
        }

        EntryStatus status;
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
    static final int KEY_SIZE = 16, READ_BLOCK_SIZE = (int) Math.floor(1024d * 4 / KEY_SIZE) * KEY_SIZE, MAX_INDEX_FILE_SIZE = 1024 * 1024 * 128;
    static final int HASH_BITS = 0x7fffffff;

    static int spread(int h) {
        return (h ^ (h >>> 16)) & HASH_BITS;
    }

    final KeyValueStoreConfig config;
    final File parentDirectory;
    final WALFileStream wal;
    final IndexNode[] indexes;
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

    public KeyValueStore(String dirPath) {
        this(new KeyValueStoreConfig(dirPath), (int) Math.ceil((double) Integer.MAX_VALUE * KEY_SIZE / MAX_INDEX_FILE_SIZE), Serializer.DEFAULT);
    }

    public KeyValueStore(@NonNull KeyValueStoreConfig config, int indexFileCount, Serializer serializer) {
        this.config = config;

        parentDirectory = new File(config.getDirectoryPath());
        this.serializer = serializer;

        File logFile = new File(getParentDirectory(), LOG_FILE);
        wal = new WALFileStream(logFile, config.getLogGrowSize(), config.getLogReaderCount(), serializer);

        indexes = new IndexNode[indexFileCount];

//        long pos = metaStore.meta.getLogPosition();
////        pos = HEADER_LENGTH;
//        while (pos < writer.getLength()) {
//            $<Long> endPos = $();
//            Entry<TK, TV> val = findValue(pos, null, endPos);
//            if (val == null || val.value == null) {
//                continue;
//            }
//
//            TK k = val.key;
//            KeyData<TK> key = findKey(k);
//            if (key == null) {
//                key = new KeyData<>(k, k.hashCode());
//            }
//            synchronized (this) {
//                key.logPosition = pos;
//                key.logSize = (int) (endPos.v - key.logPosition);
//                if (val.value == null) {
//                    key.logPosition = TOMB_MARK;
//                }
//                metaStore.meta.setLogLength(endPos.v);
//
//                saveKey(key);
//            }
//            if (key.position == -1 && key.logPosition != TOMB_MARK) {
//                metaStore.meta.incrementSize();
//            }
//            pos = endPos.v;
//        }
    }

    @Override
    protected void freeObjects() {
        wal.close();
        //todo index
    }

    private IndexNode indexStream(int hashCode) {
        int i = (indexes.length - 1) & spread(hashCode);
        synchronized (indexes) {
            IndexNode index = indexes[i];
            if (index == null) {
                indexes[i] = index = new IndexNode(new FileStream(new File(getIndexDirectory(), String.format("%s", i)), FileMode.READ_WRITE, BufferedRandomAccessFile.BufSize.SMALL_DATA), new ReentrantReadWriteLock());
            }
            return index;
        }
    }

    protected void write(TK k, TV v) {
        boolean incr = false;
        KeyData<TK> key = findKey(k);
        if (key == null) {
            key = new KeyData<>(k, k.hashCode());
            incr = true;
        }
        if (key.logPosition == TOMB_MARK) {
            if (v == null) {
                return;
            }
            incr = true;
        }

        Entry<TK, TV> val = new Entry<>(EntryStatus.NORMAL, k, v);
        synchronized (this) {
            saveValue(key, val);
            saveKey(key);
        }
        if (incr) {
            wal.meta.incrementSize();
        }
    }

    protected TV delete(TK k) {
        KeyData<TK> key = findKey(k);
        if (key == null) {
            return null;
        }
        Entry<TK, TV> val = findValue(key);
        if (val == null) {
            return null;
        }

        key.logPosition = TOMB_MARK;
        val.status = EntryStatus.DELETE;
        synchronized (this) {
            saveValue(key, val);
            saveKey(key);
        }
        wal.meta.decrementSize();
        return val.value;
    }

    protected TV read(TK k) {
        KeyData<TK> key = findKey(k);
        if (key == null || key.logPosition == TOMB_MARK) {
            return null;
        }

        Entry<TK, TV> val = findValue(key);
        return val != null ? val.value : null;
    }

    private void saveValue(KeyData<TK> key, Entry<TK, TV> value) {
        checkNotClosed();
        require(key, !(key.logPosition == TOMB_MARK && value.value == null));
        require(value, value.status != null);

        wal.lock.writeInvoke(() -> {
            key.logPosition = wal.meta.getLogPosition();
            serializer.serialize(value, wal);
            wal.flush();
            key.logSize = (int) (wal.meta.getLogPosition() - key.logPosition);
            if (value.status == EntryStatus.DELETE) {
                key.logPosition = TOMB_MARK;
            }
        });
        log.debug("saveValue {} {}", key, value);
    }

    private Entry<TK, TV> findValue(KeyData<TK> key) {
        require(key, key.logPosition >= 0);
        if (key.logPosition > wal.meta.getLogPosition()) {
            key.logPosition = TOMB_MARK;
            saveKey(key);
            return null;
        }

        return findValue(key.logPosition, key.key, null);
    }

    @SneakyThrows
    private Entry<TK, TV> findValue(long logPosition, TK k, $<Long> position) {
        log.debug("findValue {} {}", k == null ? "NULL" : k.hashCode(), logPosition);
        Entry<TK, TV> val = null;
        try {
            wal.setReaderPosition(logPosition);
            val = serializer.deserialize(wal, true);
        } catch (Exception e) {
            if (e instanceof StreamCorruptedException) {
                log.error("findValue {}", k.hashCode(), e);
            } else {
                throw e;
            }
        } finally {
            if (position != null) {
                position.v = wal.getReaderPosition();
            }
            wal.removeReaderPosition();
        }
        if (val == null || val.status != EntryStatus.NORMAL) {
            return null;
        }
        if (k != null && !k.equals(val.key)) {
            log.warn("Hash collision {} {}", k.hashCode(), k);
            return null;
        }
        return val;
    }

    private void saveKey(KeyData<TK> key) {
        checkNotClosed();
        log.debug("saveKey {} {}", key.hashCode, key);

        IndexNode node = indexStream(key.hashCode);
        ByteBuf buf = null;
        node.locker.writeLock().lock();
        try {
            FileStream out = node.stream;
            out.setPosition(key.position > -1 ? key.position : out.getLength());

            buf = Bytes.directBuffer(KEY_SIZE, false);
            buf.writeInt(key.hashCode);
            buf.writeLong(key.logPosition);
            buf.writeInt(key.logSize);
            out.write(buf);
            out.flush();
        } finally {
            node.locker.writeLock().unlock();
            if (buf != null) {
                buf.release();
            }
        }
    }

    private KeyData<TK> findKey(@NonNull TK k) {
        log.debug("findKey {}", k.hashCode());
//        return Tasks.threadMapCompute(k, x -> {
        int hashCode = k.hashCode();
        IndexNode node = indexStream(hashCode);
        ByteBuf buf = null;
        node.locker.readLock().lock();
        try {
            FileStream in = node.stream;
            long len = in.getLength();
            if (len == 0) {
                return null;
            }

            in.setPosition(0);
            buf = Bytes.directBuffer(KEY_SIZE, false);
            while (in.read(buf, KEY_SIZE) > 0) {
                if (buf.readInt() != hashCode) {
                    buf.clear();
                    continue;
                }
                long logPos = buf.readLong();
//                if (logPos == TOMB_MARK) {
//                    return null;
//                }

                KeyData<TK> keyData = new KeyData<>(k, hashCode);
                keyData.position = in.getPosition() - KEY_SIZE;
                keyData.logPosition = logPos;
                keyData.logSize = buf.readInt();
                return keyData;
            }

            return null;
        } finally {
            node.locker.readLock().unlock();
            if (buf != null) {
                buf.release();
            }
        }
//        });
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
        return findKey((TK) key) != null;
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
        wal.clear();

        for (int i = 0; i < indexes.length; i++) {
            IndexNode index = indexes[i];
            if (index == null) {
                continue;
            }

            index.stream.close();
            indexes[i] = null;
        }
        Files.delete(getIndexDirectory().getAbsolutePath());
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
