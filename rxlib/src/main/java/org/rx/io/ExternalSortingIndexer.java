package org.rx.io;

import lombok.NonNull;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.ToString;
import org.rx.codec.CodecUtil;
import org.rx.core.*;
import org.rx.exception.InvalidException;

import java.io.EOFException;
import java.io.File;
import java.lang.ref.WeakReference;
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
    @ToString(callSuper = true)
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
        volatile WeakReference<HashKey<TK>[]> ref;

        Partition(long position, long size) {
            super(position, size);
            endPos = position + size;
        }

        void clear() {
            wal.lock.writeInvoke(() -> {
                wal.setWriterPosition(position);
                for (int i = 0; i < size; i++) {
                    wal.write(0);
                }

                keySize = 0;
                min = max = null;
                setCache(null);
            }, position, size);
        }

        void setCache(HashKey<TK>[] ks) {
            if (ks == null) {
                ref = null;
//                cache.remove(this);
                return;
            }

            if (enableCache) {
                ref = new WeakReference<>(ks);
                Tasks.setTimeout(ks::getClass, cacheTtl, this, Constants.TIMER_REPLACE_FLAG);
//            cache.put(this, ks);
            }
        }

        @SneakyThrows
        HashKey<TK>[] unsafeLoad() {
            WeakReference<HashKey<TK>[]> r = ref;
            HashKey<TK>[] ks = r != null ? r.get() : null;
//            HashKey<TK>[] ks = cache.get(this);
            if (ks == null) {
                int keySize = this.keySize;
                boolean setSize = keySize == Constants.IO_EOF;
                List<HashKey<TK>> keys = new ArrayList<>(setSize ? 10 : keySize);

                wal.setReaderPosition(position);
                int b = HashKey.BYTES;
                byte[] buf = new byte[b];
                int kSize = setSize ? Integer.MAX_VALUE : keySize;
                for (long i = 0; i < this.size && keys.size() < kSize; i += b) {
                    if (wal.read(buf, 0, buf.length) != b) {
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

                ks = keys.toArray(ARR_TYPE);
                if (setSize) {
                    this.keySize = ks.length;
                    if (ks.length == 0) {
                        min = max = null;
                    } else {
                        min = ks[0];
                        max = ks[ks.length - 1];
                    }
                }
                setCache(ks);
            }
            return ks;
        }

        boolean find(HashKey<TK> ktf) {
            return wal.lock.readInvoke(() -> {
                HashKey<TK>[] keys = unsafeLoad();
                int i = Arrays.binarySearch(keys, ktf);
                if (i < 0) {
                    return false;
                }
                HashKey<TK> t = keys[i];
                ktf.logPosition = t.logPosition;
                ktf.keyPos = t.keyPos;
                return true;
            });
        }

        boolean save(HashKey<TK> ktf) {
            long newLogPos = ktf.logPosition;
            return wal.lock.writeInvoke(() -> {
                if (find(ktf)) {
//                    if (ktf.logPosition > t.logPosition) {
//                        return true;
//                    }
                    ktf.logPosition = newLogPos;
                    wal.setWriterPosition(ktf.keyPos + 8);
                    wal.write(Bytes.getBytes(ktf.logPosition));

                    HashKey<TK>[] ks;
                    WeakReference<HashKey<TK>[]> ref = this.ref;
                    if ((ks = (ref != null ? ref.get() : null)) != null) {
//                    if ((ks = cache.get(this)) != null) {
                        int i = (int) (ktf.keyPos - position) / HashKey.BYTES;
                        HashKey<TK> k = ks[i];
                        if (k.hashId != ktf.hashId) {
                            throw new InvalidException("compute index error");
                        }
                        ks[i].logPosition = ktf.logPosition;
                    }
                    return true;
                }

                long wPos = wal.getWriterPosition();
                if (wPos < endPos) {
                    HashKey<TK>[] oks = unsafeLoad();
                    HashKey<TK>[] ks = new HashKey[oks.length + 1];
                    System.arraycopy(oks, 0, ks, 0, oks.length);
                    ks[ks.length - 1] = Sys.deepClone(ktf);
                    Arrays.parallelSort(ks);
                    wal.setWriterPosition(position);
                    byte[] buf = new byte[HashKey.BYTES];
                    for (HashKey<TK> fk : ks) {
                        fk.keyPos = wal.getWriterPosition();
                        Bytes.getBytes(fk.hashId, buf, 0);
                        Bytes.getBytes(fk.logPosition, buf, 8);
                        Bytes.getBytes(fk.keyPos, buf, 16);
                        wal.write(buf);
                    }

                    keySize = ks.length;
                    min = ks[0];
                    max = ks[keySize - 1];
                    setCache(ks);
                    return true;
                }
                return false;
            }, position, size);
        }
    }

    static final int DEF_SORT_VAL = Integer.MAX_VALUE - 1;
    static final HashKey[] ARR_TYPE = new HashKey[0];
    final WALFileStream wal;
    final long bufSize;
    //concurrent issue
    //    final Cache<Partition, HashKey<TK>[]> cache = Cache.getInstance(Cache.MEMORY_CACHE);
    final CopyOnWriteArrayList<Partition> partitions = new CopyOnWriteArrayList<>();
    @Setter
    boolean enableCache = true;
    @Setter
    long cacheTtl = 60 * 1000;

    public ExternalSortingIndexer(File file, long bufSize, int readerCount) {
        int b = HashKey.BYTES;
        this.bufSize = bufSize = (bufSize / b) * b;
        wal = new WALFileStream(file, bufSize, readerCount, Serializer.DEFAULT);
        wal.onGrow.combine((s, e) -> ensureGrow());
        ensureGrow();
    }

    @Override
    protected void freeObjects() {
        wal.close();
    }

    void ensureGrow() {
        wal.lock.writeInvoke(() -> {
            int count = (int) (wal.getLength() / bufSize);
            for (int i = partitions.size(); i < count; i++) {
                partitions.add(new Partition(WALFileStream.HEADER_SIZE + i * bufSize, bufSize));
            }
            int rc = partitions.size() - count;
            for (int i = 0; i < rc; i++) {
                partitions.remove(partitions.size() - 1);
            }
        }, WALFileStream.HEADER_SIZE);
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
        for (Partition partition : route(fk)) {
            if (partition.save(fk)) {
                break;
            }
        }
    }

    @Override
    public KeyEntity<TK> find(@NonNull TK k) {
        HashKey<TK> fk = new HashKey<>(k);
        for (Partition partition : route(fk)) {
            if (partition.find(fk)) {
                return fk;
            }
        }
        return null;
    }

    Iterable<Partition> route(HashKey<TK> fk) {
        if (partitions.size() <= 5) {
            return partitions;
        }
        return wal.lock.readInvoke(() -> Linq.from(partitions).orderBy(p -> {
            HashKey<TK> min = p.min;
            HashKey<TK> max = p.max;
            if (min == null || max == null) {
                return Integer.MAX_VALUE;
            }
            if (min.hashId <= fk.hashId && fk.hashId <= max.hashId) {
                return p.keySize;
            }
            return DEF_SORT_VAL;
        }), WALFileStream.HEADER_SIZE);
    }

    @Override
    public void clear() {
        wal.lock.writeInvoke(() -> {
            for (Partition partition : partitions) {
                partition.clear();
            }
            wal.clear();
        });
    }

    @Override
    public String toString() {
        return "ExternalSortingIndexer{" +
                "name=" + wal.getName() +
                ", bufSize=" + bufSize +
                ", partitions=" + Linq.from(partitions).select(p -> p.keySize).toList() + " / " + size() +
                '}';
    }
}
