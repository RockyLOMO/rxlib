package org.rx.io;

import io.netty.buffer.ByteBuf;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Cache;
import org.rx.core.Constants;
import org.rx.core.Disposable;
import org.rx.core.Strings;
import org.rx.core.cache.MemoryCache;

import java.io.File;
import java.nio.channels.FileChannel;

import static org.rx.core.Extends.require;
import static org.rx.core.Extends.tryClose;

/**
 * murmur3_128  com.google.common.hash.Hashing.goodFastHash(128);
 * only cache key position
 *
 * @param <TK>
 */
@Slf4j
final class HashFileIndexer<TK> extends Disposable {
    @RequiredArgsConstructor
    @EqualsAndHashCode
    @ToString
    static class KeyData<TK> {
        final TK key;
        private long position = Constants.IO_EOF;

        final int hashCode;
        long logPosition;
    }

    @RequiredArgsConstructor
    static class Slot {
        private final HashFileIndexer<?> owner;
        private final FileStream main;
        private final CompositeLock lock;
        private long _wroteBytes = HEADER_SIZE;
        private IOStream<?, ?> writer, reader;

        Slot(HashFileIndexer<?> owner, File indexFile) {
            this.owner = owner;
            main = new FileStream(indexFile, FileMode.READ_WRITE, BufferedRandomAccessFile.BufSize.NON_BUF);
            lock = main.getLock();
            if (!ensureGrow()) {
                createStream();
            }
            loadSize();
        }

        private void createStream() {
            lock.writeInvoke(() -> {
                long length = main.getLength();
                writer = main.mmap(FileChannel.MapMode.READ_WRITE, 0, length);
                reader = main.mmap(FileChannel.MapMode.READ_ONLY, 0, length);
            });
        }

        private void releaseStream() {
            lock.writeInvoke(() -> {
                tryClose(writer);
                tryClose(reader);
            });
        }

        private boolean ensureGrow() {
            return lock.writeInvoke(() -> {
                long length = main.getLength();
                if (length < owner.growSize
                        || (float) getWroteBytes() / length > WALFileStream.GROW_FACTOR) {
//                    assert wroteBytes != 0;
                    long resize = length + owner.growSize;
                    log.debug("growSize {} {}->{}", main.getName(), length, resize);
                    releaseStream();
                    main.setLength(resize);
                    createStream();
                    return true;
                }
                return false;
            });
        }

        public synchronized long getWroteBytes() {
            return _wroteBytes;
        }

        public synchronized void incrementWroteBytes() {
            _wroteBytes += KEY_SIZE;
            owner.queue.offer(main.getName(), 0, x -> saveSize());
        }

        public synchronized void resetWroteBytes() {
            _wroteBytes = HEADER_SIZE;
        }

        void saveSize() {
            ByteBuf buf = Bytes.directBuffer(HEADER_SIZE);
            try {
                lock.writeInvoke(() -> {
                    writer.setPosition(0);
                    int size = (int) ((getWroteBytes() - HEADER_SIZE) / KEY_SIZE);
                    buf.writeInt(size);
                    writer.write(buf);
//                    writer.flush();
                }, 0, HEADER_SIZE);
            } finally {
                buf.release();
            }
        }

        void loadSize() {
            ByteBuf buf = Bytes.directBuffer(HEADER_SIZE);
            try {
                lock.readInvoke(() -> {
                    reader.setPosition(0);
                    if (reader.read(buf) > 0) {
                        int size = buf.readInt();
                        _wroteBytes = HEADER_SIZE + (long) size * KEY_SIZE;
                    }
                }, 0, HEADER_SIZE);
            } finally {
                buf.release();
            }
        }
    }

    static final boolean HEX_DUMP = false;
    static final int HEADER_SIZE = 4;
    static final int KEY_SIZE = 12;
    //    static final int READ_PAGE_CACHE_SIZE = (int) Math.floor(1024d * 4 / KEY_SIZE) * KEY_SIZE;
    static final int HASH_BITS = 0x7fffffff;

    static int spread(int h) {
        return (h ^ (h >>> 16)) & HASH_BITS;
    }

    private final File directory;
    private final int growSize;
    private final Slot[] slots;
    private final WriteBehindQueue<String, Integer> queue;
    private final Cache<TK, KeyData<TK>> cache = new MemoryCache<>(b -> MemoryCache.weightBuilder(b, 0.2f, 16 * 2 + 8 + 4 + 8));

