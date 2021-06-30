package org.rx.io;

import io.netty.buffer.ByteBuf;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.rx.core.App.require;

/**
 * meta
 * size + logLength
 *
 * <p>index
 * status(1) + key.hashCode(4) + pos(8) + size(4)
 *
 * <p>log
 * value
 */
@Slf4j
public class KeyValueStore<TK, TV> implements ConcurrentMap<TK, TV> {
    static class MetaData implements Serializable {
        private static final long serialVersionUID = -4204525178919466203L;

        final AtomicInteger size = new AtomicInteger();
        long logLength;
    }

    @RequiredArgsConstructor
    static class KeyData {
        long position = -1;
        KeyStatus status = KeyStatus.NORMAL;
        final int hashCode;
        long logPosition;
        int logSize;
    }

    @RequiredArgsConstructor
    enum KeyStatus {
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
    static final int HEADER_LENGTH = 1024 * 2;
    static final int KEY_SIZE = 17, READ_BLOCK_SIZE = (int) Math.floor(1024d * 4 / KEY_SIZE) * KEY_SIZE, MAX_INDEX_FILE_SIZE = 1024 * 1024 * 128;
    static final int HASH_BITS = 0x7fffffff;

    static final int spread(int h) {
        return (h ^ (h >>> 16)) & HASH_BITS;
    }

    final File parentDirectory;
    final MetaData metaData;
    final FileStream reader, writer;
    final ReentrantReadWriteLock locker = new ReentrantReadWriteLock();
    final IndexNode[] indexes;
    final Serializer<TV> serializer;

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
        this(dirPath, (int) Math.ceil((double) Integer.MAX_VALUE * KEY_SIZE / MAX_INDEX_FILE_SIZE), Serializer.DEFAULT);
    }

    public KeyValueStore(@NonNull String dirPath, int indexFileCount, Serializer<TV> serializer) {
        parentDirectory = new File(dirPath);

        File logFile = new File(getParentDirectory(), LOG_FILE);
        writer = new FileStream(logFile, BufferedRandomAccessFile.FileMode.READ_WRITE, BufferedRandomAccessFile.BufSize.LARGE_DATA);
        reader = new FileStream(logFile, BufferedRandomAccessFile.FileMode.READ_ONLY, BufferedRandomAccessFile.BufSize.LARGE_DATA);
        if (writer.getLength() == 0) {
            saveMetaData(metaData = new MetaData());
            writer.setPosition(HEADER_LENGTH);
        } else {
            metaData = loadMetaData();
            writer.setPosition(Math.max(HEADER_LENGTH, metaData.logLength));
        }

        indexes = new IndexNode[indexFileCount];
        this.serializer = serializer;
    }

    private void saveMetaData(MetaData metaData) {
        writer.lockWrite(0, HEADER_LENGTH);
        try {
            metaData.logLength = writer.getPosition();
            writer.setPosition(0);
            IOStream.serialize(metaData, writer);
        } finally {
            writer.unlock(0, HEADER_LENGTH);
        }
    }

    private MetaData loadMetaData() {
        reader.lockRead(0, HEADER_LENGTH);
        try {
            reader.setPosition(0);
            return IOStream.deserialize(reader, true);
        } finally {
            reader.unlock(0, HEADER_LENGTH);
        }
    }

    private IndexNode indexStream(int hashCode) {
        int i = (indexes.length - 1) & spread(hashCode);
        synchronized (indexes) {
            IndexNode index = indexes[i];
            if (index == null) {
                indexes[i] = index = new IndexNode(new FileStream(new File(getIndexDirectory(), String.format("%s", i)), BufferedRandomAccessFile.FileMode.READ_WRITE, BufferedRandomAccessFile.BufSize.SMALL_DATA), new ReentrantReadWriteLock());
            }
            return index;
        }
    }

    protected void write(TK k, TV v) {
        KeyData key = findKey(k);
        if (key == null) {
            key = new KeyData(k.hashCode());
        }

        synchronized (this) {
            saveValue(key, v);
            saveKey(key);
        }
        if (key.position == -1) {
            metaData.size.incrementAndGet();
        }
    }

    protected TV read(TK k) {
        KeyData key = findKey(k);
        if (key == null || key.status != KeyStatus.NORMAL) {
            return null;
        }

        return findValue(key);
    }

    protected TV delete(TK k) {
        KeyData key = findKey(k);
        if (key == null) {
            return null;
        }

        TV val = findValue(key);
        key.status = KeyStatus.DELETE;
        saveKey(key);
        metaData.size.decrementAndGet();
        return val;
    }

    private void saveValue(KeyData keyData, TV v) {
        locker.writeLock().lock();
        try {
            keyData.logPosition = writer.getPosition();
            serializer.serialize(v, writer);
            keyData.logSize = (int) (writer.getPosition() - keyData.logPosition);
        } finally {
            locker.writeLock().unlock();
        }
    }

    private TV findValue(KeyData keyData) {
        require(keyData, keyData.logPosition >= 0 && keyData.logSize > 0);

        locker.readLock().lock();
        try {
            reader.setPosition(keyData.logPosition);
            return serializer.deserialize(reader);
        } finally {
            locker.readLock().unlock();
        }
    }

    private void saveKey(KeyData keyData) {
        require(keyData, keyData.status != null);
        require(keyData, keyData.logPosition >= 0 && keyData.logSize > 0);

        IndexNode node = indexStream(keyData.hashCode);
        ByteBuf buf = null;
        node.locker.writeLock().lock();
        try {
            FileStream out = node.stream;
            out.setPosition(keyData.position > -1 ? keyData.position : out.getLength());

            buf = Bytes.directBuffer(KEY_SIZE, false);
            buf.writeByte(keyData.status.value);
            buf.writeInt(keyData.hashCode);
            buf.writeLong(keyData.logPosition);
            buf.writeInt(keyData.logSize);
            out.write(buf);
        } finally {
            node.locker.writeLock().unlock();
            if (buf != null) {
                buf.release();
            }
        }
    }

    private KeyData findKey(TK k) {
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
                byte status = buf.readByte();
                if (buf.readInt() != hashCode) {
                    buf.clear();
                    continue;
                }
                if (status != KeyStatus.NORMAL.value) {
                    return null;
                }

                KeyData keyData = new KeyData(hashCode);
                keyData.position = in.getPosition() - KEY_SIZE;
                keyData.logPosition = buf.readLong();
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
        for (Entry<? extends TK, ? extends TV> entry : m.entrySet()) {
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
        locker.writeLock().lock();
        try {
            writer.setLength(metaData.logLength = HEADER_LENGTH);
            metaData.size.set(0);
        } finally {
            locker.writeLock().unlock();
        }

        for (IndexNode index : indexes) {
            if (index == null) {
                continue;
            }

            index.locker.writeLock().lock();
            try {
                index.stream.setLength(0);
            } finally {
                index.locker.writeLock().unlock();
            }
        }
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
    public Set<Entry<TK, TV>> entrySet() {
        throw new UnsupportedOperationException();
    }
}
