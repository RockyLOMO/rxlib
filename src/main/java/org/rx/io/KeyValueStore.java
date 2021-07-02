package org.rx.io;

import io.netty.buffer.ByteBuf;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.core.Tasks;

import java.io.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.rx.core.App.require;

/**
 * meta
 * size + logLength
 *
 * <p>index
 * key.hashCode(4) + pos(8) + size(4)
 *
 * <p>log
 * status(1) + key.hashCode(4) + value
 */
@Slf4j
public class KeyValueStore<TK, TV> extends Disposable implements ConcurrentMap<TK, TV> {
    @RequiredArgsConstructor
    static class KeyData {
        long position = -1;
        final int hashCode;
        long logPosition;
        int logSize;
    }

    @AllArgsConstructor
    static class ValueData<TV> implements Serializable {
        private static final long serialVersionUID = -2218602651671401557L;

        private void writeObject(ObjectOutputStream out) throws IOException {
            out.writeByte(status.value);
            out.writeInt(hashCode);
            out.writeObject(value);
        }

        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            status = in.readByte() == EntryStatus.NORMAL.value ? EntryStatus.NORMAL : EntryStatus.DELETE;
            hashCode = in.readInt();
            value = (TV) in.readObject();
        }

        EntryStatus status;
        int hashCode;
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
    static final int HEADER_LENGTH = 512;
    static final int DELETED_POSITION = -1;
    static final int KEY_SIZE = 16, READ_BLOCK_SIZE = (int) Math.floor(1024d * 4 / KEY_SIZE) * KEY_SIZE, MAX_INDEX_FILE_SIZE = 1024 * 1024 * 128;
    static final int HASH_BITS = 0x7fffffff;

    static int spread(int h) {
        return (h ^ (h >>> 16)) & HASH_BITS;
    }

