package org.rx.io;

import io.netty.buffer.ByteBuf;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Cache;
import org.rx.core.Disposable;
import org.rx.core.Strings;
import org.rx.core.Tasks;

import java.io.File;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicInteger;

import static org.rx.core.App.require;

@Slf4j
final class HashFileIndexer<TK> extends Disposable {
    @RequiredArgsConstructor
    @ToString
    static class KeyData<TK> {
        final TK key;
        private long position = -1;

        final int hashCode;
        long logPosition;
    }

    @RequiredArgsConstructor
    static class Slot {
        private final HashFileIndexer<?> owner;
        private final FileStream main;
        private final CompositeLock lock;
        private final AtomicInteger wroteSize = new AtomicInteger();
        private volatile long wroteBytes = HEADER_SIZE;
        private IOStream<?, ?> stream;

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
            lock.writeInvoke(() -> stream = main.mmap(FileChannel.MapMode.READ_WRITE, 0, main.getLength()));
        }

        private void releaseStream() {
            lock.writeInvoke(() -> {
                if (stream != null) {
                    stream.close();
                }
            });
        }

        private boolean ensureGrow() {
            return lock.writeInvoke(() -> {
                long length = main.getLength();
                if (length < owner.growSize
                        || (float) wroteBytes / length > WALFileStream.GROW_FACTOR) {
//                    assert wroteBytes != 0;
                    long resize = length + owner.growSize;
                    log.debug("growLength {} {}->{}", main.getName(), length, resize);
                    releaseStream();
                    main.setLength(resize);
                    createStream();
                    return true;
                }
                return false;
            });
        }

        public int incrementSize() {
            return lock.writeInvoke(() -> {
                int size = wroteSize.incrementAndGet();
                wroteBytes = HEADER_SIZE + (long) size * KEY_SIZE;
                saveSize();
                return size;
            });
        }

        void saveSize() {
            ByteBuf buf = Bytes.directBuffer(HEADER_SIZE, false);
            try {
                lock.writeInvoke(() -> {
                    stream.setPosition(0);
                    buf.writeInt(wroteSize.get());
                    stream.write(buf);
                    stream.flush();
                }, 0, HEADER_SIZE);
            } finally {
                buf.release();
            }
        }

        void loadSize() {
            ByteBuf buf = Bytes.directBuffer(HEADER_SIZE, false);
            try {
                lock.readInvoke(() -> {
                    stream.setPosition(0);
                    if (stream.read(buf) > 0) {
                        wroteSize.set(buf.readInt());
                        wroteBytes = HEADER_SIZE + (long) wroteSize.get() * KEY_SIZE;
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

    public HashFileIndexer(@NonNull File directory, long slotSize, int growSize) {
        require(slotSize, slotSize > 0);
        this.growSize = growSize;

        directory.mkdirs();
        this.directory = directory;

        int slotCount = (int) Math.ceil((double) Integer.MAX_VALUE * KEY_SIZE / slotSize);
        slots = new Slot[slotCount];
    }

    @Override
    protected void freeObjects() {
        for (Slot slot : slots) {
            if (slot == null) {
                continue;
            }

            slot.stream.close();
            slot.main.close();
        }
    }

    public void clear() {
        synchronized (slots) {
            for (int i = 0; i < slots.length; i++) {
                Slot slot = slots[i];
                if (slot == null) {
                    continue;
                }

                slot.stream.close();
                slot.main.close();
                slots[i] = null;
            }
            Files.delete(directory.getAbsolutePath());
        }
    }

    private Slot slot(int hashCode) {
        int i = (slots.length - 1) & spread(hashCode);
        synchronized (slots) {
            Slot slot = slots[i];
            if (slot == null) {
                slots[i] = slot = new Slot(this, new File(directory, String.format("%s", i)));
            }
            return slot;
        }
    }

    public void saveKey(KeyData<TK> key) {
        checkNotClosed();
        require(key, key.position >= -1);

        Slot slot = slot(key.hashCode);
        ByteBuf buf = Bytes.directBuffer(KEY_SIZE, false);
        try {
            slot.lock.writeInvoke(() -> {
                slot.ensureGrow();
//                if (key.key != null) {
//                    cache().remove(key.key);
//                }

                IOStream<?, ?> out = slot.stream;
                long pos = key.position == -1 ? slot.wroteBytes : key.position;
                out.setPosition(pos);

                buf.writeInt(key.hashCode);
                buf.writeLong(key.logPosition);
                log.debug("saveKey {} -> {} pos={}{}", slot.main.getName(), key, pos, dump(buf));
                out.write(buf);
                out.flush();

                if (key.position == -1) {
                    slot.incrementSize();
                }
            }, HEADER_SIZE);
        } finally {
            if (buf != null) {
                buf.release();
            }
        }
    }

    public KeyData<TK> findKey(@NonNull TK k) {
//        return Tasks.threadMapCompute(k, x -> {
//        return cache().get(k, x -> {
        int hashCode = k.hashCode();
        Slot slot = slot(hashCode);
        ByteBuf buf = Bytes.directBuffer(KEY_SIZE, false);
        try {
            return slot.lock.readInvoke(() -> {
                IOStream<?, ?> in = slot.stream;

                in.setPosition(HEADER_SIZE);
                long pos = in.getPosition();
                long endPos = slot.wroteBytes;
                while (pos < endPos && in.read(buf, KEY_SIZE) > 0) {
//                        log.debug("findKey {} -> {} pos={}{}", slot.main.getName(), k.hashCode(), pos, dump(buf));
                    int hash = buf.readInt();
                    if (pos > HEADER_SIZE && hash == 0) {
                        log.error("sync error {} pos={}", k, pos);
                    }
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

                log.debug("findKey {} -> {} pos={}{}", slot.main.getName(), k.hashCode(), pos, dump(buf));
                return null;
            }, HEADER_SIZE);
        } finally {
            if (buf != null) {
                buf.release();
            }
        }
//        });
    }

    private Cache<TK, KeyData<TK>> cache() {
        return Cache.getInstance(Cache.LOCAL_CACHE);
    }

    private String dump(ByteBuf buf) {
        if (!HEX_DUMP) {
            return Strings.EMPTY;
        }
        return "\n" + Bytes.hexDump(buf);
    }
}
