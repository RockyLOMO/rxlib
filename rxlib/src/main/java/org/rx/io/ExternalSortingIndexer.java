package org.rx.io;

import lombok.NonNull;
import org.rx.codec.CodecUtil;
import org.rx.core.Constants;
import org.rx.core.Disposable;

import java.io.EOFException;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.rx.core.Extends.eq;

/**
 * <p>index
 * crc64(8) + logPos(8) + keyPos(8)
 *
 * @param <TK>
 */
class ExternalSortingIndexer<TK> extends Disposable implements KeyIndexer<TK> {
    static class HashKey<TK> extends KeyIndexer.KeyEntity<TK> implements Comparable<HashKey<TK>> {
        static final int BYTES = 24;

        static <TK> long hash(TK key) {
            return key instanceof Long ? (Long) key : CodecUtil.hash64(Serializer.DEFAULT.serializeToBytes(key));
        }

        private static final long serialVersionUID = -3136532663217712845L;
        long hashId;
        long keyPos = Constants.IO_EOF;

        private HashKey() {
            super(null);
        }

        public HashKey(TK key) {
            super(key);
            hashId = hash(key);
        }

        @Override
        public int compareTo(HashKey<TK> o) {
//            if (o == null) {
//                return -1;
//            }
            return Long.compare(hashId, o.hashId);
        }
    }

    class Partition extends FileStream.Block {
        final long endPos;
        volatile WeakReference<HashKey<TK>[]> ref;

        public Partition(long position, long size) {
            super(position, size);
            endPos = position + size;
        }

        HashKey<TK>[] load(boolean forWrite) {
            WeakReference<HashKey<TK>[]> r = ref;
            HashKey<TK>[] ks = r.get();
            if (ks == null) {
                ks = (HashKey<TK>[]) fs.lock.readInvoke(() -> {
                    int size = fs.meta.getSize();
                    List<HashKey<TK>> keys = new ArrayList<>(forWrite ? size + 1 : size);

                    fs.setReaderPosition(position);
                    int b = HashKey.BYTES;
                    byte[] buf = new byte[b];
                    for (long i = 0; i < this.size; i += b) {
                        if (fs.read(buf, 0, buf.length) != b) {
                            throw new EOFException();
                        }
                        HashKey<TK> k = new HashKey<>();
                        k.hashId = Bytes.getLong(buf, 0);
                        if (k.hashId == 0) {
                            break;
                        }
                        k.logPosition = Bytes.getLong(buf, 8);
                        k.keyPos = Bytes.getLong(buf, 16);
                        keys.add(k);
                    }
                    if (forWrite) {
                        keys.add(null);
                    }
                    return keys;
                }, position, size).toArray();
                ref = new WeakReference<>(ks);
            }
            return ks;
        }

        public HashKey<TK> find(TK k) {
            HashKey<TK> ktf = new HashKey<>(k);
            HashKey<TK>[] keys = load(false);
            int i = Arrays.binarySearch(keys, ktf);
            if (i == -1) {
                return null;
            }
            HashKey<TK> fk = keys[i];
            ktf.logPosition = fk.logPosition;
            ktf.keyPos = fk.keyPos;
            return ktf;
        }

        boolean save(HashKey<TK> key) {
            HashKey<TK> ktf = find(key.key);
            if (ktf != null) {
                ktf.logPosition = key.logPosition;
                fs.lock.writeInvoke(() -> {
                    long wPos = fs.getWriterPosition();
                    fs.setWriterPosition(ktf.keyPos + 8);
                    fs.write(Bytes.getBytes(ktf.logPosition));
                    fs.setWriterPosition(wPos);
                }, position, size);
                return true;
            }

            long wPos = fs.getWriterPosition();
            if (wPos < endPos) {
                fs.lock.writeInvoke(() -> {
                    HashKey<TK>[] ks = load(true);
                    ks[ks.length - 1] = key;
                    Arrays.parallelSort(ks);

                    fs.setWriterPosition(position);
                    byte[] buf = new byte[HashKey.BYTES];
                    for (HashKey<TK> fk : ks) {
                        fk.keyPos = fs.getWriterPosition();
                        Bytes.getBytes(fk.hashId, buf, 0);
                        Bytes.getBytes(fk.logPosition, buf, 8);
                        Bytes.getBytes(fk.keyPos, buf, 16);
                        fs.write(buf);
                    }
                    ref = new WeakReference<>(ks);
                }, wPos, endPos - wPos);
                return true;
            }
            return false;
        }
    }

    final WALFileStream fs;
    final int bufKeyCount;
    final long bufSize;
    final List<Partition> partitions = new CopyOnWriteArrayList<>();

    public ExternalSortingIndexer(File file, long bufSize, int readerCount) {
        this.bufSize = bufSize = (bufKeyCount = (int) (bufSize / 16)) * 16;
        fs = new WALFileStream(file, bufSize, readerCount, Serializer.DEFAULT);
        fs.onGrow.combine((s, e) -> ensureGrow());
        ensureGrow();
    }

    @Override
    protected void freeObjects() {
        fs.close();
    }

    synchronized void ensureGrow() {
        int count = (int) (fs.getLength() / bufSize);
        for (int i = partitions.size(); i < count; i++) {
            partitions.add(new Partition(WALFileStream.HEADER_SIZE + i * bufSize, bufSize));
        }
        int rc = partitions.size() - count;
        for (int i = 0; i < rc; i++) {
            partitions.remove(partitions.size() - 1);
        }
    }

    @Override
    public KeyEntity<TK> newKey(TK key) {
        return new HashKey<>(key);
    }

    @Override
    public void save(@NonNull KeyEntity<TK> key) {
        HashKey<TK> fk = (HashKey<TK>) key;
        for (Partition partition : partitions) {
            if (partition.save(fk)) {
                break;
            }
        }
    }

    @Override
    public KeyEntity<TK> find(@NonNull TK k) {
        for (Partition partition : partitions) {
            HashKey<TK> key = partition.find(k);
            if (key != null) {
                return key;
            }
        }
        return null;
    }

    @Override
    public void clear() {
        fs.clear();
    }
}