    final File parentDirectory;
    final FileStream writer;
    final CompositeLock locker;
    final KeyValueMetaStore metaStore;
    final CompositeMmap metaMmap;
    final LinkedTransferQueue<FileStream> readers = new LinkedTransferQueue<>();
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
        this(dirPath, 1, (int) Math.ceil((double) Integer.MAX_VALUE * KEY_SIZE / MAX_INDEX_FILE_SIZE), Serializer.DEFAULT);
    }

    /**
     * @param dirPath
     * @param readerCount    The magnetic hard disk head needs to seek the next read position (taking about 5ms) for each thread.
     *                       Thus, reading with multiple threads effectively bounces the disk between seeks, slowing it down.
     *                       The only recommended way to read a file from a single disk is to read sequentially with one thread.
     * @param indexFileCount
     * @param serializer
     */
    public KeyValueStore(@NonNull String dirPath, int readerCount, int indexFileCount, Serializer serializer) {
        parentDirectory = new File(dirPath);
        this.serializer = serializer;

        File logFile = new File(getParentDirectory(), LOG_FILE);
        writer = new FileStream(logFile, FileMode.READ_WRITE, BufferedRandomAccessFile.BufSize.LARGE_DATA);
        locker = writer.getLock();

        for (int i = 0; i < readerCount; i++) {
            readers.offer(new FileStream(logFile, FileMode.READ_ONLY, BufferedRandomAccessFile.BufSize.LARGE_DATA));
        }

        metaStore = new KeyValueMetaStore(this::saveMetaData, this::loadMetaData);
        writer.setPosition(Math.max(HEADER_LENGTH, metaStore.meta.getLogLength()));
        metaMmap = writer.mmap(FileChannel.MapMode.READ_WRITE, 0, HEADER_LENGTH); //在loadMetaData之后

        indexes = new IndexNode[indexFileCount];

        if (metaStore.meta.getLogLength() < writer.getLength()) {
//todo reconver
        }
    }

    @Override
    protected void freeObjects() {
        saveMetaData(metaStore.meta);
    }

    private void saveMetaData(KeyValueMetaStore.MetaData metaData) {
        locker.writeInvoke(() -> {
            metaData.setLogLength(writer.getPosition());
            writer.setPosition(0);
            serializer.serialize(metaData, writer);
            writer.setPosition(metaData.getLogLength());
        }, 0, HEADER_LENGTH);
    }

    @SneakyThrows
    private KeyValueMetaStore.MetaData loadMetaData() {
        if (writer.getLength() == 0) {
            return new KeyValueMetaStore.MetaData();
        }

        FileStream reader = readers.take();
        try {
            return locker.readInvoke(() -> {
                reader.setPosition(0);
                return serializer.deserialize(reader, true);
            }, 0, HEADER_LENGTH);
        } finally {
            readers.offer(reader);
        }
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
        KeyData key = findKey(k);
        if (key == null) {
            key = new KeyData(k.hashCode());
        }
        ValueData<TV> val = new ValueData<>(EntryStatus.NORMAL, key.hashCode, v);

        synchronized (this) {
            saveValue(key, val);
            saveKey(key);
        }
        if (key.position == -1) {
            metaStore.meta.incrementSize();
        }
    }

    protected TV delete(TK k) {
        KeyData key = findKey(k);
        if (key == null) {
            return null;
        }
        ValueData<TV> val = findValue(key);
        if (val == null) {
            return null;
        }

        key.logPosition = -1;
        val.status = EntryStatus.DELETE;
        synchronized (this) {
            saveValue(key, val);
            saveKey(key);
        }
        metaStore.meta.decrementSize();
        return val.value;
    }

    protected TV read(TK k) {
        KeyData key = findKey(k);
        if (key == null) {
            return null;
        }

        ValueData<TV> val = findValue(key);
        return val != null ? val.value : null;
    }

    private void saveValue(KeyData key, ValueData<TV> value) {
        require(value, value.status != null);

        locker.writeInvoke(() -> {
            key.logPosition = writer.getPosition();
            serializer.serialize(value, writer);
            writer.flush();
            key.logSize = (int) (writer.getPosition() - key.logPosition);

            metaStore.meta.setLogLength(writer.getPosition());
        });
    }

    private ValueData<TV> findValue(KeyData key) {
        require(key, key.logPosition >= 0);
        if (key.logPosition > metaStore.meta.getLogLength()) {
            key.logPosition = DELETED_POSITION;
            saveKey(key);
            return null;
        }

        return findValue(key.logPosition);
    }

    @SneakyThrows
    private ValueData<TV> findValue(long logPosition) {
        FileStream reader = readers.take();
        try {
            ValueData<TV> val = locker.readInvoke(() -> {
                reader.setPosition(logPosition);
                return serializer.deserialize(reader, true);
            });
            if (val.status != EntryStatus.NORMAL) {
                return null;
            }
            return val;
        } finally {
            readers.offer(reader);
        }
    }

    private void saveKey(KeyData keyData) {
        IndexNode node = indexStream(keyData.hashCode);
        ByteBuf buf = null;
        node.locker.writeLock().lock();
        try {
            FileStream out = node.stream;
            out.setPosition(keyData.position > -1 ? keyData.position : out.getLength());

            buf = Bytes.directBuffer(KEY_SIZE, false);
            buf.writeInt(keyData.hashCode);
            buf.writeLong(keyData.logPosition);
            buf.writeInt(keyData.logSize);
            out.write(buf);
            out.flush();
        } finally {
            node.locker.writeLock().unlock();
            if (buf != null) {
                buf.release();
            }
        }
    }

    private KeyData findKey(@NonNull TK k) {
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
                if (logPos == DELETED_POSITION) {
                    return null;
                }

                KeyData keyData = new KeyData(hashCode);
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
        return metaStore.meta.getSize();
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
        locker.writeInvoke(() -> {
            metaStore.meta.setLogLength(HEADER_LENGTH);
            metaStore.meta.setSize(0);
            writer.setLength(HEADER_LENGTH);
        });

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
    public Set<Entry<TK, TV>> entrySet() {
        throw new UnsupportedOperationException();
    }
}