    public HashFileIndexer(@NonNull File directory, long slotSize, int growSize) {
        require(slotSize, slotSize > 0);
        this.growSize = growSize;
        this.directory = directory;

        int slotCount = (int) Math.ceil((double) Integer.MAX_VALUE * KEY_SIZE / slotSize);
        slots = new Slot[slotCount];
        queue = new WriteBehindQueue<>(Constants.DEFAULT_INTERVAL, slotCount + 1);
    }

    @Override
    protected void freeObjects() {
        for (Slot slot : slots) {
            if (slot == null) {
                continue;
            }

            slot.releaseStream();
            slot.main.close();
        }
    }

    public void clear() {
        synchronized (slots) {
            for (Slot slot : slots) {
                if (slot == null) {
                    continue;
                }

                slot.resetWroteBytes();
            }
        }
    }

    private Slot slot(int hashCode) {
        int i = (slots.length - 1) & spread(hashCode);
        synchronized (slots) {
            Slot slot = slots[i];
            if (slot == null) {
                directory.mkdirs();
                slots[i] = slot = new Slot(this, new File(directory, String.format("%s", i)));
            }
            return slot;
        }
    }

    public void saveKey(KeyData<TK> key) {
        checkNotClosed();
        require(key, key.position >= Constants.IO_EOF);

        Slot slot = slot(key.hashCode);
        slot.lock.writeInvoke(() -> {
            slot.ensureGrow();

            IOStream<?, ?> out = slot.writer;
            long pos = key.position == Constants.IO_EOF ? slot.getWroteBytes() : key.position;
            out.setPosition(pos);

            ByteBuf buf = Bytes.directBuffer(KEY_SIZE);
            try {
                buf.writeInt(key.hashCode);
                buf.writeLong(key.logPosition);
                out.write(buf);
//                out.flush();
            } finally {
                buf.release();
            }
//            log.info("saveKey {} -> {} pos={}{}", slot.main.getName(), key, pos, dump(buf));

            if (key.position == Constants.IO_EOF) {
                slot.incrementWroteBytes();
//                log.info("wroteBytes {} -> {} pos={}/{}", slot.main.getName(), key, pos, slot.getWroteBytes());
            }

            if (key.key != null) {
//                cache.remove(key.key);
                key.position = pos;
                cache.put(key.key, key); //hang?
            }
        }, HEADER_SIZE);
    }

    public KeyData<TK> findKey(@NonNull TK k) {
        return cache.get(k, x -> {
            int hashCode = k.hashCode();
            Slot slot = slot(hashCode);

            return slot.lock.readInvoke(() -> {
                IOStream<?, ?> in = slot.reader;

                in.setPosition(HEADER_SIZE);
                long pos = in.getPosition();
                long endPos = slot.getWroteBytes();
                ByteBuf buf = Bytes.directBuffer(KEY_SIZE);
                try {
                    while (pos < endPos && in.read(buf, KEY_SIZE) > 0) {
//                    log.debug("findKey {} -> {} pos={}{}", slot.main.getName(), hashCode, pos, dump(buf));
                        int hash = buf.readInt();
                        if (hash != hashCode) {
                            pos += KEY_SIZE;
                            buf.clear();
                            continue;
                        }

                        KeyData<TK> keyData = new KeyData<>(k, hashCode);
                        keyData.position = in.getPosition() - KEY_SIZE;
                        keyData.logPosition = buf.readLong();
                        return keyData;
                    }
                } finally {
                    buf.release();
                }

//                if (pos > HEADER_SIZE) {
//                    try (FileChannel open = FileChannel.open(Paths.get(slot.main.getPath()))) {
//                        open.position(pos - KEY_SIZE);
//                        ByteBuffer b = ByteBuffer.allocate(KEY_SIZE);
//                        int r = open.read(b);
//                        assert r == KEY_SIZE;
//                        b.flip();
//                        KeyData<TK> tmp = new KeyData<>(null, b.getInt());
//                        tmp.position = pos - KEY_SIZE;
//                        tmp.logPosition = b.getLong();
//                        log.error("syncErr {} -> {} pos={}/{} redo={}", slot.main.getName(), hashCode, pos, endPos, tmp);
//                    }
//                }
//                log.warn("findKey fail {} -> {} pos={}/{}{}", slot.main.getName(), hashCode, pos, endPos, dump(buf));
                return null;
            }, HEADER_SIZE);
        });
    }

    private String dump(ByteBuf buf) {
        if (!HEX_DUMP) {
            return Strings.EMPTY;
        }
        return "\n" + Bytes.hexDump(buf);
    }
}
