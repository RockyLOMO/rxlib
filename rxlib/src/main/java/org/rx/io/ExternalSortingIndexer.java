package org.rx.io;

import lombok.NonNull;
import org.rx.codec.CodecUtil;
import org.rx.core.Cache;
import org.rx.core.Constants;
import org.rx.core.Disposable;
import org.rx.core.Linq;

import java.io.EOFException;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

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

        //todo lazy key
        private HashKey() {
            super(null);
        }

        public HashKey(TK key) {
            super(key);
            hashId = hash(key);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            HashKey<?> hashKey = (HashKey<?>) o;
            return hashId == hashKey.hashId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(hashId);
        }

        @Override
        public int compareTo(HashKey<TK> o) {
            return Long.compare(hashId, o.hashId);
        }
    }

    class Partition extends FileStream.Block {
        final long endPos;
        volatile int keySize = Constants.IO_EOF;
        volatile HashKey<TK> min, max;

        Partition(long position, long size) {
            super(position, size);
            endPos = position + size;
        }

        void clear() {
            keySize = 0;
            min = max = null;
//            ref = null;
            cache.remove(this);
        }

        HashKey<TK>[] load() {
            return fs.lock.readInvoke(() -> {
                HashKey<TK>[] ks = cache.get(this);
                if (ks == null) {
                    int keySize = this.keySize;
                    boolean setSize = keySize == Constants.IO_EOF;
                    List<HashKey<TK>> keys = new ArrayList<>(setSize ? 10 : keySize);

                    fs.setReaderPosition(position);
                    int b = HashKey.BYTES;
                    byte[] buf = new byte[b];
                    int kSize = setSize ? Integer.MAX_VALUE : keySize;
                    for (long i = 0; i < this.size && keys.size() < kSize; i += b) {
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

                    if (setSize) {
                        this.keySize = keys.size();
                    }
                    ks = keys.toArray(ARR_TYPE);
                    cache.put(this, ks);
                }
                return ks;
            }, position, size);
        }

        boolean find(HashKey<TK> ktf) {
            HashKey<TK>[] keys = load();
            int i = Arrays.binarySearch(keys, ktf);
            if (i < 0) {
                return false;
            }
            HashKey<TK> t = keys[i];
            ktf.logPosition = t.logPosition;
            ktf.keyPos = t.keyPos;
            return true;
        }

        boolean save(HashKey<TK> t) {
            HashKey<TK> ktf = new HashKey<>(t.key);
            if (find(ktf)) {
                ktf.logPosition = t.logPosition;
                fs.lock.writeInvoke(() -> {
                    long wPos = fs.getWriterPosition();
                    fs.setWriterPosition(ktf.keyPos + 8);
                    fs.write(Bytes.getBytes(ktf.logPosition));
                    fs.setWriterPosition(wPos);

                    HashKey<TK>[] ks;
                    if ((ks = cache.get(this)) != null) {
                        int i = (int) (ktf.keyPos - position) / HashKey.BYTES;
                        ks[i] = ktf;
                    }
                }, position, size);
                return true;
            }

            long wPos = fs.getWriterPosition();
            if (wPos < endPos) {
                fs.lock.writeInvoke(() -> {
                    HashKey<TK>[] ks = load();
                    HashKey<TK>[] nks = new HashKey[ks.length + 1];
                    System.arraycopy(ks, 0, nks, 0, ks.length);
                    nks[nks.length - 1] = t;
                    Arrays.parallelSort(nks);

                    keySize = nks.length;
                    min = nks[0];
                    max = nks[keySize - 1];
                    cache.put(this, nks);

                    fs.setWriterPosition(position);
                    byte[] buf = new byte[HashKey.BYTES];
                    for (HashKey<TK> fk : nks) {
                        fk.keyPos = fs.getWriterPosition();
                        Bytes.getBytes(fk.hashId, buf, 0);
                        Bytes.getBytes(fk.logPosition, buf, 8);
                        Bytes.getBytes(fk.keyPos, buf, 16);
                        fs.write(buf);
                    }
                }, wPos, endPos - wPos);
                return true;
            }
            return false;
        }
    }

    static final HashKey[] ARR_TYPE = new HashKey[0];
    final WALFileStream fs;
    final long bufSize;
    final Cache<Partition, HashKey<TK>[]> cache = Cache.getInstance(Cache.MEMORY_CACHE);
    final List<Partition> partitions = new CopyOnWriteArrayList<>();

    public ExternalSortingIndexer(File file, long bufSize, int readerCount) {
        int b = HashKey.BYTES;
        this.bufSize = bufSize = (bufSize / b) * b;
        fs = new WALFileStream(file, bufSize, readerCount, Serializer.DEFAULT);
        fs.onGrow.combine((s, e) -> ensureGrow());
        ensureGrow();
    }

    @Override
    protected void freeObjects() {
        fs.close();
    }

    void ensureGrow() {
        fs.lock.writeInvoke(() -> {
            int count = (int) (fs.getLength() / bufSize);
            for (int i = partitions.size(); i < count; i++) {
                partitions.add(new Partition(WALFileStream.HEADER_SIZE + i * bufSize, bufSize));
            }
            int rc = partitions.size() - count;
            for (int i = 0; i < rc; i++) {
                partitions.remove(partitions.size() - 1);
            }
        });
    }

    public long size() {
        return (long) Linq.from(partitions).where(p -> p.keySize != Constants.IO_EOF).sum(p -> p.keySize);
    }

    @Override
    public KeyEntity<TK> newKey(TK key) {
        return new HashKey<>(key);
    }

    @Override
    public void save(@NonNull KeyEntity<TK> key) {
        HashKey<TK> fk = (HashKey<TK>) key;
        fs.lock.writeInvoke(() -> {
            for (Partition partition : route(fk)) {
                if (partition.save(fk)) {
                    break;
                }
            }
        });
    }

    @Override
    public KeyEntity<TK> find(@NonNull TK k) {
        HashKey<TK> fk = new HashKey<>(k);
        return fs.lock.readInvoke(() -> {
            for (Partition partition : route(fk)) {
                if (partition.find(fk)) {
                    return fk;
                }
            }
            return null;
        });
    }

    Iterable<Partition> route(HashKey<TK> fk) {
        return partitions;
//        return Linq.from(partitions).orderBy(p -> {
//            HashKey<TK> min = p.min;
//            HashKey<TK> max = p.max;
//            if (min == null || max == null) {
//                return 2;
//            }
//            if (min.hashId <= fk.hashId && fk.hashId <= max.hashId) {
//                return 0;
//            }
//            return 1;
//        });
    }

    @Override
    public void clear() {
        fs.lock.writeInvoke(() -> {
            for (Partition partition : partitions) {
                partition.clear();
            }
            fs.clear();
        });
    }

    @Override
    public String toString() {
        return "ExternalSortingIndexer{" +
                "name=" + fs.getName() +
                ", bufSize=" + bufSize +
                ", partitions=" + Linq.from(partitions).select(p -> p.keySize).toList() + " / " + size() +
                '}';
    }
}