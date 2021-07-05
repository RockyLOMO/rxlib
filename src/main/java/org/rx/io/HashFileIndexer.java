package org.rx.io;

import io.netty.buffer.ByteBuf;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;

import java.io.File;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
        private final AtomicLong keysBytes = new AtomicLong(HEADER_SIZE);
        private IOStream<?, ?> stream;

        Slot(HashFileIndexer<?> owner, File indexFile) {
            this.owner = owner;
            main = new FileStream(indexFile, FileMode.READ_WRITE, BufferedRandomAccessFile.BufSize.TINY_DATA);
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
                if (length < owner.bufferSize
                        || (float) keysBytes.get() / length > WALFileStream.GROW_FACTOR) {
                    assert keysBytes.get() != 0;
                    long resize = length + owner.bufferSize;
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
                keysBytes.set(HEADER_SIZE + (long) size * KEY_SIZE);
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
                });
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
                        keysBytes.set(HEADER_SIZE + (long) wroteSize.get() * KEY_SIZE);
                    }
                });
            } finally {
                buf.release();
            }
        }
    }

    static final int HEADER_SIZE = 4;
    static final int KEY_SIZE = 12;
    static final int HASH_BITS = 0x7fffffff;

    static int spread(int h) {
        return (h ^ (h >>> 16)) & HASH_BITS;
    }

    private final File directory;
    private final int bufferSize;
    private final Slot[] slots;

    public HashFileIndexer(@NonNull File directory, long slotSize, int bufferSize) {
        require(slotSize, slotSize > 0);
        this.bufferSize = bufferSize;

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

        Slot slot = slot(key.hashCode);
        ByteBuf buf = Bytes.directBuffer(KEY_SIZE, false);
        try {
            slot.lock.writeInvoke(() -> {
                slot.ensureGrow();

                IOStream<?, ?> out = slot.stream;
                out.setPosition(key.position == -1 ? slot.keysBytes.get() : key.position);

                buf.writeInt(key.hashCode);
                buf.writeLong(key.logPosition);
                log.debug("saveKey {} {}\n{}", key.hashCode, key, Bytes.hexDump(buf));
                out.write(buf);
//                out.flush();

                if (key.position == -1) {
                    slot.incrementSize();
                }
            });
        } finally {
            if (buf != null) {
                buf.release();
            }
        }
    }

    public KeyData<TK> findKey(@NonNull TK k) {
//        return Tasks.threadMapCompute(k, x -> {
        int hashCode = k.hashCode();
        Slot slot = slot(hashCode);
        ByteBuf buf = Bytes.directBuffer(KEY_SIZE, false);
        try {
            return slot.lock.readInvoke(() -> {
                IOStream<?, ?> in = slot.stream;

                in.setPosition(HEADER_SIZE);
                long pos = in.getPosition();
                long endPos = slot.keysBytes.get();
                while (pos < endPos && in.read(buf, KEY_SIZE) > 0) {
                    pos += KEY_SIZE;
                    log.debug("findKey {}\n{}", k.hashCode(), Bytes.hexDump(buf));
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
                    return keyData;
                }

                return null;
            });
        } finally {
            if (buf != null) {
                buf.release();
            }
        }
//        });
    }
}
